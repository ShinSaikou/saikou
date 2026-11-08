package com.shinsaikou.downloads

import com.shinsaikou.core.metadata.comicinfo.ComicInfoXml
import com.shinsaikou.data.DatabaseTransactionRunner
import com.shinsaikou.domain.download.model.DownloadManifestEntry
import com.shinsaikou.domain.download.model.DownloadState
import com.shinsaikou.domain.download.repository.DownloadManifestRepository
import com.shinsaikou.domain.error.ErrorKey
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE
import tachiyomi.domain.manga.interactor.GetManga
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * The Experimental "Save chapters as CBZ files" switch (R162 item 7): the engine writes a chapter as
 * `<chapter>.cbz` instead of a folder when [com.shinsaikou.domain.preferences.DownloadPreferences.saveChaptersAsCBZ]
 * is on as the chapter starts, and the files, the manifest and the delete paths treat it as a download.
 */
class DownloadEngineCbzTest : DownloadTestBase() {

    private val scheduler = mockk<DownloadScheduler>(relaxed = true) {
        every { isRunning } returns flowOf(false)
    }

    /** Set by the test that watches, or fails, the moment a manifest row is written. */
    private var onRecord: (suspend (DownloadManifestEntry) -> Unit)? = null

    override val manifestRepository: DownloadManifestRepository
        get() = object : DownloadManifestRepository by realManifestRepository {
            override suspend fun recordDownload(entry: DownloadManifestEntry) {
                onRecord?.invoke(entry)
                realManifestRepository.recordDownload(entry)
            }
        }

    @BeforeEach
    fun seed() {
        insertManga(id = 1)
        insertChapter(id = 10, mangaId = 1)
        insertChapter(id = 11, mangaId = 1)
    }

    private fun TestScope.newEngine() = engine(StandardTestDispatcher(testScheduler))

    /** An engine whose ComicInfo writer is [writer], so a test can act between the pages and the archive. */
    private fun TestScope.engineWith(writer: ChapterComicInfoWriter): DownloadEngine {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return DownloadEngine(
            queueRepository = queueRepository,
            manifestRepository = manifestRepository,
            transactionRunner = DatabaseTransactionRunner(handler),
            getManga = GetManga(mangaRepository),
            chapterRepository = chapterRepository,
            sourceManager = sourceManager,
            downloadRoot = downloadRoot,
            downloadPreferences = downloadPreferences,
            libraryPreferences = libraryPreferences,
            comicInfoWriter = writer,
            locator = locator(dispatcher),
            writeLock = writeLock,
            dispatchers = dispatchers(dispatcher),
        )
    }

    /** A real writer that runs [afterWrite] once the chapter's ComicInfo.xml is in place. */
    private fun TestScope.writerThen(afterWrite: suspend (tmpDir: File) -> Unit): ChapterComicInfoWriter {
        val real = comicInfoWriter(StandardTestDispatcher(testScheduler))
        val writer = mockk<ChapterComicInfoWriter>()
        coEvery { writer.write(any(), any(), any(), any()) } coAnswers {
            real.write(arg(0), arg(1), arg(2), arg(3))
            afterWrite(firstArg())
        }
        return writer
    }

    private fun TestScope.queue(engine: DownloadEngine) = WorkManagerDownloadQueue(
        queueRepository = queueRepository,
        manifest = manifest(StandardTestDispatcher(testScheduler)),
        chapterRepository = chapterRepository,
        getManga = GetManga(mangaRepository),
        locator = locator(StandardTestDispatcher(testScheduler)),
        downloadRoot = downloadRoot,
        engine = engine,
        scheduler = scheduler,
        dispatchers = dispatchers(StandardTestDispatcher(testScheduler)),
        appScope = backgroundScope,
    )

    private fun saveAsCbz(on: Boolean = true) = downloadPreferences.saveChaptersAsCBZ.set(on)

    private suspend fun archiveOf(chapterId: Long): File =
        File(mangaDir(chapter(chapterId).mangaId), chapterDirName(chapterId) + ".cbz")

    private suspend fun archiveTmpOf(chapterId: Long): File =
        File(mangaDir(chapter(chapterId).mangaId), chapterDirName(chapterId) + ".cbz_tmp")

    private suspend fun enqueue(vararg chapterIds: Long) =
        chapterIds.forEachIndexed { index, id -> queueRepository.enqueue(chapterId = id, mangaId = 1, now = index.toLong()) }

    /** Every file or folder in the entry's folder, by name. */
    private suspend fun names(): List<String> = mangaDir(1).list().orEmpty().sorted()

    private fun File.zipEntries(): List<ZipEntry> = ZipFile(this).use { it.entries().toList() }

    private fun File.zipBytes(name: String): ByteArray =
        ZipFile(this).use { zip -> zip.getInputStream(zip.getEntry(name)).use { it.readBytes() } }

    private fun pageName(index: Int) = "%03d.png".format(index + 1)

    /** The pages a finished chapter [chapterUrl] holds, in the order the source listed them. */
    private fun expectedPages(chapterUrl: String): List<Pair<String, List<Byte>>> = List(PAGES) {
        pageName(it) to FakeSource.pageBytes(FakeSource.imageUrl(chapterUrl, it)).toList()
    }

    /** Chapter 10 saved as a folder, then the switch flipped on while it is fetched, so chapter 11 is an archive. */
    private suspend fun downloadOneFolderAndOneArchive(testScope: TestScope) {
        source.onImage = { page, _ ->
            if (page.imageUrl == FakeSource.imageUrl("/ch/10", 0)) saveAsCbz(true)
        }
        enqueue(10, 11)
        testScope.newEngine().run() shouldBe DownloadEngine.Outcome(completed = 2, failed = 0)
    }

    // Switch off

    @Test
    fun `with the switch off a chapter is a folder and no archive work happens`() = runTest {
        enqueue(10)

        newEngine().run() shouldBe DownloadEngine.Outcome(completed = 1, failed = 0)

        finalDir(10).isDirectory shouldBe true
        names() shouldContainExactly listOf(chapterDirName(10))
        realManifestRepository.getByChapterId(10).shouldNotBeNull().chapterDirName shouldBe chapterDirName(10)
        File(finalDir(10), COMIC_INFO_FILE).isFile shouldBe true
    }

    @Test
    fun `with the switch off an archive without a row is left alone`() = runTest {
        val orphan = archiveOf(10).apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        enqueue(10)

        newEngine().run() shouldBe DownloadEngine.Outcome(completed = 1, failed = 0)

        orphan.readBytes().toList() shouldBe listOf<Byte>(1, 2, 3)
        finalDir(10).isDirectory shouldBe true
        realManifestRepository.getByChapterId(10).shouldNotBeNull().chapterDirName shouldBe chapterDirName(10)
    }

    // Switch on: the archive and its row

    @Test
    fun `an archive holds the pages in page order, then ComicInfo, every entry stored, with the page bytes intact`() =
        runTest {
            saveAsCbz()
            enqueue(10)

            newEngine().run() shouldBe DownloadEngine.Outcome(completed = 1, failed = 0)

            val archive = archiveOf(10)
            val entries = archive.zipEntries()
            entries.map { it.name } shouldContainExactly (0 until PAGES).map(::pageName) + COMIC_INFO_FILE
            entries.forEach { it.method shouldBe ZipEntry.STORED }
            expectedPages("/ch/10").forEach { (name, bytes) -> archive.zipBytes(name).toList() shouldBe bytes }
            val info = ComicInfoXml().decode(archive.zipBytes(COMIC_INFO_FILE).inputStream())
            info.title?.value shouldBe "Chapter 10"
        }

    @Test
    fun `an archive row names the archive, its size and page count, and the pages folder is gone`() = runTest {
        saveAsCbz()
        enqueue(10)

        newEngine().run()

        val archive = archiveOf(10)
        val row = realManifestRepository.getByChapterId(10).shouldNotBeNull()
        row.chapterDirName shouldBe archive.name
        row.sizeBytes shouldBe archive.length()
        row.pageCount shouldBe PAGES.toLong()
        row.sourceId shouldBe SOURCE_ID
        tmpDir(10).exists() shouldBe false
        finalDir(10).exists() shouldBe false
        archiveTmpOf(10).exists() shouldBe false
        names() shouldContainExactly listOf(archive.name)
        queueRepository.getActive().shouldBeEmpty()
    }

    @Test
    fun `an archived chapter is downloaded, found by the locator and the manifest, and has no page list`() = runTest {
        saveAsCbz()
        enqueue(10)
        newEngine().run()
        val manifest = manifest(StandardTestDispatcher(testScheduler))
        val row = realManifestRepository.getByChapterId(10).shouldNotBeNull()

        manifest.isDownloaded(10) shouldBe true
        manifest.getChapterDir(10) shouldBe archiveOf(10)
        locator(StandardTestDispatcher(testScheduler)).findChapterDir(row) shouldBe archiveOf(10)
        // An archive's pages are the reader's to open.
        manifest.getPages(10).shouldBeNull()
    }

    @Test
    fun `a resumed chapter is archived with its earlier pages and not with a half written one`() = runTest {
        saveAsCbz()
        val reachedFourthPage = CompletableDeferred<Unit>()
        source.onImage = { page, attempt ->
            if (page.imageUrl == FakeSource.imageUrl("/ch/10", 3) && attempt == 1) {
                reachedFourthPage.complete(Unit)
                awaitCancellation()
            }
        }
        enqueue(10)
        val firstWorker = launch { newEngine().run() }
        reachedFourthPage.await()
        firstWorker.cancelAndJoin()
        File(tmpDir(10), "004.tmp").writeBytes(byteArrayOf(1, 2, 3))

        newEngine().run() shouldBe DownloadEngine.Outcome(completed = 1, failed = 0)

        archiveOf(10).zipEntries().map { it.name } shouldContainExactly
            (0 until PAGES).map(::pageName) + COMIC_INFO_FILE
        source.imageCallsFor("/ch/10", 0) shouldBe 1
        archiveOf(10).zipBytes("004.png").toList() shouldBe
            FakeSource.pageBytes(FakeSource.imageUrl("/ch/10", 3)).toList()
    }

    // Failure paths

    @Test
    fun `a failure while the archive is written deletes the partial archive, keeps the pages and the retry succeeds`() =
        runTest {
            saveAsCbz()
            enqueue(10)
            // A page the archive cannot read: the zip is opened and has begun by then.
            val unreadable = writerThen { tmpDir -> File(tmpDir, pageName(2)).setReadable(false) }
            // Root reads any file, so there the page cannot be made unreadable and the case does not arise.
            assumeFalse(System.getProperty("user.name") == "root")

            engineWith(unreadable).run() shouldBe DownloadEngine.Outcome(completed = 0, failed = 1)

            archiveTmpOf(10).exists() shouldBe false
            archiveOf(10).exists() shouldBe false
            realManifestRepository.getByChapterId(10).shouldBeNull()
            queueRepository.getByChapterId(10).shouldNotBeNull().state shouldBe DownloadState.ERROR
            tmpDir(10).list().orEmpty().filter { it.endsWith(".png") }.sorted() shouldContainExactly
                (0 until PAGES).map(::pageName)

            File(tmpDir(10), pageName(2)).setReadable(true)
            queueRepository.setState(10, DownloadState.QUEUED, null, 0)
            newEngine().run() shouldBe DownloadEngine.Outcome(completed = 1, failed = 0)

            source.totalImageCalls() shouldBe PAGES
            archiveOf(10).zipEntries().size shouldBe PAGES + 1
            tmpDir(10).exists() shouldBe false
        }

    @Test
    fun `a stop after the pages and before the archive leaves no archive file and keeps the pages for the retry`() =
        runTest {
            saveAsCbz()
            enqueue(10)
            lateinit var run: Job
            val stopping = writerThen { run.cancel() }

            run = launch { engineWith(stopping).run() }
            run.join()

            archiveTmpOf(10).exists() shouldBe false
            archiveOf(10).exists() shouldBe false
            realManifestRepository.getByChapterId(10).shouldBeNull()
            queueRepository.getByChapterId(10).shouldNotBeNull().state shouldBe DownloadState.QUEUED
            tmpDir(10).list().orEmpty().count { it.endsWith(".png") } shouldBe PAGES

            newEngine().run() shouldBe DownloadEngine.Outcome(completed = 1, failed = 0)

            source.totalImageCalls() shouldBe PAGES
            archiveOf(10).isFile shouldBe true
        }

    @Test
    fun `an archive that cannot be renamed into place leaves no partial file, no row, and keeps the pages`() = runTest {
        saveAsCbz()
        // A folder the archive cannot replace: the rename onto a non-empty folder fails.
        File(archiveOf(10), "keep").apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        enqueue(10)

        newEngine().run() shouldBe DownloadEngine.Outcome(completed = 0, failed = 1)

        archiveTmpOf(10).exists() shouldBe false
        realManifestRepository.getByChapterId(10).shouldBeNull()
        queueRepository.getByChapterId(10).shouldNotBeNull().state shouldBe DownloadState.ERROR
        tmpDir(10).list().orEmpty().count { it.endsWith(".png") } shouldBe PAGES
    }

    @Test
    fun `a manifest transaction that fails deletes the archive, keeps the pages, and the retry succeeds`() = runTest {
        saveAsCbz()
        enqueue(10)
        onRecord = { throw IOException("fixture: row refused") }

        newEngine().run() shouldBe DownloadEngine.Outcome(completed = 0, failed = 1)

        archiveOf(10).exists() shouldBe false
        archiveTmpOf(10).exists() shouldBe false
        realManifestRepository.getByChapterId(10).shouldBeNull()
        queueRepository.getByChapterId(10).shouldNotBeNull().state shouldBe DownloadState.ERROR
        tmpDir(10).list().orEmpty().count { it.endsWith(".png") } shouldBe PAGES

        onRecord = null
        queueRepository.setState(10, DownloadState.QUEUED, null, 0)
        newEngine().run() shouldBe DownloadEngine.Outcome(completed = 1, failed = 0)

        source.totalImageCalls() shouldBe PAGES
        archiveOf(10).isFile shouldBe true
        tmpDir(10).exists() shouldBe false
    }

    @Test
    fun `a chapter removed from the queue before the commit leaves neither the archive nor the pages`() = runTest {
        saveAsCbz()
        val lastPage = CompletableDeferred<Unit>()
        enqueue(10)

        val run = launch {
            newEngine().run { if (it.pagesDone == it.pagesTotal) lastPage.complete(Unit) } shouldBe
                DownloadEngine.Outcome(completed = 0, failed = 0)
        }
        lastPage.await()
        queueRepository.remove(listOf(10))
        run.join()

        realManifestRepository.getByChapterId(10).shouldBeNull()
        archiveOf(10).exists() shouldBe false
        archiveTmpOf(10).exists() shouldBe false
        tmpDir(10).exists() shouldBe false
        names().shouldBeEmpty()
    }

    @Test
    fun `an archive and a partial archive left by a crash with no row are replaced by the retry`() = runTest {
        saveAsCbz()
        val stale = archiveOf(10).apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(9, 9, 9))
        }
        archiveTmpOf(10).writeBytes(byteArrayOf(8, 8))
        enqueue(10)

        newEngine().run() shouldBe DownloadEngine.Outcome(completed = 1, failed = 0)

        stale.zipEntries().map { it.name } shouldContainExactly (0 until PAGES).map(::pageName) + COMIC_INFO_FILE
        archiveTmpOf(10).exists() shouldBe false
        realManifestRepository.getByChapterId(10).shouldNotBeNull().chapterDirName shouldBe stale.name
    }

    @Test
    fun `a crash between the archive rename and the row reuses the pages and replaces the archive without fetching`() =
        runTest {
            saveAsCbz()
            val pages = tmpDir(10).apply { mkdirs() }
            (0 until PAGES).forEach { index ->
                File(pages, pageName(index)).writeBytes(FakeSource.pageBytes(FakeSource.imageUrl("/ch/10", index)))
            }
            archiveOf(10).writeBytes(byteArrayOf(9, 9, 9))
            enqueue(10)

            newEngine().run() shouldBe DownloadEngine.Outcome(completed = 1, failed = 0)

            source.totalImageCalls() shouldBe 0
            archiveOf(10).zipEntries().map { it.name } shouldContainExactly
                (0 until PAGES).map(::pageName) + COMIC_INFO_FILE
            tmpDir(10).exists() shouldBe false
        }

    /**
     * An archive in place with no row and no pages folder, as a migration that moved it to this
     * chapter's path and died before the row followed leaves it: it may be the only copy, so a
     * retry that fails leaves it as it was, and one that succeeds replaces it at the commit.
     */
    @Test
    fun `an archive in place with no pages folder survives a retry that fails and is replaced by one that succeeds`() =
        runTest {
            saveAsCbz()
            val existing = byteArrayOf(7, 7, 7, 7)
            val archive = archiveOf(10).apply {
                parentFile.mkdirs()
                writeBytes(existing)
            }
            tmpDir(10).exists() shouldBe false
            enqueue(10)
            source.onImage = { _, _ -> throw IllegalStateException("fixture: the source is down") }

            newEngine().run() shouldBe DownloadEngine.Outcome(completed = 0, failed = 1)

            archive.readBytes() shouldBe existing
            archiveTmpOf(10).exists() shouldBe false
            realManifestRepository.getByChapterId(10).shouldBeNull()
            queueRepository.getByChapterId(10).shouldNotBeNull().state shouldBe DownloadState.ERROR

            source.onImage = { _, _ -> }
            queueRepository.setState(10, DownloadState.QUEUED, null, 0)
            newEngine().run() shouldBe DownloadEngine.Outcome(completed = 1, failed = 0)

            archive.zipEntries().map { it.name } shouldContainExactly (0 until PAGES).map(::pageName) + COMIC_INFO_FILE
            archive.zipBytes(pageName(2)).toList() shouldBe expectedPages("/ch/10")[2].second
            realManifestRepository.getByChapterId(10).shouldNotBeNull().chapterDirName shouldBe archive.name
            archiveTmpOf(10).exists() shouldBe false
            tmpDir(10).exists() shouldBe false
        }

    @Test
    fun `an archive in place survives a failure while the new archive is written`() = runTest {
        saveAsCbz()
        val existing = byteArrayOf(7, 7, 7, 7)
        val archive = archiveOf(10).apply {
            parentFile.mkdirs()
            writeBytes(existing)
        }
        enqueue(10)
        val unreadable = writerThen { tmpDir -> File(tmpDir, pageName(2)).setReadable(false) }
        // Root reads any file, so there the page cannot be made unreadable and the case does not arise.
        assumeFalse(System.getProperty("user.name") == "root")

        engineWith(unreadable).run() shouldBe DownloadEngine.Outcome(completed = 0, failed = 1)

        archive.readBytes() shouldBe existing
        archiveTmpOf(10).exists() shouldBe false
        realManifestRepository.getByChapterId(10).shouldBeNull()
        File(tmpDir(10), pageName(2)).setReadable(true)
    }

    // The switch is read per chapter

    @Test
    fun `flipping the switch between chapters gives one folder and one archive under the same entry`() = runTest {
        downloadOneFolderAndOneArchive(this)

        finalDir(10).isDirectory shouldBe true
        archiveOf(11).isFile shouldBe true
        names() shouldContainExactlyInAnyOrder listOf(chapterDirName(10), chapterDirName(11) + ".cbz")
        realManifestRepository.getByChapterId(10).shouldNotBeNull().chapterDirName shouldBe chapterDirName(10)
        realManifestRepository.getByChapterId(11).shouldNotBeNull().chapterDirName shouldBe chapterDirName(11) + ".cbz"
    }

    @Test
    fun `a folder and an archive of one entry are both downloaded and found`() = runTest {
        downloadOneFolderAndOneArchive(this)
        val manifest = manifest(StandardTestDispatcher(testScheduler))

        manifest.isDownloaded(10) shouldBe true
        manifest.isDownloaded(11) shouldBe true
        manifest.getChapterDir(10) shouldBe finalDir(10)
        manifest.getChapterDir(11) shouldBe archiveOf(11)
        manifest.getPages(10).shouldNotBeNull().size shouldBe PAGES
    }

    @Test
    fun `deleting one chapter removes its folder or its archive and leaves the other kind`() = runTest {
        downloadOneFolderAndOneArchive(this)
        val manifest = manifest(StandardTestDispatcher(testScheduler))

        manifest.delete(listOf(11))

        archiveOf(11).exists() shouldBe false
        realManifestRepository.getByChapterId(11).shouldBeNull()
        finalDir(10).isDirectory shouldBe true
        manifest.isDownloaded(10) shouldBe true

        manifest.delete(listOf(10))

        finalDir(10).exists() shouldBe false
        realManifestRepository.getAll().shouldBeEmpty()
    }

    @Test
    fun `deleting every download of the entry removes the folder, the archive and the entry's folder`() = runTest {
        downloadOneFolderAndOneArchive(this)
        val entryDir = mangaDir(1)

        manifest(StandardTestDispatcher(testScheduler)).deleteManga(1)

        entryDir.exists() shouldBe false
        realManifestRepository.getAll().shouldBeEmpty()
    }

    // Already downloaded, and the R71 part 2 check

    @Test
    fun `a queued chapter that already has its archive is dropped as downloaded and not fetched again`() = runTest {
        saveAsCbz()
        enqueue(10)
        newEngine().run()
        val before = archiveOf(10).readBytes()
        val fetched = source.totalImageCalls()
        enqueue(10)

        newEngine().run() shouldBe DownloadEngine.Outcome(completed = 0, failed = 0)

        source.totalImageCalls() shouldBe fetched
        queueRepository.getActive().shouldBeEmpty()
        archiveOf(10).readBytes().toList() shouldBe before.toList()
        names() shouldContainExactly listOf(archiveOf(10).name)
    }

    @Test
    fun `a chapter with its archive is dropped as downloaded even when the switch is off by then`() = runTest {
        saveAsCbz()
        enqueue(10)
        newEngine().run()
        saveAsCbz(false)
        val fetched = source.totalImageCalls()
        enqueue(10)

        newEngine().run() shouldBe DownloadEngine.Outcome(completed = 0, failed = 0)

        source.totalImageCalls() shouldBe fetched
        finalDir(10).exists() shouldBe false
        queueRepository.getActive().shouldBeEmpty()
    }

    @Test
    fun `a row naming an archive whose file is gone is downloaded again and its own row is replaced`() = runTest {
        saveAsCbz()
        realManifestRepository.recordDownload(manifestEntry(chapter(10), manga(1), chapterDirName(10) + ".cbz"))
        enqueue(10)

        newEngine().run() shouldBe DownloadEngine.Outcome(completed = 1, failed = 0)

        archiveOf(10).isFile shouldBe true
        realManifestRepository.getByChapterId(10).shouldNotBeNull().sizeBytes shouldBe archiveOf(10).length()
    }

    @Test
    fun `another chapter's archive of the same name holds the name, so the chapter is refused`() = runTest {
        saveAsCbz()
        realManifestRepository.recordDownload(manifestEntry(chapter(11), manga(1), chapterDirName(10) + ".cbz"))
        enqueue(10)

        newEngine().run() shouldBe DownloadEngine.Outcome(completed = 0, failed = 1)

        queueRepository.getByChapterId(10).shouldNotBeNull().errorMessage shouldBe ErrorKey.DOWNLOAD_FOLDER_IN_USE.id
        source.totalImageCalls() shouldBe 0
        tmpDir(10).exists() shouldBe false
        archiveOf(10).exists() shouldBe false
        realManifestRepository.getByChapterId(11).shouldNotBeNull().chapterDirName shouldBe
            chapterDirName(10) + ".cbz"
    }

    @Test
    fun `another chapter's folder name holds the name too when the chapter would be an archive`() = runTest {
        saveAsCbz()
        realManifestRepository.recordDownload(manifestEntry(chapter(11), manga(1), chapterDirName(10)))
        enqueue(10)

        newEngine().run() shouldBe DownloadEngine.Outcome(completed = 0, failed = 1)

        queueRepository.getByChapterId(10).shouldNotBeNull().errorMessage shouldBe ErrorKey.DOWNLOAD_FOLDER_IN_USE.id
        source.totalImageCalls() shouldBe 0
    }

    @Test
    fun `with the switch off another chapter's archive of the same name does not hold the folder`() = runTest {
        realManifestRepository.recordDownload(manifestEntry(chapter(11), manga(1), chapterDirName(10) + ".cbz"))
        enqueue(10)

        newEngine().run() shouldBe DownloadEngine.Outcome(completed = 1, failed = 0)

        finalDir(10).isDirectory shouldBe true
    }

    // Temporary archive files

    @Test
    fun `the temporary folders of a chapter include its partial archive`() = runTest {
        val folder = tmpDir(10).apply { mkdirs() }
        val partial = archiveTmpOf(10).apply { writeBytes(byteArrayOf(1)) }
        val other = archiveTmpOf(11).apply { writeBytes(byteArrayOf(1)) }

        locator(StandardTestDispatcher(testScheduler)).findTmpDirs(SOURCE_ID, manga(1).title, chapter(10))
            .shouldContainExactlyInAnyOrder(folder, partial)

        other.exists() shouldBe true
    }

    @Test
    fun `removing a chapter from the queue deletes its partial archive with its pages`() = runTest {
        enqueue(10, 11)
        tmpDir(10).apply { mkdirs() }
        val partial = archiveTmpOf(10).apply { writeBytes(byteArrayOf(1)) }
        val kept = archiveTmpOf(11).apply { writeBytes(byteArrayOf(1)) }

        queue(newEngine()).remove(listOf(10))

        partial.exists() shouldBe false
        tmpDir(10).exists() shouldBe false
        kept.exists() shouldBe true
    }

    @Test
    fun `a run deletes a partial archive no queue row resumes and keeps the archive a row records`() = runTest {
        saveAsCbz()
        enqueue(10)
        newEngine().run()
        val recorded = archiveOf(10)
        val leftover = archiveTmpOf(11).apply { writeBytes(byteArrayOf(1)) }

        newEngine().run() shouldBe DownloadEngine.Outcome(completed = 0, failed = 0)

        leftover.exists() shouldBe false
        recorded.isFile shouldBe true
        realManifestRepository.getByChapterId(10).shouldNotBeNull()
    }
}

package com.shinsaikou.downloads.adoption

import com.hippo.unifile.UniFile
import com.shinsaikou.data.DatabaseTransactionRunner
import com.shinsaikou.domain.database.TransactionRunner
import com.shinsaikou.domain.download.model.DownloadManifestEntry
import com.shinsaikou.domain.download.repository.DownloadManifestRepository
import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.domain.error.KeyedFailure
import com.shinsaikou.downloads.DownloadEngine
import com.shinsaikou.downloads.DownloadManifestSweep
import com.shinsaikou.downloads.DownloadTestBase
import com.shinsaikou.downloads.MIN_FREE_BYTES
import com.shinsaikou.downloads.mangaDirIn
import com.shinsaikou.image.ImageUtil
import eu.kanade.tachiyomi.data.download.DownloadProvider
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE
import tachiyomi.data.source.DatabaseStubSourceNames
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.time.Duration.Companion.hours
import kotlin.time.measureTime

/**
 * The adoption of upstream's downloads (phase 2 unit J) over a generated upstream tree, against the real
 * repositories on the in-memory driver. The tree is read through [UniFileAdoptionNode] over plain
 * files, the class the worker opens the picked tree with, so the walker and the copier run exactly
 * as they do on a device; only the archive check counts a zip's entries here, because libarchive is
 * native code the JVM does not have.
 *
 * Every name here is synthetic; `CONTRIBUTING.md` binds test data too.
 */
class DownloadAdoptionTest : DownloadTestBase() {

    /** Set by the test that watches the moment a manifest row is written. */
    private var onRecord: (suspend (DownloadManifestEntry) -> Unit)? = null

    override val manifestRepository: DownloadManifestRepository
        get() = object : DownloadManifestRepository by realManifestRepository {
            override suspend fun recordDownload(entry: DownloadManifestEntry) {
                onRecord?.invoke(entry)
                realManifestRepository.recordDownload(entry)
            }
        }

    private val picked: File get() = File(filesDir, "picked")

    /** Upstream's folder for the fixture source: `HttpSource.toString`, "Fixture Source (EN)". */
    private val upstreamSourceDir = "Fixture Source (EN)"

    @BeforeEach
    fun seed() {
        insertManga(id = 1, title = "Title One")
        insertChapter(id = 10, mangaId = 1)
        insertChapter(id = 11, mangaId = 1, scanlator = "Group A")
        insertChapter(id = 12, mangaId = 1)
    }

    private fun TestScope.walker(): AdoptionWalker = AdoptionWalker(
        mangaRepository = mangaRepository,
        chapterRepository = chapterRepository,
        sourceManager = sourceManager,
        stubSourceNames = DatabaseStubSourceNames(handler),
        libraryPreferences = libraryPreferences,
        dispatchers = dispatchers(StandardTestDispatcher(testScheduler)),
    )

    private fun TestScope.adoption(
        transactionRunner: TransactionRunner = DatabaseTransactionRunner(handler),
    ): DownloadAdoption {
        val dispatchers = dispatchers(StandardTestDispatcher(testScheduler))
        val locator = locator(StandardTestDispatcher(testScheduler))
        val walker = walker()
        val copier = AdoptionCopier(
            downloadRoot = downloadRoot,
            locator = locator,
            manifestRepository = manifestRepository,
            queueRepository = queueRepository,
            transactionRunner = transactionRunner,
            comicInfoWriter = comicInfoWriter(StandardTestDispatcher(testScheduler)),
            writeLock = writeLock,
            archives = ZipChapterArchives,
            sourceManager = sourceManager,
            libraryPreferences = libraryPreferences,
            dispatchers = dispatchers,
        )
        val sweep = DownloadManifestSweep(manifestRepository, chapterRepository, locator, libraryPreferences, writeLock)
        return DownloadAdoption(walker, copier, sweep, downloadRoot, dispatchers)
    }

    private fun tree(root: File = picked): AdoptionNode = UniFileAdoptionNode(UniFile.fromFile(root)!!)

    /** Chapter [chapterId]'s current folder name, the one upstream writes today. */
    private suspend fun currentName(chapterId: Long): String {
        val chapter = chapter(chapterId)
        return DownloadProvider.getChapterDirName(chapter.name, chapter.scanlator, chapter.url, false)
    }

    /** An upstream chapter folder of [pages] PNG pages, named `001.png` on, under `downloads/`. */
    private fun upstreamFolder(name: String, pages: Int, source: String = upstreamSourceDir, manga: String = "Title One"): File =
        File(picked, "downloads/$source/$manga/$name").apply {
            mkdirs()
            repeat(pages) { File(this, "%03d.png".format(it + 1)).writeBytes(page("$name/$it")) }
        }

    /** An upstream `.cbz` of [pages] PNG pages under `downloads/`. */
    private fun upstreamArchive(name: String, pages: Int, manga: String = "Title One"): File =
        File(picked, "downloads/$upstreamSourceDir/$manga/$name").apply {
            parentFile.mkdirs()
            ZipOutputStream(outputStream()).use { zip ->
                repeat(pages) {
                    zip.putNextEntry(ZipEntry("%03d.png".format(it + 1)))
                    zip.write(page("$name/$it"))
                    zip.closeEntry()
                }
            }
        }

    /** Where the copy of chapter [name] of the fixture source's "Title One" lands. */
    private fun copied(name: String, sourceName: String = source.name, title: String = "Title One"): File =
        File(mangaDirIn(downloadRoot.root(), sourceName, title, false), name)

    @Test
    fun `a folder named exactly as DownloadProvider names its chapter is copied and recorded`() = runTest {
        val name = currentName(10)
        upstreamFolder(name, pages = 3)

        val summary = adoption().run(tree())

        summary.matched shouldBe 1
        summary.copied shouldBe 1
        summary.unmatched.shouldBeEmpty()
        summary.failures.shouldBeEmpty()
        val entry = realManifestRepository.getByChapterId(10).shouldNotBeNull()
        entry.chapterDirName shouldBe name
        entry.pageCount shouldBe 3
        entry.sourceId shouldBe SOURCE_ID
        entry.mangaTitle shouldBe "Title One"
        val dir = copied(name)
        dir.list()!!.sorted() shouldContainExactly listOf("001.png", "002.png", "003.png", COMIC_INFO_FILE)
        File(dir, "002.png").readBytes().toList() shouldBe page("$name/1").toList()
        File(dir.parentFile, name + DownloadProvider.TMP_DIR_SUFFIX).exists() shouldBe false
        manifest(StandardTestDispatcher(testScheduler)).getChapterDir(10) shouldBe dir
    }

    @Test
    fun `with too little storage free the run stops before copying and says so in the summary`() = runTest {
        upstreamFolder(currentName(10), pages = 2)
        upstreamFolder(currentName(12), pages = 2)
        freeBytes = MIN_FREE_BYTES - 1

        val summary = adoption().run(tree())

        summary.matched shouldBe 2
        summary.copied shouldBe 0
        val ofTheRun = AdoptionFailure(path = "", reason = AdoptionFailureReason.STORAGE_FULL)
        summary.failures shouldContainExactly listOf(ofTheRun)
        realManifestRepository.getAll().shouldBeEmpty()
        copied(currentName(10)).exists() shouldBe false

        freeBytes = MIN_FREE_BYTES
        adoption().run(tree()).copied shouldBe 2
    }

    @Test
    fun `a ComicInfo the folder carries is copied as it is, not written again`() = runTest {
        val name = currentName(10)
        File(upstreamFolder(name, pages = 1), COMIC_INFO_FILE).writeText("<ComicInfo><Title>Kept</Title></ComicInfo>")

        adoption().run(tree())

        File(copied(name), COMIC_INFO_FILE).readText() shouldBe "<ComicInfo><Title>Kept</Title></ComicInfo>"
    }

    @Test
    fun `a legacy chapter name, without the url hash, is matched and recorded under that name`() = runTest {
        val chapter = chapter(11)
        val legacy = DownloadProvider.getLegacyChapterDirNames(chapter.name, chapter.scanlator, chapter.url, false).first()
        legacy shouldBe "Group A_Chapter 11"
        upstreamFolder(legacy, pages = 2)

        val summary = adoption().run(tree())

        summary.copied shouldBe 1
        realManifestRepository.getByChapterId(11).shouldNotBeNull().chapterDirName shouldBe legacy
        manifest(StandardTestDispatcher(testScheduler)).isDownloaded(11) shouldBe true
    }

    @Test
    fun `the name the other ASCII setting gives is matched too`() = runTest {
        insertChapter(id = 13, mangaId = 1, name = "Chapitre été")
        val chapter = chapter(13)
        val ascii = DownloadProvider.getChapterDirName(chapter.name, chapter.scanlator, chapter.url, true)
        ascii shouldNotBe currentName(13)
        upstreamFolder(ascii, pages = 1)

        adoption().run(tree()).copied shouldBe 1

        realManifestRepository.getByChapterId(13).shouldNotBeNull().chapterDirName shouldBe ascii
    }

    @Test
    fun `a cbz is copied as a cbz, opened as an archive, and recorded with that name`() = runTest {
        val name = currentName(10) + ".cbz"
        val archive = upstreamArchive(name, pages = 4)

        val summary = adoption().run(tree())

        summary.copied shouldBe 1
        val entry = realManifestRepository.getByChapterId(10).shouldNotBeNull()
        entry.chapterDirName shouldBe name
        entry.pageCount shouldBe 4
        val copy = copied(name)
        copy.isFile shouldBe true
        copy.readBytes().toList() shouldBe archive.readBytes().toList()
        entry.sizeBytes shouldBe archive.length()
        manifest(StandardTestDispatcher(testScheduler)).getChapterDir(10) shouldBe copy
    }

    @Test
    fun `a cbz that is not an archive fails, and nothing is recorded or left behind`() = runTest {
        val name = currentName(10) + ".cbz"
        File(picked, "downloads/$upstreamSourceDir/Title One/$name").apply {
            parentFile.mkdirs()
            writeText("not an archive")
        }

        val summary = adoption().run(tree())

        summary.copied shouldBe 0
        summary.failures.single().reason shouldBe AdoptionFailureReason.NOT_AN_ARCHIVE
        summary.failures.single().path shouldBe "downloads/$upstreamSourceDir/Title One/$name"
        realManifestRepository.getByChapterId(10).shouldBeNull()
        copied(name).parentFile.list().orEmpty().toList().shouldBeEmpty()
    }

    @Test
    fun `folders that match nothing are listed by their path and left where they are`() = runTest {
        upstreamFolder(currentName(10), pages = 1)
        // A chapter folder under a url hash no chapter has, a manga folder of no library entry, a
        // source folder of no source, and a download upstream had not finished.
        upstreamFolder("Chapter 10_ffffff", pages = 1)
        upstreamFolder("Chapter 1_000000", pages = 1, manga = "Title Elsewhere")
        upstreamFolder("Chapter 1_000000", pages = 1, source = "Fixture Unknown (EN)", manga = "Title One")
        upstreamFolder(currentName(12) + DownloadProvider.TMP_DIR_SUFFIX, pages = 1)
        val before = snapshot(picked)

        val summary = adoption().run(tree())

        summary.copied shouldBe 1
        summary.unmatched.map { it.path }.sorted() shouldContainExactly listOf(
            "downloads/$upstreamSourceDir/Title Elsewhere",
            "downloads/$upstreamSourceDir/Title One/Chapter 10_ffffff",
            "downloads/$upstreamSourceDir/Title One/${currentName(12)}_tmp",
            "downloads/Fixture Unknown (EN)",
        ).sorted()
        realManifestRepository.getAll().map { it.chapterId } shouldContainExactly listOf(10L)
        snapshot(picked) shouldBe before
    }

    @Test
    fun `the downloads folder itself may be the picked tree, and the source folder name ignores case`() = runTest {
        upstreamFolder(currentName(10), pages = 1, source = "fixture source (en)")

        val summary = adoption().run(tree(File(picked, "downloads")))

        summary.copied shouldBe 1
        summary.unmatched.shouldBeEmpty()
    }

    @Test
    fun `a source that is not installed is matched through the name remembered for its id`() = runTest {
        insertManga(id = 2, title = "Title Two", sourceId = GONE_SOURCE_ID)
        insertChapter(id = 20, mangaId = 2)
        insertManga(id = 3, title = "Title Three", sourceId = FORGOTTEN_SOURCE_ID)
        insertChapter(id = 30, mangaId = 3)
        DatabaseStubSourceNames(handler).remember(GONE_SOURCE_ID, "Fixture Gone", "fr")
        val name = currentName(20)
        upstreamFolder(name, pages = 2, source = "Fixture Gone (FR)", manga = "Title Two")
        // Nothing remembers a name for this id, so its folder cannot be told apart from any other.
        upstreamFolder(currentName(30), pages = 1, source = "Fixture Forgotten (EN)", manga = "Title Three")

        val summary = adoption().run(tree())

        summary.copied shouldBe 1
        summary.unmatched.map { it.path } shouldContainExactly listOf("downloads/Fixture Forgotten (EN)")
        val entry = realManifestRepository.getByChapterId(20).shouldNotBeNull()
        entry.sourceId shouldBe GONE_SOURCE_ID
        copied(name, sourceName = "Fixture Gone", title = "Title Two").isDirectory shouldBe true
        // The locator walks every source folder, so the reader finds it with no source loaded.
        manifest(StandardTestDispatcher(testScheduler)).getChapterDir(20).shouldNotBeNull()
    }

    @Test
    fun `a source a backup remembered without a language is matched by its name, unless the name is shared`() =
        runTest {
            insertManga(id = 4, title = "Title Four", sourceId = RESTORED_SOURCE_ID)
            insertChapter(id = 40, mangaId = 4)
            insertManga(id = 5, title = "Title Five", sourceId = TWIN_SOURCE_ID)
            insertChapter(id = 50, mangaId = 5)
            insertManga(id = 6, title = "Title Six", sourceId = OTHER_TWIN_SOURCE_ID)
            insertChapter(id = 60, mangaId = 6)
            // What the backup importer remembers: upstream's backup names a source but gives no language.
            val names = DatabaseStubSourceNames(handler)
            names.remember(RESTORED_SOURCE_ID, "Fixture Restored", "")
            names.remember(TWIN_SOURCE_ID, "Fixture Twin", "")
            names.remember(OTHER_TWIN_SOURCE_ID, "Fixture Twin", "")
            val name = currentName(40)
            upstreamFolder(name, pages = 1, source = "Fixture Restored (PT-BR)", manga = "Title Four")
            upstreamFolder(currentName(50), pages = 1, source = "Fixture Twin (EN)", manga = "Title Five")
            // The name alone, with no language after it, is not the form upstream writes.
            upstreamFolder(currentName(40), pages = 1, source = "Fixture Restored", manga = "Title Four")

            val summary = adoption().run(tree())

            summary.copied shouldBe 1
            summary.unmatched.map { it.path }.sorted() shouldContainExactly
                listOf("downloads/Fixture Restored", "downloads/Fixture Twin (EN)")
            realManifestRepository.getByChapterId(40).shouldNotBeNull().sourceId shouldBe RESTORED_SOURCE_ID
            copied(name, sourceName = "Fixture Restored", title = "Title Four").isDirectory shouldBe true
        }

    @Test
    fun `a copy whose page count differs from the folder's is not recorded and leaves nothing behind`() = runTest {
        val name = currentName(10)
        upstreamFolder(name, pages = 2)
        // A provider can list two documents under one display name; both then land on one file.
        val tree = RewritingNode(tree(), rewrite = { node ->
            if (node.name == name) node.children() + node.children().first { it.name == "001.png" } else node.children()
        })

        val summary = adoption().run(tree)

        summary.copied shouldBe 0
        val failure = summary.failures.single()
        failure.reason shouldBe AdoptionFailureReason.PAGE_COUNT_MISMATCH
        failure.detail shouldBe "3/2"
        realManifestRepository.getByChapterId(10).shouldBeNull()
        copied(name).exists() shouldBe false
        File(copied(name).parentFile, name + DownloadProvider.TMP_DIR_SUFFIX).exists() shouldBe false
    }

    @Test
    fun `a copy that fails keeps the failure's key and the detail the shared resolver gives it`() = runTest {
        val name = currentName(10)
        upstreamFolder(name, pages = 2)
        val tree = RewritingNode(tree(), onOpen = { path ->
            if (path == "$name/002.png") throw Keyed(ErrorKey.WEBDAV_HTTP, "507")
        })

        val summary = adoption().run(tree)

        summary.copied shouldBe 0
        val failure = summary.failures.single()
        failure.reason shouldBe AdoptionFailureReason.ERROR
        failure.key shouldBe ErrorKey.WEBDAV_HTTP
        failure.detail shouldBe "507"
        realManifestRepository.getByChapterId(10).shouldBeNull()
        File(copied(name).parentFile, name + DownloadProvider.TMP_DIR_SUFFIX).exists() shouldBe false
    }

    @Test
    fun `no manifest row is written before the copy has its final name`() = runTest {
        upstreamFolder(currentName(10), pages = 2)
        upstreamArchive(currentName(12) + ".cbz", pages = 2)
        val seen = mutableListOf<Triple<String, Boolean, Boolean>>()
        onRecord = { entry ->
            val final = copied(entry.chapterDirName)
            val tmp = File(final.parentFile, entry.chapterDirName + DownloadProvider.TMP_DIR_SUFFIX)
            seen += Triple(entry.chapterDirName, final.exists(), tmp.exists())
        }

        adoption().run(tree()).copied shouldBe 2

        seen shouldContainExactly listOf(
            Triple(currentName(10), true, false),
            Triple(currentName(12) + ".cbz", true, false),
        )
    }

    @Test
    fun `an interrupted run leaves no row for the chapter it was copying, and the next run finishes the rest`() =
        runTest {
            upstreamFolder(currentName(10), pages = 2)
            upstreamFolder(currentName(12), pages = 3)
            val stopAt = currentName(12)
            lateinit var run: Job
            val stopping = RewritingNode(tree(), onOpen = { path -> if (path == "$stopAt/002.png") run.cancel() })

            run = launch { adoption().run(stopping) }
            run.join()

            run.isCancelled shouldBe true
            realManifestRepository.getByChapterId(10).shouldNotBeNull()
            realManifestRepository.getByChapterId(12).shouldBeNull()
            copied(stopAt).exists() shouldBe false
            File(copied(stopAt).parentFile, stopAt + DownloadProvider.TMP_DIR_SUFFIX).list()!!.sorted() shouldBe
                listOf("001.png", "002.png")
            val firstCopy = copied(currentName(10)).listFiles()!!.associate { it.name to it.lastModified() }

            // A new worker: nothing but the database and the files carries over.
            val summary = adoption().run(tree())

            summary.matched shouldBe 2
            summary.skipped shouldBe 1
            summary.copied shouldBe 1
            realManifestRepository.getByChapterId(12).shouldNotBeNull().pageCount shouldBe 3
            copied(stopAt).list()!!.filter { it != COMIC_INFO_FILE }.sorted() shouldBe
                listOf("001.png", "002.png", "003.png")
            File(copied(stopAt).parentFile, stopAt + DownloadProvider.TMP_DIR_SUFFIX).exists() shouldBe false
            copied(currentName(10)).listFiles()!!.associate { it.name to it.lastModified() } shouldBe firstCopy
        }

    @Test
    fun `a second run over an adopted tree copies nothing and changes nothing in the tree`() = runTest {
        upstreamFolder(currentName(10), pages = 2)
        upstreamArchive(currentName(11) + ".cbz", pages = 2)
        val before = snapshot(picked)

        adoption().run(tree()).copied shouldBe 2
        val second = adoption().run(tree())

        second.matched shouldBe 2
        second.skipped shouldBe 2
        second.copied shouldBe 0
        snapshot(picked) shouldBe before
    }

    @Test
    fun `a chapter queued for download is left to the engine`() = runTest {
        upstreamFolder(currentName(10), pages = 1)
        queueRepository.enqueue(chapterId = 10, mangaId = 1, now = 0)

        val summary = adoption().run(tree())

        summary.failures.single().reason shouldBe AdoptionFailureReason.QUEUED
        realManifestRepository.getByChapterId(10).shouldBeNull()
    }

    @Test
    fun `when a folder and an archive both match one chapter the folder is taken and the archive listed`() = runTest {
        upstreamFolder(currentName(10), pages = 1)
        upstreamArchive(currentName(10) + ".cbz", pages = 1)

        val summary = adoption().run(tree())

        summary.copied shouldBe 1
        realManifestRepository.getByChapterId(10).shouldNotBeNull().chapterDirName shouldBe currentName(10)
        summary.unmatched.map { it.path } shouldContainExactly listOf("downloads/$upstreamSourceDir/Title One/${currentName(10)}.cbz")
    }

    /**
     * Each level gives its own reason (unit adoption-unmatched-reasons): a source folder of no
     * source, an entry folder of no library entry, a chapter name no chapter has, a download upstream
     * had not finished, and a second copy of a chapter already taken. Every one carries the source
     * folder read as upstream writes it, and those under an entry folder that folder's name; a chapter
     * that matched no chapter carries the entry whose chapter list it is missing from.
     */
    @Test
    fun `every unmatched path carries the reason its level gave, the source folder's name and language, and its entry`() =
        runTest {
            upstreamFolder(currentName(10), pages = 1)
            upstreamArchive(currentName(10) + ".cbz", pages = 1)
            upstreamFolder("Chapter 10_ffffff", pages = 1)
            upstreamFolder(currentName(12) + DownloadProvider.TMP_DIR_SUFFIX, pages = 1)
            upstreamFolder("Chapter 1_000000", pages = 1, manga = "Title Elsewhere")
            upstreamFolder("Chapter 1_000000", pages = 1, source = "Fixture Unknown (PT-BR)", manga = "Title One")
            upstreamFolder("Chapter 1_000000", pages = 1, source = "Fixture Bare", manga = "Title One")

            val summary = adoption().run(tree())

            summary.copied shouldBe 1
            val inSource = "downloads/$upstreamSourceDir"
            summary.unmatched.sortedBy { it.path } shouldContainExactly listOf(
                AdoptionUnmatched(
                    "$inSource/Title Elsewhere",
                    AdoptionUnmatchedReason.NOT_IN_LIBRARY,
                    sourceName = "Fixture Source",
                    sourceLang = "EN",
                    title = "Title Elsewhere",
                ),
                AdoptionUnmatched(
                    "$inSource/Title One/Chapter 10_ffffff",
                    AdoptionUnmatchedReason.NO_CHAPTER,
                    sourceName = "Fixture Source",
                    sourceLang = "EN",
                    title = "Title One",
                    mangaId = 1,
                ),
                AdoptionUnmatched(
                    "$inSource/Title One/${currentName(10)}.cbz",
                    AdoptionUnmatchedReason.DUPLICATE,
                    sourceName = "Fixture Source",
                    sourceLang = "EN",
                    title = "Title One",
                ),
                AdoptionUnmatched(
                    "$inSource/Title One/${currentName(12)}${DownloadProvider.TMP_DIR_SUFFIX}",
                    AdoptionUnmatchedReason.UNFINISHED,
                    sourceName = "Fixture Source",
                    sourceLang = "EN",
                    title = "Title One",
                ),
                AdoptionUnmatched(
                    "downloads/Fixture Bare",
                    AdoptionUnmatchedReason.SOURCE_NOT_INSTALLED,
                    sourceName = "Fixture Bare",
                ),
                AdoptionUnmatched(
                    "downloads/Fixture Unknown (PT-BR)",
                    AdoptionUnmatchedReason.SOURCE_NOT_INSTALLED,
                    sourceName = "Fixture Unknown",
                    sourceLang = "PT-BR",
                ),
            ).sortedBy { it.path }
        }

    /**
     * The 40,000 rule's figure for the report (unit adoption-unmatched-reasons): a tree of 1,000
     * library entries of two one-page chapters each, walked alone, then copied, then copied again
     * with everything there, which is what the summary's Copy again does. Printed, not asserted,
     * since the host is shared; the report quotes it.
     */
    @Test
    fun `cost of a tree of 1,000 entries`() = runTest {
        (1..1000).forEach { index ->
            val mangaId = 1000L + index
            insertManga(id = mangaId, title = "Title Bulk $index")
            repeat(2) { n ->
                val chapterId = mangaId * 10 + n
                insertChapter(id = chapterId, mangaId = mangaId)
                upstreamFolder(currentName(chapterId), pages = 1, manga = "Title Bulk $index")
            }
        }

        val walk: AdoptionWalker.Result
        val walkTime = measureTime { walk = walker().walk(tree()) }
        val first: AdoptionSummary
        val copyTime = measureTime { first = adoption().run(tree()) }
        val again: AdoptionSummary
        val againTime = measureTime { again = adoption().run(tree()) }

        walk.matches.size shouldBe 2000
        walk.unmatched.shouldBeEmpty()
        first.copied shouldBe 2000
        again.skipped shouldBe 2000
        println(
            "ADOPTION_COST entries=1000 chapters=2000 walk_ms=${walkTime.inWholeMilliseconds} " +
                "copy_ms=${copyTime.inWholeMilliseconds} again_ms=${againTime.inWholeMilliseconds}",
        )
    }

    @Test
    fun `a source folder's name reads back into upstream's name and language, or whole when it has none`() {
        AdoptionWalker.parseSourceDirName("Fixture Source (EN)") shouldBe ("Fixture Source" to "EN")
        AdoptionWalker.parseSourceDirName("Fixture Source (pt-BR)") shouldBe ("Fixture Source" to "pt-BR")
        AdoptionWalker.parseSourceDirName("Fixture (Old) Source (FR)") shouldBe ("Fixture (Old) Source" to "FR")
        AdoptionWalker.parseSourceDirName("Fixture Source") shouldBe ("Fixture Source" to null)
        AdoptionWalker.parseSourceDirName("Fixture Source (two words)") shouldBe ("Fixture Source (two words)" to null)
        AdoptionWalker.parseSourceDirName(" (EN)") shouldBe (" (EN)" to null)
    }

    @Test
    fun `a copy a stopped run renamed but never recorded is replaced, not trusted`() = runTest {
        val name = currentName(10)
        upstreamFolder(name, pages = 2)
        copied(name).apply {
            mkdirs()
            File(this, "001.png").writeBytes(byteArrayOf(1))
        }

        adoption().run(tree()).copied shouldBe 1

        File(copied(name), "001.png").readBytes().toList() shouldBe page("$name/0").toList()
        realManifestRepository.getByChapterId(10).shouldNotBeNull().pageCount shouldBe 2
    }

    @Test
    fun `a folder another download's row still names is left to that download`() = runTest {
        val name = currentName(10)
        upstreamFolder(name, pages = 1)
        val other = File(copied(name), "001.png").apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(7))
        }
        realManifestRepository.recordDownload(manifestEntry(chapter(12), manga(1), name))

        val summary = adoption().run(tree())

        summary.failures.single().reason shouldBe AdoptionFailureReason.DESTINATION_IN_USE
        other.readBytes().toList() shouldBe listOf<Byte>(7)
        realManifestRepository.getByChapterId(10).shouldBeNull()
    }

    @Test
    fun `a chapter queued while it is being copied is downloaded by the engine alone, after the copy`() = runTest {
        val name = currentName(10)
        upstreamFolder(name, pages = 2)
        val engine = engine(StandardTestDispatcher(testScheduler))
        var download: Job? = null
        // The owner taps Download once the copy has begun, and the download worker starts at once.
        val tree = RewritingNode(tree(), onOpen = { path ->
            if (path == "$name/001.png" && download == null) {
                download = launch {
                    queueRepository.enqueue(chapterId = 10, mangaId = 1, now = 0)
                    engine.run() shouldBe DownloadEngine.Outcome(completed = 1, failed = 0)
                }
            }
        })

        // The copy's commit waits an hour, time enough for an engine that did not wait to finish.
        val summary = adoption(transactionRunner = slowCommit()).run(tree)
        download.shouldNotBeNull().join()

        summary.failures.single().reason shouldBe AdoptionFailureReason.QUEUED
        realManifestRepository.getByChapterId(10).shouldNotBeNull().pageCount shouldBe PAGES.toLong()
        val dir = copied(name)
        dir.list()!!.filter { it != COMIC_INFO_FILE }.size shouldBe PAGES
        dir.listFiles()!!.filter { it.name != COMIC_INFO_FILE }.sortedBy { it.name }.forEachIndexed { index, file ->
            file.readBytes().toList() shouldBe FakeSource.pageBytes(FakeSource.imageUrl("/ch/10", index)).toList()
        }
        queueRepository.getByChapterId(10).shouldBeNull()
    }

    @Test
    fun `a download re-matched to the chapter while it is being copied keeps its row and its files`() = runTest {
        val name = currentName(10)
        upstreamFolder(name, pages = 2)
        // Chapter 10's own download under its name without the hash, recorded for a chapter row
        // that has since been replaced.
        val legacy = "Chapter 10"
        val kept = File(copied(legacy), "001.png").apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(7))
        }
        val sweep = DownloadManifestSweep(
            manifestRepository,
            chapterRepository,
            locator(StandardTestDispatcher(testScheduler)),
            libraryPreferences,
            writeLock,
        )
        var rematch: Job? = null
        // The download settings screen counts unmatched downloads while the copy runs.
        val tree = RewritingNode(tree(), onOpen = { path ->
            if (path == "$name/001.png" && rematch == null) {
                rematch = launch {
                    realManifestRepository.recordDownload(
                        manifestEntry(chapter(10), manga(1), legacy).copy(chapterId = REPLACED_CHAPTER_ID),
                    )
                    sweep.rematch() shouldBe 1
                }
            }
        })

        val summary = adoption().run(tree)
        rematch.shouldNotBeNull().join()

        summary.copied shouldBe 0
        summary.skipped shouldBe 1
        realManifestRepository.getByChapterId(10).shouldNotBeNull().chapterDirName shouldBe legacy
        kept.readBytes().toList() shouldBe listOf<Byte>(7)
        copied(name).exists() shouldBe false
    }

    @Test
    fun `a folder a delisted chapter of another entry with the same title still names is left to it`() = runTest {
        // A second library entry of the same source with the same title: one folder holds both.
        insertManga(id = 2, title = "Title One")
        insertChapter(id = 100, mangaId = 1, name = "Chapter 5", scanlator = "Group B", url = "/ch/5")
        insertChapter(id = 200, mangaId = 2, name = "Chapter 5", scanlator = "Group B", url = "/ch/5")
        val legacy = "Group B_Chapter 5"
        val kept = File(copied(legacy), "001.png").apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(7))
        }
        realManifestRepository.recordDownload(manifestEntry(chapter(100), manga(1), legacy))
        // The source delisted chapter 100: its row is soft-deleted, and its download is kept.
        exec("UPDATE chapters SET deleted_at = 1 WHERE _id = 100")
        upstreamFolder(legacy, pages = 1)
        // A row under chapter 10's folder name but of another title names another folder.
        insertManga(id = 3, title = "Title Elsewhere")
        insertChapter(id = 300, mangaId = 3)
        realManifestRepository.recordDownload(manifestEntry(chapter(300), manga(3), currentName(10)))
        upstreamFolder(currentName(10), pages = 1)

        val summary = adoption().run(tree())

        summary.matched shouldBe 2
        summary.copied shouldBe 1
        val failure = summary.failures.single()
        failure.reason shouldBe AdoptionFailureReason.DESTINATION_IN_USE
        failure.path shouldBe "downloads/$upstreamSourceDir/Title One/$legacy"
        kept.readBytes().toList() shouldBe listOf<Byte>(7)
        realManifestRepository.getByChapterId(200).shouldBeNull()
        realManifestRepository.getByChapterId(100).shouldNotBeNull().chapterDirName shouldBe legacy
        realManifestRepository.getByChapterId(10).shouldNotBeNull().chapterDirName shouldBe currentName(10)
    }

    @Test
    fun `a folder name another chapter's row holds is refused even while that row's files are gone`() = runTest {
        val name = currentName(10)
        upstreamFolder(name, pages = 1)
        realManifestRepository.recordDownload(manifestEntry(chapter(12), manga(1), name))

        val summary = adoption().run(tree())

        summary.failures.single().reason shouldBe AdoptionFailureReason.DESTINATION_IN_USE
        copied(name).exists() shouldBe false
        realManifestRepository.getByChapterId(10).shouldBeNull()
    }

    @Test
    fun `a matched folder with no image in it fails with no pages, and nothing is recorded or left behind`() = runTest {
        val name = currentName(10)
        File(picked, "downloads/$upstreamSourceDir/Title One/$name").apply {
            mkdirs()
            File(this, "notes.txt").writeText("not a page")
        }

        val summary = adoption().run(tree())

        summary.matched shouldBe 1
        summary.copied shouldBe 0
        val failure = summary.failures.single()
        failure.reason shouldBe AdoptionFailureReason.NO_PAGES
        failure.path shouldBe "downloads/$upstreamSourceDir/Title One/$name"
        realManifestRepository.getByChapterId(10).shouldBeNull()
        copied(name).exists() shouldBe false
        File(copied(name).parentFile, name + DownloadProvider.TMP_DIR_SUFFIX).exists() shouldBe false
    }

    @Test
    fun `a run started before the source registry has loaded waits for it before matching a source folder`() =
        runTest {
            // WorkManager resumed the run as the process started: nothing is installed yet.
            sourceManager.isInitialized.value = false
            val name = currentName(10)
            upstreamFolder(name, pages = 2)

            var summary: AdoptionSummary? = null
            launch { summary = adoption().run(tree()) }
            advanceUntilIdle()

            summary.shouldBeNull()
            realManifestRepository.getAll().shouldBeEmpty()

            sourceManager.isInitialized.value = true
            advanceUntilIdle()

            val done = summary.shouldNotBeNull()
            done.copied shouldBe 1
            done.unmatched.shouldBeEmpty()
            realManifestRepository.getByChapterId(10).shouldNotBeNull().chapterDirName shouldBe name
        }

    @Test
    fun `a run stopped while it waits for the source registry ends with nothing written`() = runTest {
        sourceManager.isInitialized.value = false
        upstreamFolder(currentName(10), pages = 2)
        val before = snapshot(picked)

        val run = launch { adoption().run(tree()) }
        advanceUntilIdle()
        run.cancel()
        run.join()

        run.isCancelled shouldBe true
        realManifestRepository.getAll().shouldBeEmpty()
        downloadRoot.root().walkTopDown().filter { it.isFile }.toList().shouldBeEmpty()
        snapshot(picked) shouldBe before
    }

    /** The copier's commit transaction, entered after an hour of the test's virtual time. */
    private fun slowCommit() = object : TransactionRunner {
        private val real = DatabaseTransactionRunner(handler)

        override suspend fun <T> inTransaction(block: suspend () -> T): T {
            delay(1.hours)
            return real.inTransaction(block)
        }
    }

    /** Every path under [root] with a digest of its bytes and its modification time. */
    private fun snapshot(root: File): Map<String, String> = root.walkTopDown().associate { file ->
        val digest = if (file.isFile) {
            MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        } else {
            "dir"
        }
        file.relativeTo(root).path to "$digest ${file.lastModified()}"
    }

    /** A PNG signature followed by [label], so every page's bytes are its own. */
    private fun page(label: String): ByteArray =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + label.toByteArray()

    /**
     * A tree that answers as [delegate] does, except that [rewrite] may change a directory's
     * listing and [onOpen] sees every file opened, as `<parent>/<name>`.
     */
    private class RewritingNode(
        private val delegate: AdoptionNode,
        private val parentName: String = "",
        private val onOpen: (String) -> Unit = {},
        private val rewrite: (AdoptionNode) -> List<AdoptionNode> = { it.children() },
    ) : AdoptionNode by delegate {

        override fun children(): List<AdoptionNode> =
            rewrite(delegate).map { RewritingNode(it, delegate.name, onOpen, rewrite) }

        override fun openStream(): InputStream {
            onOpen("$parentName/${delegate.name}")
            return delegate.openStream()
        }
    }

    /** A failure that declares its own key and detail, the way sync's and a tracker's do. */
    private class Keyed(
        override val errorKey: ErrorKey,
        override val errorDetail: String?,
    ) : IOException("keyed text"), KeyedFailure

    /** Counts a zip's pages the way the reader's archive loader decides a page. */
    private object ZipChapterArchives : ChapterArchives {
        override fun pageCount(file: File): Int = ZipFile(file).use { zip ->
            zip.entries().toList().count { entry ->
                !entry.isDirectory && ImageUtil.isImage(entry.name) { zip.getInputStream(entry) }
            }
        }
    }

    private companion object {
        const val GONE_SOURCE_ID = 8L
        const val FORGOTTEN_SOURCE_ID = 9L
        const val RESTORED_SOURCE_ID = 10L
        const val TWIN_SOURCE_ID = 11L
        const val OTHER_TWIN_SOURCE_ID = 12L

        /** A chapter id no chapter row has: the row a download was recorded under before a refresh replaced it. */
        const val REPLACED_CHAPTER_ID = 99L
    }
}

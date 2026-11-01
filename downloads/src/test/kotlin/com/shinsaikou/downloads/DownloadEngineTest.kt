package com.shinsaikou.downloads

import com.shinsaikou.core.metadata.comicinfo.ComicInfoXml
import com.shinsaikou.data.DatabaseTransactionRunner
import com.shinsaikou.domain.download.model.DownloadManifestEntry
import com.shinsaikou.domain.download.model.DownloadState
import com.shinsaikou.domain.download.repository.DownloadManifestRepository
import com.shinsaikou.domain.error.ErrorKey
import eu.kanade.tachiyomi.network.HttpException
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.track.model.Track
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class DownloadEngineTest : DownloadTestBase() {

    private val scheduler = mockk<DownloadScheduler>(relaxed = true) {
        every { isRunning } returns flowOf(false)
    }

    /** Set by the test that watches the moment a manifest row is written. */
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

    private fun TestScope.newEngine() = engine(StandardTestDispatcher(testScheduler))

    private fun pageFiles(dir: File): List<String> =
        dir.list().orEmpty().filter { it != COMIC_INFO_FILE }.sorted()

    @Test
    fun `an enqueued chapter ends in the manifest with its pages, its ComicInfo and no temporary files`() = runTest {
        val engine = newEngine()
        val queue = queue(engine)

        queue.enqueue(manga(1), listOf(chapter(10), chapter(11)), autoStart = true)
        coVerify(exactly = 1) { scheduler.start() }
        queueRepository.getActive().map { it.chapterId } shouldContainExactly listOf(10L, 11L)

        val outcome = engine.run()

        outcome shouldBe DownloadEngine.Outcome(completed = 2, failed = 0)
        queueRepository.getActive().shouldBeEmpty()
        for (chapterId in listOf(10L, 11L)) {
            val entry = realManifestRepository.getByChapterId(chapterId).shouldNotBeNull()
            val dir = finalDir(chapterId)
            entry.chapterDirName shouldBe dir.name
            entry.pageCount shouldBe PAGES
            entry.sourceId shouldBe SOURCE_ID
            entry.sizeBytes shouldBe dir.listFiles()!!.sumOf { it.length() }
            pageFiles(dir) shouldContainExactly listOf("001.png", "002.png", "003.png", "004.png", "005.png")
            File(dir, COMIC_INFO_FILE).isFile shouldBe true
            tmpDir(chapterId).exists() shouldBe false
            // Page 3 of the list holds page 3's bytes, whatever index the source gave it.
            File(dir, "003.png").readBytes() shouldBe FakeSource.pageBytes(FakeSource.imageUrl("/ch/$chapterId", 2))
        }
        val manifest = manifest(StandardTestDispatcher(testScheduler))
        manifest.isDownloaded(10) shouldBe true
        manifest.getPages(10).shouldNotBeNull().size shouldBe PAGES
        source.totalImageCalls() shouldBe 2 * PAGES
    }

    /** What the notification says beside the chapter: the chapters left, the one downloading included. */
    @Test
    fun `progress names the chapter in flight and the chapters left`() = runTest {
        queueRepository.enqueue(chapterId = 10, mangaId = 1, now = 0)
        queueRepository.enqueue(chapterId = 11, mangaId = 1, now = 0)
        val reports = mutableListOf<DownloadEngine.Progress>()

        newEngine().run { reports += it }

        reports.map { Triple(it.chapterId, it.chapterName, it.chaptersLeft) }.distinct() shouldContainExactly listOf(
            Triple(10L, "Chapter 10", 2),
            Triple(11L, "Chapter 11", 1),
        )
        reports.last() shouldBe DownloadEngine.Progress(11, "Title 1", "Chapter 11", PAGES, PAGES, chaptersLeft = 1)
    }

    @Test
    fun `a worker stopped mid-chapter keeps the row and its progress, and a new one fetches only the missing pages`() =
        runTest {
            val reachedFourthPage = CompletableDeferred<Unit>()
            source.onImage = { page, attempt ->
                if (page.imageUrl == FakeSource.imageUrl("/ch/10", 3) && attempt == 1) {
                    reachedFourthPage.complete(Unit)
                    awaitCancellation()
                }
            }
            queueRepository.enqueue(chapterId = 10, mangaId = 1, now = 0)

            val firstWorker = launch { newEngine().run() }
            reachedFourthPage.await()
            firstWorker.cancelAndJoin()

            val stopped = queueRepository.getByChapterId(10).shouldNotBeNull()
            stopped.state shouldBe DownloadState.QUEUED
            stopped.downloadedPages shouldBe 3
            stopped.totalPages shouldBe PAGES
            realManifestRepository.getByChapterId(10).shouldBeNull()
            pageFiles(tmpDir(10)) shouldContainExactly listOf("001.png", "002.png", "003.png")
            // A write the process died in: it sits under its temporary name and is not a page.
            File(tmpDir(10), "004.tmp").writeBytes(byteArrayOf(1, 2, 3))

            newEngine().run() shouldBe DownloadEngine.Outcome(completed = 1, failed = 0)

            (0 until 3).forEach { source.imageCallsFor("/ch/10", it) shouldBe 1 }
            source.imageCallsFor("/ch/10", 3) shouldBe 2
            source.imageCallsFor("/ch/10", 4) shouldBe 1
            source.pageListCalls.get() shouldBe 2
            realManifestRepository.getByChapterId(10).shouldNotBeNull()
            pageFiles(finalDir(10)) shouldContainExactly listOf("001.png", "002.png", "003.png", "004.png", "005.png")
            File(finalDir(10), "004.png").readBytes() shouldBe FakeSource.pageBytes(FakeSource.imageUrl("/ch/10", 3))
        }

    /**
     * The force-stop row's resume: the worker starts with the process, before the extension host
     * has loaded. The engine waits for the registry, and the registry is ready only once every
     * provider has loaded (`DefaultSourceManagerTest`), so the chapter is fetched instead of failing
     * as a source that is not installed.
     */
    @Test
    fun `a run waits for the source registry and fails nothing while it waits`() = runTest {
        sourceManager.isInitialized.value = false
        queueRepository.enqueue(chapterId = 10, mangaId = 1, now = 0)

        var outcome: DownloadEngine.Outcome? = null
        launch { outcome = newEngine().run() }
        advanceUntilIdle()

        outcome.shouldBeNull()
        source.pageListCalls.get() shouldBe 0
        queueRepository.getByChapterId(10).shouldNotBeNull().state shouldBe DownloadState.QUEUED

        sourceManager.isInitialized.value = true
        advanceUntilIdle()

        outcome shouldBe DownloadEngine.Outcome(completed = 1, failed = 0)
        realManifestRepository.getByChapterId(10).shouldNotBeNull()
    }

    @Test
    fun `no manifest row exists before the chapter folder has its final name`() = runTest {
        val observed = mutableListOf<String>()
        source.onImage = { _, _ ->
            if (realManifestRepository.getByChapterId(10) != null) observed += "row while fetching pages"
        }
        onRecord = { entry ->
            val final = finalDir(entry.chapterId)
            if (!final.isDirectory) observed += "row before the final folder exists"
            if (tmpDir(entry.chapterId).exists()) observed += "row while the _tmp folder exists"
            if (pageFiles(final).size != PAGES) observed += "row before every page is in the final folder"
            if (!File(final, COMIC_INFO_FILE).isFile) observed += "row before ComicInfo.xml"
            if (queueRepository.getByChapterId(entry.chapterId) == null) observed += "queue row gone before the insert"
            observed += "recorded ${entry.chapterId}"
        }
        queueRepository.enqueue(chapterId = 10, mangaId = 1, now = 0)

        newEngine().run()

        observed shouldContainExactly listOf("recorded 10")
        queueRepository.getByChapterId(10).shouldBeNull()
    }

    @Test
    fun `a failing page is retried after 2, 4 and 8 seconds and the chapter completes`() = runTest {
        val attemptTimes = mutableListOf<Long>()
        source.onImage = { page, attempt ->
            if (page.imageUrl == FakeSource.imageUrl("/ch/10", 1)) {
                attemptTimes += testScheduler.currentTime
                if (attempt <= DownloadEngine.MAX_RETRIES) throw IOException("HTTP error 503")
            }
        }
        queueRepository.enqueue(chapterId = 10, mangaId = 1, now = 0)

        newEngine().run() shouldBe DownloadEngine.Outcome(completed = 1, failed = 0)

        attemptTimes.zipWithNext { a, b -> b - a } shouldContainExactly listOf(2_000L, 4_000L, 8_000L)
        realManifestRepository.getByChapterId(10).shouldNotBeNull()
    }

    /** What the library update already honours: the wait the site named, within a minute. */
    @Test
    fun `a page refused with a Retry-After waits what it asks, up to a minute, never less than the backoff`() =
        runTest {
            val attemptTimes = mutableListOf<Long>()
            source.onImage = { page, attempt ->
                if (page.imageUrl == FakeSource.imageUrl("/ch/10", 1)) {
                    attemptTimes += testScheduler.currentTime
                    when (attempt) {
                        1 -> throw HttpException(refusal(429, retryAfter = "20"))
                        2 -> throw HttpException(refusal(503, retryAfter = "600"))
                        3 -> throw HttpException(refusal(429, retryAfter = "1"))
                    }
                }
            }
            queueRepository.enqueue(chapterId = 10, mangaId = 1, now = 0)

            newEngine().run() shouldBe DownloadEngine.Outcome(completed = 1, failed = 0)

            attemptTimes.zipWithNext { a, b -> b - a } shouldContainExactly listOf(20_000L, 60_000L, 8_000L)
        }

    private fun refusal(code: Int, retryAfter: String): Response = Response.Builder()
        .request(Request.Builder().url(FakeSource.imageUrl("/ch/10", 1)).build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("Fixture")
        .header("Retry-After", retryAfter)
        .build()

    @Test
    fun `a page that keeps failing leaves its chapter in error with its pages kept, and the run moves on`() = runTest {
        var broken = true
        source.onImage = { page, _ ->
            if (broken && page.imageUrl == FakeSource.imageUrl("/ch/10", 1)) throw IOException("HTTP error 503")
        }
        val engine = newEngine()
        val queue = queue(engine)
        queue.enqueue(manga(1), listOf(chapter(10), chapter(11)), autoStart = false)

        engine.run() shouldBe DownloadEngine.Outcome(completed = 1, failed = 1)

        source.imageCallsFor("/ch/10", 1) shouldBe 1 + DownloadEngine.MAX_RETRIES
        val failed = queueRepository.getByChapterId(10).shouldNotBeNull()
        failed.state shouldBe DownloadState.ERROR
        failed.errorMessage shouldBe "HTTP error 503"
        realManifestRepository.getByChapterId(10).shouldBeNull()
        pageFiles(tmpDir(10)) shouldContainExactly listOf("001.png", "003.png", "004.png", "005.png")
        realManifestRepository.getByChapterId(11).shouldNotBeNull()

        // Start queues the failed chapter again; the next run fetches the one page it lacks.
        broken = false
        queue.start()
        queueRepository.getByChapterId(10)!!.state shouldBe DownloadState.QUEUED
        engine.run() shouldBe DownloadEngine.Outcome(completed = 1, failed = 0)
        source.imageCallsFor("/ch/10", 1) shouldBe 2 + DownloadEngine.MAX_RETRIES
        listOf(0, 2, 3, 4).forEach { source.imageCallsFor("/ch/10", it) shouldBe 1 }
    }

    /** The root shares internal storage with the database; upstream starts no chapter under 200 MB free. */
    @Test
    fun `with too little storage free the run stops before the next chapter and leaves it queued`() = runTest {
        queueRepository.enqueue(chapterId = 10, mangaId = 1, now = 0)
        queueRepository.enqueue(chapterId = 11, mangaId = 1, now = 0)
        onRecord = { freeBytes = MIN_FREE_BYTES - 1 }

        newEngine().run() shouldBe DownloadEngine.Outcome(completed = 1, failed = 0, storageFull = true)

        realManifestRepository.getByChapterId(10).shouldNotBeNull()
        val waiting = queueRepository.getByChapterId(11).shouldNotBeNull()
        waiting.state shouldBe DownloadState.QUEUED
        source.imageCallsFor("/ch/11", 0) shouldBe 0
        tmpDir(11).exists() shouldBe false

        // Space freed: the next run downloads it.
        freeBytes = MIN_FREE_BYTES
        newEngine().run() shouldBe DownloadEngine.Outcome(completed = 1, failed = 0)
        realManifestRepository.getByChapterId(11).shouldNotBeNull()
    }

    @Test
    fun `a run deletes the _tmp folders no queue row will resume and keeps every other`() = runTest {
        insertChapter(id = 12, mangaId = 1)
        insertChapter(id = 13, mangaId = 1)
        queueRepository.enqueue(chapterId = 10, mangaId = 1, now = 0)
        queueRepository.enqueue(chapterId = 11, mangaId = 1, now = 0)
        queueRepository.setState(11, DownloadState.ERROR, "timeout", 0)
        // Queued, with one page done; failed, kept for its retry; and one no row resumes.
        val firstPage = FakeSource.pageBytes(FakeSource.imageUrl("/ch/10", 0))
        File(tmpDir(10).apply { mkdirs() }, "001.png").writeBytes(firstPage)
        File(tmpDir(11).apply { mkdirs() }, "001.png").writeBytes(byteArrayOf(1))
        File(tmpDir(12).apply { mkdirs() }, "001.png").writeBytes(byteArrayOf(1))
        // A copy from a folder that a stopped run left, and an orphan under another source's folder.
        val strayArchive = File(mangaDir(1), "Old.cbz_tmp").apply { writeBytes(byteArrayOf(1)) }
        val otherSource = File(downloadRoot.root(), "Other Source/Title 1/${chapterDirName(12)}_tmp").apply { mkdirs() }
        // A name that ends like one, but a manifest row records it, and a finished chapter.
        val recorded = File(mangaDir(1), "Recorded_tmp").apply { mkdirs() }
        realManifestRepository.recordDownload(manifestEntry(chapter(13), manga(1), recorded.name))
        val finished = finalDir(12).apply { mkdirs() }

        newEngine().run() shouldBe DownloadEngine.Outcome(completed = 1, failed = 0)

        // Chapter 10 resumed from its _tmp folder: its first page was not fetched again.
        source.imageCallsFor("/ch/10", 0) shouldBe 0
        realManifestRepository.getByChapterId(10).shouldNotBeNull()
        pageFiles(tmpDir(11)) shouldContainExactly listOf("001.png")
        tmpDir(12).exists() shouldBe false
        strayArchive.exists() shouldBe false
        otherSource.exists() shouldBe false
        recorded.isDirectory shouldBe true
        finished.isDirectory shouldBe true
    }

    @Test
    fun `a queue with no worker and too little storage free says that is why`() = runTest {
        every { scheduler.status } returns flowOf(DownloadStatus.IDLE)
        freeBytes = MIN_FREE_BYTES - 1

        queue(newEngine()).status.first { it == DownloadStatus.STORAGE_FULL }
    }

    /**
     * The network went before WorkManager noticed: the chapter goes back in the queue and the run
     * ends, where it used to fail this chapter and every one after it in about 14 seconds each.
     */
    @Test
    fun `a chapter that fails for want of a network stays queued, and the run ends for a retry`() = runTest {
        source.onImage = { page, _ ->
            if (page.imageUrl == FakeSource.imageUrl("/ch/10", 1)) throw UnknownHostException("fixture: offline")
        }
        queueRepository.enqueue(chapterId = 10, mangaId = 1, now = 0)
        queueRepository.enqueue(chapterId = 11, mangaId = 1, now = 0)

        newEngine().run() shouldBe DownloadEngine.Outcome(completed = 0, failed = 0, interrupted = true)

        queueRepository.getByChapterId(10).shouldNotBeNull().state shouldBe DownloadState.QUEUED
        queueRepository.getByChapterId(11).shouldNotBeNull().state shouldBe DownloadState.QUEUED
        source.imageCallsFor("/ch/11", 0) shouldBe 0
        pageFiles(tmpDir(10)) shouldContainExactly listOf("001.png", "003.png", "004.png", "005.png")
    }

    @Test
    fun `a chapter that keeps failing for want of a network fails after three retried runs, and the run goes on`() =
        runTest {
            source.onImage = { page, _ ->
                if (page.imageUrl == FakeSource.imageUrl("/ch/10", 1)) throw SocketTimeoutException("fixture: timeout")
            }
            queueRepository.enqueue(chapterId = 10, mangaId = 1, now = 0)
            queueRepository.enqueue(chapterId = 11, mangaId = 1, now = 0)
            val engine = newEngine()

            repeat(DownloadEngine.MAX_NETWORK_REQUEUES) {
                engine.run() shouldBe DownloadEngine.Outcome(completed = 0, failed = 0, interrupted = true)
            }
            engine.run() shouldBe DownloadEngine.Outcome(completed = 1, failed = 1)

            val failed = queueRepository.getByChapterId(10).shouldNotBeNull()
            failed.state shouldBe DownloadState.ERROR
            failed.errorMessage shouldBe ErrorKey.TIMEOUT.id
            realManifestRepository.getByChapterId(11).shouldNotBeNull()
        }

    @Test
    fun `a chapter that fails for another reason fails at once, as before`() = runTest {
        source.onImage = { page, _ ->
            if (page.imageUrl == FakeSource.imageUrl("/ch/10", 1)) throw HttpException(404)
        }
        queueRepository.enqueue(chapterId = 10, mangaId = 1, now = 0)

        newEngine().run() shouldBe DownloadEngine.Outcome(completed = 0, failed = 1)
    }

    @Test
    fun `a chapter with no pages is an error, not a download`() = runTest {
        val empty = FakeSource(id = SOURCE_ID, pagesPerChapter = 0)
        val emptyEngine = DownloadEngine(
            queueRepository, manifestRepository, DatabaseTransactionRunner(handler),
            GetManga(mangaRepository), chapterRepository, FakeSourceManager(empty), downloadRoot,
            downloadPreferences, libraryPreferences, comicInfoWriter(StandardTestDispatcher(testScheduler)),
            locator(StandardTestDispatcher(testScheduler)), writeLock,
            dispatchers(StandardTestDispatcher(testScheduler)),
        )
        queueRepository.enqueue(chapterId = 10, mangaId = 1, now = 0)

        emptyEngine.run() shouldBe DownloadEngine.Outcome(completed = 0, failed = 1)

        // The key, not text: the queue screen names it in the reader's language when it is shown.
        queueRepository.getByChapterId(10)!!.errorMessage shouldBe ErrorKey.PAGE_LIST_EMPTY.id
        realManifestRepository.getByChapterId(10).shouldBeNull()
    }

    @Test
    fun `a page that keeps failing with an HTTP status keeps the status in its row, not only the key`() = runTest {
        // What a source's client really throws (OkHttpExtensions), not a look-alike IOException. Its
        // key needs the status code beside it, which one column cannot hold, so the text stays.
        source.onImage = { page, _ ->
            if (page.imageUrl == FakeSource.imageUrl("/ch/10", 1)) throw HttpException(503)
        }
        queueRepository.enqueue(chapterId = 10, mangaId = 1, now = 0)

        newEngine().run() shouldBe DownloadEngine.Outcome(completed = 0, failed = 1)

        val failed = queueRepository.getByChapterId(10).shouldNotBeNull()
        failed.state shouldBe DownloadState.ERROR
        failed.errorMessage shouldBe "HTTP error 503"
    }

    @Test
    fun `an HTTP status wrapped in another failure keeps the status in its row, not the wrapper's text`() = runTest {
        source.onImage = { page, _ ->
            if (page.imageUrl == FakeSource.imageUrl("/ch/10", 1)) throw IOException("Request failed", HttpException(503))
        }
        queueRepository.enqueue(chapterId = 10, mangaId = 1, now = 0)

        newEngine().run() shouldBe DownloadEngine.Outcome(completed = 0, failed = 1)

        queueRepository.getByChapterId(10).shouldNotBeNull().errorMessage shouldBe "HTTP error 503"
    }

    /** A source's message can be a whole error page; the row keeps what a snackbar shows of it. */
    @Test
    fun `a page that keeps failing for another reason keeps its message's first line, cut to the cap`() = runTest {
        val errorPage = "<html>" + "x".repeat(5_000)
        source.onImage = { page, _ ->
            if (page.imageUrl == FakeSource.imageUrl("/ch/10", 1)) {
                throw IllegalStateException("\n  $errorPage  \n<body>the rest of the page</body>")
            }
        }
        queueRepository.enqueue(chapterId = 10, mangaId = 1, now = 0)

        newEngine().run() shouldBe DownloadEngine.Outcome(completed = 0, failed = 1)

        val failed = queueRepository.getByChapterId(10).shouldNotBeNull()
        failed.state shouldBe DownloadState.ERROR
        failed.errorMessage shouldBe "<html>" + "x".repeat(ErrorKey.MESSAGE_MAX_LENGTH - 7) + "\u2026"
    }

    @Test
    fun `a chapter whose source is not installed stores the key, not the exception's message`() = runTest {
        insertManga(id = 2, sourceId = SOURCE_ID + 1)
        insertChapter(id = 20, mangaId = 2)
        queueRepository.enqueue(chapterId = 20, mangaId = 2, now = 0)

        newEngine().run() shouldBe DownloadEngine.Outcome(completed = 0, failed = 1)

        val failed = queueRepository.getByChapterId(20).shouldNotBeNull()
        failed.state shouldBe DownloadState.ERROR
        failed.errorMessage shouldBe "source_not_installed"
    }

    @Test
    fun `cancelling the chapter in flight removes its row and its _tmp folder, and the run goes on`() = runTest {
        val reachedThirdPage = CompletableDeferred<Unit>()
        source.onImage = { page, _ ->
            if (page.imageUrl == FakeSource.imageUrl("/ch/10", 2)) {
                reachedThirdPage.complete(Unit)
                awaitCancellation()
            }
        }
        val engine = newEngine()
        val queue = queue(engine)
        queue.enqueue(manga(1), listOf(chapter(10), chapter(11)), autoStart = false)

        val run = launch { engine.run() shouldBe DownloadEngine.Outcome(completed = 1, failed = 0) }
        reachedThirdPage.await()
        tmpDir(10).exists() shouldBe true
        queue.remove(listOf(10))
        run.join()

        queueRepository.getByChapterId(10).shouldBeNull()
        tmpDir(10).exists() shouldBe false
        finalDir(10).exists() shouldBe false
        realManifestRepository.getByChapterId(10).shouldBeNull()
        realManifestRepository.getByChapterId(11).shouldNotBeNull()
        queueRepository.getActive().shouldBeEmpty()
    }

    @Test
    fun `clearing the queue stops the worker and removes every row and every _tmp folder`() = runTest {
        val reachedSecondPage = CompletableDeferred<Unit>()
        source.onImage = { page, _ ->
            if (page.imageUrl == FakeSource.imageUrl("/ch/10", 1)) {
                reachedSecondPage.complete(Unit)
                awaitCancellation()
            }
        }
        val engine = newEngine()
        val queue = queue(engine)
        queue.enqueue(manga(1), listOf(chapter(10), chapter(11)), autoStart = false)

        val run = launch { engine.run() }
        reachedSecondPage.await()
        queue.clear()
        run.join()

        coVerify(exactly = 1) { scheduler.stop() }
        queueRepository.subscribeAll().first().shouldBeEmpty()
        tmpDir(10).exists() shouldBe false
        tmpDir(11).exists() shouldBe false
        realManifestRepository.getAll().shouldBeEmpty()
        source.imageCallsFor("/ch/11", 0) shouldBe 0
    }

    @Test
    fun `a commit cut between the folder rename and the transaction is recorded by the next run without fetching`() =
        runTest {
            // The state a process death leaves after step 4: the final folder, no manifest row, the
            // queue row still there.
            val final = finalDir(10).apply { mkdirs() }
            (0 until PAGES).forEach { index ->
                File(final, "%03d.png".format(index + 1))
                    .writeBytes(FakeSource.pageBytes(FakeSource.imageUrl("/ch/10", index)))
            }
            queueRepository.enqueue(chapterId = 10, mangaId = 1, now = 0)

            newEngine().run() shouldBe DownloadEngine.Outcome(completed = 1, failed = 0)

            source.totalImageCalls() shouldBe 0
            realManifestRepository.getByChapterId(10).shouldNotBeNull()
            File(finalDir(10), COMIC_INFO_FILE).isFile shouldBe true
            tmpDir(10).exists() shouldBe false
        }

    @Test
    fun `a chapter whose queue row goes after its last page is not recorded and leaves no folder`() = runTest {
        val lastPage = CompletableDeferred<Unit>()
        queueRepository.enqueue(chapterId = 10, mangaId = 1, now = 0)

        val run = launch {
            newEngine().run { if (it.pagesDone == it.pagesTotal) lastPage.complete(Unit) } shouldBe
                DownloadEngine.Outcome(completed = 0, failed = 0)
        }
        lastPage.await()
        // A remove that lands once every page is written: the commit re-reads the row it lost.
        queueRepository.remove(listOf(10))
        run.join()

        realManifestRepository.getByChapterId(10).shouldBeNull()
        finalDir(10).exists() shouldBe false
        tmpDir(10).exists() shouldBe false
    }

    @Test
    fun `a folder another entry's chapter still has a row for is not taken over, and that entry keeps it`() = runTest {
        // A second library entry of the same source with the same title: one folder holds both.
        insertManga(id = 2, title = "Title 1")
        insertChapter(id = 100, mangaId = 1, name = "Chapter 5", scanlator = "Group B", url = "/ch/5")
        insertChapter(id = 200, mangaId = 2, name = "Chapter 5", scanlator = "Group B", url = "/ch/5")
        finalDir(200) shouldBe finalDir(100)
        val kept = File(finalDir(100), "001.png").apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(7))
        }
        realManifestRepository.recordDownload(manifestEntry(chapter(100), manga(1), chapterDirName(100)))
        val keptRow = realManifestRepository.getByChapterId(100).shouldNotBeNull()
        // The source delisted chapter 100: its row is soft-deleted, and its download is kept.
        exec("UPDATE chapters SET deleted_at = 1 WHERE _id = 100")
        // A row under chapter 10's folder name but of another title names another folder.
        insertManga(id = 3, title = "Title Elsewhere")
        insertChapter(id = 300, mangaId = 3)
        realManifestRepository.recordDownload(manifestEntry(chapter(300), manga(3), chapterDirName(10)))
        queueRepository.enqueue(chapterId = 200, mangaId = 2, now = 0)
        queueRepository.enqueue(chapterId = 10, mangaId = 1, now = 1)

        newEngine().run() shouldBe DownloadEngine.Outcome(completed = 1, failed = 1)

        val refused = queueRepository.getByChapterId(200).shouldNotBeNull()
        refused.state shouldBe DownloadState.ERROR
        refused.errorMessage shouldBe ErrorKey.DOWNLOAD_FOLDER_IN_USE.id
        realManifestRepository.getByChapterId(200).shouldBeNull()
        realManifestRepository.getByChapterId(100) shouldBe keptRow
        finalDir(100).list()!!.toList() shouldContainExactly listOf("001.png")
        kept.readBytes().toList() shouldBe listOf<Byte>(7)
        tmpDir(100).exists() shouldBe false
        source.imageCallsFor("/ch/5", 0) shouldBe 0
        realManifestRepository.getByChapterId(10).shouldNotBeNull().pageCount shouldBe PAGES
    }

    @Test
    fun `a folder another chapter's row names is refused while its files are gone, and a chapter's own row never is`() =
        runTest {
            insertChapter(id = 12, mangaId = 1)
            realManifestRepository.recordDownload(manifestEntry(chapter(11), manga(1), chapterDirName(10)))
            // Chapter 12's own row, whose files are gone: the download replaces it.
            realManifestRepository.recordDownload(manifestEntry(chapter(12), manga(1), chapterDirName(12)))
            queueRepository.enqueue(chapterId = 10, mangaId = 1, now = 0)
            queueRepository.enqueue(chapterId = 12, mangaId = 1, now = 1)

            newEngine().run() shouldBe DownloadEngine.Outcome(completed = 1, failed = 1)

            queueRepository.getByChapterId(10).shouldNotBeNull().errorMessage shouldBe ErrorKey.DOWNLOAD_FOLDER_IN_USE.id
            finalDir(10).exists() shouldBe false
            tmpDir(10).exists() shouldBe false
            realManifestRepository.getByChapterId(10).shouldBeNull()
            pageFiles(finalDir(12)).size shouldBe PAGES
            queueRepository.getByChapterId(12).shouldBeNull()
        }

    @Test
    fun `an already downloaded chapter is not queued again`() = runTest {
        val engine = newEngine()
        val queue = queue(engine)
        queue.enqueue(manga(1), listOf(chapter(10)), autoStart = false)
        engine.run()

        queue.enqueue(manga(1), listOf(chapter(10), chapter(11)), autoStart = false)

        queueRepository.getActive().map { it.chapterId } shouldContainExactly listOf(11L)
    }

    @Test
    fun `the ComicInfo written for a chapter decodes back to that chapter's fields`() = runTest {
        insertChapter(id = 12, mangaId = 1, name = "Chapter 12.5", scanlator = "Group A", number = 12.5)
        categoryRepository.insert(Category(id = 0, name = " Shelf ", order = 1, flags = 0))
        val shelf = categoryRepository.getAll().single { it.name == " Shelf " }
        mangaRepository.setMangaCategories(1, listOf(shelf.id))
        trackRepository.insert(
            Track(
                id = 0, mangaId = 1, trackerId = 1, remoteId = 1, libraryId = null, title = "Tracked",
                lastChapterRead = 0.0, totalChapters = 0, status = 0, score = 0.0,
                remoteUrl = "https://tracker.example.invalid/1", startDate = 0, finishDate = 0, private = false,
            ),
        )
        queueRepository.enqueue(chapterId = 12, mangaId = 1, now = 0)
        queueRepository.enqueue(chapterId = 10, mangaId = 1, now = 0)

        newEngine().run()

        val manga = manga(1)
        val halfChapter = File(finalDir(12), COMIC_INFO_FILE).inputStream().use { ComicInfoXml().decode(it) }
        halfChapter.title?.value shouldBe "Chapter 12.5"
        halfChapter.number?.value shouldBe "12.5"
        halfChapter.translator?.value shouldBe "Group A"
        halfChapter.series?.value shouldBe manga.title
        halfChapter.summary?.value shouldBe manga.description
        halfChapter.writer?.value shouldBe manga.author
        halfChapter.penciller?.value shouldBe manga.artist
        halfChapter.genre?.value shouldBe manga.genre!!.joinToString()
        halfChapter.publishingStatus?.value shouldBe "Ongoing"
        halfChapter.categories?.value shouldBe "Shelf"
        halfChapter.source?.value shouldBe source.name
        halfChapter.web?.value?.split(" ") shouldContainExactlyInAnyOrder
            listOf("https://tracker.example.invalid/1", "https://example.invalid/ch/12")

        val wholeChapter = File(finalDir(10), COMIC_INFO_FILE).inputStream().use { ComicInfoXml().decode(it) }
        wholeChapter.title?.value shouldBe "Chapter 10"
        wholeChapter.number?.value shouldBe "10"
        wholeChapter.translator.shouldBeNull()
    }
}

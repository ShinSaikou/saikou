package com.shinsaikou.downloads

import com.shinsaikou.data.DatabaseTransactionRunner
import com.shinsaikou.domain.download.model.DownloadQueueEntry
import com.shinsaikou.domain.download.model.DownloadState
import com.shinsaikou.domain.download.repository.DownloadQueueRepository
import com.shinsaikou.source.CatalogueSource
import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceManager
import com.shinsaikou.source.StubSource
import com.shinsaikou.source.model.ChapterInfo
import com.shinsaikou.source.model.ImageResult
import com.shinsaikou.source.model.MangaInfo
import com.shinsaikou.source.model.PageInfo
import eu.kanade.tachiyomi.data.download.DownloadProvider
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.interactor.GetManga
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.TestTimeSource

/**
 * The engine with the Experimental switch "Download from several sources at once"
 * ([com.shinsaikou.domain.preferences.DownloadPreferences.parallelSourceDownloads]) on and off.
 *
 * Three fixture sources (A, B, C) serve chapters whose last page waits on a gate the test opens, so
 * which chapters are running at any moment is decided by the test and not by timing. The engine runs
 * on the test scheduler, so `advanceUntilIdle` runs everything that can run until each chapter is
 * waiting at its gate. A chapter is named by its key, `<letter>:<url>`, as [key] builds it.
 *
 * Chapters: A has 11, 12 and 13 (manga 1), B has 21 and 22 (manga 2), C has 31 and 32 (manga 3).
 */
class DownloadEngineSeveralSourcesTest : DownloadTestBase() {

    private val scheduler = mockk<DownloadScheduler>(relaxed = true) {
        every { isRunning } returns flowOf(false)
    }

    private val trace = Collections.synchronizedList(mutableListOf<String>())
    private val gates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val failures = ConcurrentHashMap<String, Throwable>()
    private var pages = 1

    private val sourceA = GateSource(101, "Fixture A", "A")
    private val sourceB = GateSource(102, "Fixture B", "B")
    private val sourceC = GateSource(103, "Fixture C", "C")
    private val sources = ThreeSources(listOf(sourceA, sourceB, sourceC))

    @BeforeEach
    fun seed() {
        insertManga(id = 1, sourceId = 101)
        insertManga(id = 2, sourceId = 102)
        insertManga(id = 3, sourceId = 103)
        listOf(11L, 12L, 13L).forEach { insertChapter(id = it, mangaId = 1) }
        listOf(21L, 22L).forEach { insertChapter(id = it, mangaId = 2) }
        listOf(31L, 32L).forEach { insertChapter(id = it, mangaId = 3) }
        downloadPreferences.parallelSourceDownloads.set(true)
        downloadPreferences.parallelSourceLimit.set(5)
    }

    /** One fixture source. The last page of a chapter waits at the chapter's gate. */
    private inner class GateSource(override val id: Long, override val name: String, val letter: String) : Source {
        override val lang = "en"

        override suspend fun getMangaDetails(manga: MangaInfo): MangaInfo = manga

        override suspend fun getChapterList(manga: MangaInfo): List<ChapterInfo> = emptyList()

        override suspend fun getPageList(chapter: ChapterInfo): List<PageInfo> {
            trace += "start:$letter:${chapter.url}"
            return List(pages) { PageInfo(index = it, imageUrl = "https://example.invalid${chapter.url}/$it.png") }
        }

        override suspend fun getImage(page: PageInfo): ImageResult {
            val address = page.imageUrl!!
            val url = address.removePrefix("https://example.invalid").substringBeforeLast('/')
            val index = address.substringAfterLast('/').removeSuffix(".png").toInt()
            val key = "$letter:$url"
            if (index == pages - 1) {
                try {
                    gate(key).await()
                    failures[key]?.let { throw it }
                } finally {
                    trace += "end:$key"
                }
            }
            return ImageResult.ofBytes(FakeSource.pageBytes(address), "image/png", address)
        }

        override fun getChapterUrl(chapter: ChapterInfo): String = "https://example.invalid${chapter.url}"
    }

    private class ThreeSources(private val all: List<Source>) : SourceManager {
        override val isInitialized = MutableStateFlow(true)
        override fun get(id: Long): Source? = all.firstOrNull { it.id == id }
        override fun getOrStub(id: Long): Source = get(id) ?: StubSource.unknown(id)
        override fun getCatalogueSources(): Flow<List<CatalogueSource>> = flowOf(emptyList())
        override fun getSources(): Flow<List<Source>> = isInitialized.map { all }
        override fun getStubSources(): Flow<List<StubSource>> = emptyFlow()
    }

    private fun gate(key: String) = gates.getOrPut(key) { CompletableDeferred() }

    private fun key(chapterId: Long) = "${"?ABC"[(chapterId / 10).toInt()]}:/ch/$chapterId"

    private fun release(vararg chapterIds: Long) = chapterIds.forEach { gate(key(it)).complete(Unit) }

    private fun releaseAll() {
        listOf(11L, 12L, 13L, 21L, 22L, 31L, 32L).forEach { release(it) }
    }

    /** The chapters whose pages are being fetched now: started, and not yet at the end of their last page. */
    private fun running(): Set<String> = synchronized(trace) {
        val set = linkedSetOf<String>()
        trace.forEach { if (it.startsWith("start:")) set += it.removePrefix("start:") else set -= it.removePrefix("end:") }
        set
    }

    private fun started(): List<String> = synchronized(trace) {
        trace.filter { it.startsWith("start:") }.map { it.removePrefix("start:") }
    }

    /** The most chapters of the keys' prefix `<letter>:` that were running at one time. */
    private fun maxRunning(prefix: String): Int = synchronized(trace) {
        var now = 0
        var most = 0
        val live = mutableSetOf<String>()
        trace.forEach {
            val key = it.substringAfter(':')
            if (!key.startsWith(prefix)) return@forEach
            if (it.startsWith("start:")) live += key else live -= key
            now = live.size
            if (now > most) most = now
        }
        most
    }

    private fun maxRunningAll(): Int = synchronized(trace) {
        var most = 0
        val live = mutableSetOf<String>()
        trace.forEach {
            val key = it.substringAfter(':')
            if (it.startsWith("start:")) live += key else live -= key
            if (live.size > most) most = live.size
        }
        most
    }

    private fun TestScope.newEngine(): DownloadEngine {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return DownloadEngine(
            queueRepository = queueRepository,
            manifestRepository = manifestRepository,
            transactionRunner = DatabaseTransactionRunner(handler),
            getManga = GetManga(mangaRepository),
            chapterRepository = chapterRepository,
            sourceManager = sources,
            downloadRoot = downloadRoot,
            downloadPreferences = downloadPreferences,
            libraryPreferences = libraryPreferences,
            comicInfoWriter = comicInfoWriter(dispatcher),
            locator = DownloadLocator(downloadRoot, sources, libraryPreferences, dispatchers(dispatcher)),
            writeLock = writeLock,
            dispatchers = dispatchers(dispatcher),
        )
    }

    /**
     * An engine whose first queue read is followed by a removal of [removedChapterId]'s row, as if
     * a removal landed just after the read and its cancel found no job to stop. Later reads see the
     * real queue.
     */
    private fun TestScope.engineRemovedAfterRead(removedChapterId: Long): DownloadEngine {
        val real = queueRepository
        var removed = false
        val racing = object : DownloadQueueRepository by real {
            override suspend fun getActive(): List<DownloadQueueEntry> {
                val rows = real.getActive()
                if (!removed) {
                    removed = true
                    real.remove(listOf(removedChapterId))
                }
                return rows
            }
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        return DownloadEngine(
            queueRepository = racing,
            manifestRepository = manifestRepository,
            transactionRunner = DatabaseTransactionRunner(handler),
            getManga = GetManga(mangaRepository),
            chapterRepository = chapterRepository,
            sourceManager = sources,
            downloadRoot = downloadRoot,
            downloadPreferences = downloadPreferences,
            libraryPreferences = libraryPreferences,
            comicInfoWriter = comicInfoWriter(dispatcher),
            locator = DownloadLocator(downloadRoot, sources, libraryPreferences, dispatchers(dispatcher)),
            writeLock = writeLock,
            dispatchers = dispatchers(dispatcher),
        )
    }

    private fun TestScope.queue(engine: DownloadEngine): WorkManagerDownloadQueue {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val locator = DownloadLocator(downloadRoot, sources, libraryPreferences, dispatchers(dispatcher))
        return WorkManagerDownloadQueue(
            queueRepository = queueRepository,
            manifest = FolderDownloadManifest(manifestRepository, locator, dispatchers(dispatcher)),
            chapterRepository = chapterRepository,
            getManga = GetManga(mangaRepository),
            locator = locator,
            downloadRoot = downloadRoot,
            engine = engine,
            scheduler = scheduler,
            dispatchers = dispatchers(dispatcher),
            appScope = backgroundScope,
        )
    }

    private suspend fun enqueue(vararg chapterIds: Long) = chapterIds.forEach {
        queueRepository.enqueue(chapterId = it, mangaId = chapter(it).mangaId, now = 0)
    }

    private suspend fun tmpDirOf(chapterId: Long): java.io.File {
        val chapter = chapter(chapterId)
        val manga = manga(chapter.mangaId)
        val name = DownloadProvider.getChapterDirName(chapter.name, chapter.scanlator, chapter.url, false)
        return java.io.File(
            downloadRoot.mangaDir(sources.get(manga.source)!!.name, manga.title),
            name + DownloadProvider.TMP_DIR_SUFFIX,
        )
    }

    // The switch off.

    @Test
    fun `with the switch off one chapter runs at a time in queue order, whatever the sources`() = runTest {
        downloadPreferences.parallelSourceDownloads.set(false)
        enqueue(11, 21, 12, 31)
        val run = async { newEngine().run() }

        advanceUntilIdle()
        running() shouldBe setOf(key(11))
        release(11)
        advanceUntilIdle()
        running() shouldBe setOf(key(21))
        release(21)
        advanceUntilIdle()
        running() shouldBe setOf(key(12))
        release(12)
        advanceUntilIdle()
        running() shouldBe setOf(key(31))
        release(31)

        run.await() shouldBe DownloadEngine.Outcome(completed = 4, failed = 0)
        started() shouldContainExactly listOf(11L, 21L, 12L, 31L).map(::key)
        maxRunningAll() shouldBe 1
    }

    @Test
    fun `with the switch off the limit is not read and a chapter holds the whole write lock`() = runTest {
        downloadPreferences.parallelSourceLimit.set(10)
        downloadPreferences.parallelSourceDownloads.set(false)
        enqueue(11, 21)
        val run = async { newEngine().run() }
        advanceUntilIdle()
        var folderHolderEntered = false
        val folderHolder = launch { writeLock.withFolderLock(listOf("unrelated")) { folderHolderEntered = true } }

        advanceUntilIdle()
        folderHolderEntered shouldBe false

        releaseAll()
        run.await()
        folderHolder.join()
        folderHolderEntered shouldBe true
    }

    // The switch on.

    @Test
    fun `chapters of two sources overlap, and each source's chapters run one after another in queue order`() =
        runTest {
            enqueue(11, 12, 21, 22)
            val run = async { newEngine().run() }

            advanceUntilIdle()
            running() shouldBe setOf(key(11), key(21))

            release(21)
            advanceUntilIdle()
            running() shouldBe setOf(key(11), key(22))

            release(11)
            advanceUntilIdle()
            running() shouldBe setOf(key(12), key(22))

            releaseAll()
            run.await() shouldBe DownloadEngine.Outcome(completed = 4, failed = 0)
            started().filter { it.startsWith("A:") } shouldContainExactly listOf(11L, 12L).map(::key)
            started().filter { it.startsWith("B:") } shouldContainExactly listOf(21L, 22L).map(::key)
            maxRunning("A:") shouldBe 1
            maxRunning("B:") shouldBe 1
            maxRunningAll() shouldBe 2
        }

    @Test
    fun `a source's later chapter is not started while an earlier one of that source waits for a free place`() =
        runTest {
            downloadPreferences.parallelSourceLimit.set(2)
            // 12 is A's second row. A is busy with 11, so the engine passes it and starts B's 21.
            enqueue(11, 12, 21)
            val run = async { newEngine().run() }

            advanceUntilIdle()
            running() shouldBe setOf(key(11), key(21))
            started().contains(key(12)) shouldBe false

            releaseAll()
            run.await() shouldBe DownloadEngine.Outcome(completed = 3, failed = 0)
        }

    @Test
    fun `a limit of 1 is strict queue order`() = runTest {
        downloadPreferences.parallelSourceLimit.set(1)
        enqueue(11, 21, 12, 31)
        val run = async { newEngine().run() }

        advanceUntilIdle()
        running() shouldBe setOf(key(11))
        release(11)
        advanceUntilIdle()
        running() shouldBe setOf(key(21))
        release(21)
        advanceUntilIdle()
        running() shouldBe setOf(key(12))

        releaseAll()
        run.await() shouldBe DownloadEngine.Outcome(completed = 4, failed = 0)
        started() shouldContainExactly listOf(11L, 21L, 12L, 31L).map(::key)
        maxRunningAll() shouldBe 1
    }

    @Test
    fun `a limit of 2 with three sources leaves the third waiting until one frees`() = runTest {
        downloadPreferences.parallelSourceLimit.set(2)
        enqueue(11, 21, 31)
        val run = async { newEngine().run() }

        advanceUntilIdle()
        running() shouldBe setOf(key(11), key(21))
        queueRepository.getByChapterId(31).shouldNotBeNull().state shouldBe DownloadState.QUEUED

        release(21)
        advanceUntilIdle()
        running() shouldBe setOf(key(11), key(31))

        releaseAll()
        run.await() shouldBe DownloadEngine.Outcome(completed = 3, failed = 0)
        maxRunningAll() shouldBe 2
    }

    @Test
    fun `a limit above the top of the range is held to the top and a limit below 1 to 1`() = runTest {
        downloadPreferences.parallelSourceLimit.set(0)
        enqueue(11, 21)
        val run = async { newEngine().run() }

        advanceUntilIdle()
        running() shouldBe setOf(key(11))

        downloadPreferences.parallelSourceLimit.set(99)
        releaseAll()
        run.await() shouldBe DownloadEngine.Outcome(completed = 2, failed = 0)
    }

    @Test
    fun `lowering the limit mid-run takes effect at the next chapter, not before`() = runTest {
        downloadPreferences.parallelSourceLimit.set(3)
        enqueue(11, 21, 31, 12, 22, 32)
        val run = async { newEngine().run() }
        advanceUntilIdle()
        running() shouldBe setOf(key(11), key(21), key(31))

        downloadPreferences.parallelSourceLimit.set(1)
        advanceUntilIdle()
        running() shouldBe setOf(key(11), key(21), key(31))

        // Two of three have ended, one is still running: at a limit of 1 nothing starts yet.
        release(11, 21)
        advanceUntilIdle()
        running() shouldBe setOf(key(31))

        release(31)
        advanceUntilIdle()
        running() shouldBe setOf(key(12))

        releaseAll()
        run.await() shouldBe DownloadEngine.Outcome(completed = 6, failed = 0)
        // After the three first chapters, never more than one at a time.
        maxRunningAll() shouldBe 3
        started().drop(3) shouldContainExactly listOf(12L, 22L, 32L).map(::key)
    }

    @Test
    fun `raising the limit mid-run starts nothing until a chapter ends`() = runTest {
        downloadPreferences.parallelSourceLimit.set(1)
        enqueue(11, 21, 31)
        val run = async { newEngine().run() }
        advanceUntilIdle()
        running() shouldBe setOf(key(11))

        downloadPreferences.parallelSourceLimit.set(2)
        advanceUntilIdle()
        running() shouldBe setOf(key(11))

        release(11)
        advanceUntilIdle()
        running() shouldBe setOf(key(21), key(31))

        releaseAll()
        run.await() shouldBe DownloadEngine.Outcome(completed = 3, failed = 0)
    }

    @Test
    fun `turning the switch off mid-run leaves one chapter at a time from the next chapter`() = runTest {
        downloadPreferences.parallelSourceLimit.set(3)
        enqueue(11, 21, 31, 12, 22)
        val run = async { newEngine().run() }
        advanceUntilIdle()
        running() shouldBe setOf(key(11), key(21), key(31))

        downloadPreferences.parallelSourceDownloads.set(false)
        advanceUntilIdle()
        running() shouldBe setOf(key(11), key(21), key(31))

        release(11, 21, 31)
        advanceUntilIdle()
        running() shouldBe setOf(key(12))
        release(12)
        advanceUntilIdle()
        running() shouldBe setOf(key(22))

        releaseAll()
        run.await() shouldBe DownloadEngine.Outcome(completed = 5, failed = 0)
        started().drop(3) shouldContainExactly listOf(12L, 22L).map(::key)
    }

    @Test
    fun `turning the switch on mid-run starts several sources from the next chapter`() = runTest {
        downloadPreferences.parallelSourceDownloads.set(false)
        enqueue(11, 21, 31)
        val run = async { newEngine().run() }
        advanceUntilIdle()
        running() shouldBe setOf(key(11))

        downloadPreferences.parallelSourceDownloads.set(true)
        advanceUntilIdle()
        running() shouldBe setOf(key(11))

        release(11)
        advanceUntilIdle()
        running() shouldBe setOf(key(21), key(31))

        releaseAll()
        run.await() shouldBe DownloadEngine.Outcome(completed = 3, failed = 0)
    }

    @Test
    fun `chapters running at once hold the write lock shared, so a whole-root holder waits for all of them`() =
        runTest {
            enqueue(11, 21)
            val run = async { newEngine().run() }
            advanceUntilIdle()
            running() shouldBe setOf(key(11), key(21))
            var rootHolderEntered = false
            val rootHolder = launch { writeLock.withLock { rootHolderEntered = true } }

            release(11)
            advanceUntilIdle()
            rootHolderEntered shouldBe false

            release(21)
            run.await()
            rootHolder.join()
            rootHolderEntered shouldBe true
        }

    // Cancel, pause, reorder and new rows.

    @Test
    fun `cancelling one running chapter stops only it, removes its _tmp folder, and frees its source`() = runTest {
        enqueue(11, 21, 22)
        val engine = newEngine()
        val queue = queue(engine)
        val run = async { engine.run() }
        advanceUntilIdle()
        running() shouldBe setOf(key(11), key(21))
        tmpDirOf(21).exists() shouldBe true

        queue.remove(listOf(21))
        advanceUntilIdle()

        queueRepository.getByChapterId(21).shouldBeNull()
        tmpDirOf(21).exists() shouldBe false
        // 11 was not touched, and B's next chapter took the free place.
        running() shouldBe setOf(key(11), key(22))
        queueRepository.getByChapterId(11).shouldNotBeNull().state shouldBe DownloadState.DOWNLOADING

        releaseAll()
        run.await() shouldBe DownloadEngine.Outcome(completed = 2, failed = 0)
        realManifestRepository.getByChapterId(11).shouldNotBeNull()
        realManifestRepository.getByChapterId(22).shouldNotBeNull()
        realManifestRepository.getByChapterId(21).shouldBeNull()
    }

    @Test
    fun `pausing the run stops every running chapter and returns them to queued`() = runTest {
        enqueue(11, 21, 31)
        val run = launch { newEngine().run() }
        advanceUntilIdle()
        running() shouldBe setOf(key(11), key(21), key(31))

        run.cancelAndJoin()

        listOf(11L, 21L, 31L).forEach {
            queueRepository.getByChapterId(it).shouldNotBeNull().state shouldBe DownloadState.QUEUED
            realManifestRepository.getByChapterId(it).shouldBeNull()
            tmpDirOf(it).exists() shouldBe true
        }

        // A new run resumes them all.
        releaseAll()
        newEngine().run() shouldBe DownloadEngine.Outcome(completed = 3, failed = 0)
    }

    @Test
    fun `a reorder during a run changes which chapter starts next`() = runTest {
        downloadPreferences.parallelSourceLimit.set(1)
        enqueue(11, 21, 31)
        val run = async { newEngine().run() }
        advanceUntilIdle()
        running() shouldBe setOf(key(11))

        queueRepository.reorder(listOf(11, 31, 21))
        release(11)
        advanceUntilIdle()

        running() shouldBe setOf(key(31))
        releaseAll()
        run.await() shouldBe DownloadEngine.Outcome(completed = 3, failed = 0)
        started() shouldContainExactly listOf(11L, 31L, 21L).map(::key)
    }

    @Test
    fun `a row queued mid-run is picked up when a chapter ends`() = runTest {
        downloadPreferences.parallelSourceLimit.set(2)
        enqueue(11)
        val run = async { newEngine().run() }
        advanceUntilIdle()
        running() shouldBe setOf(key(11))

        enqueue(21)
        release(11)
        advanceUntilIdle()
        running() shouldBe setOf(key(21))

        releaseAll()
        run.await() shouldBe DownloadEngine.Outcome(completed = 2, failed = 0)
    }

    // Low storage and no network.

    @Test
    fun `low storage stops new chapters while the running ones finish, and the run ends storage full`() = runTest {
        downloadPreferences.parallelSourceLimit.set(2)
        enqueue(11, 21, 31, 12)
        val run = async { newEngine().run() }
        advanceUntilIdle()
        running() shouldBe setOf(key(11), key(21))

        freeBytes = MIN_FREE_BYTES - 1
        release(11)
        advanceUntilIdle()
        // 11 ended, and the free place was not filled.
        running() shouldBe setOf(key(21))

        releaseAll()
        run.await() shouldBe DownloadEngine.Outcome(completed = 2, failed = 0, storageFull = true)
        started() shouldContainExactly listOf(11L, 21L).map(::key)
        listOf(31L, 12L).forEach {
            queueRepository.getByChapterId(it).shouldNotBeNull().state shouldBe DownloadState.QUEUED
            tmpDirOf(it).exists() shouldBe false
        }
    }

    @Test
    fun `too little storage before the first chapter ends the run at once with every row queued`() = runTest {
        freeBytes = MIN_FREE_BYTES - 1
        enqueue(11, 21)

        newEngine().run() shouldBe DownloadEngine.Outcome(completed = 0, failed = 0, storageFull = true)

        started() shouldContainExactly emptyList()
        queueRepository.getActive().map { it.chapterId } shouldContainExactly listOf(11L, 21L)
    }

    @Test
    fun `a chapter put back for want of a network stops new chapters, the running ones finish, and the run is interrupted`() =
        runTest {
            downloadPreferences.parallelSourceLimit.set(2)
            enqueue(11, 21, 31, 12)
            failures[key(11)] = UnknownHostException("fixture: offline")
            val run = async { newEngine().run() }
            advanceUntilIdle()
            running() shouldBe setOf(key(11), key(21))

            release(11)
            advanceUntilIdle()
            queueRepository.getByChapterId(11).shouldNotBeNull().state shouldBe DownloadState.QUEUED
            // 21 is still running and nothing new started in 11's place.
            running() shouldBe setOf(key(21))

            releaseAll()
            run.await() shouldBe DownloadEngine.Outcome(completed = 1, failed = 0, interrupted = true)
            realManifestRepository.getByChapterId(21).shouldNotBeNull()
            started() shouldContainExactly listOf(11L, 21L).map(::key)
            listOf(11L, 31L, 12L).forEach {
                queueRepository.getByChapterId(it).shouldNotBeNull().state shouldBe DownloadState.QUEUED
            }
        }

    @Test
    fun `network failures are counted per chapter, so a chapter that keeps failing fails alone after three runs`() =
        runTest {
            downloadPreferences.parallelSourceLimit.set(2)
            enqueue(11, 21)
            failures[key(11)] = UnknownHostException("fixture: offline")
            releaseAll()
            val engine = newEngine()

            // The other source's chapter finishes in the first run; only the failing one comes back.
            engine.run() shouldBe DownloadEngine.Outcome(completed = 1, failed = 0, interrupted = true)
            repeat(DownloadEngine.MAX_NETWORK_REQUEUES - 1) {
                engine.run() shouldBe DownloadEngine.Outcome(completed = 0, failed = 0, interrupted = true)
            }
            engine.run() shouldBe DownloadEngine.Outcome(completed = 0, failed = 1)

            queueRepository.getByChapterId(11).shouldNotBeNull().state shouldBe DownloadState.ERROR
            realManifestRepository.getByChapterId(21).shouldNotBeNull()
        }

    // Folder names that could meet.

    private suspend fun seedPair(nameFirst: String, nameSecond: String) {
        insertManga(id = 4, title = "Title 4", sourceId = 101)
        insertManga(id = 5, title = "Title 5", sourceId = 102)
        insertChapter(id = 41, mangaId = 4, name = nameFirst, url = "/same")
        insertChapter(id = 42, mangaId = 5, name = nameSecond, url = "/same")
    }

    private suspend fun TestScope.assertSerialised(first: String, second: String) {
        val run = async { newEngine().run() }
        advanceUntilIdle()
        running() shouldBe setOf(first)
        gate(first).complete(Unit)
        advanceUntilIdle()
        running() shouldBe setOf(second)
        gate(second).complete(Unit)
        run.await() shouldBe DownloadEngine.Outcome(completed = 2, failed = 0)
    }

    @Test
    fun `two chapters whose folder names meet under the other ascii setting do not run at once`() = runTest {
        seedPair("Ch \u00e9", "Ch c3a9")
        // The fixture only means what it says if the two names do meet.
        val first = chapter(41)
        val second = chapter(42)
        DownloadProvider.getChapterDirName(first.name, null, first.url, true) shouldBe
            DownloadProvider.getChapterDirName(second.name, null, second.url, false)
        enqueue(41, 42)

        assertSerialised("A:/same", "B:/same")
    }

    @Test
    fun `two chapters whose folder names differ only in case do not run at once`() = runTest {
        seedPair("Chapter x", "chapter X")
        enqueue(41, 42)

        assertSerialised("A:/same", "B:/same")
    }

    @Test
    fun `two chapters with the same folder name in different sources do not run at once`() = runTest {
        seedPair("Chapter x", "Chapter x")
        enqueue(41, 42)

        assertSerialised("A:/same", "B:/same")
    }

    @Test
    fun `chapters whose folder names cannot meet run at once`() = runTest {
        seedPair("Chapter x", "Chapter y")
        enqueue(41, 42)
        val run = async { newEngine().run() }

        advanceUntilIdle()

        running() shouldBe setOf("A:/same", "B:/same")
        gate("A:/same").complete(Unit)
        gate("B:/same").complete(Unit)
        run.await() shouldBe DownloadEngine.Outcome(completed = 2, failed = 0)
    }

    // Progress.

    @Test
    fun `progress sums the pages of the chapters running and names the one running longest`() = runTest {
        pages = 3
        enqueue(11, 21)
        val reports = mutableListOf<DownloadEngine.Progress>()
        val run = async { newEngine().run { reports += it } }
        advanceUntilIdle()

        val together = reports.last()
        together.chaptersRunning shouldBe 2
        together.pagesDone shouldBe 4
        together.pagesTotal shouldBe 6
        together.chapterId shouldBe 11
        together.chapterName shouldBe "Chapter 11"
        together.chaptersLeft shouldBe 2

        // One ends: the board drops it, and the report is the remaining chapter's alone.
        release(11)
        advanceUntilIdle()
        val alone = reports.last()
        alone.chaptersRunning shouldBe 1
        alone.chapterId shouldBe 21
        alone.pagesDone shouldBe 2
        alone.pagesTotal shouldBe 3

        release(21)
        run.await()
    }

    /**
     * The device check's case: a fast source empties while a slow one runs. The chapters left must
     * drop with every chapter that ends, in any source, and what the cadence lets through to the
     * notification must say so, back in the one-chapter form once one chapter is left running.
     */
    @Test
    fun `the chapters left follow the queue as a fast source finishes while a slow one runs`() = runTest {
        pages = 2
        enqueue(11, 12, 13, 21, 22)
        val reports = mutableListOf<DownloadEngine.Progress>()
        val sent = mutableListOf<DownloadEngine.Progress>()
        val cadence = DownloadProgressCadence(TestTimeSource())
        val run = async {
            newEngine().run {
                reports += it
                if (cadence.shouldSend(it)) sent += it
            }
        }
        advanceUntilIdle()
        reports.last().chaptersRunning shouldBe 2
        reports.last().chaptersLeft shouldBe 5

        release(11)
        advanceUntilIdle()
        reports.last().chaptersRunning shouldBe 2
        reports.last().chaptersLeft shouldBe 4

        release(12, 13)
        advanceUntilIdle()
        // Source A is done; B's first chapter still runs and its second waits: two left.
        running() shouldBe setOf("B:/ch/21")
        reports.last() shouldBe DownloadEngine.Progress(21, "Title 2", "Chapter 21", 1, 2, chaptersLeft = 2)
        sent.last() shouldBe reports.last()

        release(21)
        advanceUntilIdle()
        reports.last().chapterId shouldBe 22
        reports.last().chaptersLeft shouldBe 1
        sent.last().chaptersLeft shouldBe 1

        release(22)
        run.await() shouldBe DownloadEngine.Outcome(completed = 5, failed = 0)
    }

    @Test
    fun `progress of a single chapter is that chapter's own, as with the switch off`() = runTest {
        pages = 3
        enqueue(11)
        val reports = mutableListOf<DownloadEngine.Progress>()
        val run = async { newEngine().run { reports += it } }
        advanceUntilIdle()
        release(11)
        run.await()

        reports.map { it.chaptersRunning }.distinct() shouldContainExactly listOf(1)
        reports.last() shouldBe DownloadEngine.Progress(11, "Title 1", "Chapter 11", 3, 3, chaptersLeft = 1)
    }

    @Test
    fun `a chapter removed right after the queue was read is dropped unstarted while another source's chapter from that read downloads`() =
        runTest {
            enqueue(11, 21)
            val run = async { engineRemovedAfterRead(removedChapterId = 11).run() }

            advanceUntilIdle()
            started() shouldContainExactly listOf(key(21))
            running() shouldBe setOf(key(21))
            release(21)

            run.await() shouldBe DownloadEngine.Outcome(completed = 1, failed = 0)
            started() shouldContainExactly listOf(key(21))
            tmpDirOf(11).exists() shouldBe false
            realManifestRepository.getByChapterId(11).shouldBeNull()
            realManifestRepository.getByChapterId(21).shouldNotBeNull()
            queueRepository.getActive() shouldBe emptyList()
        }
}

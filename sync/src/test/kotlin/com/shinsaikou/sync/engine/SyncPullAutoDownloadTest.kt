package com.shinsaikou.sync.engine

import com.shinsaikou.data.DatabaseTransactionRunner
import com.shinsaikou.data.chapter.ScanlatorPriorityRepositoryImpl
import com.shinsaikou.data.download.DownloadManifestRepositoryImpl
import com.shinsaikou.data.download.DownloadQueueRepositoryImpl
import com.shinsaikou.domain.chapter.ChapterReplacements
import com.shinsaikou.domain.chapter.interactor.GetExcludedScanlators
import com.shinsaikou.domain.chapter.interactor.GetScanlatorPriority
import com.shinsaikou.domain.chapter.interactor.SyncChaptersWithSource
import com.shinsaikou.domain.download.model.DownloadQueueEntry
import com.shinsaikou.domain.download.repository.DownloadQueueRepository
import com.shinsaikou.domain.manga.interactor.UpdateManga
import com.shinsaikou.domain.preferences.DownloadPreferences
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.ReaderPreferences
import com.shinsaikou.downloads.AutoDownloadRules
import com.shinsaikou.downloads.DownloadManifest
import com.shinsaikou.downloads.DownloadQueue
import com.shinsaikou.source.model.ChapterInfo
import com.shinsaikou.sync.InMemorySyncBackend
import com.shinsaikou.sync.engine.SyncCompaction.Companion.DAY_MS
import com.shinsaikou.sync.format.RemoteName
import com.shinsaikou.sync.format.SyncLayout
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import tachiyomi.data.history.HistoryRepositoryImpl
import tachiyomi.domain.chapter.interactor.FilterChaptersForDownload
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga

/**
 * The sync pull's report to auto-download (phase 3 unit AD): the chapters a pull inserted go to
 * [com.shinsaikou.domain.download.AutoDownload] once, after the transactions that hold them committed,
 * except a snapshot's, a first pull's export and those of an entry that arrived in the library in
 * the same pull. The wire does not change; the report is read from what the apply did.
 *
 * The first test is the two-store case through E2's engine: [AutoDownloadRules] over device B's
 * own database and its download queue table, as `:downloads` binds it in the app.
 */
class SyncPullAutoDownloadTest {

    private var now = 0L
    private val backend = InMemorySyncBackend(clock = { now })
    private val a = SyncTestDevice(backend)

    /** Every download [TableQueue] started, across the devices of one test. */
    private var starts = 0

    private val b = SyncTestDevice(backend, autoDownloadOf = ::rulesOver)

    /** A device with the recording default, for the tests of what the pull reports. */
    private val c = SyncTestDevice(backend)

    @AfterEach
    fun close() {
        a.close()
        b.close()
        c.close()
    }

    private fun rulesOver(device: SyncTestDevice): AutoDownloadRules {
        val chapters = ChapterRepositoryImpl(device.handler)
        val getChaptersByMangaId = GetChaptersByMangaId(chapters)
        val getManga = GetManga(MangaRepositoryImpl(device.handler))
        val downloadPreferences = DownloadPreferences(InMemoryPreferenceStore()).apply { downloadNewChapters.set(true) }
        return AutoDownloadRules(
            filterChaptersForDownload = FilterChaptersForDownload(
                getChaptersByMangaId,
                downloadPreferences,
                GetCategories(CategoryRepositoryImpl(device.handler)),
            ),
            getNextChapters = GetNextChapters(getChaptersByMangaId, getManga, mockk()),
            getScanlatorPriority = GetScanlatorPriority(ScanlatorPriorityRepositoryImpl(device.handler)),
            getManga = getManga,
            chapterRepository = chapters,
            // Nothing is on disk on a sync test device.
            manifest = mockk<DownloadManifest>(relaxed = true),
            queue = TableQueue(DownloadQueueRepositoryImpl(device.handler)),
            downloadPreferences = downloadPreferences,
            readerPreferences = ReaderPreferences(InMemoryPreferenceStore()),
        )
    }

    /** The download queue as its table holds it, with a start that is only counted. */
    private inner class TableQueue(private val repository: DownloadQueueRepository) : DownloadQueue {
        override fun subscribe(): Flow<List<DownloadQueueEntry>> = repository.subscribeAll()
        override val isRunning: StateFlow<Boolean> = MutableStateFlow(false)
        override val isPaused: StateFlow<Boolean> = MutableStateFlow(false)

        override suspend fun enqueue(manga: Manga, chapters: List<Chapter>, autoStart: Boolean) {
            chapters.forEach { repository.enqueue(chapterId = it.id, mangaId = manga.id, now = now) }
            if (autoStart) start()
        }

        override suspend fun start() {
            starts++
        }

        override suspend fun remove(chapterIds: List<Long>) = error("not used")
        override suspend fun removeManga(mangaId: Long) = error("not used")
        override suspend fun reorder(orderedChapterIds: List<Long>) = error("not used")
        override suspend fun pause() = error("not used")
        override suspend fun clear() = error("not used")
    }

    /** The library update's insert on A: the same interactor over A's own repositories. */
    private fun SyncTestDevice.updateFromSource(mangaId: Long, vararg urls: String) = runBlocking {
        val chapters = ChapterRepositoryImpl(handler)
        val getChaptersByMangaId = GetChaptersByMangaId(chapters)
        val mangas = MangaRepositoryImpl(handler)
        SyncChaptersWithSource(
            chapterRepository = chapters,
            shouldUpdateDbChapter = ShouldUpdateDbChapter(),
            updateManga = UpdateManga(mangas, FetchInterval(getChaptersByMangaId)),
            getChaptersByMangaId = getChaptersByMangaId,
            transactionRunner = DatabaseTransactionRunner(handler),
            downloadManifestRepository = DownloadManifestRepositoryImpl(handler),
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
            getExcludedScanlators = GetExcludedScanlators(mangas),
            historyRepository = HistoryRepositoryImpl(handler),
            chapterReplacements = ChapterReplacements(chapters, DatabaseTransactionRunner(handler)),
        ).await(urls.map { ChapterInfo(url = it, name = "Chapter ${it.substringAfterLast('/')}") }, mangas.getMangaById(mangaId))
    }

    /** The chapter urls in [device]'s download queue table, in queue order. */
    private fun queuedUrls(device: SyncTestDevice): List<String> = runBlocking {
        DownloadQueueRepositoryImpl(device.handler).subscribeAll().first().map { entry ->
            device.string("SELECT url FROM chapters WHERE _id = ${entry.chapterId}")!!
        }
    }

    private fun SyncTestDevice.chapterIdOf(mangaId: Long, url: String): Long =
        chapterId(mangaId(SyncTestDevice.SOURCE, "/series/$mangaId")!!, url)!!

    @Test
    fun `a chapter the update inserted on A reaches B by a pull and is queued on B, and a first pull queues nothing`() {
        a.addFavourite(1)
        a.addChapter(10, mangaId = 1)
        a.sync()

        // B's first pull of A is A's export: a whole library, not what is new.
        b.sync().shouldNotBeNull().exportsApplied shouldBe 1
        queuedUrls(b) shouldBe emptyList()
        starts shouldBe 0

        val inserted = a.updateFromSource(1, "/series/1/chapter/11", "/series/1/chapter/10")
        inserted.map { it.url } shouldBe listOf("/series/1/chapter/11")
        a.sync()
        b.sync().shouldNotBeNull().segmentsApplied shouldBe 1

        queuedUrls(b) shouldBe listOf("/series/1/chapter/11")
        starts shouldBe 1

        // Pulling nothing new queues nothing more and starts nothing.
        b.sync()
        queuedUrls(b) shouldBe listOf("/series/1/chapter/11")
        starts shouldBe 1
    }

    /**
     * A chapter new to B but read on A before B pulled it: the pull reports it, and the rule leaves
     * it out under the default preferences (skip duplicate read chapters off).
     */
    @Test
    fun `a chapter read on A before B pulls it is not queued on B`() {
        a.addFavourite(1)
        a.addChapter(10, mangaId = 1)
        a.sync()
        b.sync().shouldNotBeNull().exportsApplied shouldBe 1

        a.addChapter(11, mangaId = 1)
        a.addChapter(12, mangaId = 1)
        a.exec("UPDATE chapters SET read = 1 WHERE _id = 11")
        a.sync()
        b.sync().shouldNotBeNull().segmentsApplied shouldBe 1

        b.long("SELECT read FROM chapters WHERE _id = ${b.chapterIdOf(1, "/series/1/chapter/11")}") shouldBe 1
        queuedUrls(b) shouldBe listOf("/series/1/chapter/12")
        starts shouldBe 1
    }

    /**
     * The entry is in C's library before C first reads A, so the arrived-entry rule does not cover
     * the chapter A's export brings: the first pull's own rule is what leaves it out.
     */
    @Test
    fun `a first pull of a device's export reports nothing, even for an entry already in the library here`() {
        c.addFavourite(1)
        c.sync()
        a.addFavourite(1)
        a.addChapter(10, mangaId = 1)
        a.sync()

        c.sync().shouldNotBeNull().exportsApplied shouldBe 1
        c.chapterIdOf(1, "/series/1/chapter/10")
        c.pulledChapters shouldBe emptyList()

        a.addChapter(11, mangaId = 1)
        a.sync()
        c.sync()

        c.pulledChapters shouldBe listOf(listOf(c.chapterIdOf(1, "/series/1/chapter/11")))
    }

    @Test
    fun `a pull reports the chapters it inserted once, with the latch released`() {
        a.addFavourite(1)
        a.addChapter(10, mangaId = 1)
        a.sync()
        c.sync()
        c.pulledChapters shouldBe emptyList()

        a.addChapter(11, mangaId = 1)
        a.addChapter(12, mangaId = 1)
        a.sync()
        c.sync()

        c.pulledChapters shouldBe listOf(listOf(c.chapterIdOf(1, "/series/1/chapter/11"), c.chapterIdOf(1, "/series/1/chapter/12")))
        (c.autoDownload as RecordingAutoDownload).latchedAtReport shouldBe listOf(0L)
    }

    @Test
    fun `a chapter that arrives tombstoned or already here is not reported`() {
        a.addFavourite(1)
        a.addChapter(10, mangaId = 1)
        a.sync()
        c.sync()

        a.addChapter(11, mangaId = 1)
        a.exec("UPDATE chapters SET deleted_at = 5000 WHERE _id = 11")
        a.exec("UPDATE chapters SET read = 1 WHERE _id = 10")
        a.sync()
        c.sync().shouldNotBeNull().segmentsApplied shouldBe 1

        c.long("SELECT count(*) FROM chapters WHERE deleted_at IS NOT NULL") shouldBe 1
        c.pulledChapters shouldBe emptyList()
    }

    @Test
    fun `the chapters of an entry that arrived in the library in the same pull are not reported`() {
        a.addFavourite(1)
        a.addFavourite(3)
        a.exec("UPDATE mangas SET favorite = 0 WHERE _id = 3")
        a.sync()
        c.sync()

        // Entry 2 is new to C, and entry 3 becomes a favourite there, each with a chapter.
        a.addFavourite(2)
        a.addChapter(20, mangaId = 2)
        a.exec("UPDATE mangas SET favorite = 1 WHERE _id = 3")
        a.addChapter(30, mangaId = 3)
        a.addChapter(11, mangaId = 1)
        a.sync()
        c.sync()

        c.long("SELECT count(*) FROM mangas WHERE favorite = 1") shouldBe 3
        c.pulledChapters shouldBe listOf(listOf(c.chapterIdOf(1, "/series/1/chapter/11")))
    }

    @Test
    fun `a snapshot's chapters are not reported, and the segment after it is`() {
        a.addFavourite(1)
        a.addChapter(10, mangaId = 1)
        a.sync()
        c.sync()
        repeat(3) { i ->
            a.addFavourite(100L + i)
            a.sync().shouldNotBeNull().segmentsPushed shouldBe 1
        }
        a.addChapter(11, mangaId = 1)
        a.sync().shouldNotBeNull().segmentsPushed shouldBe 1
        now = 14 * DAY_MS
        a.addFavourite(200)
        a.sync().shouldNotBeNull().compacted shouldBe true
        now = 15 * DAY_MS
        a.addChapter(12, mangaId = 1)
        a.sync().shouldNotBeNull().segmentsPushed shouldBe 1
        // C's chain from its cursor cannot reach the snapshot's point, so it reads the snapshot.
        val segments = backend.paths().mapNotNull { SyncLayout.parse(it) as? RemoteName.Segment }
            .filter { it.deviceId == a.deviceId }.sortedBy { it.lastSeq }
        backend.hide(segments[1].path)

        val result = c.sync().shouldNotBeNull()

        result.snapshotApplied shouldBe true
        result.segmentsApplied shouldBe 1
        c.chapterIdOf(1, "/series/1/chapter/11")
        c.pulledChapters shouldBe listOf(listOf(c.chapterIdOf(1, "/series/1/chapter/12")))
    }
}

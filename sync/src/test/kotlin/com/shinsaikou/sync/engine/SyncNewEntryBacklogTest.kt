package com.shinsaikou.sync.engine

import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.data.DatabaseTransactionRunner
import com.shinsaikou.data.chapter.ScanlatorPriorityRepositoryImpl
import com.shinsaikou.data.download.DownloadManifestRepositoryImpl
import com.shinsaikou.domain.chapter.interactor.GetExcludedScanlators
import com.shinsaikou.domain.chapter.interactor.GetScanlatorPriority
import com.shinsaikou.domain.chapter.interactor.SyncChaptersWithSource
import com.shinsaikou.domain.download.model.DownloadQueueEntry
import com.shinsaikou.domain.manga.interactor.UpdateManga
import com.shinsaikou.domain.preferences.DownloadPreferences
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.ReaderPreferences
import com.shinsaikou.downloads.AutoDownloadRules
import com.shinsaikou.downloads.DownloadManifest
import com.shinsaikou.downloads.DownloadQueue
import com.shinsaikou.libraryupdate.FlatLibraryUpdate
import com.shinsaikou.source.CatalogueSource
import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceManager
import com.shinsaikou.source.StubSource
import com.shinsaikou.source.model.ChapterInfo
import com.shinsaikou.source.model.ImageResult
import com.shinsaikou.source.model.MangaInfo
import com.shinsaikou.source.model.PageInfo
import com.shinsaikou.sync.InMemorySyncBackend
import com.shinsaikou.sync.SyncDigest
import com.shinsaikou.sync.format.RemoteName
import com.shinsaikou.sync.format.SyncLayout
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
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
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import kotlin.time.Clock
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers as CoroutineDispatchers

/**
 * An entry another device added arrives with its whole chapter list (phase 4 unit NB, R117
 * carry-over 1, challenge c5 changes 6, 7 and 8 (c)). Device A adds an entry of 100 chapters the
 * way the manga screen does: the chapter list fetched while it is not a favourite, then
 * `UpdateManga.awaitUpdateFavorite` over `MangaRepositoryImpl`, never a raw UPDATE, which would fire
 * the progress trigger alone. B pulls it, and B's library update (the real [FlatLibraryUpdate],
 * `SyncChaptersWithSource` and [AutoDownloadRules] with "Download new chapters" on, over B's own
 * database) runs against a source listing those 100 and one more. Before the fix B held none of the
 * chapters and queued all 101. Both devices run every trigger; the counts here are read from the
 * download queue each device's rules wrote to, never from a write-back of the refresh (R120
 * carry-over 1).
 */
class SyncNewEntryBacklogTest {

    /** Each device's download queue, as its rules filled it. */
    private val queues = mutableMapOf<SyncTestDevice, RecordingQueue>()
    private val backend = InMemorySyncBackend()
    private val a = SyncTestDevice(backend, autoDownloadOf = ::rulesOver)
    private val b = SyncTestDevice(backend, autoDownloadOf = ::rulesOver)

    /** The chapters the source lists for each entry url, newest first, as a source orders them. */
    private val listings = mutableMapOf<String, List<ChapterInfo>>()

    @AfterEach
    fun close() {
        a.close()
        b.close()
    }

    private fun rulesOver(device: SyncTestDevice): AutoDownloadRules {
        val queue = RecordingQueue().also { queues[device] = it }
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
            queue = queue,
            downloadPreferences = downloadPreferences,
            readerPreferences = ReaderPreferences(InMemoryPreferenceStore()),
        )
    }

    /** The urls of the chapters [device]'s rules queued, in order. */
    private fun queuedUrls(device: SyncTestDevice): List<String> =
        queues.getValue(device).queued.map { device.string("SELECT url FROM chapters WHERE _id = $it")!! }

    /** The interactors of [device], over its own repositories, as the graph builds them. */
    private inner class Library(private val device: SyncTestDevice) {
        val mangas = MangaRepositoryImpl(device.handler)
        private val chapters = ChapterRepositoryImpl(device.handler)
        private val getChaptersByMangaId = GetChaptersByMangaId(chapters)
        private val fetchInterval = FetchInterval(getChaptersByMangaId)
        val updateManga = UpdateManga(mangas, fetchInterval)
        private val libraryPreferences = LibraryPreferences(InMemoryPreferenceStore())
        val syncChaptersWithSource = SyncChaptersWithSource(
            chapterRepository = chapters,
            shouldUpdateDbChapter = ShouldUpdateDbChapter(),
            updateManga = updateManga,
            getChaptersByMangaId = getChaptersByMangaId,
            transactionRunner = DatabaseTransactionRunner(device.handler),
            downloadManifestRepository = DownloadManifestRepositoryImpl(device.handler),
            libraryPreferences = libraryPreferences,
            getExcludedScanlators = GetExcludedScanlators(mangas),
            historyRepository = HistoryRepositoryImpl(device.handler),
        )

        /** One run of the library update, [automatic] as the worker passes it, with the preferences' defaults. */
        fun update(automatic: Boolean): FlatLibraryUpdate.Outcome = runBlocking {
            val io = CoroutineDispatchers.Default
            FlatLibraryUpdate(
                getLibraryManga = GetLibraryManga(mangas),
                getManga = GetManga(mangas),
                getChaptersByMangaId = getChaptersByMangaId,
                sourceManager = OneSourceManager(ListingSource()),
                syncChaptersWithSource = syncChaptersWithSource,
                updateManga = updateManga,
                fetchInterval = fetchInterval,
                libraryPreferences = libraryPreferences,
                autoDownload = device.autoDownload,
                dispatchers = Dispatchers(io = io, default = io, main = io),
                timeSource = TimeSource.Monotonic,
            ).run(categoryId = null, automatic = automatic) {}
        }

        /**
         * A library add from the manga screen: the entry's row, its chapter list fetched while it is
         * not a favourite, then the add. Returns the entry's id. [withDetails] false is a source
         * whose details page gave none of description, author, artist or genre.
         */
        fun browseAdd(url: String, chapterCount: Int, withDetails: Boolean = true): Long = runBlocking {
            listings[url] = listing(url, chapterCount)
            val details = if (withDetails) "About $url" to "Author $url" else null to null
            val manga = mangas.insertNetworkManga(
                listOf(
                    Manga.create().copy(
                        source = SyncTestDevice.SOURCE,
                        url = url,
                        title = "Title $url",
                        description = details.first,
                        author = details.second,
                        initialized = true,
                    ),
                ),
            ).single()
            syncChaptersWithSource.await(listings.getValue(url), manga, manualFetch = true)
            updateManga.awaitUpdateFavorite(manga.id, favorite = true) shouldBe true
            manga.id
        }
    }

    private fun listing(url: String, count: Int): List<ChapterInfo> = (count downTo 1).map {
        ChapterInfo(url = "$url/chapter/$it", name = "Chapter $it", chapterNumber = it.toFloat(), dateUpload = 1_000L * it)
    }

    /** One source answering every entry with its [listings] row. */
    private inner class ListingSource : Source {
        override val id = SyncTestDevice.SOURCE
        override val name = "Fixture source"
        override val lang = "en"
        override suspend fun getMangaDetails(manga: MangaInfo) = manga.copy(initialized = true)
        override suspend fun getChapterList(manga: MangaInfo) = listings.getValue(manga.url)
        override suspend fun getPageList(chapter: ChapterInfo): List<PageInfo> = error("not used")
        override suspend fun getImage(page: PageInfo): ImageResult = error("not used")
    }

    /** A registry that has loaded and holds [source] alone. */
    private class OneSourceManager(private val source: Source) : SourceManager {
        override val isInitialized: StateFlow<Boolean> = MutableStateFlow(true)
        override fun get(id: Long): Source? = source.takeIf { it.id == id }
        override fun getOrStub(id: Long): Source = get(id) ?: error("no other source here")
        override fun getCatalogueSources(): Flow<List<CatalogueSource>> = flowOf(emptyList())
        override fun getSources(): Flow<List<Source>> = flowOf(listOf(source))
        override fun getStubSources(): Flow<List<StubSource>> = emptyFlow()
    }

    /** The download queue's enqueues, in order; nothing is downloaded. */
    private class RecordingQueue : DownloadQueue {
        val queued = mutableListOf<Long>()
        override fun subscribe(): Flow<List<DownloadQueueEntry>> = emptyFlow()
        override val isRunning: StateFlow<Boolean> = MutableStateFlow(false)
        override val isPaused: StateFlow<Boolean> = MutableStateFlow(false)
        override suspend fun enqueue(manga: Manga, chapters: List<Chapter>, autoStart: Boolean) {
            queued += chapters.map { it.id }
        }
        override suspend fun start() = Unit
        override suspend fun remove(chapterIds: List<Long>) = error("not used")
        override suspend fun removeManga(mangaId: Long) = error("not used")
        override suspend fun reorder(orderedChapterIds: List<Long>) = error("not used")
        override suspend fun pause() = error("not used")
        override suspend fun clear() = error("not used")
    }

    /** Both devices past their first exports, so every later change travels in segments. */
    private fun paired() {
        a.sync()
        b.sync()
        a.sync()
    }

    private fun SyncTestDevice.entryId(url: String): Long = mangaId(SyncTestDevice.SOURCE, url).shouldNotBeNull()

    private fun SyncTestDevice.chapterCount(url: String): Long =
        long("SELECT count(*) FROM chapters WHERE manga_id = ${entryId(url)}")!!

    /** The chapter urls of [url]'s entry the Updates tab lists here (`updatesView`), newest fetch first. */
    private fun SyncTestDevice.updatesOf(url: String): List<String> =
        rows("SELECT chapterUrl FROM updatesView WHERE mangaId = ${entryId(url)} ORDER BY chapterUrl", 1).map { it.single()!! }

    private fun digest(device: SyncTestDevice): SyncDigest.Result = runBlocking { SyncDigest.of(device.store) }

    /** Waits until the clock has passed [dateAdded], so a chapter fetched now is newer than the add. */
    private fun SyncTestDevice.afterTheAdd(url: String) {
        val dateAdded = long("SELECT date_added FROM mangas WHERE _id = ${entryId(url)}")!!
        while (Clock.System.now().toEpochMilliseconds() <= dateAdded) Thread.sleep(1)
    }

    @Test
    fun `an entry the other device added arrives with every chapter, and a manual update here queues only the chapter new at the source`() {
        paired()
        Library(a).browseAdd(SERIES, chapterCount = 100)
        a.sync().shouldNotBeNull().segmentsPushed shouldBe 1

        b.sync().shouldNotBeNull().segmentsApplied shouldBe 1
        // Read before the update and asserted with what follows it, in one block, so a run without
        // the fix reports what B then queues as well as what it lacked after the pull (R29).
        val chaptersAfterPull = b.chapterCount(SERIES)
        val digestsAfterPull = digest(b) to digest(a)
        val updatesAfterPull = b.updatesOf(SERIES)
        val queuedByPull = queuedUrls(b)

        listings[SERIES] = listing(SERIES, 101)
        b.afterTheAdd(SERIES)
        val outcome = Library(b).update(automatic = false)

        assertSoftly {
            chaptersAfterPull shouldBe 100
            digestsAfterPull.first shouldBe digestsAfterPull.second
            updatesAfterPull.shouldBeEmpty()
            queuedByPull.shouldBeEmpty()
            outcome.failures.shouldBeEmpty()
            queuedUrls(b).size shouldBe 1
            queuedUrls(b) shouldContainExactly listOf("$SERIES/chapter/101")
            b.updatesOf(SERIES) shouldContainExactly listOf("$SERIES/chapter/101")
            a.updatesOf(SERIES).shouldBeEmpty()
        }
    }

    /**
     * The entry's details travel with it, so `syncInsert` writes it initialized and B's automatic
     * update applies the restrictions as A's does: with 100 unread chapters the default
     * restrictions skip it on both devices, and a newly paired device does not refresh its whole
     * pulled library once (owner ruling, 2026-10-15).
     */
    @Test
    fun `an automatic update with the default restrictions skips the added entry on both devices, since its details arrived with it`() {
        paired()
        Library(a).browseAdd(SERIES, chapterCount = 100)
        a.sync()
        b.sync()
        val initializedAfterPull = b.long("SELECT initialized FROM mangas WHERE _id = ${b.entryId(SERIES)}")
        listings[SERIES] = listing(SERIES, 101)
        b.afterTheAdd(SERIES)

        Library(a).update(automatic = true)
        Library(b).update(automatic = true)

        assertSoftly {
            initializedAfterPull shouldBe 1L
            queuedUrls(a).shouldBeEmpty()
            a.chapterCount(SERIES) shouldBe 100
            queuedUrls(b).shouldBeEmpty()
            b.chapterCount(SERIES) shouldBe 100
        }
    }

    /**
     * An entry that arrives with none of its details stays `initialized = 0` on B, so B's automatic
     * update refreshes it whatever the restrictions, as upstream v0.21.0 does for an entry never
     * fetched. A skips it, having fetched it. B's one refresh queues only the chapter new at the
     * source and marks the entry initialized, after which the restrictions skip it on B too.
     */
    @Test
    fun `an entry that arrives with none of its details is refreshed once by an automatic update, then skipped like any other`() {
        paired()
        Library(a).browseAdd(SERIES, chapterCount = 100, withDetails = false)
        a.sync()
        b.sync()
        val initializedAfterPull = b.long("SELECT initialized FROM mangas WHERE _id = ${b.entryId(SERIES)}")
        listings[SERIES] = listing(SERIES, 101)
        b.afterTheAdd(SERIES)

        Library(a).update(automatic = true)
        Library(b).update(automatic = true)
        val queuedByFirst = queuedUrls(b)
        val initializedAfterFirst = b.long("SELECT initialized FROM mangas WHERE _id = ${b.entryId(SERIES)}")
        listings[SERIES] = listing(SERIES, 102)
        Library(b).update(automatic = true)

        assertSoftly {
            initializedAfterPull shouldBe 0L
            queuedUrls(a).shouldBeEmpty()
            a.chapterCount(SERIES) shouldBe 100
            queuedByFirst shouldContainExactly listOf("$SERIES/chapter/101")
            initializedAfterFirst shouldBe 1L
            queuedUrls(b) shouldContainExactly listOf("$SERIES/chapter/101")
            b.chapterCount(SERIES) shouldBe 101
        }
    }

    @Test
    fun `the pull that brings the added entry counts none of its chapters as new`() {
        // g8 test 4: the arrived-entry rule of R97 item 3, now over the whole list.
        val c = SyncTestDevice(backend)
        try {
            paired()
            c.sync()
            Library(a).browseAdd(SERIES, chapterCount = 100)
            a.sync()

            c.sync().shouldNotBeNull().segmentsApplied shouldBe 1

            c.chapterCount(SERIES) shouldBe 100
            c.pulledChapters.flatten().shouldBeEmpty()
        } finally {
            c.close()
        }
    }

    /**
     * Change 8 (c): A's add straddles a full batch's boundary. Its first segment, the only one B can
     * list, ends before the add, so B's update between the two pulls never meets the entry, and the
     * second pull brings it whole. Without the cut the first segment carries the entry and part of
     * its list, and B's update takes the rest of the list for new.
     */
    @Test
    fun `a pull stopped between the two segments of a push that crossed an add, then an update, then the rest, queues only the chapter new at the source`() {
        paired()
        // A filler entry fetched once, so B's manual update skips it as upstream's does, with 4,949
        // chapters: 4,951 log rows with its insert and its update strategy, and the add's 101 rows
        // straddle the 5,000th.
        a.addFavourite(1)
        a.exec("UPDATE mangas SET update_strategy = 1 WHERE _id = 1")
        a.exec(
            """
            WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 4949)
            INSERT INTO chapters(manga_id, url, name, read, bookmark, last_page_read, chapter_number,
                source_order, date_fetch, date_upload, memo)
            SELECT 1, '/series/1/chapter/' || i, 'Chapter ' || i, 0, 0, 0, i, i, 0, 0, CAST('{}' AS BLOB) FROM n
            """,
        )
        val pushedBefore = a.preferences.lastPushedSeq.get()
        Library(a).browseAdd(SERIES, chapterCount = 100)
        (a.latestSeq() - pushedBefore) shouldBe 5_052
        a.sync().shouldNotBeNull().segmentsPushed shouldBe 2

        val segments = runBlocking { backend.list(SyncLayout.devicePrefix(a.deviceId)) }
            .mapNotNull { SyncLayout.parse(it.path) as? RemoteName.Segment }
            .filter { it.afterSeq >= pushedBefore }
            .sortedBy { it.afterSeq }
        segments.drop(1).forEach { backend.hide(it.path) }

        b.sync().shouldNotBeNull().segmentsApplied shouldBe 1
        listings[SERIES] = listing(SERIES, 101)
        b.mangaId(SyncTestDevice.SOURCE, SERIES)?.let { b.afterTheAdd(SERIES) }
        Library(b).update(automatic = false)
        val queuedBetween = queuedUrls(b)

        segments.drop(1).forEach { backend.unhide(it.path) }
        b.sync().shouldNotBeNull().segmentsApplied shouldNotBe 0
        b.afterTheAdd(SERIES)
        Library(b).update(automatic = false)

        assertSoftly {
            segments.first().lastSeq shouldBe pushedBefore + 4_951
            queuedBetween.shouldBeEmpty()
            b.chapterCount(SERIES) shouldBe 101
            queuedUrls(b).size shouldBe 1
            queuedUrls(b) shouldContainExactly listOf("$SERIES/chapter/101")
        }
    }

    private companion object {
        const val SERIES = "/series/2"
    }
}

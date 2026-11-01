package com.shinsaikou.sync.engine

import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.data.DatabaseTransactionRunner
import com.shinsaikou.data.chapter.ScanlatorPriorityRepositoryImpl
import com.shinsaikou.data.download.DownloadManifestRepositoryImpl
import com.shinsaikou.domain.chapter.ChapterReplacements
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
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
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
import tachiyomi.data.updates.UpdatesRepositoryImpl
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
 * An entry bulk added from a source's listing joins the library with no chapters: the add is
 * `UpdateManga.awaitUpdateFavorite` and no chapter fetch comes first. Its first library update then
 * reads the whole back catalogue. The real [FlatLibraryUpdate], `SyncChaptersWithSource` and
 * [AutoDownloadRules] with "Download new chapters" on run over a real database, and the Updates
 * query is read from `UpdatesRepositoryImpl`. Only the chapter uploaded after the add is new.
 */
class FirstListBacklogTest {

    private val device = SyncTestDevice(InMemorySyncBackend())
    private val queue = RecordingQueue()
    private val libraryPreferences = LibraryPreferences(InMemoryPreferenceStore())
    private val downloadPreferences = DownloadPreferences(InMemoryPreferenceStore()).apply { downloadNewChapters.set(true) }
    private val mangas = MangaRepositoryImpl(device.handler)
    private val chapters = ChapterRepositoryImpl(device.handler)
    private val getChaptersByMangaId = GetChaptersByMangaId(chapters)
    private val fetchInterval = FetchInterval(getChaptersByMangaId)
    private val updateManga = UpdateManga(mangas, fetchInterval)
    private val getManga = GetManga(mangas)

    /** The chapters the source lists for each entry url, newest first. */
    private val listings = mutableMapOf<String, List<ChapterInfo>>()

    @AfterEach
    fun close() {
        device.close()
    }

    private val autoDownload = AutoDownloadRules(
        filterChaptersForDownload = FilterChaptersForDownload(
            getChaptersByMangaId,
            downloadPreferences,
            GetCategories(CategoryRepositoryImpl(device.handler)),
        ),
        getNextChapters = GetNextChapters(getChaptersByMangaId, getManga, mockk()),
        getScanlatorPriority = GetScanlatorPriority(ScanlatorPriorityRepositoryImpl(device.handler)),
        getManga = getManga,
        chapterRepository = chapters,
        // Nothing is on disk on a test device.
        manifest = mockk<DownloadManifest>(relaxed = true),
        queue = queue,
        downloadPreferences = downloadPreferences,
        readerPreferences = ReaderPreferences(InMemoryPreferenceStore()),
    )

    private val syncChaptersWithSource = SyncChaptersWithSource(
        chapterRepository = chapters,
        shouldUpdateDbChapter = ShouldUpdateDbChapter(),
        updateManga = updateManga,
        getChaptersByMangaId = getChaptersByMangaId,
        transactionRunner = DatabaseTransactionRunner(device.handler),
        downloadManifestRepository = DownloadManifestRepositoryImpl(device.handler),
        libraryPreferences = libraryPreferences,
        getExcludedScanlators = GetExcludedScanlators(mangas),
        historyRepository = HistoryRepositoryImpl(device.handler),
        chapterReplacements = ChapterReplacements(chapters, DatabaseTransactionRunner(device.handler)),
    )

    private fun libraryUpdate(): FlatLibraryUpdate.Outcome = runBlocking {
        val io = CoroutineDispatchers.Default
        FlatLibraryUpdate(
            getLibraryManga = GetLibraryManga(mangas),
            getManga = getManga,
            getChaptersByMangaId = getChaptersByMangaId,
            sourceManager = OneSourceManager(ListingSource()),
            syncChaptersWithSource = syncChaptersWithSource,
            updateManga = updateManga,
            fetchInterval = fetchInterval,
            libraryPreferences = libraryPreferences,
            autoDownload = autoDownload,
            dispatchers = Dispatchers(io = io, default = io, main = io),
            timeSource = TimeSource.Monotonic,
        ).run(categoryId = null, automatic = false) {}
    }

    /** Adds [url] to the library the way a bulk add from a listing does: a row, then the favourite flag. */
    private fun bulkAdd(url: String): Long = runBlocking {
        val manga = mangas.insertNetworkManga(
            listOf(Manga.create().copy(source = SyncTestDevice.SOURCE, url = url, title = "Title $url", initialized = true)),
        ).single()
        updateManga.awaitUpdateFavorite(manga.id, favorite = true) shouldBe true
        manga.id
    }

    private fun dateAdded(id: Long): Long = device.long("SELECT date_added FROM mangas WHERE _id = $id")!!

    private fun updatesTab(): List<String> = runBlocking {
        UpdatesRepositoryImpl(device.handler).subscribeAll(
            after = 0L,
            limit = 1_000L,
            unread = null,
            started = null,
            bookmarked = null,
            hideExcludedScanlators = false,
            includedCategories = emptyList(),
            excludedCategories = emptyList(),
        ).first().map { it.chapterUrl }
    }

    private fun queuedUrls(): List<String> =
        queue.queued.map { device.string("SELECT url FROM chapters WHERE _id = $it")!! }

    @Test
    fun `the first update of a bulk added entry counts, announces, queues and lists only the chapter uploaded after the add`() {
        val id = bulkAdd(SERIES)
        val added = dateAdded(id)
        val backlog = (1..50).map { ChapterInfo(url = "$SERIES/$it", name = "Chapter $it", chapterNumber = it.toFloat(), dateUpload = added - 1_000L * it) }
        val later = ChapterInfo(url = "$SERIES/51", name = "Chapter 51", chapterNumber = 51f, dateUpload = added + 1L)
        listings[SERIES] = listOf(later) + backlog.reversed()
        // The later chapter is fetched now, which must be past the add for the view to list it.
        while (Clock.System.now().toEpochMilliseconds() <= added) Thread.sleep(1)

        val outcome = libraryUpdate()

        assertSoftly {
            outcome.failures.shouldBeEmpty()
            device.long("SELECT count(*) FROM chapters WHERE manga_id = $id") shouldBe 51L
            libraryPreferences.newUpdatesCount.get() shouldBe 1
            outcome.updated.getValue(id).map { it.url } shouldContainExactly listOf("$SERIES/51")
            queuedUrls() shouldContainExactly listOf("$SERIES/51")
            updatesTab() shouldContainExactly listOf("$SERIES/51")
        }
    }

    @Test
    fun `a second update after a bulk add counts a chapter the source lists afterwards`() {
        val id = bulkAdd(SERIES)
        val added = dateAdded(id)
        val backlog = (1..3).map { ChapterInfo(url = "$SERIES/$it", name = "Chapter $it", chapterNumber = it.toFloat(), dateUpload = added - 1_000L * it) }
        listings[SERIES] = backlog.reversed()
        libraryUpdate()

        listings[SERIES] = listOf(ChapterInfo(url = "$SERIES/4", name = "Chapter 4", chapterNumber = 4f, dateUpload = 1L)) + backlog.reversed()
        val outcome = libraryUpdate()

        assertSoftly {
            outcome.failures.shouldBeEmpty()
            libraryPreferences.newUpdatesCount.get() shouldBe 1
            outcome.updated.getValue(id).map { it.url } shouldContainExactly listOf("$SERIES/4")
            queuedUrls() shouldContainExactly listOf("$SERIES/4")
        }
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

    private companion object {
        const val SERIES = "/series/2"
    }
}

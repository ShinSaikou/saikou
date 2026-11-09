package com.shinsaikou.ui.manga

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.shinsaikou.core.common.preferences.BasePreferences
import com.shinsaikou.data.AndroidDatabaseHandler
import com.shinsaikou.domain.chapter.interactor.GetAvailableScanlators
import com.shinsaikou.domain.chapter.interactor.GetExcludedScanlators
import com.shinsaikou.domain.chapter.interactor.GetScanlatorPriority
import com.shinsaikou.domain.chapter.interactor.SetExcludedScanlators
import com.shinsaikou.domain.chapter.interactor.SetReadStatus
import com.shinsaikou.domain.chapter.interactor.SetScanlatorPriority
import com.shinsaikou.domain.chapter.interactor.SyncChaptersWithSource
import com.shinsaikou.domain.chapter.interactor.ToggleBookmark
import com.shinsaikou.domain.chapter.model.ScanlatorPriority
import com.shinsaikou.domain.download.AutoDownload
import com.shinsaikou.domain.download.DownloadedChapters
import com.shinsaikou.domain.download.model.DownloadQueueEntry
import com.shinsaikou.domain.download.model.DownloadState
import com.shinsaikou.domain.manga.interactor.RefreshMangaFromSource
import com.shinsaikou.domain.manga.interactor.SetMangaDetailsOverride
import com.shinsaikou.domain.manga.interactor.SetMangaViewerFlags
import com.shinsaikou.domain.manga.interactor.UpdateManga
import com.shinsaikou.domain.manga.model.ReaderOrientation
import com.shinsaikou.domain.manga.model.ReadingMode
import com.shinsaikou.domain.preferences.AutoTrackState
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.LibraryPreferences.ChapterSwipeAction
import com.shinsaikou.domain.preferences.LibraryUpdateRestrictions
import com.shinsaikou.domain.preferences.ReaderPreferences
import com.shinsaikou.domain.preferences.TrackPreferences
import com.shinsaikou.domain.preferences.UiPreferences
import com.shinsaikou.domain.track.TrackProgress
import com.shinsaikou.downloads.DownloadManifest
import com.shinsaikou.downloads.DownloadQueue
import com.shinsaikou.i18n.R
import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceIds
import com.shinsaikou.source.SourceManager
import com.shinsaikou.source.StubSource
import com.shinsaikou.source.model.ChapterInfo
import com.shinsaikou.source.model.MangaInfo
import com.shinsaikou.source.model.MangaStatus
import com.shinsaikou.source.model.MangaUpdate
import com.shinsaikou.source.model.UpdateStrategy
import com.shinsaikou.sync.MapPreferenceStore
import com.shinsaikou.tracking.FakeOAuthTracker
import com.shinsaikou.tracking.TrackerManager
import com.shinsaikou.ui.chapter.ChapterDownload
import com.shinsaikou.ui.chapter.ChapterDownloadAction
import com.shinsaikou.ui.chapter.ChapterDownloadTap
import com.shinsaikou.ui.chapter.tap
import com.shinsaikou.ui.components.DownloadAction
import com.shinsaikou.ui.components.DuplicateEntry
import com.shinsaikou.ui.fixture.ColdSourceRegistry
import com.shinsaikou.ui.fixture.RecordingReadChapterCleanup
import com.shinsaikou.ui.fixture.deletingSetReadStatus
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.interactor.SetMangaChapterFlags
import tachiyomi.domain.manga.interactor.UpdateMangaNotes
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.model.MangaUpdate as DomainMangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers
import kotlinx.coroutines.Dispatchers as KotlinxDispatchers
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import java.io.IOException
import java.util.Properties

/**
 * This view model is what makes a freshly added or freshly browsed entry actually fetch a
 * chapter list: before it existed nothing in the app ever called SyncChaptersWithSource on
 * first open, so the tests around the automatic refresh guard are the regression that matters
 * most here, not just the Loading to Success happy path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MangaDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val appDispatchers = AppDispatchers(io = testDispatcher, default = testDispatcher, main = testDispatcher)

    private val getMangaWithChapters = mockk<GetMangaWithChapters>()

    /** The row read before the automatic refresh starts; there, unless a test deletes it. */
    private val getManga = mockk<GetManga> { coEvery { await(any()) } answers { fixtureManga(id = firstArg()) } }
    private val updateManga = mockk<UpdateManga>()
    private val setMangaDetailsOverride = mockk<SetMangaDetailsOverride>()
    private val updateMangaNotes = mockk<UpdateMangaNotes>()
    private val coverActions = mockk<MangaCoverActions>()
    private val syncChaptersWithSource = mockk<SyncChaptersWithSource>()
    private val sourceManager = mockk<SourceManager>()
    private val setReadStatus = mockk<SetReadStatus>()
    private val toggleBookmark = mockk<ToggleBookmark>(relaxed = true)
    private val setMangaChapterFlags = mockk<SetMangaChapterFlags>(relaxed = true)
    private val setMangaDefaultChapterFlags = mockk<SetMangaDefaultChapterFlags>(relaxed = true)
    private val setMangaViewerFlags = mockk<SetMangaViewerFlags>(relaxed = true)
    private val getExcludedScanlators = mockk<GetExcludedScanlators>()
    private val setExcludedScanlators = mockk<SetExcludedScanlators>(relaxed = true)
    private val getAvailableScanlators = mockk<GetAvailableScanlators>()
    private val getScanlatorPriority = mockk<GetScanlatorPriority>()
    private val setScanlatorPriority = mockk<SetScanlatorPriority>(relaxed = true)
    private val getNextChapters = mockk<GetNextChapters>()
    private val getCategories = mockk<GetCategories>()
    private val setMangaCategories = mockk<SetMangaCategories>(relaxed = true)
    private val getDuplicateLibraryManga = mockk<GetDuplicateLibraryManga>()
    private val fetchInterval = mockk<FetchInterval>()
    private val libraryPreferences = LibraryPreferences(InMemoryPreferenceStore())
    private val readerPreferences = ReaderPreferences(InMemoryPreferenceStore())
    private val uiPreferences = UiPreferences(InMemoryPreferenceStore())
    private val downloadQueue = mockk<DownloadQueue>(relaxed = true)
    private val downloadManifest = mockk<DownloadManifest>(relaxed = true)
    private val downloadedChapters = mockk<DownloadedChapters>()
    private val basePreferences = BasePreferences(InMemoryPreferenceStore())
    private val trackPreferences = TrackPreferences(MapPreferenceStore())
    private val trackProgress = FakeTrackProgress()
    private val autoDownload = mockk<AutoDownload>(relaxed = true)
    private var trackerManager = TrackerManager(emptySet())
    private val trackRows = MutableStateFlow<List<Track>>(emptyList())
    private val trackRepository = mockk<TrackRepository> {
        every { getTracksByMangaIdAsFlow(any()) } returns trackRows
    }

    @BeforeEach
    fun setUp() {
        KotlinxDispatchers.setMain(testDispatcher)
        // The projection is a three way combine now, so a test that says nothing about scanlators
        // still needs both flows to emit or the state never leaves Loading.
        every { getExcludedScanlators.subscribe(any()) } returns flowOf(emptySet())
        // The stored chapters a refresh hands its source.
        coEvery { getMangaWithChapters.awaitChapters(any(), any()) } returns emptyList()
        every { getAvailableScanlators.subscribe(any()) } returns flowOf(emptySet())
        every { getScanlatorPriority.subscribe(any()) } returns flowOf(emptyList())
        // The same for the downloaded set and the queue, which every projection now reads too.
        every { downloadedChapters.subscribeByMangaId(any()) } returns flowOf(emptySet())
        every { downloadQueue.subscribe() } returns flowOf(emptyList())
        // A registry that has finished its first load; the cold-start tests pass their own.
        every { sourceManager.isInitialized } returns MutableStateFlow(true)
        // The duplicate dialog names each match's source; one no test installs reads as a stub.
        every { sourceManager.getOrStub(any()) } answers {
            StubSource(id = firstArg(), name = "Fixture Gone Source", lang = "")
        }
    }

    @AfterEach
    fun tearDown() {
        KotlinxDispatchers.resetMain()
    }

    private fun fixtureManga(
        id: Long = 1L,
        source: Long = 10L,
        favorite: Boolean = false,
        initialized: Boolean = true,
        chapterFlags: Long = 0L,
        viewerFlags: Long = 0L,
    ) = Manga.create().copy(
        id = id,
        source = source,
        favorite = favorite,
        initialized = initialized,
        chapterFlags = chapterFlags,
        viewerFlags = viewerFlags,
        title = "Fixture Manga",
        url = "fixture/1",
    )

    private fun fixtureChapter(
        id: Long = 100L,
        mangaId: Long = 1L,
        name: String = "Chapter 1",
        bookmark: Boolean = false,
        scanlator: String? = null,
    ) = Chapter.create().copy(
        id = id,
        mangaId = mangaId,
        name = name,
        bookmark = bookmark,
        scanlator = scanlator,
    )

    /**
     * The refresh asks through [Source.getMangaUpdate]; this mock answers it the way the contract's
     * default does, from the two single calls, so a test stubs and verifies those.
     */
    private fun fixtureSource(): Source = mockk<Source> {
        val source = this
        every { name } returns "Fixture Source"
        every { homeUrl } returns null
        coEvery { getMangaUpdate(any(), any(), any(), any()) } coAnswers {
            val manga = firstArg<MangaInfo>()
            MangaUpdate(
                manga = if (thirdArg<Boolean>()) source.getMangaDetails(manga) else null,
                chapters = if (arg<Boolean>(3)) source.getChapterList(manga) else null,
            )
        }
    }

    private fun viewModel(
        mangaId: Long,
        fromSource: Boolean,
        sourceManager: SourceManager = this.sourceManager,
        setReadStatus: SetReadStatus = this.setReadStatus,
        getMangaWithChapters: GetMangaWithChapters = this.getMangaWithChapters,
        getManga: GetManga = this.getManga,
    ) = MangaDetailViewModel(
        mangaId = mangaId,
        fromSource = fromSource,
        getMangaWithChapters = getMangaWithChapters,
        getManga = getManga,
        updateManga = updateManga,
        setMangaDetailsOverride = setMangaDetailsOverride,
        updateMangaNotes = updateMangaNotes,
        coverActions = coverActions,
        // The real interactor over this test's mocks, so every refresh test still reads the
        // contract calls and the chapter sync the screen's fetch made before phase 4 unit MA
        // moved it out of the view model.
        refreshMangaFromSource = RefreshMangaFromSource(
            getMangaWithChapters = getMangaWithChapters,
            updateManga = updateManga,
            syncChaptersWithSource = syncChaptersWithSource,
            fetchInterval = fetchInterval,
            reconcileMergedRead = mockk(relaxed = true),
            libraryPreferences = libraryPreferences,
            dispatchers = appDispatchers,
        ),
        sourceManager = sourceManager,
        setReadStatus = setReadStatus,
        toggleBookmark = toggleBookmark,
        setMangaChapterFlags = setMangaChapterFlags,
        setMangaDefaultChapterFlags = setMangaDefaultChapterFlags,
        setMangaViewerFlags = setMangaViewerFlags,
        getExcludedScanlators = getExcludedScanlators,
        setExcludedScanlators = setExcludedScanlators,
        getAvailableScanlators = getAvailableScanlators,
        getScanlatorPriority = getScanlatorPriority,
        setScanlatorPriority = setScanlatorPriority,
        getNextChapters = getNextChapters,
        getCategories = getCategories,
        setMangaCategories = setMangaCategories,
        getDuplicateLibraryManga = getDuplicateLibraryManga,
        libraryPreferences = libraryPreferences,
        readerPreferences = readerPreferences,
        uiPreferences = uiPreferences,
        downloadQueue = downloadQueue,
        downloadManifest = downloadManifest,
        downloadedChapters = downloadedChapters,
        basePreferences = basePreferences,
        trackPreferences = trackPreferences,
        trackProgress = trackProgress,
        autoDownload = autoDownload,
        trackerManager = trackerManager,
        trackRepository = trackRepository,
        getMergedGroups = mockk { every { subscribe(any()) } returns flowOf(null) },
        getMergedChapters = mockk(),
        reconcileMergedRead = mockk(),
        mergeEntries = mockk(),
        addMergedEntry = mockk(),
        setMergedOrder = mockk(),
        setMergedMain = mockk(),
        setMergedCheckUpdates = mockk(),
        setMergedShowAll = mockk(),
        removeMergedMember = mockk(),
        unmergeEntries = mockk(),
        dispatchers = appDispatchers,
    )

    @Test
    fun `the state moves from Loading to Success once GetMangaWithChapters emits`() = runTest(testDispatcher) {
        val manga = fixtureManga(initialized = true)
        val chapters = listOf(fixtureChapter())
        coEvery { getMangaWithChapters.subscribe(manga.id, applyScanlatorFilter = true) } returns
            flowOf(manga to chapters)
        every { sourceManager.get(manga.source) } returns fixtureSource()
        coEvery { getNextChapters.await(manga.id) } returns emptyList()

        val viewModel = viewModel(manga.id, fromSource = false)

        viewModel.state.test {
            awaitItem() shouldBe MangaDetailViewModel.State.Loading
            advanceUntilIdle()
            val success = awaitItem() as MangaDetailViewModel.State.Success
            success.manga shouldBe manga
            success.chapters shouldContainExactly chapters
        }
        coVerify(exactly = 0) { syncChaptersWithSource.await(any(), any(), any(), any()) }
    }

    @Test
    fun `a screen restored before the registry has loaded stays loading, then shows the installed source`() =
        runTest(testDispatcher) {
            val manga = fixtureManga(initialized = true)
            coEvery { getMangaWithChapters.subscribe(manga.id, applyScanlatorFilter = true) } returns
                flowOf(manga to listOf(fixtureChapter()))
            coEvery { getNextChapters.await(manga.id) } returns emptyList()
            val registry = ColdSourceRegistry(mapOf(manga.source to fixtureSource()))

            val viewModel = viewModel(manga.id, fromSource = false, sourceManager = registry.manager)
            advanceUntilIdle()
            viewModel.state.value shouldBe MangaDetailViewModel.State.Loading

            registry.finishLoad()
            advanceUntilIdle()

            val success = viewModel.state.value as MangaDetailViewModel.State.Success
            success.sourceInstalled shouldBe true
            success.sourceName shouldBe "Fixture Source"
        }

    @Test
    fun `an entry from a listing restored cold refreshes from its source once the registry loads`() =
        runTest(testDispatcher) {
            val manga = fixtureManga(initialized = true)
            val source = fixtureSource()
            coEvery { getMangaWithChapters.subscribe(manga.id, applyScanlatorFilter = true) } returns
                flowOf(manga to listOf(fixtureChapter()))
            coEvery { getNextChapters.await(manga.id) } returns emptyList()
            coEvery { source.getMangaDetails(any()) } returns MangaInfo(url = "fixture/1", title = "Fixture Manga")
            coEvery { updateManga.awaitUpdateFromSource(any(), any(), any(), any()) } returns true
            every { fetchInterval.getWindow(any(), any()) } returns Pair(0L, 0L)
            coEvery { getMangaWithChapters.awaitManga(manga.id) } returns manga
            coEvery { source.getChapterList(any()) } returns emptyList()
            coEvery { syncChaptersWithSource.await(any(), any(), any(), any()) } returns emptyList()
            val registry = ColdSourceRegistry(mapOf(manga.source to source))

            val viewModel = viewModel(manga.id, fromSource = true, sourceManager = registry.manager)
            viewModel.events.test {
                advanceUntilIdle()
                registry.finishLoad()
                advanceUntilIdle()

                // The automatic refresh of an entry opened from a listing, which is manual and
                // therefore always reports; before the wait it reported the source missing.
                (awaitItem() as MangaDetailViewModel.Event.Snackbar).messageRes shouldBe R.string.manga_up_to_date
            }
            coVerify(exactly = 1) { syncChaptersWithSource.await(any(), any(), any(), any()) }
        }

    @Test
    fun `the automatic first refresh calls SyncChaptersWithSource when the entry arrives uninitialised`() =
        runTest(testDispatcher) {
            val manga = fixtureManga(initialized = false)
            val chapters = listOf(fixtureChapter())
            val source = fixtureSource()
            coEvery { getMangaWithChapters.subscribe(manga.id, applyScanlatorFilter = true) } returns
                flowOf(manga to chapters)
            every { sourceManager.get(manga.source) } returns source
            coEvery { getNextChapters.await(manga.id) } returns emptyList()
            coEvery { source.getMangaDetails(any()) } returns MangaInfo(url = "fixture/1", title = "Fixture Manga")
            coEvery {
                updateManga.awaitUpdateFromSource(any(), any(), any(), any())
            } returns true
            every { fetchInterval.getWindow(any(), any()) } returns Pair(0L, 0L)
            coEvery { getMangaWithChapters.awaitManga(manga.id) } returns manga
            coEvery { source.getChapterList(any()) } returns
                listOf(ChapterInfo(url = "fixture/1/ch1", name = "Chapter 1"))
            coEvery { syncChaptersWithSource.await(any(), any(), any(), any()) } returns emptyList()

            val viewModel = viewModel(manga.id, fromSource = false)
            advanceUntilIdle()

            coVerify(exactly = 1) {
                syncChaptersWithSource.await(
                    rawSourceChapters = any(),
                    manga = any(),
                    manualFetch = false,
                    fetchWindow = any(),
                )
            }
        }

    @Test
    fun `a refresh whose source call throws keeps the Success state with its chapters and emits a snackbar event`() =
        runTest(testDispatcher) {
            val manga = fixtureManga(initialized = true)
            val chapters = listOf(fixtureChapter())
            val source = fixtureSource()
            val failure = RuntimeException("boom")
            coEvery { getMangaWithChapters.subscribe(manga.id, applyScanlatorFilter = true) } returns
                flowOf(manga to chapters)
            every { sourceManager.get(manga.source) } returns source
            coEvery { getNextChapters.await(manga.id) } returns emptyList()
            coEvery { source.getMangaDetails(any()) } throws failure

            val viewModel = viewModel(manga.id, fromSource = false)
            advanceUntilIdle()

            viewModel.events.test {
                viewModel.refresh()
                advanceUntilIdle()
                val event = awaitItem() as MangaDetailViewModel.Event.Snackbar
                event.messageRes shouldBe R.string.manga_refresh_failed
                // The throwable crosses a withContext boundary on its way out of fetch, and
                // coroutine stacktrace recovery hands the catch block a copy of it rather than the
                // instance thrown here. Asserting the message keeps this stable either way, and the
                // message is what the snackbar formats.
                (event.args.single() as Throwable).message shouldBe failure.message
            }

            val settled = viewModel.state.value as MangaDetailViewModel.State.Success
            settled.manga shouldBe manga
            settled.chapters shouldContainExactly chapters
            settled.refreshing shouldBe false
        }

    // ---- New chapters to AutoDownload (phase 3 unit AD) ----

    /** A settled screen whose source answers a refresh, and whose chapter sync inserts [inserted]. */
    private fun stubRefresh(manga: Manga, inserted: List<Chapter>): Manga {
        val source = fixtureSource()
        val fresh = manga.copy(title = "Fixture Manga, refetched")
        stubSettledScreen(manga, listOf(fixtureChapter()))
        every { sourceManager.get(manga.source) } returns source
        coEvery { source.getMangaDetails(any()) } returns MangaInfo(url = "fixture/1", title = "Fixture Manga")
        coEvery { updateManga.awaitUpdateFromSource(any(), any(), any(), any()) } returns true
        every { fetchInterval.getWindow(any(), any()) } returns Pair(0L, 0L)
        coEvery { getMangaWithChapters.awaitManga(manga.id) } returns fresh
        coEvery { source.getChapterList(any()) } returns listOf(ChapterInfo(url = "fixture/1/ch2", name = "Chapter 2"))
        coEvery { syncChaptersWithSource.await(any(), any(), any(), any()) } returns inserted
        return fresh
    }

    /** Upstream's manual fetch: the new chapters through the rule, the queue started. */
    @Test
    fun `a refresh the owner asked for hands its new chapters to AutoDownload with a start`() = runTest(testDispatcher) {
        val manga = fixtureManga(favorite = true)
        val inserted = listOf(fixtureChapter(id = 101L, name = "Chapter 2"))
        val fresh = stubRefresh(manga, inserted)

        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()
        viewModel.events.test {
            viewModel.refresh()
            advanceUntilIdle()
            (awaitItem() as MangaDetailViewModel.Event.Snackbar).messageRes shouldBe R.string.manga_new_chapters
        }

        coVerify(exactly = 1) { autoDownload.queueNewChapters(fresh, inserted, autoStart = true) }
    }

    @Test
    fun `the automatic refresh on open hands nothing to AutoDownload, even from a listing`() = runTest(testDispatcher) {
        val manga = fixtureManga(favorite = true)
        stubRefresh(manga, listOf(fixtureChapter(id = 101L, name = "Chapter 2")))

        viewModel(manga.id, fromSource = true)
        advanceUntilIdle()

        coVerify(exactly = 1) { syncChaptersWithSource.await(any(), any(), any(), any()) }
        coVerify(exactly = 0) { autoDownload.queueNewChapters(any(), any(), any()) }
    }

    @Test
    fun `a refresh that inserts nothing hands nothing to AutoDownload`() = runTest(testDispatcher) {
        val manga = fixtureManga(favorite = true)
        stubRefresh(manga, emptyList())

        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()
        viewModel.refresh()
        advanceUntilIdle()

        coVerify(exactly = 0) { autoDownload.queueNewChapters(any(), any(), any()) }
    }

    @Test
    fun `a rule that fails leaves the refresh reporting its new chapters`() = runTest(testDispatcher) {
        val manga = fixtureManga(favorite = true)
        stubRefresh(manga, listOf(fixtureChapter(id = 101L, name = "Chapter 2")))
        coEvery { autoDownload.queueNewChapters(any(), any(), any()) } throws
            java.io.IOException("fixture: the queue table refused the insert")

        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()
        viewModel.events.test {
            viewModel.refresh()
            advanceUntilIdle()
            val event = awaitItem() as MangaDetailViewModel.Event.Snackbar
            event.messageRes shouldBe R.string.manga_new_chapters
            event.args shouldBe listOf(1)
        }
        (viewModel.state.value as MangaDetailViewModel.State.Success).refreshing shouldBe false
    }

    @Test
    fun `toggleFavorite on a non favourite sets favourite and applies the default category when one is named`() =
        runTest(testDispatcher) {
            val manga = fixtureManga(favorite = false, initialized = true)
            val chapters = listOf(fixtureChapter())
            val category = Category(id = 5L, name = "Fixture Category", order = 0L, flags = 0L)
            libraryPreferences.defaultCategory.set(5)
            coEvery { getMangaWithChapters.subscribe(manga.id, applyScanlatorFilter = true) } returns
                flowOf(manga to chapters)
            every { sourceManager.get(manga.source) } returns fixtureSource()
            coEvery { getNextChapters.await(manga.id) } returns emptyList()
            coEvery { updateManga.awaitUpdateFavorite(manga.id, true) } returns true
            coEvery { getCategories.await() } returns listOf(category)
            coEvery { getDuplicateLibraryManga.invoke(manga) } returns emptyList()

            val viewModel = viewModel(manga.id, fromSource = false)
            advanceUntilIdle()

            viewModel.toggleFavorite()
            advanceUntilIdle()

            coVerify { updateManga.awaitUpdateFavorite(manga.id, true) }
            coVerify { setMangaCategories.await(manga.id, listOf(5L)) }
        }

    /**
     * The entry screen's library button removed at once with a snackbar that had no action; Undo puts
     * back what a plain add would not: the date added, and the categories as they were.
     */
    @Test
    fun `removing from the library offers Undo, which restores the date added and the categories`() =
        runTest(testDispatcher) {
            val manga = fixtureManga(favorite = true).copy(dateAdded = 1_700_000_000_000L)
            stubSettledScreen(manga, listOf(fixtureChapter()))
            val reading = Category(id = 5L, name = "Reading", order = 0L, flags = 0L)
            val later = Category(id = 6L, name = "Later", order = 1L, flags = 0L)
            // The categories at the removal, then one taken off meanwhile, as another device might.
            coEvery { getCategories.await(manga.id) } returnsMany listOf(listOf(reading, later), listOf(reading))
            coEvery { updateManga.awaitUpdateFavorite(manga.id, false) } returns true
            coEvery { updateManga.await(any()) } returns true
            val viewModel = viewModel(manga.id, fromSource = false)
            advanceUntilIdle()

            var removal: MangaDetailViewModel.Removal? = null
            viewModel.events.test {
                viewModel.toggleFavorite()
                advanceUntilIdle()
                removal = (awaitItem() as MangaDetailViewModel.Event.RemovedFromLibrary).removal
            }
            removal shouldBe MangaDetailViewModel.Removal(manga.dateAdded, setOf(5L, 6L))
            coVerify(exactly = 1) { updateManga.awaitUpdateFavorite(manga.id, false) }

            coEvery { getManga.await(manga.id) } returns manga.copy(favorite = false, dateAdded = 0L)
            viewModel.undoRemoveFromLibrary(removal!!)
            advanceUntilIdle()

            coVerify(exactly = 1) {
                updateManga.await(DomainMangaUpdate(id = manga.id, favorite = true, dateAdded = manga.dateAdded))
            }
            coVerify(exactly = 1) { setMangaCategories.await(manga.id, match { it.toSet() == setOf(5L, 6L) }) }
            // Undo is not an add: no default category, no add snackbar.
            coVerify(exactly = 0) { updateManga.awaitUpdateFavorite(manga.id, true) }
        }

    @Test
    fun `Undo leaves categories that did not change, and an entry already added back, alone`() =
        runTest(testDispatcher) {
            val manga = fixtureManga(favorite = false)
            stubSettledScreen(manga, listOf(fixtureChapter()))
            val reading = Category(id = 5L, name = "Reading", order = 0L, flags = 0L)
            coEvery { getCategories.await(manga.id) } returns listOf(reading)
            coEvery { updateManga.await(any()) } returns true
            val viewModel = viewModel(manga.id, fromSource = false)
            advanceUntilIdle()

            viewModel.undoRemoveFromLibrary(MangaDetailViewModel.Removal(dateAdded = 42L, categoryIds = setOf(5L)))
            advanceUntilIdle()
            coVerify(exactly = 1) {
                updateManga.await(DomainMangaUpdate(id = manga.id, favorite = true, dateAdded = 42L))
            }
            coVerify(exactly = 0) { setMangaCategories.await(any(), any()) }

            coEvery { getManga.await(manga.id) } returns manga.copy(favorite = true)
            viewModel.undoRemoveFromLibrary(MangaDetailViewModel.Removal(dateAdded = 42L, categoryIds = emptySet()))
            advanceUntilIdle()
            coVerify(exactly = 1) { updateManga.await(any()) }
            coVerify(exactly = 0) { setMangaCategories.await(any(), any()) }
        }

    @Test
    fun `toggleFavorite on a non favourite entry already in the library asks about the duplicate first`() =
        runTest(testDispatcher) {
            val manga = fixtureManga(favorite = false, initialized = true)
            val chapters = listOf(fixtureChapter())
            val duplicate = fixtureManga(id = 99L, favorite = true)
            coEvery { getMangaWithChapters.subscribe(manga.id, applyScanlatorFilter = true) } returns
                flowOf(manga to chapters)
            every { sourceManager.get(manga.source) } returns fixtureSource()
            coEvery { getNextChapters.await(manga.id) } returns emptyList()
            coEvery { getDuplicateLibraryManga.invoke(manga) } returns
                listOf(MangaWithChapterCount(manga = duplicate, chapterCount = 3L))

            val viewModel = viewModel(manga.id, fromSource = false)
            advanceUntilIdle()

            viewModel.toggleFavorite()
            advanceUntilIdle()

            val settled = viewModel.state.value as MangaDetailViewModel.State.Success
            val dialog = settled.dialog as MangaDetailViewModel.Dialog.DuplicateManga
            dialog.duplicates.map { it.manga } shouldContainExactly listOf(duplicate)
            // The dialog stopped the add before it started: nothing here writes the favourite flag
            // or a category until the owner answers the question.
            coVerify(exactly = 0) { updateManga.awaitUpdateFavorite(any(), any()) }
            coVerify(exactly = 0) { setMangaCategories.await(any(), any()) }
        }

    /**
     * Upstream lists every match; the dialog had shown the first one only. Each carries its source
     * and chapter count, and Migrate acts on the one the owner picked.
     */
    @Test
    fun `the duplicate question lists every match with its source and chapter count`() = runTest(testDispatcher) {
        val manga = fixtureManga(favorite = false, initialized = true)
        val installed = fixtureManga(id = 98L, source = 20L, favorite = true)
        val gone = fixtureManga(id = 99L, source = 30L, favorite = true)
        stubSettledScreen(manga, listOf(fixtureChapter()))
        every { sourceManager.getOrStub(20L) } returns fixtureSource()
        coEvery { getDuplicateLibraryManga.invoke(manga) } returns listOf(
            MangaWithChapterCount(manga = installed, chapterCount = 12L),
            MangaWithChapterCount(manga = gone, chapterCount = 3L),
        )
        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()

        viewModel.toggleFavorite()
        advanceUntilIdle()

        val dialog = (viewModel.state.value as MangaDetailViewModel.State.Success).dialog
            as MangaDetailViewModel.Dialog.DuplicateManga
        dialog.duplicates shouldContainExactly listOf(
            DuplicateEntry(installed, chapterCount = 12L, sourceName = "Fixture Source", sourceInstalled = true),
            DuplicateEntry(gone, chapterCount = 3L, sourceName = "Fixture Gone Source", sourceInstalled = false),
        )

        viewModel.showMigrateDialog(gone.id)
        (viewModel.state.value as MangaDetailViewModel.State.Success).dialog shouldBe
            MangaDetailViewModel.Dialog.Migrate(current = gone, target = manga)
        coVerify(exactly = 0) { updateManga.awaitUpdateFavorite(any(), any()) }
    }

    /**
     * The whole point of asking first: a duplicate the owner declines to add leaves the database
     * exactly as it found it. [MangaDetailViewModel.dismissDialog] never reaches [updateManga] or
     * [setMangaCategories] at all, unlike [MangaDetailViewModel.addAnyway] which resumes the add.
     */
    @Test
    fun `dismissing the duplicate dialog leaves the database untouched`() = runTest(testDispatcher) {
        val manga = fixtureManga(favorite = false, initialized = true)
        val chapters = listOf(fixtureChapter())
        val duplicate = fixtureManga(id = 99L, favorite = true)
        coEvery { getMangaWithChapters.subscribe(manga.id, applyScanlatorFilter = true) } returns
            flowOf(manga to chapters)
        every { sourceManager.get(manga.source) } returns fixtureSource()
        coEvery { getNextChapters.await(manga.id) } returns emptyList()
        coEvery { getDuplicateLibraryManga.invoke(manga) } returns
            listOf(MangaWithChapterCount(manga = duplicate, chapterCount = 3L))

        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()

        viewModel.toggleFavorite()
        advanceUntilIdle()
        val asked = (viewModel.state.value as MangaDetailViewModel.State.Success).dialog
        (asked as MangaDetailViewModel.Dialog.DuplicateManga).duplicates.single().manga shouldBe duplicate

        viewModel.dismissDialog()
        advanceUntilIdle()

        (viewModel.state.value as MangaDetailViewModel.State.Success).dialog shouldBe null
        coVerify(exactly = 0) { updateManga.awaitUpdateFavorite(any(), any()) }
        coVerify(exactly = 0) { setMangaCategories.await(any(), any()) }
    }

    @Test
    fun `addAnyway from the duplicate dialog adds the entry to the library`() = runTest(testDispatcher) {
        val manga = fixtureManga(favorite = false, initialized = true)
        val chapters = listOf(fixtureChapter())
        val duplicate = fixtureManga(id = 99L, favorite = true)
        libraryPreferences.defaultCategory.set(-1)
        coEvery { getMangaWithChapters.subscribe(manga.id, applyScanlatorFilter = true) } returns
            flowOf(manga to chapters)
        every { sourceManager.get(manga.source) } returns fixtureSource()
        coEvery { getNextChapters.await(manga.id) } returns emptyList()
        coEvery { getDuplicateLibraryManga.invoke(manga) } returns
            listOf(MangaWithChapterCount(manga = duplicate, chapterCount = 3L))
        coEvery { getCategories.await() } returns emptyList()
        coEvery { updateManga.awaitUpdateFavorite(manga.id, true) } returns true

        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()
        viewModel.toggleFavorite()
        advanceUntilIdle()

        viewModel.addAnyway()
        advanceUntilIdle()

        coVerify(exactly = 1) { updateManga.awaitUpdateFavorite(manga.id, true) }
        (viewModel.state.value as MangaDetailViewModel.State.Success).dialog shouldBe null
    }

    /**
     * The duplicate dialog's Migrate (phase 4 unit MA), upstream's tap on a duplicate: the migrate
     * dialog moving the entry already in the library onto the one on this screen, with nothing
     * written by the detail view model itself.
     */
    @Test
    fun `Migrate from the duplicate dialog opens the migrate dialog from the duplicate onto this entry`() = runTest(testDispatcher) {
        val manga = fixtureManga(favorite = false, initialized = true)
        val duplicate = fixtureManga(id = 99L, favorite = true)
        coEvery { getMangaWithChapters.subscribe(manga.id, applyScanlatorFilter = true) } returns
            flowOf(manga to listOf(fixtureChapter()))
        every { sourceManager.get(manga.source) } returns fixtureSource()
        coEvery { getNextChapters.await(manga.id) } returns emptyList()
        coEvery { getDuplicateLibraryManga.invoke(manga) } returns
            listOf(MangaWithChapterCount(manga = duplicate, chapterCount = 3L))

        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()
        viewModel.toggleFavorite()
        advanceUntilIdle()

        viewModel.showMigrateDialog(duplicate.id)
        advanceUntilIdle()

        (viewModel.state.value as MangaDetailViewModel.State.Success).dialog shouldBe
            MangaDetailViewModel.Dialog.Migrate(current = duplicate, target = manga)
        coVerify(exactly = 0) { updateManga.awaitUpdateFavorite(any(), any()) }
        coVerify(exactly = 0) { setMangaCategories.await(any(), any()) }
    }

    @Test
    fun `a migration that failed closes the dialog and says so in a snackbar`() = runTest(testDispatcher) {
        val manga = fixtureManga(favorite = false, initialized = true)
        val duplicate = fixtureManga(id = 99L, favorite = true)
        coEvery { getMangaWithChapters.subscribe(manga.id, applyScanlatorFilter = true) } returns
            flowOf(manga to listOf(fixtureChapter()))
        every { sourceManager.get(manga.source) } returns fixtureSource()
        coEvery { getNextChapters.await(manga.id) } returns emptyList()
        coEvery { getDuplicateLibraryManga.invoke(manga) } returns
            listOf(MangaWithChapterCount(manga = duplicate, chapterCount = 3L))
        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()
        viewModel.toggleFavorite()
        advanceUntilIdle()
        viewModel.showMigrateDialog(duplicate.id)
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.migrationFailed()
            advanceUntilIdle()
            awaitItem() shouldBe MangaDetailViewModel.Event.Snackbar(R.string.migration_single_failed)
        }
        (viewModel.state.value as MangaDetailViewModel.State.Success).dialog shouldBe null
    }

    @Test
    fun `markRead calls SetReadStatus`() = runTest(testDispatcher) {
        val manga = fixtureManga(initialized = true)
        val chapter = fixtureChapter()
        val chapters = listOf(chapter)
        coEvery { getMangaWithChapters.subscribe(manga.id, applyScanlatorFilter = true) } returns
            flowOf(manga to chapters)
        every { sourceManager.get(manga.source) } returns fixtureSource()
        coEvery { getNextChapters.await(manga.id) } returns emptyList()
        coEvery { setReadStatus.await(true, chapter) } returns SetReadStatus.Result.Success(listOf(chapter))

        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()

        viewModel.markRead(chapter, true)
        advanceUntilIdle()

        coVerify { setReadStatus.await(true, chapter) }
    }

    // ---- After manually marked as read (phase 3 unit DR), through the real SetReadStatus ----

    /** The screen over [chapters] of [manga], its mark reads going through [setReadStatus]. */
    private fun TestScope.settledScreen(manga: Manga, chapters: List<Chapter>, setReadStatus: SetReadStatus): MangaDetailViewModel {
        coEvery { getMangaWithChapters.subscribe(manga.id, applyScanlatorFilter = true) } returns flowOf(manga to chapters)
        every { sourceManager.get(manga.source) } returns fixtureSource()
        coEvery { getNextChapters.await(manga.id) } returns emptyList()
        return viewModel(manga.id, fromSource = false, setReadStatus = setReadStatus).also { advanceUntilIdle() }
    }

    @Test
    fun `markRead with after manually marked as read on hands the chapter to the download cleanup`() = runTest(testDispatcher) {
        val chapter = fixtureChapter()
        val cleanup = RecordingReadChapterCleanup()
        val viewModel = settledScreen(fixtureManga(), listOf(chapter), deletingSetReadStatus(mockk(relaxed = true), cleanup))

        viewModel.markRead(chapter, true)
        advanceUntilIdle()

        cleanup.marked shouldBe listOf(listOf(chapter.id))
    }

    @Test
    fun `mark previous as read with the switch on hands the chapters it wrote to the download cleanup`() = runTest(testDispatcher) {
        val manga = fixtureManga(chapterFlags = Manga.CHAPTER_SORTING_NUMBER)
        val chapters = (1L..4L).map { numbered(it, it.toDouble(), read = it == 1L) }
        val chapterRepository = mockk<ChapterRepository>(relaxed = true)
        val mangaRepository = mockk<MangaRepository>()
        coEvery { chapterRepository.getChapterByMangaId(manga.id, any()) } returns chapters
        coEvery { mangaRepository.getMangaById(manga.id) } returns manga
        val cleanup = RecordingReadChapterCleanup()
        val viewModel = settledScreen(manga, chapters, deletingSetReadStatus(chapterRepository, cleanup, mangaRepository))

        // The row's "<" button is gone (plan 2.6); one selected chapter and the bar's action are
        // the same awaitAllBefore call.
        viewModel.toggleSelection(chapters[3].id)
        viewModel.markSelectedPreviousRead()
        advanceUntilIdle()

        cleanup.marked shouldBe listOf(listOf(2L, 3L))
    }

    @Test
    fun `marking the selection read with the switch on hands the chapters it wrote to the download cleanup`() = runTest(testDispatcher) {
        val chapters = listOf(numbered(1, 1.0), numbered(2, 2.0, read = true), numbered(3, 3.0))
        val cleanup = RecordingReadChapterCleanup()
        val viewModel = settledScreen(fixtureManga(), chapters, deletingSetReadStatus(mockk(relaxed = true), cleanup))

        chapters.forEach { viewModel.toggleSelection(it.id) }
        viewModel.markSelectedRead(true)
        advanceUntilIdle()

        cleanup.marked.map { it.toSet() } shouldBe listOf(setOf(1L, 3L))
    }

    /**
     * The task brief expected a snackbar here, but reading continueReading shows it just
     * returns when nextUnread is null: no event of any kind is sent, and there is no string
     * resource anywhere for a "no next chapter" message. This test proves the actual current
     * behaviour (no OpenReader, no event at all) instead of asserting a snackbar that the
     * production code never emits.
     */
    /**
     * project() used to read `state.value` into a local before suspending on the IO round trip for
     * [GetNextChapters], then blind-assign the built [MangaDetailViewModel.State.Success] back once
     * that round trip returned. Anything written to state during the suspension, a selection here,
     * but equally a refresh flag, a dialog or descriptionExpanded, was silently discarded because
     * the write landed on the stale snapshot rather than on whatever state actually held by then.
     * This selects a chapter while a second projection is suspended on that same IO call and proves
     * the selection is still there once the projection finishes, which only holds if the current
     * state is re-read from inside the update rather than captured before the suspension.
     */
    @Test
    fun `a selection made while a projection is suspended on IO survives that projection landing`() =
        runTest(testDispatcher) {
            val manga = fixtureManga(initialized = true)
            val chapter = fixtureChapter(id = 100L)
            val mangaFlow = MutableStateFlow(manga to listOf(chapter))
            coEvery { getMangaWithChapters.subscribe(manga.id, applyScanlatorFilter = true) } returns mangaFlow
            every { sourceManager.get(manga.source) } returns fixtureSource()

            // The first projection, on view model construction, resolves immediately so the screen
            // starts out in a settled Success state with something to select.
            coEvery { getNextChapters.await(manga.id) } returns emptyList()
            val viewModel = viewModel(manga.id, fromSource = false)
            advanceUntilIdle()
            (viewModel.state.value as MangaDetailViewModel.State.Success).chapters shouldContainExactly
                listOf(chapter)

            // A second projection is triggered by a re-emission carrying a real change (MangaFlow is
            // a MutableStateFlow, and an emission structurally equal to the current value would be
            // conflated away rather than reaching the collector at all), and this time the IO call
            // is held open until the test lets it go.
            val held = CompletableDeferred<List<Chapter>>()
            coEvery { getNextChapters.await(manga.id) } coAnswers { held.await() }
            mangaFlow.value = manga.copy(favorite = true) to listOf(chapter)
            advanceUntilIdle()

            // The second projection is now suspended mid-flight. The owner selects the chapter while
            // it is still out there.
            viewModel.toggleSelection(chapter.id)
            (viewModel.state.value as MangaDetailViewModel.State.Success).selection shouldBe setOf(chapter.id)

            // The IO call finally returns and the suspended projection resumes and writes its state.
            held.complete(emptyList())
            advanceUntilIdle()

            val settled = viewModel.state.value as MangaDetailViewModel.State.Success
            // Proves the second projection actually landed, rather than the assertion above passing
            // because the emission was conflated away and only the first projection ever ran.
            settled.manga.favorite shouldBe true
            settled.selection shouldBe setOf(chapter.id)
        }

    @Test
    fun `continueReading with no next chapter sends no event`() = runTest(testDispatcher) {
        val manga = fixtureManga(initialized = true)
        val chapters = listOf(fixtureChapter())
        coEvery { getMangaWithChapters.subscribe(manga.id, applyScanlatorFilter = true) } returns
            flowOf(manga to chapters)
        every { sourceManager.get(manga.source) } returns fixtureSource()
        coEvery { getNextChapters.await(manga.id) } returns emptyList()

        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()

        val settled = viewModel.state.value as MangaDetailViewModel.State.Success
        settled.nextUnread shouldBe null

        viewModel.events.test {
            viewModel.continueReading()
            advanceUntilIdle()
            expectNoEvents()
        }
    }

    /**
     * The three stubs a settled Success state needs. The scanlator flows are stubbed once in
     * [setUp]; a test that cares about them re-stubs before it builds its view model.
     */
    private fun stubSettledScreen(manga: Manga, chapters: List<Chapter>) {
        coEvery { getMangaWithChapters.subscribe(manga.id, applyScanlatorFilter = true) } returns
            flowOf(manga to chapters)
        every { sourceManager.get(manga.source) } returns fixtureSource()
        coEvery { getNextChapters.await(manga.id) } returns emptyList()
    }

    /**
     * The defect this unit closes. `awaitSetBookmarkFilter` had been written, tested at the
     * interactor and then never called by anything: the detail screen offered no control that
     * could reach it, so the bookmarked filter was a flag the app could read and never write.
     */
    @Test
    fun `setChapterFilter writes the bookmarked flag through SetMangaChapterFlags`() = runTest(testDispatcher) {
        val manga = fixtureManga()
        stubSettledScreen(manga, listOf(fixtureChapter()))

        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()

        viewModel.setChapterFilter(MangaDetailViewModel.ChapterFilter.BOOKMARKED, TriState.ENABLED_IS)
        viewModel.setChapterFilter(MangaDetailViewModel.ChapterFilter.BOOKMARKED, TriState.ENABLED_NOT)
        viewModel.setChapterFilter(MangaDetailViewModel.ChapterFilter.BOOKMARKED, TriState.DISABLED)
        advanceUntilIdle()

        coVerify(exactly = 1) { setMangaChapterFlags.awaitSetBookmarkFilter(manga, Manga.CHAPTER_SHOW_BOOKMARKED) }
        coVerify(exactly = 1) { setMangaChapterFlags.awaitSetBookmarkFilter(manga, Manga.CHAPTER_SHOW_NOT_BOOKMARKED) }
        coVerify(exactly = 1) { setMangaChapterFlags.awaitSetBookmarkFilter(manga, Manga.SHOW_ALL) }
    }

    /** The other half of the same defect: the filter the projection has always enforced. */
    @Test
    fun `project shows only bookmarked chapters while the bookmarked filter includes`() = runTest(testDispatcher) {
        val manga = fixtureManga(chapterFlags = Manga.CHAPTER_SHOW_BOOKMARKED)
        val bookmarked = fixtureChapter(id = 100L, name = "Chapter 1", bookmark = true)
        val plain = fixtureChapter(id = 101L, name = "Chapter 2")
        stubSettledScreen(manga, listOf(bookmarked, plain))

        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()

        val settled = viewModel.state.value as MangaDetailViewModel.State.Success
        settled.chapters shouldContainExactly listOf(bookmarked)
    }

    @Test
    fun `setChapterFilter writes the unread flag through SetMangaChapterFlags`() = runTest(testDispatcher) {
        val manga = fixtureManga()
        stubSettledScreen(manga, listOf(fixtureChapter()))

        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()

        viewModel.setChapterFilter(MangaDetailViewModel.ChapterFilter.UNREAD, TriState.ENABLED_IS)
        viewModel.setChapterFilter(MangaDetailViewModel.ChapterFilter.UNREAD, TriState.ENABLED_NOT)
        viewModel.setChapterFilter(MangaDetailViewModel.ChapterFilter.UNREAD, TriState.DISABLED)
        advanceUntilIdle()

        coVerify(exactly = 1) { setMangaChapterFlags.awaitSetUnreadFilter(manga, Manga.CHAPTER_SHOW_UNREAD) }
        coVerify(exactly = 1) { setMangaChapterFlags.awaitSetUnreadFilter(manga, Manga.CHAPTER_SHOW_READ) }
        coVerify(exactly = 1) { setMangaChapterFlags.awaitSetUnreadFilter(manga, Manga.SHOW_ALL) }
    }

    /**
     * The tapped field, not the current one. The interactor flips the direction when the two are
     * equal, which is why the old flip-only control could never change the sorting field at all.
     */
    @Test
    fun `setChapterSort hands the chosen field to awaitSetSortingModeOrFlipOrder`() = runTest(testDispatcher) {
        val manga = fixtureManga(chapterFlags = Manga.CHAPTER_SORTING_NUMBER)
        stubSettledScreen(manga, listOf(fixtureChapter()))

        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()

        viewModel.setChapterSort(Manga.CHAPTER_SORTING_UPLOAD_DATE)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            setMangaChapterFlags.awaitSetSortingModeOrFlipOrder(manga, Manga.CHAPTER_SORTING_UPLOAD_DATE)
        }
        coVerify(exactly = 0) {
            setMangaChapterFlags.awaitSetSortingModeOrFlipOrder(manga, Manga.CHAPTER_SORTING_NUMBER)
        }
    }

    @Test
    fun `setChapterDisplay writes the display flag`() = runTest(testDispatcher) {
        val manga = fixtureManga()
        stubSettledScreen(manga, listOf(fixtureChapter()))

        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()

        viewModel.setChapterDisplay(Manga.CHAPTER_DISPLAY_NUMBER)
        advanceUntilIdle()

        coVerify(exactly = 1) { setMangaChapterFlags.awaitSetDisplayMode(manga, Manga.CHAPTER_DISPLAY_NUMBER) }
    }

    /** The volume headers toggle (phase 4 unit VG): the entry's own bit, both ways. */
    @Test
    fun `setChapterGroupByVolume writes the volume headers bit`() = runTest(testDispatcher) {
        val manga = fixtureManga()
        stubSettledScreen(manga, listOf(fixtureChapter()))

        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()

        viewModel.setChapterGroupByVolume(true)
        viewModel.setChapterGroupByVolume(false)
        advanceUntilIdle()

        coVerify(exactly = 1) { setMangaChapterFlags.awaitSetGroupByVolume(manga, true) }
        coVerify(exactly = 1) { setMangaChapterFlags.awaitSetGroupByVolume(manga, false) }
    }

    // ---- The chapter defaults (phase 4 unit CS, challenge c2 g3 A items 3 and 4) ----

    /** Upstream's init: an entry not in the library takes the defaults once per open, whatever re-emits. */
    @Test
    fun `opening an entry not in the library applies the chapter defaults to it once`() = runTest(testDispatcher) {
        val manga = fixtureManga(favorite = false)
        val emissions = MutableStateFlow(manga to listOf(fixtureChapter()))
        coEvery { getMangaWithChapters.subscribe(manga.id, applyScanlatorFilter = true) } returns emissions
        every { sourceManager.get(manga.source) } returns fixtureSource()
        coEvery { getNextChapters.await(manga.id) } returns emptyList()

        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()
        emissions.value = manga.copy(chapterFlags = Manga.CHAPTER_SORTING_NUMBER) to listOf(fixtureChapter())
        advanceUntilIdle()

        coVerify(exactly = 1) { setMangaDefaultChapterFlags.await(manga) }
        coVerify(exactly = 1) { setMangaDefaultChapterFlags.await(any()) }
        (viewModel.state.value as MangaDetailViewModel.State.Success).manga.sorting shouldBe Manga.CHAPTER_SORTING_NUMBER
    }

    /**
     * Upstream 7ff97761b: the reader open over the entry screen stops the screen watching, and the
     * progress it writes on every page then re-projects nothing until the screen watches again.
     */
    @Test
    fun `a screen that stopped watching is not projected again until it watches again`() = runTest(testDispatcher) {
        val manga = fixtureManga(favorite = true)
        val emissions = MutableStateFlow(manga to listOf(fixtureChapter(name = "Chapter 1")))
        coEvery { getMangaWithChapters.subscribe(manga.id, applyScanlatorFilter = true) } returns emissions
        every { sourceManager.get(manga.source) } returns fixtureSource()
        coEvery { getNextChapters.await(manga.id) } returns emptyList()
        val viewModel = viewModel(manga.id, fromSource = false)
        // Not the background scope: advanceUntilIdle stops once only background work is left, and
        // the watcher letting go is that work.
        val screen = launch { viewModel.state.collect {} }
        advanceUntilIdle()

        screen.cancel()
        advanceUntilIdle()
        emissions.value = manga to listOf(fixtureChapter(name = "Chapter 1, read"))
        advanceUntilIdle()

        (viewModel.state.value as MangaDetailViewModel.State.Success).chapters.single().name shouldBe "Chapter 1"
        coVerify(exactly = 1) { getNextChapters.await(manga.id) }

        val back = launch { viewModel.state.collect {} }
        advanceUntilIdle()

        (viewModel.state.value as MangaDetailViewModel.State.Success).chapters.single().name shouldBe
            "Chapter 1, read"
        back.cancel()
    }

    @Test
    fun `a screen away for four seconds, as in a rotation, is still projected, and one away for six is not`() =
        runTest(testDispatcher) {
            val manga = fixtureManga(favorite = true)
            val emissions = MutableStateFlow(manga to listOf(fixtureChapter(name = "Chapter 1")))
            coEvery { getMangaWithChapters.subscribe(manga.id, applyScanlatorFilter = true) } returns emissions
            every { sourceManager.get(manga.source) } returns fixtureSource()
            coEvery { getNextChapters.await(manga.id) } returns emptyList()
            val viewModel = viewModel(manga.id, fromSource = false)
            val screen = launch { viewModel.state.collect {} }
            advanceUntilIdle()

            screen.cancel()
            advanceTimeBy(4_000)
            emissions.value = manga to listOf(fixtureChapter(name = "Chapter 1, read"))
            runCurrent()

            (viewModel.state.value as MangaDetailViewModel.State.Success).chapters.single().name shouldBe
                "Chapter 1, read"
            coVerify(exactly = 2) { getNextChapters.await(manga.id) }

            advanceTimeBy(2_000)
            emissions.value = manga to listOf(fixtureChapter(name = "Chapter 1, read twice"))
            runCurrent()

            (viewModel.state.value as MangaDetailViewModel.State.Success).chapters.single().name shouldBe
                "Chapter 1, read"
            coVerify(exactly = 2) { getNextChapters.await(manga.id) }
        }

    /** A rotation: the screen comes back inside the timeout, and the pause it started never lands. */
    @Test
    fun `a screen back before the timeout is still projected after it, with no re-collection between`() =
        runTest(testDispatcher) {
            val manga = fixtureManga(favorite = true)
            val emissions = MutableStateFlow(manga to listOf(fixtureChapter(name = "Chapter 1")))
            coEvery { getMangaWithChapters.subscribe(manga.id, applyScanlatorFilter = true) } returns emissions
            every { sourceManager.get(manga.source) } returns fixtureSource()
            coEvery { getNextChapters.await(manga.id) } returns emptyList()
            val viewModel = viewModel(manga.id, fromSource = false)
            val screen = launch { viewModel.state.collect {} }
            advanceUntilIdle()

            screen.cancel()
            advanceTimeBy(4_000)
            val back = launch { viewModel.state.collect {} }
            runCurrent()
            advanceTimeBy(2_000)
            emissions.value = manga to listOf(fixtureChapter(name = "Chapter 1, read"))
            runCurrent()

            (viewModel.state.value as MangaDetailViewModel.State.Success).chapters.single().name shouldBe
                "Chapter 1, read"
            // The first projection and this one: a pause that landed and lifted would have re-run it.
            coVerify(exactly = 2) { getNextChapters.await(manga.id) }
            back.cancel()
        }

    @Test
    fun `opening an entry in the library leaves its chapter settings alone`() = runTest(testDispatcher) {
        val manga = fixtureManga(favorite = true, chapterFlags = Manga.CHAPTER_SHOW_BOOKMARKED)
        stubSettledScreen(manga, listOf(fixtureChapter()))

        viewModel(manga.id, fromSource = false)
        advanceUntilIdle()

        coVerify(exactly = 0) { setMangaDefaultChapterFlags.await(any()) }
        coVerify(exactly = 0) { setMangaDefaultChapterFlags.awaitAll() }
    }

    /**
     * Without the box, upstream's "Set as default" writes the six defaults and nothing else: not this
     * entry (the round trip Saikou used to make is gone), not the library.
     */
    @Test
    fun `setAsDefaultChapterSettings without the library box writes the defaults and applies them nowhere`() =
        runTest(testDispatcher) {
            val manga = fixtureManga(
                favorite = true,
                chapterFlags = Manga.CHAPTER_SHOW_BOOKMARKED or Manga.CHAPTER_SORTING_UPLOAD_DATE,
            )
            stubSettledScreen(manga, listOf(fixtureChapter()))
            val viewModel = viewModel(manga.id, fromSource = false)
            advanceUntilIdle()

            viewModel.setAsDefaultChapterSettings(applyToLibrary = false)
            advanceUntilIdle()

            libraryPreferences.filterChapterByBookmarked.get() shouldBe Manga.CHAPTER_SHOW_BOOKMARKED
            libraryPreferences.sortChapterBySourceOrNumber.get() shouldBe Manga.CHAPTER_SORTING_UPLOAD_DATE
            coVerify(exactly = 0) { setMangaDefaultChapterFlags.await(any()) }
            coVerify(exactly = 0) { setMangaDefaultChapterFlags.awaitAll() }
        }

    /**
     * With the box ticked, every entry in the library takes the new defaults. Reading the preference
     * from inside the stub proves the defaults were written before `awaitAll` read them.
     */
    @Test
    fun `setAsDefaultChapterSettings with the library box writes the defaults, then applies them to the library`() =
        runTest(testDispatcher) {
            val manga = fixtureManga(
                favorite = true,
                chapterFlags = Manga.CHAPTER_SHOW_BOOKMARKED or Manga.CHAPTER_SORTING_UPLOAD_DATE,
            )
            stubSettledScreen(manga, listOf(fixtureChapter()))
            var bookmarkedWhenApplied: Long? = null
            var sortingWhenApplied: Long? = null
            coEvery { setMangaDefaultChapterFlags.awaitAll() } coAnswers {
                bookmarkedWhenApplied = libraryPreferences.filterChapterByBookmarked.get()
                sortingWhenApplied = libraryPreferences.sortChapterBySourceOrNumber.get()
            }
            val viewModel = viewModel(manga.id, fromSource = false)
            advanceUntilIdle()

            viewModel.setAsDefaultChapterSettings(applyToLibrary = true)
            advanceUntilIdle()

            coVerify(exactly = 1) { setMangaDefaultChapterFlags.awaitAll() }
            coVerify(exactly = 0) { setMangaDefaultChapterFlags.await(any()) }
            bookmarkedWhenApplied shouldBe Manga.CHAPTER_SHOW_BOOKMARKED
            sortingWhenApplied shouldBe Manga.CHAPTER_SORTING_UPLOAD_DATE
        }

    @Test
    fun `setAsDefaultChapterSettings emits the confirmation snackbar`() = runTest(testDispatcher) {
        val manga = fixtureManga()
        stubSettledScreen(manga, listOf(fixtureChapter()))

        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.setAsDefaultChapterSettings(applyToLibrary = false)
            advanceUntilIdle()
            val event = awaitItem() as MangaDetailViewModel.Event.Snackbar
            event.messageRes shouldBe R.string.chapter_settings_updated
        }
    }

    /** Upstream's Reset: the stored defaults onto this entry, the defaults themselves untouched, nothing said. */
    @Test
    fun `resetToDefaultChapterSettings applies the stored defaults to this entry only`() = runTest(testDispatcher) {
        val manga = fixtureManga(favorite = true, chapterFlags = Manga.CHAPTER_SHOW_BOOKMARKED)
        stubSettledScreen(manga, listOf(fixtureChapter()))
        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.resetToDefaultChapterSettings()
            advanceUntilIdle()
            expectNoEvents()
        }

        coVerify(exactly = 1) { setMangaDefaultChapterFlags.await(manga) }
        coVerify(exactly = 0) { setMangaDefaultChapterFlags.awaitAll() }
        libraryPreferences.filterChapterByBookmarked.get() shouldBe Manga.SHOW_ALL
    }

    /** Phase 4 unit SC: the dialog's one confirm writes both collections, then closes. */
    @Test
    fun `the scanlator dialog's one confirm writes the exclusions and the ranking and closes the dialog`() =
        runTest(testDispatcher) {
            val manga = fixtureManga()
            stubSettledScreen(manga, listOf(fixtureChapter()))

            val viewModel = viewModel(manga.id, fromSource = false)
            advanceUntilIdle()

            viewModel.showScanlatorFilter()
            (viewModel.state.value as MangaDetailViewModel.State.Success).dialog shouldBe
                MangaDetailViewModel.Dialog.ScanlatorFilter

            viewModel.confirmScanlators(
                setOf("Group B"),
                MangaDetailViewModel.RankingChange.Write(listOf("Group C", "Group A")),
            )
            advanceUntilIdle()

            coVerify(exactly = 1) { setExcludedScanlators.await(manga.id, setOf("Group B")) }
            coVerify(exactly = 1) { setScanlatorPriority.await(manga.id, listOf("Group C", "Group A")) }
            coVerify(exactly = 0) { setScanlatorPriority.clear(any()) }
            (viewModel.state.value as MangaDetailViewModel.State.Success).dialog shouldBe null
        }

    @Test
    fun `a confirm that keeps the ranking writes the exclusions alone`() = runTest(testDispatcher) {
        val manga = fixtureManga()
        stubSettledScreen(manga, listOf(fixtureChapter()))

        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()

        viewModel.confirmScanlators(setOf("Group B"), MangaDetailViewModel.RankingChange.Keep)
        advanceUntilIdle()

        coVerify(exactly = 1) { setExcludedScanlators.await(manga.id, setOf("Group B")) }
        coVerify(exactly = 0) { setScanlatorPriority.await(any(), any()) }
        coVerify(exactly = 0) { setScanlatorPriority.clear(any()) }
    }

    @Test
    fun `a confirm after Reset order clears the ranking`() = runTest(testDispatcher) {
        val manga = fixtureManga()
        stubSettledScreen(manga, listOf(fixtureChapter()))

        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()

        viewModel.confirmScanlators(emptySet(), MangaDetailViewModel.RankingChange.Clear)
        advanceUntilIdle()

        coVerify(exactly = 1) { setExcludedScanlators.await(manga.id, emptySet()) }
        coVerify(exactly = 1) { setScanlatorPriority.clear(manga.id) }
        coVerify(exactly = 0) { setScanlatorPriority.await(any(), any()) }
    }

    /**
     * Two copies of the next number, Group A's first in reading order. Unranked, the resume button
     * opens the first copy, as it always has; with Group B ranked, Group B's (phase 4 unit SC). The
     * ranking also reaches the state top first, for the dialog.
     */
    @Test
    fun `the resume button on a ranked title opens the ranked group's copy of the next chapter`() =
        runTest(testDispatcher) {
            val manga = fixtureManga()
            stubSettledScreen(manga, listOf(fixtureChapter()))
            val next = listOf(
                fixtureChapter(id = 201L, scanlator = "Group A").copy(chapterNumber = 5.0),
                fixtureChapter(id = 202L, scanlator = "Group B").copy(chapterNumber = 5.0),
                fixtureChapter(id = 203L, scanlator = "Group A").copy(chapterNumber = 6.0),
            )
            coEvery { getNextChapters.await(manga.id) } returns next

            val unranked = viewModel(manga.id, fromSource = false)
            advanceUntilIdle()
            (unranked.state.value as MangaDetailViewModel.State.Success).nextUnread?.id shouldBe 201L

            every { getScanlatorPriority.subscribe(manga.id) } returns flowOf(
                listOf(
                    ScanlatorPriority(mangaId = manga.id, scanlator = "Group B", priority = 0L),
                    ScanlatorPriority(mangaId = manga.id, scanlator = "Group A", priority = 1L),
                ),
            )
            val ranked = viewModel(manga.id, fromSource = false)
            advanceUntilIdle()

            val settled = ranked.state.value as MangaDetailViewModel.State.Success
            settled.nextUnread?.id shouldBe 202L
            settled.scanlatorRanking shouldBe listOf("Group B", "Group A")
        }

    /**
     * The chapter list is read with the scanlator filter already applied, so an excluded group's
     * chapters are not in it. Deriving the group list from those chapters would delete the row the
     * owner needs to un-exclude the group, which is an exclusion with no way back.
     */
    @Test
    fun `availableScanlators comes from the unfiltered flow so an excluded group can still be cleared`() =
        runTest(testDispatcher) {
            val manga = fixtureManga()
            stubSettledScreen(manga, listOf(fixtureChapter(scanlator = "Group A")))
            every { getAvailableScanlators.subscribe(manga.id) } returns flowOf(setOf("Group A", "Group B"))
            every { getExcludedScanlators.subscribe(manga.id) } returns flowOf(setOf("Group B"))

            val viewModel = viewModel(manga.id, fromSource = false)
            advanceUntilIdle()

            val settled = viewModel.state.value as MangaDetailViewModel.State.Success
            settled.availableScanlators shouldBe setOf("Group A", "Group B")
            settled.excludedScanlators shouldBe setOf("Group B")
        }

    @Test
    fun `filterActive is true when an available scanlator is excluded`() = runTest(testDispatcher) {
        val manga = fixtureManga()
        stubSettledScreen(manga, listOf(fixtureChapter(scanlator = "Group A")))
        every { getAvailableScanlators.subscribe(manga.id) } returns flowOf(setOf("Group A", "Group B"))
        every { getExcludedScanlators.subscribe(manga.id) } returns flowOf(setOf("Group B"))

        val excluding = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()
        (excluding.state.value as MangaDetailViewModel.State.Success).filterActive shouldBe true

        // A group the entry has no chapters from is a stale exclusion, not a live filter: nothing
        // is being hidden, so nothing should be lit.
        every { getExcludedScanlators.subscribe(manga.id) } returns flowOf(setOf("Group C"))
        val stale = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()
        (stale.state.value as MangaDetailViewModel.State.Success).filterActive shouldBe false
    }

    /**
     * R45 ended: the downloaded bit lights the icon again now that the sheet has a row that clears
     * it, and the More tab's switch lights it too, because it forces that row on.
     */
    @Test
    fun `filterActive follows the downloaded flag and the downloaded-only switch`() = runTest(testDispatcher) {
        val downloaded = fixtureManga(chapterFlags = Manga.CHAPTER_SHOW_DOWNLOADED)
        stubSettledScreen(downloaded, listOf(fixtureChapter()))
        val flagged = viewModel(downloaded.id, fromSource = false)
        advanceUntilIdle()
        (flagged.state.value as MangaDetailViewModel.State.Success).filterActive shouldBe true

        val plain = fixtureManga(id = 2L)
        stubSettledScreen(plain, listOf(fixtureChapter(mangaId = 2L)))
        val unflagged = viewModel(plain.id, fromSource = false)
        advanceUntilIdle()
        (unflagged.state.value as MangaDetailViewModel.State.Success).filterActive shouldBe false

        basePreferences.downloadedOnly.set(true)
        advanceUntilIdle()
        val forced = unflagged.state.value as MangaDetailViewModel.State.Success
        forced.filterActive shouldBe true
        forced.downloadedFilter shouldBe TriState.ENABLED_IS
    }

    /**
     * The set comes from [DownloadedChapters], which leaves out a manifest row whose folder is gone
     * (`LocatedDownloadedChaptersTest`), so chapter 101 here stands for both kinds of not downloaded.
     */
    @Test
    fun `project keeps only downloaded chapters under the downloaded filter, and only the rest under its negation`() =
        runTest(testDispatcher) {
            val onDisk = fixtureChapter(id = 100L, name = "Chapter 1")
            val notOnDisk = fixtureChapter(id = 101L, name = "Chapter 2")
            every { downloadedChapters.subscribeByMangaId(any()) } returns flowOf(setOf(onDisk.id))

            val including = fixtureManga(chapterFlags = Manga.CHAPTER_SHOW_DOWNLOADED)
            stubSettledScreen(including, listOf(onDisk, notOnDisk))
            val kept = viewModel(including.id, fromSource = false)
            advanceUntilIdle()
            (kept.state.value as MangaDetailViewModel.State.Success).chapters shouldContainExactly listOf(onDisk)

            val excluding = fixtureManga(id = 2L, chapterFlags = Manga.CHAPTER_SHOW_NOT_DOWNLOADED)
            stubSettledScreen(excluding, listOf(onDisk, notOnDisk))
            val dropped = viewModel(excluding.id, fromSource = false)
            advanceUntilIdle()
            (dropped.state.value as MangaDetailViewModel.State.Success).chapters shouldContainExactly
                listOf(notOnDisk)

            val all = fixtureManga(id = 3L)
            stubSettledScreen(all, listOf(onDisk, notOnDisk))
            val unfiltered = viewModel(all.id, fromSource = false)
            advanceUntilIdle()
            (unfiltered.state.value as MangaDetailViewModel.State.Success).chapters.map { it.id } shouldContainExactly
                listOf(100L, 101L)
        }

    /** `BasePreferences.downloadedOnly` forces the filter on over an entry whose own bit is clear. */
    @Test
    fun `the downloaded-only switch hides chapters that are not downloaded`() = runTest(testDispatcher) {
        val onDisk = fixtureChapter(id = 100L, name = "Chapter 1")
        val notOnDisk = fixtureChapter(id = 101L, name = "Chapter 2")
        every { downloadedChapters.subscribeByMangaId(any()) } returns flowOf(setOf(onDisk.id))
        val manga = fixtureManga()
        stubSettledScreen(manga, listOf(onDisk, notOnDisk))

        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()
        (viewModel.state.value as MangaDetailViewModel.State.Success).chapters.size shouldBe 2

        basePreferences.downloadedOnly.set(true)
        advanceUntilIdle()
        (viewModel.state.value as MangaDetailViewModel.State.Success).chapters shouldContainExactly listOf(onDisk)
    }

    /** A Local entry is on disk by definition, so the downloaded filter keeps every chapter. */
    @Test
    fun `a local entry counts every chapter as downloaded and offers no download state`() = runTest(testDispatcher) {
        val manga = fixtureManga(source = SourceIds.LOCAL, chapterFlags = Manga.CHAPTER_SHOW_DOWNLOADED)
        val chapters = listOf(fixtureChapter(id = 100L), fixtureChapter(id = 101L, name = "Chapter 2"))
        stubSettledScreen(manga, chapters)

        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()

        val settled = viewModel.state.value as MangaDetailViewModel.State.Success
        settled.chapters.map { it.id } shouldContainExactly listOf(100L, 101L)
        settled.isLocal shouldBe true
        settled.downloadOf(100L) shouldBe ChapterDownload.None

        // Neither action reaches the engine, even if something calls it past the hidden buttons.
        viewModel.toggleSelection(100L)
        viewModel.downloadSelected()
        viewModel.deleteSelected()
        advanceUntilIdle()
        coVerify(exactly = 0) { downloadQueue.enqueue(any(), any(), any()) }
        (viewModel.state.value as MangaDetailViewModel.State.Success).dialog shouldBe null
    }

    @Test
    fun `setChapterFilter writes the downloaded flag through SetMangaChapterFlags`() = runTest(testDispatcher) {
        val manga = fixtureManga()
        stubSettledScreen(manga, listOf(fixtureChapter()))

        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()

        viewModel.setChapterFilter(MangaDetailViewModel.ChapterFilter.DOWNLOADED, TriState.ENABLED_IS)
        viewModel.setChapterFilter(MangaDetailViewModel.ChapterFilter.DOWNLOADED, TriState.ENABLED_NOT)
        viewModel.setChapterFilter(MangaDetailViewModel.ChapterFilter.DOWNLOADED, TriState.DISABLED)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            setMangaChapterFlags.awaitSetDownloadedFilter(manga, Manga.CHAPTER_SHOW_DOWNLOADED)
        }
        coVerify(exactly = 1) {
            setMangaChapterFlags.awaitSetDownloadedFilter(manga, Manga.CHAPTER_SHOW_NOT_DOWNLOADED)
        }
        coVerify(exactly = 1) { setMangaChapterFlags.awaitSetDownloadedFilter(manga, Manga.SHOW_ALL) }
    }

    /** Oldest first whatever the list's order, so the chapters arrive in reading order. */
    @Test
    fun `downloadSelected queues the selection in reading order and starts the worker`() = runTest(testDispatcher) {
        val manga = fixtureManga()
        val first = fixtureChapter(id = 100L, name = "Chapter 1").copy(sourceOrder = 1L, chapterNumber = 1.0)
        val second = fixtureChapter(id = 101L, name = "Chapter 2").copy(sourceOrder = 0L, chapterNumber = 2.0)
        stubSettledScreen(manga, listOf(second, first))

        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()
        viewModel.toggleSelection(second.id)
        viewModel.toggleSelection(first.id)
        viewModel.downloadSelected()
        advanceUntilIdle()

        coVerify(exactly = 1) { downloadQueue.enqueue(manga, listOf(first, second), autoStart = true) }
        (viewModel.state.value as MangaDetailViewModel.State.Success).selection shouldBe emptySet()
    }

    /** The order is the displayed one: ids run against source order here, so id order would pick other rows. */
    @Test
    fun `longPressChapter on the first then the fifth displayed chapter selects the first five`() = runTest(testDispatcher) {
        val manga = fixtureManga(chapterFlags = Manga.CHAPTER_SORTING_SOURCE)
        // Source order 0 to 6 on these ids, so the list reads in neither id direction.
        val chapters = listOf(104L, 101L, 106L, 102L, 107L, 103L, 105L).mapIndexed { order, id ->
            fixtureChapter(id = id, name = "Chapter $id").copy(sourceOrder = order.toLong())
        }
        stubSettledScreen(manga, chapters)
        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()
        val shown = (viewModel.state.value as MangaDetailViewModel.State.Success).chapters.map { it.id }
        shown.sorted() shouldNotBe shown
        shown.sortedDescending() shouldNotBe shown

        viewModel.longPressChapter(shown[0])
        viewModel.longPressChapter(shown[4])

        (viewModel.state.value as MangaDetailViewModel.State.Success).selection shouldBe shown.take(5).toSet()
    }

    @Test
    fun `longPressChapter on a selected chapter deselects it`() = runTest(testDispatcher) {
        val manga = fixtureManga()
        val chapters = (1L..3L).map { fixtureChapter(id = 100L + it).copy(sourceOrder = it) }
        stubSettledScreen(manga, chapters)
        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()

        viewModel.longPressChapter(101L)
        viewModel.longPressChapter(101L)

        (viewModel.state.value as MangaDetailViewModel.State.Success).selection shouldBe emptySet()
    }

    /**
     * Only chapters with files are asked about, and nothing is deleted until the owner confirms. The
     * delete goes through [DownloadManifest], which removes the folder and then the row.
     */
    @Test
    fun `deleteSelected asks about the downloaded chapters and the confirmation deletes them`() =
        runTest(testDispatcher) {
            val onDisk = fixtureChapter(id = 100L, name = "Chapter 1")
            val notOnDisk = fixtureChapter(id = 101L, name = "Chapter 2")
            every { downloadedChapters.subscribeByMangaId(any()) } returns flowOf(setOf(onDisk.id))
            val manga = fixtureManga()
            stubSettledScreen(manga, listOf(onDisk, notOnDisk))

            val viewModel = viewModel(manga.id, fromSource = false)
            advanceUntilIdle()
            viewModel.toggleSelection(onDisk.id)
            viewModel.toggleSelection(notOnDisk.id)
            viewModel.deleteSelected()
            advanceUntilIdle()

            val asking = viewModel.state.value as MangaDetailViewModel.State.Success
            asking.dialog shouldBe MangaDetailViewModel.Dialog.DeleteChapters(listOf(onDisk.id))
            asking.selection shouldBe setOf(onDisk.id, notOnDisk.id)
            coVerify(exactly = 0) { downloadManifest.delete(any()) }

            viewModel.confirmDeleteChapters()
            advanceUntilIdle()

            coVerify(exactly = 1) { downloadManifest.delete(listOf(onDisk.id)) }
            val settled = viewModel.state.value as MangaDetailViewModel.State.Success
            settled.dialog shouldBe null
            settled.selection shouldBe emptySet()
        }

    @Test
    fun `deleteSelected with nothing downloaded says so instead of asking`() = runTest(testDispatcher) {
        val manga = fixtureManga()
        stubSettledScreen(manga, listOf(fixtureChapter()))

        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()
        viewModel.toggleSelection(100L)

        viewModel.events.test {
            viewModel.deleteSelected()
            advanceUntilIdle()
            awaitItem() shouldBe MangaDetailViewModel.Event.Snackbar(R.string.chapter_delete_none)
        }
        (viewModel.state.value as MangaDetailViewModel.State.Success).dialog shouldBe null
        coVerify(exactly = 0) { downloadManifest.delete(any()) }
    }

    /** What each chapter row draws, from the queue rows of this entry and the downloaded set. */
    @Test
    fun `downloadOf maps the queue and the downloaded set to the row's state`() = runTest(testDispatcher) {
        val manga = fixtureManga()
        val ids = (100L..105L).toList()
        stubSettledScreen(manga, ids.map { fixtureChapter(id = it, name = "Chapter $it") })
        every { downloadedChapters.subscribeByMangaId(any()) } returns flowOf(setOf(104L))
        every { downloadQueue.subscribe() } returns flowOf(
            listOf(
                queueRow(chapterId = 100L, state = DownloadState.QUEUED),
                queueRow(chapterId = 101L, state = DownloadState.DOWNLOADING, pages = 3, total = 12),
                queueRow(chapterId = 102L, state = DownloadState.ERROR),
                queueRow(chapterId = 103L, state = DownloadState.PAUSED),
                // Another entry's row for a chapter id this one does not have is not drawn.
                queueRow(chapterId = 105L, mangaId = 9L, state = DownloadState.ERROR),
            ),
        )

        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()

        val settled = viewModel.state.value as MangaDetailViewModel.State.Success
        ids.map(settled::downloadOf) shouldContainExactly listOf(
            ChapterDownload.Queued,
            ChapterDownload.Downloading(pages = 3, total = 12),
            ChapterDownload.Failed,
            ChapterDownload.Queued,
            ChapterDownload.Downloaded,
            ChapterDownload.None,
        )
    }

    private fun queueRow(
        chapterId: Long,
        state: DownloadState,
        mangaId: Long = 1L,
        pages: Long = 0,
        total: Long = 0,
    ) = DownloadQueueEntry(
        id = chapterId,
        chapterId = chapterId,
        mangaId = mangaId,
        state = state,
        queueOrder = chapterId,
        totalPages = total,
        downloadedPages = pages,
        errorMessage = null,
        dateAdded = 0,
        dateStarted = null,
        dateCompleted = null,
    )

    // ---- The bar's download menu (phase 3 unit AD) ----

    /**
     * Chapters 1 to 8 as a source lists them, newest first: id 101 is chapter 1 with the highest
     * source position. Handed to the screen out of order, so the reading order is the view model's.
     */
    private fun menuChapters(read: Set<Long> = emptySet(), bookmarked: Set<Long> = emptySet()) =
        (101L..108L).map { id ->
            fixtureChapter(id = id, name = "Chapter ${id - 100}", bookmark = id in bookmarked)
                .copy(sourceOrder = 108L - id, chapterNumber = (id - 100).toDouble(), read = id in read)
        }.shuffled(java.util.Random(7))

    /** The ids of the one started enqueue [action] made, or null when it made none started. */
    private fun TestScope.enqueuedBy(manga: Manga, action: DownloadAction): List<Long>? {
        val started = mutableListOf<List<Chapter>>()
        coEvery { downloadQueue.enqueue(manga, capture(started), autoStart = true) } returns Unit
        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()
        viewModel.download(action)
        advanceUntilIdle()
        return started.singleOrNull()?.map { it.id }
    }

    @Test
    fun `next N takes the unread chapters in reading order, less the downloaded and the queued`() =
        runTest(testDispatcher) {
            val manga = fixtureManga()
            stubSettledScreen(manga, menuChapters(read = setOf(101L)))
            every { downloadedChapters.subscribeByMangaId(any()) } returns flowOf(setOf(102L))
            every { downloadQueue.subscribe() } returns flowOf(listOf(queueRow(chapterId = 103L, state = DownloadState.QUEUED)))

            // Queued oldest first, which is the source's order reversed, and the queue started.
            enqueuedBy(manga, DownloadAction.NEXT_5_CHAPTERS) shouldBe listOf(104L, 105L, 106L, 107L, 108L)
        }

    @Test
    fun `next 1 is the first unread chapter whichever way the list is sorted`() = runTest(testDispatcher) {
        // Ascending by source order, the opposite of the default.
        val manga = fixtureManga(chapterFlags = Manga.CHAPTER_SORT_ASC)
        stubSettledScreen(manga, menuChapters(read = setOf(101L, 102L)))

        enqueuedBy(manga, DownloadAction.NEXT_1_CHAPTER) shouldBe listOf(103L)
    }

    /** Upstream's `skipFiltered`: on by default, the list's filters decide the pool; off, every chapter. */
    @Test
    fun `with skip filtered on, the chapter list's filter narrows what next N draws from`() = runTest(testDispatcher) {
        val manga = fixtureManga(chapterFlags = Manga.CHAPTER_SHOW_BOOKMARKED)
        stubSettledScreen(manga, menuChapters(bookmarked = setOf(104L, 106L)))

        enqueuedBy(manga, DownloadAction.NEXT_1_CHAPTER) shouldBe listOf(104L)
    }

    @Test
    fun `with skip filtered off, next N draws from every chapter`() = runTest(testDispatcher) {
        readerPreferences.skipFiltered.set(false)
        val manga = fixtureManga(chapterFlags = Manga.CHAPTER_SHOW_BOOKMARKED)
        stubSettledScreen(manga, menuChapters(bookmarked = setOf(104L, 106L)))

        enqueuedBy(manga, DownloadAction.NEXT_1_CHAPTER) shouldBe listOf(101L)
    }

    @Test
    fun `unread takes every unread chapter not yet downloaded`() = runTest(testDispatcher) {
        val manga = fixtureManga()
        stubSettledScreen(manga, menuChapters(read = setOf(101L, 102L, 103L)))
        every { downloadedChapters.subscribeByMangaId(any()) } returns flowOf(setOf(108L))

        enqueuedBy(manga, DownloadAction.UNREAD_CHAPTERS) shouldBe listOf(104L, 105L, 106L, 107L)
    }

    /** Bookmarked asks nothing about read state, as in upstream. */
    @Test
    fun `bookmarked takes the bookmarked chapters, read or not, not yet downloaded`() = runTest(testDispatcher) {
        val manga = fixtureManga()
        stubSettledScreen(manga, menuChapters(read = setOf(101L), bookmarked = setOf(101L, 105L, 107L)))
        every { downloadedChapters.subscribeByMangaId(any()) } returns flowOf(setOf(107L))

        enqueuedBy(manga, DownloadAction.BOOKMARKED_CHAPTERS) shouldBe listOf(101L, 105L)
    }

    @Test
    fun `a choice that leaves nothing to download queues nothing`() = runTest(testDispatcher) {
        val manga = fixtureManga()
        stubSettledScreen(manga, menuChapters(read = (101L..108L).toSet()))

        enqueuedBy(manga, DownloadAction.NEXT_25_CHAPTERS) shouldBe null
        coVerify(exactly = 0) { downloadQueue.enqueue(any(), any(), any()) }
    }

    @Test
    fun `setReadingMode writes the per-manga viewer flag and closes the dialog`() = runTest(testDispatcher) {
        val manga = fixtureManga()
        stubSettledScreen(manga, listOf(fixtureChapter()))

        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()

        viewModel.showReadingMode()
        viewModel.setReadingMode(ReadingMode.WEBTOON)
        advanceUntilIdle()

        coVerify(exactly = 1) { setMangaViewerFlags.awaitSetReadingMode(manga.id, ReadingMode.WEBTOON) }
        (viewModel.state.value as MangaDetailViewModel.State.Success).dialog shouldBe null
    }

    /**
     * Phase 5 unit ED and R165 item 4: the edit dialog's Save hands every value it holds to the
     * interactor, which decides what is an edit, and closes the dialog.
     */
    @Test
    fun `saving the edit dialog writes what it holds through SetMangaDetailsOverride and closes it`() =
        runTest(testDispatcher) {
            val manga = fixtureManga()
            stubSettledScreen(manga, listOf(fixtureChapter()))
            coEvery { setMangaDetailsOverride.await(manga.id, any()) } returns true
            val viewModel = viewModel(manga.id, fromSource = false)
            advanceUntilIdle()

            viewModel.showEditDetails()
            (viewModel.state.value as MangaDetailViewModel.State.Success).dialog shouldBe
                MangaDetailViewModel.Dialog.EditDetails
            viewModel.events.test {
                viewModel.saveDetails(EDIT)
                advanceUntilIdle()
                expectNoEvents()
            }

            coVerify(exactly = 1) { setMangaDetailsOverride.await(manga.id, EDIT) }
            (viewModel.state.value as MangaDetailViewModel.State.Success).dialog shouldBe null
        }

    @Test
    fun `a blank title is not saved and the dialog stays open`() = runTest(testDispatcher) {
        val manga = fixtureManga()
        stubSettledScreen(manga, listOf(fixtureChapter()))
        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()

        viewModel.showEditDetails()
        viewModel.saveDetails(EDIT.copy(title = "  "))
        advanceUntilIdle()

        coVerify(exactly = 0) { setMangaDetailsOverride.await(any(), any()) }
        (viewModel.state.value as MangaDetailViewModel.State.Success).dialog shouldBe
            MangaDetailViewModel.Dialog.EditDetails
    }

    @Test
    fun `a save that failed is said on the snackbar`() = runTest(testDispatcher) {
        val manga = fixtureManga()
        stubSettledScreen(manga, listOf(fixtureChapter()))
        coEvery { setMangaDetailsOverride.await(manga.id, any()) } returns false
        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()

        viewModel.showEditDetails()
        viewModel.events.test {
            viewModel.saveDetails(EDIT)
            advanceUntilIdle()
            (awaitItem() as MangaDetailViewModel.Event.Snackbar).messageRes shouldBe R.string.manga_edit_failed
        }
    }

    /** Reset all, once the dialog's question is answered: every edit cleared through the interactor. */
    @Test
    fun `reset all clears every edit through the interactor, and a write that failed is said`() =
        runTest(testDispatcher) {
            val manga = fixtureManga()
            stubSettledScreen(manga, listOf(fixtureChapter()))
            coEvery { setMangaDetailsOverride.awaitReset(manga.id) } returns false
            val viewModel = viewModel(manga.id, fromSource = false)
            advanceUntilIdle()

            viewModel.showEditDetails()
            viewModel.events.test {
                viewModel.resetDetails()
                advanceUntilIdle()
                (awaitItem() as MangaDetailViewModel.Event.Snackbar).messageRes shouldBe R.string.manga_edit_failed
            }

            coVerify(exactly = 1) { setMangaDetailsOverride.awaitReset(manga.id) }
            (viewModel.state.value as MangaDetailViewModel.State.Success).dialog shouldBe null
        }

    @Test
    fun `showUpdateInterval opens the update interval dialog`() = runTest(testDispatcher) {
        val manga = fixtureManga()
        stubSettledScreen(manga, listOf(fixtureChapter()))
        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()

        viewModel.showUpdateInterval()

        (viewModel.state.value as MangaDetailViewModel.State.Success).dialog shouldBe
            MangaDetailViewModel.Dialog.UpdateInterval
    }

    @Test
    fun `setUpdateInterval writes the chosen days through UpdateManga for the shown entry`() =
        runTest(testDispatcher) {
            val manga = fixtureManga()
            stubSettledScreen(manga, listOf(fixtureChapter()))
            coEvery { updateManga.awaitUpdateCustomInterval(any(), any()) } returns true
            val viewModel = viewModel(manga.id, fromSource = false)
            advanceUntilIdle()

            viewModel.setUpdateInterval(14)
            advanceUntilIdle()

            coVerify(exactly = 1) { updateManga.awaitUpdateCustomInterval(manga, 14) }
        }

    @Test
    fun `setUpdateInterval with 0 hands the entry back to the estimate and leaves the dialog open`() =
        runTest(testDispatcher) {
            val manga = fixtureManga()
            stubSettledScreen(manga, listOf(fixtureChapter()))
            coEvery { updateManga.awaitUpdateCustomInterval(any(), any()) } returns true
            val viewModel = viewModel(manga.id, fromSource = false)
            advanceUntilIdle()
            viewModel.showUpdateInterval()

            viewModel.setUpdateInterval(0)
            advanceUntilIdle()

            coVerify(exactly = 1) { updateManga.awaitUpdateCustomInterval(manga, 0) }
            (viewModel.state.value as MangaDetailViewModel.State.Success).dialog shouldBe
                MangaDetailViewModel.Dialog.UpdateInterval
        }

    @Test
    fun `the detail state carries the update settings and the chapter counts the cadence reads`() =
        runTest(testDispatcher) {
            val manga = fixtureManga()
            stubSettledScreen(manga, listOf(fixtureChapter(id = 1L).copy(read = true), fixtureChapter(id = 2L)))
            libraryPreferences.autoUpdateInterval.set(12)
            libraryPreferences.autoUpdateMangaRestrictions.set(
                setOf(LibraryUpdateRestrictions.MANGA_OUTSIDE_RELEASE_PERIOD),
            )
            libraryPreferences.customUpdateInterval.set(true)

            val viewModel = viewModel(manga.id, fromSource = false)
            advanceUntilIdle()

            val success = viewModel.state.value as MangaDetailViewModel.State.Success
            success.chapterTotal shouldBe 2
            success.chapterRead shouldBe 1
            success.automaticUpdates shouldBe true
            success.customUpdateInterval shouldBe true
            success.updateRestrictions shouldBe setOf(LibraryUpdateRestrictions.MANGA_OUTSIDE_RELEASE_PERIOD)
        }

    @Test
    fun `automatic updates read as off while the update interval hours are 0`() = runTest(testDispatcher) {
        val manga = fixtureManga()
        stubSettledScreen(manga, listOf(fixtureChapter()))
        libraryPreferences.autoUpdateInterval.set(0)

        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()

        (viewModel.state.value as MangaDetailViewModel.State.Success).automaticUpdates shouldBe false
    }

    @Test
    fun `the interval choice is offered with the switch and the release rule on`() = runTest(testDispatcher) {
        val success = settledWithUpdateSettings(customInterval = true, releaseRule = true)

        success.offersIntervalChoice shouldBe true
    }

    @Test
    fun `the interval choice is not offered while the experimental switch is off`() = runTest(testDispatcher) {
        val success = settledWithUpdateSettings(customInterval = false, releaseRule = true)

        success.offersIntervalChoice shouldBe false
    }

    @Test
    fun `the interval choice is not offered while the release rule is off`() = runTest(testDispatcher) {
        val success = settledWithUpdateSettings(customInterval = true, releaseRule = false)

        success.offersIntervalChoice shouldBe false
    }

    @Test
    fun `the interval choice is not offered for an entry fetched once`() = runTest(testDispatcher) {
        val success = settledWithUpdateSettings(customInterval = true, releaseRule = true, fetchOnce = true)

        success.updateCadence shouldBe UpdateCadence.FetchOnce
        success.offersIntervalChoice shouldBe false
    }

    @Test
    fun `an entry fetched once with no chapters yet still offers the interval choice`() = runTest(testDispatcher) {
        val success = settledWithUpdateSettings(customInterval = true, releaseRule = true, fetchOnce = true, chapters = 0)

        success.offersIntervalChoice shouldBe true
    }

    @Test
    fun `the interval choice is still offered with the switch off while the entry holds a chosen interval`() =
        runTest(testDispatcher) {
            val success = settledWithUpdateSettings(customInterval = false, releaseRule = true, fetchInterval = -7)

            success.offersIntervalChoice shouldBe true
        }

    @Test
    fun `the interval choice is not offered with the switch off and no chosen interval`() = runTest(testDispatcher) {
        val success = settledWithUpdateSettings(customInterval = false, releaseRule = true, fetchInterval = 0)

        success.offersIntervalChoice shouldBe false
    }

    @Test
    fun `the interval choice is not offered with the switch off and an estimated interval`() = runTest(testDispatcher) {
        val success = settledWithUpdateSettings(customInterval = false, releaseRule = true, fetchInterval = 7)

        success.offersIntervalChoice shouldBe false
    }

    @Test
    fun `a chosen interval with the switch off is not offered while the release rule is off`() =
        runTest(testDispatcher) {
            val success = settledWithUpdateSettings(customInterval = false, releaseRule = false, fetchInterval = -7)

            success.offersIntervalChoice shouldBe false
        }

    @Test
    fun `a chosen interval with the switch off is not offered for an entry fetched once`() =
        runTest(testDispatcher) {
            val success = settledWithUpdateSettings(
                customInterval = false,
                releaseRule = true,
                fetchOnce = true,
                fetchInterval = -7,
            )

            success.updateCadence shouldBe UpdateCadence.FetchOnce
            success.offersIntervalChoice shouldBe false
        }

    private suspend fun TestScope.settledWithUpdateSettings(
        customInterval: Boolean,
        releaseRule: Boolean,
        fetchOnce: Boolean = false,
        chapters: Int = 1,
        fetchInterval: Int = 0,
    ): MangaDetailViewModel.State.Success {
        val manga = fixtureManga().copy(
            fetchInterval = fetchInterval,
            updateStrategy = if (fetchOnce) UpdateStrategy.ONLY_FETCH_ONCE else UpdateStrategy.ALWAYS_UPDATE,
        )
        stubSettledScreen(manga, (1..chapters).map { fixtureChapter(id = it.toLong()) })
        libraryPreferences.autoUpdateMangaRestrictions.set(
            if (releaseRule) setOf(LibraryUpdateRestrictions.MANGA_OUTSIDE_RELEASE_PERIOD) else emptySet(),
        )
        libraryPreferences.customUpdateInterval.set(customInterval)

        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()
        return viewModel.state.value as MangaDetailViewModel.State.Success
    }

    /**
     * The notes column was backed up, synced, migrated and searched with no screen that wrote or
     * showed it, so notes restored from an upstream backup were invisible.
     */
    @Test
    fun `saving the notes dialog writes the trimmed notes through UpdateMangaNotes and closes it`() =
        runTest(testDispatcher) {
            val manga = fixtureManga()
            stubSettledScreen(manga, listOf(fixtureChapter()))
            coEvery { updateMangaNotes(manga.id, any()) } returns true
            val viewModel = viewModel(manga.id, fromSource = false)
            advanceUntilIdle()

            viewModel.showNotes()
            (viewModel.state.value as MangaDetailViewModel.State.Success).dialog shouldBe
                MangaDetailViewModel.Dialog.Notes
            viewModel.events.test {
                viewModel.saveNotes("  Stopped at the second arc\n")
                advanceUntilIdle()
                expectNoEvents()
            }

            coVerify(exactly = 1) { updateMangaNotes(manga.id, "Stopped at the second arc") }
            (viewModel.state.value as MangaDetailViewModel.State.Success).dialog shouldBe null
        }

    @Test
    fun `saving the notes unchanged writes nothing, and a write that failed is said`() = runTest(testDispatcher) {
        val manga = fixtureManga().copy(notes = "Kept")
        stubSettledScreen(manga, listOf(fixtureChapter()))
        coEvery { updateMangaNotes(manga.id, any()) } returns false
        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()

        viewModel.showNotes()
        viewModel.saveNotes("Kept ")
        advanceUntilIdle()
        coVerify(exactly = 0) { updateMangaNotes(any(), any()) }
        (viewModel.state.value as MangaDetailViewModel.State.Success).dialog shouldBe null

        viewModel.showNotes()
        viewModel.events.test {
            viewModel.saveNotes("")
            advanceUntilIdle()
            (awaitItem() as MangaDetailViewModel.Event.Snackbar).messageRes shouldBe R.string.manga_notes_failed
        }
        coVerify(exactly = 1) { updateMangaNotes(manga.id, "") }
    }

    /** Phase 5 unit ED: the cover viewer knows whether a cover can be set and whether a custom one exists. */
    @Test
    fun `the cover viewer offers editing for a library entry and knows its custom cover`() = runTest(testDispatcher) {
        val manga = fixtureManga(favorite = true)
        stubSettledScreen(manga, listOf(fixtureChapter()))
        coEvery { coverActions.customCoverExists(manga.id) } returns true
        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()

        viewModel.showCover()
        advanceUntilIdle()

        (viewModel.state.value as MangaDetailViewModel.State.Success).dialog shouldBe
            MangaDetailViewModel.Dialog.Cover(editable = true, customCover = true)
    }

    @Test
    fun `an entry only being browsed has a cover viewer with no edit`() = runTest(testDispatcher) {
        val manga = fixtureManga(favorite = false)
        stubSettledScreen(manga, listOf(fixtureChapter()))
        coEvery { coverActions.customCoverExists(manga.id) } returns false
        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()

        viewModel.showCover()
        advanceUntilIdle()

        (viewModel.state.value as MangaDetailViewModel.State.Success).dialog shouldBe
            MangaDetailViewModel.Dialog.Cover(editable = false, customCover = false)
    }

    @Test
    fun `each cover action closes the viewer and says its result on the snackbar`() = runTest(testDispatcher) {
        val manga = fixtureManga(favorite = true)
        stubSettledScreen(manga, listOf(fixtureChapter()))
        val picked = mockk<android.net.Uri>()
        coEvery { coverActions.customCoverExists(manga.id) } returns true
        coEvery { coverActions.setCustomCover(manga, picked) } returns Result.success(Unit)
        coEvery { coverActions.deleteCustomCover(manga) } returns Result.failure(IOException("disk"))
        coEvery { coverActions.save(manga) } returns Result.success(picked)
        coEvery { coverActions.share(manga) } returns Result.success(picked)
        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()

        viewModel.events.test {
            for ((action, message) in listOf<Pair<() -> Unit, Int?>>(
                { viewModel.setCustomCover(picked) } to R.string.cover_updated,
                viewModel::deleteCustomCover to R.string.manga_cover_update_failed,
                viewModel::saveCover to R.string.manga_cover_saved,
                viewModel::shareCover to null,
            )) {
                viewModel.showCover()
                advanceUntilIdle()
                action()
                advanceUntilIdle()
                (viewModel.state.value as MangaDetailViewModel.State.Success).dialog shouldBe null
                if (message == null) {
                    expectNoEvents()
                } else {
                    (awaitItem() as MangaDetailViewModel.Event.Snackbar).messageRes shouldBe message
                }
            }
        }
        coVerify(exactly = 1) { coverActions.setCustomCover(manga, picked) }
        coVerify(exactly = 1) { coverActions.share(manga) }
    }

    @Test
    fun `setOrientation writes the per-manga viewer flag and closes the dialog`() = runTest(testDispatcher) {
        val manga = fixtureManga()
        stubSettledScreen(manga, listOf(fixtureChapter()))

        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()

        viewModel.showReadingMode()
        viewModel.setOrientation(ReaderOrientation.LOCKED_PORTRAIT)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            setMangaViewerFlags.awaitSetOrientation(manga.id, ReaderOrientation.LOCKED_PORTRAIT)
        }
        (viewModel.state.value as MangaDetailViewModel.State.Success).dialog shouldBe null
    }

    /**
     * The two are not collapsed into one effective value, because "revert to default" has to be
     * able to tell an entry with no override from one whose override happens to match the default.
     */
    @Test
    fun `the state carries the ReaderPreferences defaults beside the per-manga viewer flags`() =
        runTest(testDispatcher) {
            readerPreferences.defaultReadingMode.set(ReadingMode.VERTICAL.flagValue)
            readerPreferences.defaultOrientationType.set(ReaderOrientation.LANDSCAPE.flagValue)
            val manga = fixtureManga(viewerFlags = 0L)
            stubSettledScreen(manga, listOf(fixtureChapter()))

            val viewModel = viewModel(manga.id, fromSource = false)
            advanceUntilIdle()

            val settled = viewModel.state.value as MangaDetailViewModel.State.Success
            settled.readingMode shouldBe ReadingMode.DEFAULT
            settled.orientation shouldBe ReaderOrientation.DEFAULT
            settled.defaultReadingMode shouldBe ReadingMode.VERTICAL
            settled.defaultOrientation shouldBe ReaderOrientation.LANDSCAPE
        }

    /**
     * Every write the sheet makes re-emits the chapter list, so a sheet that did not survive a
     * projection would close itself on the owner's first tap in it.
     */
    @Test
    fun `an open chapter settings sheet survives a projection landing`() = runTest(testDispatcher) {
        val manga = fixtureManga()
        val chapter = fixtureChapter()
        val mangaFlow = MutableStateFlow(manga to listOf(chapter))
        coEvery { getMangaWithChapters.subscribe(manga.id, applyScanlatorFilter = true) } returns mangaFlow
        every { sourceManager.get(manga.source) } returns fixtureSource()
        coEvery { getNextChapters.await(manga.id) } returns emptyList()

        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()

        viewModel.showChapterSettings()
        mangaFlow.value = manga.copy(chapterFlags = Manga.CHAPTER_SHOW_UNREAD) to listOf(chapter)
        advanceUntilIdle()

        val settled = viewModel.state.value as MangaDetailViewModel.State.Success
        // Proves the second projection landed rather than the emission being conflated away.
        settled.manga.unreadFilterRaw shouldBe Manga.CHAPTER_SHOW_UNREAD
        settled.dialog shouldBe MangaDetailViewModel.Dialog.ChapterSettings
    }

    // ---- The tracker push after a mark read (phase 3 unit T1) ----

    private fun numbered(id: Long, number: Double, read: Boolean = false) =
        fixtureChapter(id = id).copy(chapterNumber = number, read = read)

    /**
     * Runs [mark] on a settled screen over [chapters] with `autoUpdateTrackOnMarkRead` at [state],
     * and returns the pushes recorded and the events sent while it ran.
     */
    private suspend fun TestScope.pushUnder(
        state: AutoTrackState,
        chapters: List<Chapter>,
        mark: (MangaDetailViewModel) -> Unit,
    ): Pair<List<Double>, List<MangaDetailViewModel.Event>> {
        trackPreferences.autoUpdateTrackOnMarkRead.set(state)
        trackProgress.recorded.clear()
        val manga = fixtureManga(chapterFlags = Manga.CHAPTER_SORTING_NUMBER)
        stubSettledScreen(manga, chapters)
        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()
        val events = mutableListOf<MangaDetailViewModel.Event>()
        viewModel.events.test {
            mark(viewModel)
            advanceUntilIdle()
            cancelAndConsumeRemainingEvents().forEach { if (it is app.cash.turbine.Event.Item) events += it.value }
        }
        return trackProgress.recorded.toList() to events
    }

    /** What each [AutoTrackState] does with a mark that wrote chapters up to [number]. */
    private fun assertPushedUnder(state: AutoTrackState, number: Double, pushed: Pair<List<Double>, List<MangaDetailViewModel.Event>>) {
        val (recorded, events) = pushed
        when (state) {
            AutoTrackState.ALWAYS -> {
                recorded shouldBe listOf(number)
                events shouldBe listOf(
                    MangaDetailViewModel.Event.Snackbar(R.string.trackers_updated_summary, listOf(number.toInt())),
                )
            }
            AutoTrackState.ASK -> {
                recorded shouldBe emptyList()
                events shouldBe listOf(MangaDetailViewModel.Event.ConfirmTrackerUpdate(number))
            }
            AutoTrackState.NEVER -> {
                recorded shouldBe emptyList()
                events shouldBe emptyList()
            }
        }
    }

    @Test
    fun `marking one chapter read pushes its number under each auto track state`() = runTest(testDispatcher) {
        val chapter = numbered(100, 7.0)
        coEvery { setReadStatus.await(true, chapter) } returns SetReadStatus.Result.Success(listOf(chapter))

        for (state in AutoTrackState.entries) {
            val pushed = pushUnder(state, listOf(chapter)) { it.markRead(chapter, true) }
            io.kotest.assertions.withClue(state) { assertPushedUnder(state, 7.0, pushed) }
        }
    }

    @Test
    fun `mark previous as read pushes the highest chapter it wrote under each auto track state`() = runTest(testDispatcher) {
        val chapters = (1L..5L).map { numbered(it, it.toDouble(), read = it <= 2) }
        coEvery { setReadStatus.awaitAllBefore(chapters[4], read = true) } returns
            SetReadStatus.Result.Success(chapters.subList(2, 4))

        for (state in AutoTrackState.entries) {
            val pushed = pushUnder(state, chapters) { viewModel ->
                viewModel.toggleSelection(chapters[4].id)
                viewModel.markSelectedPreviousRead()
            }
            io.kotest.assertions.withClue(state) { assertPushedUnder(state, 4.0, pushed) }
        }
    }

    @Test
    fun `marking the selection read pushes the highest chapter it wrote under each auto track state`() = runTest(testDispatcher) {
        val chapters = listOf(numbered(1, 3.0), numbered(2, 9.0, read = true), numbered(3, 5.0))
        // Chapter 9 was read already, so SetReadStatus skips it and the push is 5, not 9.
        coEvery { setReadStatus.await(true, *anyVararg()) } returns
            SetReadStatus.Result.Success(listOf(chapters[0], chapters[2]))

        for (state in AutoTrackState.entries) {
            val pushed = pushUnder(state, chapters) { viewModel ->
                chapters.forEach { viewModel.toggleSelection(it.id) }
                viewModel.markSelectedRead(true)
            }
            io.kotest.assertions.withClue(state) { assertPushedUnder(state, 5.0, pushed) }
        }
    }

    @Test
    fun `marking before the selection read pushes the highest chapter it wrote under each auto track state`() = runTest(testDispatcher) {
        val chapters = (1L..6L).map { numbered(it, it.toDouble()) }
        coEvery { setReadStatus.awaitAllBefore(chapters[4], read = true) } returns
            SetReadStatus.Result.Success(chapters.subList(0, 4))

        for (state in AutoTrackState.entries) {
            val pushed = pushUnder(state, chapters) { viewModel ->
                viewModel.toggleSelection(chapters[1].id)
                viewModel.toggleSelection(chapters[4].id)
                viewModel.markSelectedPreviousRead()
            }
            io.kotest.assertions.withClue(state) { assertPushedUnder(state, 4.0, pushed) }
        }
    }

    /**
     * Challenge 5 T1 item 1: the owner has read to 30 and uses "mark previous as read" on chapter
     * 41, the last. Chapters 31 to 40 are written, and 40 is pushed; 41 would have set the entry
     * completed on the service.
     */
    @Test
    fun `mark previous as read on the last chapter pushes the one before it`() = runTest(testDispatcher) {
        val chapters = (1L..41L).map { numbered(it, it.toDouble(), read = it <= 30) }
        coEvery { setReadStatus.awaitAllBefore(chapters.last(), read = true) } returns
            SetReadStatus.Result.Success(chapters.subList(30, 40))

        val (recorded, _) = pushUnder(AutoTrackState.ALWAYS, chapters) { viewModel ->
            viewModel.toggleSelection(chapters.last().id)
            viewModel.markSelectedPreviousRead()
        }

        recorded shouldBe listOf(40.0)
    }

    @Test
    fun `marking unread never pushes, one chapter or a selection`() = runTest(testDispatcher) {
        val chapter = numbered(100, 7.0, read = true)
        coEvery { setReadStatus.await(false, chapter) } returns SetReadStatus.Result.Success(listOf(chapter))

        for (state in AutoTrackState.entries) {
            val one = pushUnder(state, listOf(chapter)) { it.markRead(chapter, false) }
            val selection = pushUnder(state, listOf(chapter)) { viewModel ->
                viewModel.toggleSelection(chapter.id)
                viewModel.markSelectedRead(false)
            }
            io.kotest.assertions.withClue(state) {
                one shouldBe (emptyList<Double>() to emptyList())
                selection shouldBe (emptyList<Double>() to emptyList())
            }
        }
        trackProgress.asked shouldBe 0
    }

    @Test
    fun `a mark that wrote nothing pushes nothing`() = runTest(testDispatcher) {
        val chapter = numbered(100, 7.0, read = true)
        coEvery { setReadStatus.await(true, chapter) } returns SetReadStatus.Result.NoChapters

        val pushed = pushUnder(AutoTrackState.ALWAYS, listOf(chapter)) { it.markRead(chapter, true) }

        pushed shouldBe (emptyList<Double>() to emptyList())
        trackProgress.asked shouldBe 0
    }

    @Test
    fun `nothing recorded under ALWAYS and nothing to push under ASK show nothing`() = runTest(testDispatcher) {
        val chapter = numbered(100, 7.0)
        coEvery { setReadStatus.await(true, chapter) } returns SetReadStatus.Result.Success(listOf(chapter))
        trackProgress.tracked = false

        for (state in listOf(AutoTrackState.ALWAYS, AutoTrackState.ASK)) {
            val pushed = pushUnder(state, listOf(chapter)) { it.markRead(chapter, true) }
            io.kotest.assertions.withClue(state) { pushed shouldBe (emptyList<Double>() to emptyList()) }
        }
    }

    @Test
    fun `the snackbar's OK records the push it asked about`() = runTest(testDispatcher) {
        val chapter = numbered(100, 7.0)
        coEvery { setReadStatus.await(true, chapter) } returns SetReadStatus.Result.Success(listOf(chapter))
        var viewModel: MangaDetailViewModel? = null

        pushUnder(AutoTrackState.ASK, listOf(chapter)) {
            viewModel = it
            it.markRead(chapter, true)
        }
        viewModel!!.confirmTrackerUpdate(7.0)
        advanceUntilIdle()

        trackProgress.recorded shouldBe listOf(7.0)
    }

    @Test
    fun `a push that cannot be recorded leaves the mark read standing`() = runTest(testDispatcher) {
        val chapter = numbered(100, 7.0)
        coEvery { setReadStatus.await(true, chapter) } returns SetReadStatus.Result.Success(listOf(chapter))
        trackProgress.failure = java.io.IOException("fixture: the preference file refused the commit")

        val (_, events) = pushUnder(AutoTrackState.ALWAYS, listOf(chapter)) { it.markRead(chapter, true) }

        events shouldBe emptyList()
        coVerify { setReadStatus.await(true, chapter) }
    }

    /**
     * Records every push for manga 1 while [tracked], as if one signed-in tracker were bound below
     * every chapter; throws [failure] instead when one is set. [asked] counts calls of either kind.
     */
    private class FakeTrackProgress : TrackProgress {
        val recorded = mutableListOf<Double>()
        var tracked = true
        var failure: Exception? = null
        var asked = 0

        override suspend fun wouldRecord(mangaId: Long, chapterNumber: Double): Boolean {
            asked++
            failure?.let { throw it }
            return tracked
        }

        override suspend fun record(mangaId: Long, chapterNumber: Double): Int {
            asked++
            failure?.let { throw it }
            if (!tracked) return 0
            recorded += chapterNumber
            return 1
        }
    }

    // Tracking button (phase 3 unit T3) ----------------------------------------------------------

    private fun trackRow(trackerId: Long) = Track(
        id = trackerId,
        mangaId = 1L,
        trackerId = trackerId,
        remoteId = 1000 + trackerId,
        libraryId = null,
        title = "Entry",
        lastChapterRead = 0.0,
        totalChapters = 0,
        status = 1,
        score = 0.0,
        remoteUrl = "https://tracker.example.invalid/entry",
        startDate = 0,
        finishDate = 0,
        private = false,
    )

    private suspend fun TestScope.trackingScreen(): MangaDetailViewModel {
        val manga = fixtureManga()
        stubSettledScreen(manga, listOf(fixtureChapter()))
        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()
        return viewModel
    }

    private val MangaDetailViewModel.tracking: MangaDetailViewModel.Tracking
        get() = (state.value as MangaDetailViewModel.State.Success).tracking

    @Test
    fun `with no configured tracker in the graph the tracking button is not offered`() = runTest(testDispatcher) {
        trackerManager = TrackerManager(setOf(FakeOAuthTracker(1, isConfigured = false, loggedIn = false)))

        trackingScreen().tracking shouldBe MangaDetailViewModel.Tracking(offered = false)
    }

    @Test
    fun `with no tracker signed in the button reads Tracking and opens the Tracking settings`() = runTest(testDispatcher) {
        trackerManager = TrackerManager(setOf(FakeOAuthTracker(1, loggedIn = false)))
        trackRows.value = listOf(trackRow(1))
        val viewModel = trackingScreen()

        viewModel.tracking shouldBe MangaDetailViewModel.Tracking(offered = true, anySignedIn = false, count = 0)
        viewModel.events.test {
            viewModel.openTracking()
            advanceUntilIdle()
            awaitItem() shouldBe MangaDetailViewModel.Event.OpenTrackingSettings
        }
        (viewModel.state.value as MangaDetailViewModel.State.Success).dialog shouldBe null
    }

    @Test
    fun `the count is the rows of signed-in trackers only, follows a sign-in, and the button opens the sheet`() =
        runTest(testDispatcher) {
            val signedIn = FakeOAuthTracker(1, loggedIn = true)
            val signedOut = FakeOAuthTracker(2, loggedIn = false)
            trackerManager = TrackerManager(setOf(signedIn, signedOut))
            val viewModel = trackingScreen()
            viewModel.tracking shouldBe MangaDetailViewModel.Tracking(offered = true, anySignedIn = true, count = 0)

            trackRows.value = listOf(trackRow(1), trackRow(2))
            advanceUntilIdle()
            viewModel.tracking.count shouldBe 1

            signedOut.loggedInState.value = true
            advanceUntilIdle()
            viewModel.tracking.count shouldBe 2

            viewModel.openTracking()
            (viewModel.state.value as MangaDetailViewModel.State.Success).dialog shouldBe
                MangaDetailViewModel.Dialog.TrackSheet
        }

    // ---- The chapter swipe (phase 4 unit CS) ----

    /** A settled state over one chapter per case, for [MangaDetailViewModel.State.Success.swipeOf] alone. */
    private fun swipeState(
        source: Long = 10L,
        downloaded: Set<Long> = emptySet(),
        queued: List<DownloadQueueEntry> = emptyList(),
    ) = MangaDetailViewModel.State.Success(
        manga = fixtureManga(source = source),
        chapters = emptyList(),
        sourceName = null,
        sourceInstalled = true,
        downloaded = downloaded,
        queued = queued.associateBy { it.chapterId },
    )

    @Test
    fun `a read swipe marks an unread chapter read and a read one unread, on any entry`() {
        val unread = fixtureChapter(id = 100L)
        val read = fixtureChapter(id = 101L).copy(read = true)
        for (state in listOf(swipeState(), swipeState(source = SourceIds.LOCAL))) {
            state.swipeOf(ChapterSwipeAction.ToggleRead, unread) shouldBe MangaDetailViewModel.ChapterSwipe.MarkRead
            state.swipeOf(ChapterSwipeAction.ToggleRead, read) shouldBe MangaDetailViewModel.ChapterSwipe.MarkUnread
        }
    }

    @Test
    fun `a bookmark swipe bookmarks a plain chapter and removes a bookmark, on any entry`() {
        val plain = fixtureChapter(id = 100L)
        val bookmarked = fixtureChapter(id = 101L, bookmark = true)
        for (state in listOf(swipeState(), swipeState(source = SourceIds.LOCAL))) {
            state.swipeOf(ChapterSwipeAction.ToggleBookmark, plain) shouldBe MangaDetailViewModel.ChapterSwipe.Bookmark
            state.swipeOf(ChapterSwipeAction.ToggleBookmark, bookmarked) shouldBe
                MangaDetailViewModel.ChapterSwipe.RemoveBookmark
        }
    }

    /** Upstream's `executeChapterSwipeAction`: start when not downloaded or failed, cancel when queued, delete when on disk. */
    @Test
    fun `a download swipe starts, cancels or deletes by the chapter's download state`() {
        val state = swipeState(
            downloaded = setOf(104L),
            queued = listOf(
                queueRow(chapterId = 100L, state = DownloadState.QUEUED),
                queueRow(chapterId = 101L, state = DownloadState.PAUSED),
                queueRow(chapterId = 102L, state = DownloadState.DOWNLOADING, pages = 3, total = 12),
                queueRow(chapterId = 103L, state = DownloadState.ERROR),
            ),
        )
        (100L..105L).map { state.swipeOf(ChapterSwipeAction.Download, fixtureChapter(id = it)) } shouldContainExactly listOf(
            MangaDetailViewModel.ChapterSwipe.CancelDownload,
            MangaDetailViewModel.ChapterSwipe.CancelDownload,
            MangaDetailViewModel.ChapterSwipe.CancelDownload,
            MangaDetailViewModel.ChapterSwipe.StartDownload,
            MangaDetailViewModel.ChapterSwipe.DeleteDownload,
            MangaDetailViewModel.ChapterSwipe.StartDownload,
        )
    }

    @Test
    fun `a download swipe on a Local entry does nothing, whatever the chapter's state`() {
        val state = swipeState(
            source = SourceIds.LOCAL,
            downloaded = setOf(101L),
            queued = listOf(queueRow(chapterId = 102L, state = DownloadState.QUEUED)),
        )
        (100L..102L).forEach { id ->
            state.swipeOf(ChapterSwipeAction.Download, fixtureChapter(id = id)) shouldBe null
        }
    }

    @Test
    fun `a disabled swipe does nothing to any chapter`() {
        val state = swipeState(downloaded = setOf(101L))
        listOf(
            fixtureChapter(id = 100L),
            fixtureChapter(id = 101L, bookmark = true).copy(read = true),
        ).forEach { chapter -> state.swipeOf(ChapterSwipeAction.Disabled, chapter) shouldBe null }
    }

    /**
     * A row does not swipe in selection mode (the CS verifier's finding 1; plan 2.6 keeps part E
     * off): whatever the action and the chapter's state, the swipe is no action.
     */
    @Test
    fun `no swipe does anything while chapters are selected`() {
        val state = swipeState(
            downloaded = setOf(101L),
            queued = listOf(queueRow(chapterId = 102L, state = DownloadState.QUEUED)),
        ).copy(selection = setOf(100L))
        val chapters = listOf(
            fixtureChapter(id = 100L),
            fixtureChapter(id = 101L, bookmark = true).copy(read = true),
            fixtureChapter(id = 102L),
        )
        for (action in ChapterSwipeAction.entries) {
            chapters.forEach { chapter -> state.swipeOf(action, chapter) shouldBe null }
        }
    }

    /**
     * The gesture, not the key (challenge c2 g3 A item 1): the row's start actions, revealed by a
     * swipe to the right, are `swipeToEndAction`, upstream's "Chapter on swipe to right", and with
     * nothing stored they mark read; the swipe to the left bookmarks.
     */
    @Test
    fun `with nothing stored the swipe to the right marks read and the swipe to the left bookmarks`() =
        runTest(testDispatcher) {
            val manga = fixtureManga()
            stubSettledScreen(manga, listOf(fixtureChapter()))

            val viewModel = viewModel(manga.id, fromSource = false)

            viewModel.chapterSwipeStartAction shouldBe ChapterSwipeAction.ToggleRead
            viewModel.chapterSwipeEndAction shouldBe ChapterSwipeAction.ToggleBookmark
        }

    @Test
    fun `the swipe to the left follows swipeToStartAction and the swipe to the right swipeToEndAction`() =
        runTest(testDispatcher) {
            val manga = fixtureManga()
            stubSettledScreen(manga, listOf(fixtureChapter()))
            libraryPreferences.swipeToStartAction.set(ChapterSwipeAction.Download)
            libraryPreferences.swipeToEndAction.set(ChapterSwipeAction.Disabled)

            val viewModel = viewModel(manga.id, fromSource = false)

            viewModel.chapterSwipeEndAction shouldBe ChapterSwipeAction.Download
            viewModel.chapterSwipeStartAction shouldBe ChapterSwipeAction.Disabled
        }

    /** The read swipe goes through markRead, so trackers get the push the button gives (c2 g3 A item 6). */
    @Test
    fun `a read swipe pushes its chapter to trackers under each auto track state, as markRead does`() =
        runTest(testDispatcher) {
            val chapter = numbered(100, 7.0)
            coEvery { setReadStatus.await(true, chapter) } returns SetReadStatus.Result.Success(listOf(chapter))

            for (state in AutoTrackState.entries) {
                val pushed = pushUnder(state, listOf(chapter)) { it.swipeChapter(chapter, ChapterSwipeAction.ToggleRead) }
                io.kotest.assertions.withClue(state) { assertPushedUnder(state, 7.0, pushed) }
            }
        }

    @Test
    fun `a read swipe on a read chapter marks it unread and pushes nothing`() = runTest(testDispatcher) {
        val chapter = numbered(100, 7.0, read = true)
        coEvery { setReadStatus.await(false, chapter) } returns SetReadStatus.Result.Success(listOf(chapter))

        val pushed = pushUnder(AutoTrackState.ALWAYS, listOf(chapter)) {
            it.swipeChapter(chapter, ChapterSwipeAction.ToggleRead)
        }

        coVerify(exactly = 1) { setReadStatus.await(false, chapter) }
        pushed shouldBe (emptyList<Double>() to emptyList())
    }

    @Test
    fun `a bookmark swipe writes the other bookmark state`() = runTest(testDispatcher) {
        val plain = fixtureChapter(id = 100L)
        val bookmarked = fixtureChapter(id = 101L, name = "Chapter 2", bookmark = true)
        val manga = fixtureManga()
        stubSettledScreen(manga, listOf(plain, bookmarked))
        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()

        viewModel.swipeChapter(plain, ChapterSwipeAction.ToggleBookmark)
        viewModel.swipeChapter(bookmarked, ChapterSwipeAction.ToggleBookmark)
        advanceUntilIdle()

        coVerify(exactly = 1) { toggleBookmark.await(plain.id, true) }
        coVerify(exactly = 1) { toggleBookmark.await(bookmarked.id, false) }
    }

    /** Upstream's start now: queued, moved to the front of the queue, and the queue started. */
    @Test
    fun `a download swipe on a chapter not downloaded queues it at the front and starts the queue`() =
        runTest(testDispatcher) {
            val chapter = fixtureChapter(id = 100L)
            val manga = fixtureManga()
            stubSettledScreen(manga, listOf(chapter))
            // Two chapters of another entry already wait; the enqueue appends this one behind them.
            val rows = MutableStateFlow(
                listOf(
                    queueRow(chapterId = 7L, mangaId = 9L, state = DownloadState.QUEUED),
                    queueRow(chapterId = 8L, mangaId = 9L, state = DownloadState.QUEUED),
                ),
            )
            every { downloadQueue.subscribe() } returns rows
            coEvery { downloadQueue.enqueue(manga, listOf(chapter), autoStart = false) } coAnswers {
                rows.value = rows.value + queueRow(chapterId = chapter.id, state = DownloadState.QUEUED)
            }
            val viewModel = viewModel(manga.id, fromSource = false)
            advanceUntilIdle()

            viewModel.swipeChapter(chapter, ChapterSwipeAction.Download)
            advanceUntilIdle()

            io.mockk.coVerifyOrder {
                downloadQueue.enqueue(manga, listOf(chapter), autoStart = false)
                downloadQueue.reorder(listOf(chapter.id, 7L, 8L))
                downloadQueue.start()
            }
            coVerify(exactly = 0) { downloadQueue.remove(any()) }
            coVerify(exactly = 0) { downloadManifest.delete(any()) }
        }

    @Test
    fun `a download swipe on a queued or downloading chapter cancels it`() = runTest(testDispatcher) {
        val queued = fixtureChapter(id = 100L)
        val downloading = fixtureChapter(id = 101L, name = "Chapter 2")
        val manga = fixtureManga()
        stubSettledScreen(manga, listOf(queued, downloading))
        every { downloadQueue.subscribe() } returns flowOf(
            listOf(
                queueRow(chapterId = queued.id, state = DownloadState.QUEUED),
                queueRow(chapterId = downloading.id, state = DownloadState.DOWNLOADING, pages = 2, total = 9),
            ),
        )
        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()

        viewModel.swipeChapter(queued, ChapterSwipeAction.Download)
        viewModel.swipeChapter(downloading, ChapterSwipeAction.Download)
        advanceUntilIdle()

        coVerify(exactly = 1) { downloadQueue.remove(listOf(queued.id)) }
        coVerify(exactly = 1) { downloadQueue.remove(listOf(downloading.id)) }
        coVerify(exactly = 0) { downloadQueue.enqueue(any(), any(), any()) }
        coVerify(exactly = 0) { downloadManifest.delete(any()) }
    }

    /** No confirmation, unlike the selection bar's delete: upstream's swipe deletes at once (phase4-start section 5). */
    @Test
    fun `a download swipe on a downloaded chapter deletes it with no dialog`() = runTest(testDispatcher) {
        val chapter = fixtureChapter(id = 100L)
        val manga = fixtureManga()
        stubSettledScreen(manga, listOf(chapter))
        every { downloadedChapters.subscribeByMangaId(any()) } returns flowOf(setOf(chapter.id))
        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()

        viewModel.swipeChapter(chapter, ChapterSwipeAction.Download)
        advanceUntilIdle()

        coVerify(exactly = 1) { downloadManifest.delete(listOf(chapter.id)) }
        (viewModel.state.value as MangaDetailViewModel.State.Success).dialog shouldBe null
        coVerify(exactly = 0) { downloadQueue.enqueue(any(), any(), any()) }
    }

    // ---- The row's download control (plan 2.6 part D, unit ID3) ----

    /**
     * The screen over one chapter in each download state: 100 none, 101 queued, 102 downloading,
     * 103 failed, 104 on disk.
     */
    private fun TestScope.controlScreen(manga: Manga = fixtureManga()): Pair<MangaDetailViewModel, List<Chapter>> {
        val chapters = (100L..104L).map { fixtureChapter(id = it, name = "Chapter $it") }
        stubSettledScreen(manga, chapters)
        every { downloadedChapters.subscribeByMangaId(any()) } returns flowOf(setOf(104L))
        val rows = MutableStateFlow(
            listOf(
                queueRow(chapterId = 101L, state = DownloadState.QUEUED),
                queueRow(chapterId = 102L, state = DownloadState.DOWNLOADING, pages = 2, total = 9),
                queueRow(chapterId = 103L, state = DownloadState.ERROR),
            ),
        )
        every { downloadQueue.subscribe() } returns rows
        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()
        return viewModel to chapters
    }

    /** A tap on a chapter not downloaded queues it and starts the queue, as the bar's Download does. */
    @Test
    fun `the control's download queues the chapter and starts the queue`() = runTest(testDispatcher) {
        val (viewModel, chapters) = controlScreen()

        viewModel.runChapterDownloadAction(chapters[0], ChapterDownloadAction.START)
        advanceUntilIdle()

        coVerify(exactly = 1) { downloadQueue.enqueue(any(), listOf(chapters[0]), autoStart = true) }
        coVerify(exactly = 0) { downloadQueue.reorder(any()) }
        coVerify(exactly = 0) { downloadQueue.remove(any()) }
        coVerify(exactly = 0) { downloadManifest.delete(any()) }
    }

    /** A long press, or the menu's Start downloading now: the swipe's start now, to the front of the queue. */
    @Test
    fun `the control's start now queues the chapter at the front and starts the queue`() = runTest(testDispatcher) {
        val (viewModel, chapters) = controlScreen()

        viewModel.runChapterDownloadAction(chapters[2], ChapterDownloadAction.START_NOW)
        advanceUntilIdle()

        io.mockk.coVerifyOrder {
            downloadQueue.enqueue(any(), listOf(chapters[2]), autoStart = false)
            downloadQueue.reorder(listOf(102L, 101L, 103L))
            downloadQueue.start()
        }
    }

    @Test
    fun `the control's cancel removes the queue row and its delete removes the files with no dialog`() =
        runTest(testDispatcher) {
            val (viewModel, chapters) = controlScreen()

            viewModel.runChapterDownloadAction(chapters[1], ChapterDownloadAction.CANCEL)
            viewModel.runChapterDownloadAction(chapters[4], ChapterDownloadAction.DELETE)
            advanceUntilIdle()

            coVerify(exactly = 1) { downloadQueue.remove(listOf(101L)) }
            coVerify(exactly = 1) { downloadManifest.delete(listOf(104L)) }
            (viewModel.state.value as MangaDetailViewModel.State.Success).dialog shouldBe null
            coVerify(exactly = 0) { downloadQueue.enqueue(any(), any(), any()) }
        }

    /** The retry of a failed chapter is the not-downloaded tap's download: the queue takes the row again. */
    @Test
    fun `the control's retry on a failed chapter queues it again`() = runTest(testDispatcher) {
        val (viewModel, chapters) = controlScreen()
        val settled = viewModel.state.value as MangaDetailViewModel.State.Success
        val retry = settled.downloadOf(103L).tap(longPress = false) as ChapterDownloadTap.Run

        viewModel.runChapterDownloadAction(chapters[3], retry.action)
        advanceUntilIdle()

        coVerify(exactly = 1) { downloadQueue.enqueue(any(), listOf(chapters[3]), autoStart = true) }
    }

    /** The control is not drawn on a Local entry, and a call that reaches the view model anyway does nothing. */
    @Test
    fun `a Local entry draws no download control and its actions reach neither the queue nor the manifest`() =
        runTest(testDispatcher) {
            val (viewModel, chapters) = controlScreen(fixtureManga(source = SourceIds.LOCAL))
            val settled = viewModel.state.value as MangaDetailViewModel.State.Success
            // Before state: the rows are loaded, so the absence is the Local rule's.
            settled.chapters.map { it.id } shouldContainExactly (100L..104L).toList()
            settled.showsDownloadControl shouldBe false

            for (action in ChapterDownloadAction.entries) {
                chapters.forEach { viewModel.runChapterDownloadAction(it, action) }
            }
            advanceUntilIdle()

            coVerify(exactly = 0) { downloadQueue.enqueue(any(), any(), any()) }
            coVerify(exactly = 0) { downloadQueue.remove(any()) }
            coVerify(exactly = 0) { downloadQueue.start() }
            coVerify(exactly = 0) { downloadManifest.delete(any()) }
        }

    /** While chapters are selected the control is off, as upstream's is; it is back once the selection clears. */
    @Test
    fun `the download control is off while chapters are selected and its actions do nothing there`() =
        runTest(testDispatcher) {
            val (viewModel, chapters) = controlScreen()
            (viewModel.state.value as MangaDetailViewModel.State.Success).showsDownloadControl shouldBe true

            viewModel.toggleSelection(100L)
            (viewModel.state.value as MangaDetailViewModel.State.Success).showsDownloadControl shouldBe false
            for (action in ChapterDownloadAction.entries) {
                chapters.forEach { viewModel.runChapterDownloadAction(it, action) }
            }
            advanceUntilIdle()

            coVerify(exactly = 0) { downloadQueue.enqueue(any(), any(), any()) }
            coVerify(exactly = 0) { downloadQueue.remove(any()) }
            coVerify(exactly = 0) { downloadManifest.delete(any()) }

            viewModel.clearSelection()
            (viewModel.state.value as MangaDetailViewModel.State.Success).showsDownloadControl shouldBe true
        }

    /** The bar reads the selection and the download states the rows draw (plan 2.6 part F). */
    @Test
    fun `the selection bar's actions follow the selection and the downloaded set`() = runTest(testDispatcher) {
        val (viewModel, _) = controlScreen()

        viewModel.toggleSelection(104L)
        val onDisk = (viewModel.state.value as MangaDetailViewModel.State.Success).selectionActions
        onDisk.delete shouldBe true
        onDisk.download shouldBe false
        onDisk.markPreviousRead shouldBe true

        viewModel.toggleSelection(100L)
        val mixed = (viewModel.state.value as MangaDetailViewModel.State.Success).selectionActions
        mixed.delete shouldBe true
        mixed.download shouldBe true
        mixed.markPreviousRead shouldBe false
    }

    @Test
    fun `a Local entry's selection bar offers neither download nor delete`() = runTest(testDispatcher) {
        val (viewModel, _) = controlScreen(fixtureManga(source = SourceIds.LOCAL))
        viewModel.toggleSelection(100L)
        viewModel.toggleSelection(104L)

        val actions = (viewModel.state.value as MangaDetailViewModel.State.Success).selectionActions
        actions.markRead shouldBe true
        actions.download shouldBe false
        actions.delete shouldBe false
    }

    /** The rows' upload dates follow the two date settings live, as Updates' headers do. */
    @Test
    fun `the rows read the relative timestamps and date format settings live`() = runTest(testDispatcher) {
        val (viewModel, _) = controlScreen()
        val before = viewModel.state.value as MangaDetailViewModel.State.Success
        before.relativeTime shouldBe true
        before.dateFormat shouldBe ""

        uiPreferences.relativeTime.set(false)
        uiPreferences.dateFormat.set("yyyy-MM-dd")
        advanceUntilIdle()

        val after = viewModel.state.value as MangaDetailViewModel.State.Success
        after.relativeTime shouldBe false
        after.dateFormat shouldBe "yyyy-MM-dd"
    }

    @Test
    fun `a download swipe on a Local entry reaches neither the queue nor the manifest`() = runTest(testDispatcher) {
        val chapter = fixtureChapter(id = 100L)
        val manga = fixtureManga(source = SourceIds.LOCAL)
        stubSettledScreen(manga, listOf(chapter))
        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()

        viewModel.swipeChapter(chapter, ChapterSwipeAction.Download)
        advanceUntilIdle()

        coVerify(exactly = 0) { downloadQueue.enqueue(any(), any(), any()) }
        coVerify(exactly = 0) { downloadQueue.remove(any()) }
        coVerify(exactly = 0) { downloadQueue.start() }
        coVerify(exactly = 0) { downloadManifest.delete(any()) }
    }

    @Test
    fun `a disabled swipe writes nothing`() = runTest(testDispatcher) {
        val chapter = fixtureChapter(id = 100L)
        val manga = fixtureManga()
        stubSettledScreen(manga, listOf(chapter))
        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()

        viewModel.swipeChapter(chapter, ChapterSwipeAction.Disabled)
        advanceUntilIdle()

        coVerify(exactly = 0) { setReadStatus.await(any(), *anyVararg()) }
        coVerify(exactly = 0) { toggleBookmark.await(any(), any()) }
        coVerify(exactly = 0) { downloadQueue.enqueue(any(), any(), any()) }
        coVerify(exactly = 0) { downloadManifest.delete(any()) }
    }

    /** A swipe mid-selection deletes no download and writes nothing (the CS verifier's finding 1). */
    @Test
    fun `a swipe while chapters are selected writes nothing, not even a download delete`() = runTest(testDispatcher) {
        val downloaded = fixtureChapter(id = 100L)
        val other = fixtureChapter(id = 101L, name = "Chapter 2")
        val manga = fixtureManga()
        stubSettledScreen(manga, listOf(downloaded, other))
        every { downloadedChapters.subscribeByMangaId(any()) } returns flowOf(setOf(downloaded.id))
        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()
        viewModel.toggleSelection(other.id)

        for (action in ChapterSwipeAction.entries) {
            viewModel.swipeChapter(downloaded, action)
            viewModel.swipeChapter(other, action)
        }
        advanceUntilIdle()

        coVerify(exactly = 0) { downloadManifest.delete(any()) }
        coVerify(exactly = 0) { downloadQueue.enqueue(any(), any(), any()) }
        coVerify(exactly = 0) { downloadQueue.remove(any()) }
        coVerify(exactly = 0) { setReadStatus.await(any(), *anyVararg()) }
        coVerify(exactly = 0) { toggleBookmark.await(any(), any()) }
        (viewModel.state.value as MangaDetailViewModel.State.Success).selection shouldBe setOf(other.id)
    }

    // ---- upstream's "add to library?" after a download (phase 4 unit MP2) ----

    /**
     * The events [actions] sent, read after they settle: every download path of this screen ends in
     * upstream's `startDownload` prompt, so each test drives one or more of them and reads what came out.
     */
    private suspend fun TestScope.eventsAfter(
        viewModel: MangaDetailViewModel,
        actions: (MangaDetailViewModel) -> Unit,
    ): List<MangaDetailViewModel.Event> {
        var events = emptyList<MangaDetailViewModel.Event>()
        viewModel.events.test {
            actions(viewModel)
            advanceUntilIdle()
            events = cancelAndConsumeRemainingEvents()
                .filterIsInstance<app.cash.turbine.Event.Item<MangaDetailViewModel.Event>>()
                .map { it.value }
        }
        return events
    }

    private fun TestScope.settledDownloadScreen(manga: Manga, chapters: List<Chapter>): MangaDetailViewModel {
        stubSettledScreen(manga, chapters)
        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `the download menu on an entry not in the library asks to add it once it is queued`() = runTest(testDispatcher) {
        val manga = fixtureManga(favorite = false)
        val viewModel = settledDownloadScreen(manga, menuChapters())

        val events = eventsAfter(viewModel) { it.download(DownloadAction.NEXT_1_CHAPTER) }

        events shouldContainExactly listOf(MangaDetailViewModel.Event.PromptAddToLibrary)
        coVerify(exactly = 1) { downloadQueue.enqueue(manga, any(), autoStart = true) }
    }

    @Test
    fun `the selection bar's download on an entry not in the library asks to add it`() = runTest(testDispatcher) {
        val manga = fixtureManga(favorite = false)
        val chapter = fixtureChapter()
        val viewModel = settledDownloadScreen(manga, listOf(chapter))
        viewModel.toggleSelection(chapter.id)

        val events = eventsAfter(viewModel) { it.downloadSelected() }

        events shouldContainExactly listOf(MangaDetailViewModel.Event.PromptAddToLibrary)
        coVerify(exactly = 1) { downloadQueue.enqueue(manga, listOf(chapter), autoStart = true) }
    }

    @Test
    fun `a download swipe's start now on an entry not in the library asks to add it after the start`() =
        runTest(testDispatcher) {
            val manga = fixtureManga(favorite = false)
            val chapter = fixtureChapter()
            val viewModel = settledDownloadScreen(manga, listOf(chapter))

            val events = eventsAfter(viewModel) { it.swipeChapter(chapter, ChapterSwipeAction.Download) }

            events shouldContainExactly listOf(MangaDetailViewModel.Event.PromptAddToLibrary)
            coVerify(exactly = 1) { downloadQueue.start() }
        }

    @Test
    fun `the prompt is asked once per screen, whichever download comes next`() = runTest(testDispatcher) {
        val manga = fixtureManga(favorite = false)
        val chapters = menuChapters()
        val viewModel = settledDownloadScreen(manga, chapters)

        val first = eventsAfter(viewModel) { it.download(DownloadAction.NEXT_1_CHAPTER) }
        val later = eventsAfter(viewModel) {
            it.download(DownloadAction.NEXT_5_CHAPTERS)
            it.toggleSelection(chapters.first().id)
            it.downloadSelected()
            it.swipeChapter(chapters.last(), ChapterSwipeAction.Download)
        }

        first shouldContainExactly listOf(MangaDetailViewModel.Event.PromptAddToLibrary)
        later shouldContainExactly emptyList()
        // A new screen on the same entry asks again, as upstream's flag lives in the screen's state.
        val reopened = settledDownloadScreen(manga, chapters)
        eventsAfter(reopened) { it.download(DownloadAction.NEXT_1_CHAPTER) } shouldContainExactly
            listOf(MangaDetailViewModel.Event.PromptAddToLibrary)
    }

    @Test
    fun `an entry in the library is never asked about, on any download path`() = runTest(testDispatcher) {
        val manga = fixtureManga(favorite = true)
        val chapters = menuChapters()
        val viewModel = settledDownloadScreen(manga, chapters)

        val events = eventsAfter(viewModel) {
            it.download(DownloadAction.NEXT_1_CHAPTER)
            it.toggleSelection(chapters.first().id)
            it.downloadSelected()
            it.swipeChapter(chapters.last(), ChapterSwipeAction.Download)
        }

        events shouldContainExactly emptyList()
        coVerify(exactly = 2) { downloadQueue.enqueue(manga, any(), autoStart = true) }
        coVerify(exactly = 1) { downloadQueue.start() }
    }

    @Test
    fun `a menu choice that queues nothing, a cancel and a delete ask nothing`() = runTest(testDispatcher) {
        val manga = fixtureManga(favorite = false)
        val queued = fixtureChapter(id = 100L)
        val downloaded = fixtureChapter(id = 101L)
        stubSettledScreen(manga, listOf(queued, downloaded))
        every { downloadedChapters.subscribeByMangaId(any()) } returns flowOf(setOf(downloaded.id))
        every { downloadQueue.subscribe() } returns flowOf(listOf(queueRow(chapterId = queued.id, state = DownloadState.QUEUED)))
        val viewModel = viewModel(manga.id, fromSource = false)
        advanceUntilIdle()

        val events = eventsAfter(viewModel) {
            // Nothing bookmarked: upstream's runDownloadAction starts nothing and so asks nothing.
            it.download(DownloadAction.BOOKMARKED_CHAPTERS)
            it.swipeChapter(queued, ChapterSwipeAction.Download)
            it.swipeChapter(downloaded, ChapterSwipeAction.Download)
        }

        events shouldContainExactly emptyList()
        coVerify(exactly = 1) { downloadQueue.remove(listOf(queued.id)) }
        coVerify(exactly = 1) { downloadManifest.delete(listOf(downloaded.id)) }
    }

    @Test
    fun `the prompt's Add adds the entry through the favourite button's path`() = runTest(testDispatcher) {
        val manga = fixtureManga(favorite = false)
        coEvery { getDuplicateLibraryManga.invoke(manga) } returns emptyList()
        coEvery { getCategories.await() } returns emptyList()
        coEvery { updateManga.awaitUpdateFavorite(manga.id, true) } returns true
        val viewModel = settledDownloadScreen(manga, menuChapters())
        eventsAfter(viewModel) { it.download(DownloadAction.NEXT_1_CHAPTER) }

        val events = eventsAfter(viewModel) { it.addToLibraryFromPrompt() }

        coVerify(exactly = 1) { getDuplicateLibraryManga.invoke(manga) }
        coVerify(exactly = 1) { updateManga.awaitUpdateFavorite(manga.id, true) }
        events shouldContainExactly listOf(MangaDetailViewModel.Event.Snackbar(R.string.manga_added_to_library))
    }

    @Test
    fun `the prompt's Add on an entry with a duplicate asks about the duplicate first`() = runTest(testDispatcher) {
        val manga = fixtureManga(favorite = false)
        val duplicate = fixtureManga(id = 99L, favorite = true)
        coEvery { getDuplicateLibraryManga.invoke(manga) } returns
            listOf(MangaWithChapterCount(manga = duplicate, chapterCount = 3L))
        val viewModel = settledDownloadScreen(manga, menuChapters())

        viewModel.addToLibraryFromPrompt()
        advanceUntilIdle()

        val dialog = (viewModel.state.value as MangaDetailViewModel.State.Success).dialog
        (dialog as MangaDetailViewModel.Dialog.DuplicateManga).duplicates.single().manga shouldBe duplicate
        coVerify(exactly = 0) { updateManga.awaitUpdateFavorite(any(), any()) }
    }

    @Test
    fun `the prompt's Add on an entry added meanwhile leaves it in the library`() = runTest(testDispatcher) {
        val manga = fixtureManga(favorite = true)
        val viewModel = settledDownloadScreen(manga, menuChapters())

        viewModel.addToLibraryFromPrompt()
        advanceUntilIdle()

        coVerify(exactly = 0) { updateManga.awaitUpdateFavorite(any(), any()) }
    }

    /**
     * A new-chapters notification tapped after Clear database removed its entry, or any stale entry
     * intent (phase 4 unit missing-entry). The row is really missing here, in the real schema behind
     * the real repositories, because the crash was the repository's flow throwing on an absent row,
     * which no mock of [GetMangaWithChapters] can show.
     */
    @Test
    fun `an entry id with no row shows the not-found state instead of crashing`() = runTest(testDispatcher) {
        EntryDatabase(testDispatcher).use { database ->
            val viewModel = viewModel(
                424_242L,
                fromSource = false,
                getMangaWithChapters = database.getMangaWithChapters,
                getManga = database.getManga,
            )
            advanceUntilIdle()

            viewModel.state.value shouldBe MangaDetailViewModel.State.NotFound
            coVerify(exactly = 0) { setMangaDefaultChapterFlags.await(any()) }
            coVerify(exactly = 0) { syncChaptersWithSource.await(any(), any(), any(), any()) }
        }
    }

    /**
     * The row leaves while the view model lives: Clear database, reached through Settings, which
     * this screen opens itself from the tracking button when no tracker is signed in, deletes the
     * entry with the screen under it on the back stack. The screen turns to the not-found state
     * where it stands rather than crashing the process.
     */
    @Test
    fun `an entry whose row is deleted while the screen is open turns to the not-found state`() =
        runTest(testDispatcher) {
            EntryDatabase(testDispatcher).use { database ->
                val manga = database.manga()
                every { sourceManager.get(manga.source) } returns fixtureSource()
                coEvery { getNextChapters.await(manga.id) } returns emptyList()
                val viewModel = viewModel(
                    manga.id,
                    fromSource = false,
                    getMangaWithChapters = database.getMangaWithChapters,
                    getManga = database.getManga,
                )
                advanceUntilIdle()
                (viewModel.state.value as MangaDetailViewModel.State.Success).manga.id shouldBe manga.id

                // Clear database's own delete, which removes a row that is not in the library.
                database.mangaRepository.deleteNonLibraryMangaByIds(listOf(manga.id), keepReadManga = false)
                advanceUntilIdle()

                viewModel.state.value shouldBe MangaDetailViewModel.State.NotFound
            }
        }

    /**
     * R119 carry-over 2 (phase 4 unit BF). Clear database's delete notifies the manga and chapter
     * tables together, and the two flows re-query apart, so the chapter flow can answer with the
     * empty list the delete left before the manga flow answers null. `GetMangaWithChapters`'s
     * combine then pairs the old entry with no chapters, once, and a projection of an empty chapter
     * list starts the automatic refresh, whose failure became a stray snackbar under the not-found
     * state. The repositories here answer in that order: the row is gone for a read at once, the
     * chapter flow answers next, the manga flow last.
     */
    @Test
    fun `a row deleted under the open screen starts no refresh from the empty chapter list it leaves`() =
        runTest(testDispatcher) {
            val manga = fixtureManga(initialized = true)
            // The row as a read finds it, and the two flows' answers so far.
            val stored = MutableStateFlow<Manga?>(manga)
            val mangaRows = MutableStateFlow<Manga?>(manga)
            val chapterRows = MutableStateFlow(listOf(fixtureChapter()))
            val mangaRepository = mockk<MangaRepository> {
                every { getMangaByIdOrNullAsFlow(manga.id) } returns mangaRows
                coEvery { getMangaById(manga.id) } answers { checkNotNull(stored.value) { "ResultSet returned null" } }
            }
            val chapterRepository = mockk<ChapterRepository> {
                coEvery { getChapterByMangaIdAsFlow(manga.id, true) } returns chapterRows
            }
            val source = fixtureSource()
            every { sourceManager.get(manga.source) } returns source
            coEvery { getNextChapters.await(manga.id) } returns emptyList()
            coEvery { source.getMangaDetails(any()) } throws IOException("the entry's page is gone")
            val viewModel = viewModel(
                manga.id,
                fromSource = false,
                getMangaWithChapters = GetMangaWithChapters(mangaRepository, chapterRepository),
                getManga = GetManga(mangaRepository),
            )

            viewModel.events.test {
                advanceUntilIdle()
                (viewModel.state.value as MangaDetailViewModel.State.Success).chapters.size shouldBe 1

                // The delete removes the row and, by its foreign key, the chapters, in one statement.
                stored.value = null
                chapterRows.value = emptyList()
                advanceUntilIdle()
                mangaRows.value = null
                advanceUntilIdle()

                viewModel.state.value shouldBe MangaDetailViewModel.State.NotFound
                expectNoEvents()
            }
            coVerify(exactly = 0) { source.getMangaDetails(any()) }
        }
}

/**
 * The real schema, triggers and `:data` repositories on the in-memory JVM driver, whose queries and
 * flows run on [dispatcher] so the test's scheduler drives them. [manga] inserts one fetched entry,
 * not in the library, with one chapter, so opening it starts no automatic refresh.
 */
private class EntryDatabase(dispatcher: CoroutineDispatcher) : AutoCloseable {

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), Database.Schema.synchronous())

    private val database = run {
        driver.execute(null, "PRAGMA foreign_keys = ON", 0).value
        Database(
            driver = driver,
            chaptersAdapter = Chapters.Adapter(memoAdapter = MemoColumnAdapter),
            historyAdapter = History.Adapter(last_readAdapter = DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(
                genreAdapter = StringListColumnAdapter,
                update_strategyAdapter = UpdateStrategyColumnAdapter,
                memoAdapter = MemoColumnAdapter,
                genre_overrideAdapter = StringListColumnAdapter,
            ),
        )
    }

    private val handler = AndroidDatabaseHandler(database, dispatcher)
    val mangaRepository: MangaRepository = MangaRepositoryImpl(handler)
    private val chapterRepository: ChapterRepository = ChapterRepositoryImpl(handler)
    val getMangaWithChapters = GetMangaWithChapters(mangaRepository, chapterRepository)
    val getManga = GetManga(mangaRepository)

    suspend fun manga(): Manga {
        val row = mangaRepository.insertNetworkManga(
            listOf(Manga.create().copy(source = 10L, url = "fixture/1", title = "Fixture Manga", initialized = true)),
        ).single()
        chapterRepository.addAll(
            listOf(Chapter.create().copy(mangaId = row.id, url = "fixture/1/ch1", name = "Chapter 1")),
        )
        return mangaRepository.getMangaById(row.id)
    }

    override fun close() = driver.close()
}

/** What the edit dialog holds on Save: every detail edited (R165 item 4). */
private val EDIT = SetMangaDetailsOverride.Edit(
    title = "My title",
    description = "My text",
    author = "My author",
    artist = "My artist",
    status = MangaStatus.COMPLETED.code.toLong(),
    genres = listOf("Comedy"),
)

package com.shinsaikou.ui.library

import androidx.compose.ui.state.ToggleableState
import app.cash.turbine.test
import com.shinsaikou.core.common.preferences.BasePreferences
import com.shinsaikou.domain.chapter.interactor.GetScanlatorPriority
import com.shinsaikou.domain.chapter.interactor.SetReadStatus
import com.shinsaikou.domain.download.DownloadedChapters
import com.shinsaikou.domain.download.model.DownloadQueueEntry
import com.shinsaikou.domain.download.model.DownloadState
import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.domain.library.interactor.FilterLibrary
import com.shinsaikou.domain.library.interactor.GetLibraryItems
import com.shinsaikou.domain.library.interactor.SetManualLibraryOrder
import com.shinsaikou.domain.library.interactor.SortLibrary
import com.shinsaikou.domain.library.model.LibraryItem
import com.shinsaikou.domain.library.search.LibrarySearchMatcher
import com.shinsaikou.domain.manga.interactor.UpdateManga
import com.shinsaikou.domain.merged.interactor.MergeEntries
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.downloads.DownloadQueue
import com.shinsaikou.i18n.R
import com.shinsaikou.libraryupdate.LibraryUpdateError
import com.shinsaikou.libraryupdate.LibraryUpdateErrorStore
import com.shinsaikou.libraryupdate.LibraryUpdateScheduler
import com.shinsaikou.libraryupdate.LibraryUpdateWorker
import com.shinsaikou.source.CatalogueSource
import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceManager
import com.shinsaikou.source.StubSource
import com.shinsaikou.source.model.MangaStatus
import com.shinsaikou.tracking.FakeOAuthTracker
import com.shinsaikou.tracking.TrackerManager
import com.shinsaikou.ui.components.DownloadAction
import com.shinsaikou.ui.fixture.RecordingReadChapterCleanup
import com.shinsaikou.ui.fixture.deletingSetReadStatus
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetDisplayMode
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.interactor.SetSortModeForCategory
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.interactor.GetBookmarkedChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers

/**
 * Every test feeds the projection through the real `FilterLibrary` and `SortLibrary`, so an
 * assertion about a filter or a sort is an assertion about what the library screen would draw
 * rather than about a call being forwarded.
 *
 * Preferences are a real [LibraryPreferences] over the in-memory store, which emits on a write, so
 * the tests that turn a setting on and expect the rows to change are exercising the same path the
 * settings sheet uses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val libraryPreferences = LibraryPreferences(KeyedPreferenceStore())
    private val basePreferences = BasePreferences(InMemoryPreferenceStore())
    private val getLibraryItems = mockk<GetLibraryItems>()
    private val sourceManager = mockk<SourceManager>()
    private val libraryUpdateScheduler = mockk<LibraryUpdateScheduler>()

    /**
     * Mocked rather than real: the store's own `load()` hardcodes
     * `kotlinx.coroutines.Dispatchers.IO`, which a test scheduler cannot drive.
     */
    private val errorStore = mockk<LibraryUpdateErrorStore>()
    private val errorsFlow = MutableStateFlow<List<LibraryUpdateError>>(emptyList())
    private val getCategories = mockk<GetCategories>()
    private val setMangaCategories = mockk<SetMangaCategories>()
    private val setReadStatus = mockk<SetReadStatus>()
    private val updateManga = mockk<UpdateManga>()
    private val getNextChapters = mockk<GetNextChapters>()
    private val getScanlatorPriority = mockk<GetScanlatorPriority>()
    private val categoryRepository = mockk<CategoryRepository>(relaxed = true)
    private val setDisplayMode = SetDisplayMode(libraryPreferences)
    private val setSortModeForCategory = SetSortModeForCategory(libraryPreferences, categoryRepository)
    private val filterLibrary = FilterLibrary(LibrarySearchMatcher())
    private val sortLibrary = SortLibrary()
    private val downloadQueue = mockk<DownloadQueue>(relaxed = true)
    private val queueRows = MutableStateFlow<List<DownloadQueueEntry>>(emptyList())
    private val downloadedChapters = mockk<DownloadedChapters>()
    private val downloadedIds = mutableMapOf<Long, Set<Long>>()
    private val getBookmarkedChaptersByMangaId = mockk<GetBookmarkedChaptersByMangaId>()
    private val mergeEntries = mockk<MergeEntries>(relaxed = true)
    private var trackerManager = TrackerManager(emptySet())
    private val trackRows = MutableStateFlow<List<Track>>(emptyList())
    private val trackRepository = mockk<TrackRepository>().also {
        every { it.getTracksAsFlow() } returns trackRows
    }

    /**
     * The manual order's writes (phase 4 unit MS), each id list as `SetManualLibraryOrder` handed it
     * to the repository, which numbers the list from 1. [manualLibrary] feeds them back.
     */
    private val manualWrites = mutableListOf<List<Long>>()
    private val mangaRepository = mockk<MangaRepository>().also {
        coEvery { it.setManualPositions(any()) } answers { manualWrites += firstArg<List<Long>>() }
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        // Runs wait only in a start without extensions, which one test sets up.
        every { libraryUpdateScheduler.isPaused } returns false
        coEvery { errorStore.load() } returns Unit
        // Unranked unless a test ranks the title.
        coEvery { getScanlatorPriority.await(any()) } returns emptyMap()
        every { errorStore.errors } returns errorsFlow
        every { downloadQueue.subscribe() } returns queueRows
        every { downloadedChapters.subscribeByMangaId(any()) } answers {
            flowOf(downloadedIds[firstArg()].orEmpty())
        }
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- The projection ----

    @Test
    fun `the initial projection reaches state with the fed items and default badges`() = runTest(testDispatcher) {
        val items = listOf(fixtureItem(id = 1L, title = "Wandering Cartographer"))
        feed(items)

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.state.value.loading shouldBe false
        viewModel.state.value.visibleItems.map { it.item } shouldContainExactly items
        viewModel.state.value.total shouldBe 1
        val row = viewModel.state.value.visibleItems.single()
        row.unreadBadge shouldBe 7L
        row.downloadBadge shouldBe null
        row.languageBadge shouldBe null
        row.localBadge shouldBe false
    }

    @Test
    fun `writing a badge preference changes the projected row`() = runTest(testDispatcher) {
        feed(listOf(fixtureItem(id = 1L, title = "Wandering Cartographer", downloadCount = 3L)))

        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.state.value.visibleItems.single().downloadBadge shouldBe null

        libraryPreferences.unreadBadge.set(false)
        libraryPreferences.downloadBadge.set(true)
        advanceUntilIdle()

        val row = viewModel.state.value.visibleItems.single()
        row.unreadBadge shouldBe null
        row.downloadBadge shouldBe 3L
    }

    @Test
    fun `the local badge is projected, which needs isLocal off the row`() = runTest(testDispatcher) {
        feed(
            listOf(
                fixtureItem(id = 1L, title = "Wandering Cartographer", isLocal = true),
                fixtureItem(id = 2L, title = "Forest of Silence", isLocal = false),
            ),
        )

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.state.value.visibleItems.single { it.id == 1L }.localBadge shouldBe true
        viewModel.state.value.visibleItems.single { it.id == 2L }.localBadge shouldBe false
    }

    @Test
    fun `a search query filters the list to matching items`() = runTest(testDispatcher) {
        val wandering = fixtureItem(id = 1L, title = "Wandering Cartographer")
        val forest = fixtureItem(id = 2L, title = "Forest of Silence")
        feed(listOf(wandering, forest))

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.search("Wandering")
        advanceUntilIdle()

        viewModel.state.value.visibleItems.map { it.item } shouldContainExactly listOf(wandering)
        viewModel.state.value.query shouldBe "Wandering"
        // total counts the unfiltered feed, so it should not shrink alongside the filtered items.
        viewModel.state.value.total shouldBe 2
    }

    @Test
    fun `a query the parser cannot turn into a real term leaves every item in place`() = runTest(testDispatcher) {
        val wandering = fixtureItem(id = 1L, title = "Wandering Cartographer")
        val forest = fixtureItem(id = 2L, title = "Forest of Silence")
        feed(listOf(wandering, forest))

        val viewModel = viewModel()
        advanceUntilIdle()

        // A lone hyphen collapses to an empty AND group with nothing left to negate, which the
        // parser folds down to EmptyQueryNode. EmptyQueryNode always matches, so nothing is
        // filtered out even though the raw text does not name a real field or term.
        viewModel.search("-")
        advanceUntilIdle()

        // Sorted, not fed order: the default library sort is alphabetical by title, so Forest comes
        // first. Asserting the sorted order here also pins that default.
        viewModel.state.value.visibleItems.map { it.item } shouldContainExactly listOf(forest, wandering)
    }

    @Test
    fun `getCatalogueSources feeds a real source into the projection without changing the result`() =
        runTest(testDispatcher) {
            val item = fixtureItem(id = 1L, title = "Wandering Cartographer", source = 42L)
            feed(listOf(item))
            val catalogueSource = mockk<CatalogueSource> {
                every { id } returns 42L
                every { lang } returns "en"
                every { name } returns "Fixture Source"
                every { supportsLatest } returns true
            }
            every { sourceManager.getCatalogueSources() } returns flowOf(listOf(catalogueSource))

            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.state.value.visibleItems.map { it.item } shouldContainExactly listOf(item)
        }

    // ---- A source that is not installed (phase 4 unit MB) ----

    @Test
    fun `a plain search for a not-installed source's remembered name finds that source's entries`() =
        runTest(testDispatcher) {
            val gone = fixtureItem(id = 1L, title = "Wandering Cartographer", source = 77L)
            val installed = fixtureItem(id = 2L, title = "Forest of Silence", source = 1L)
            feed(listOf(gone, installed))
            every { sourceManager.getOrStub(77L) } returns StubSource(77L, "Old Harbor Archive", "en")

            val viewModel = viewModel()
            viewModel.search("old harbor archive")
            advanceUntilIdle()

            viewModel.state.value.visibleItems.map { it.id } shouldContainExactly listOf(1L)
        }

    @Test
    fun `a not-installed source's name found after the first projection reaches the search`() = runTest(testDispatcher) {
        feed(listOf(fixtureItem(id = 1L, title = "Wandering Cartographer", source = 77L)))
        var remembered: StubSource = StubSource.unknown(77L)
        every { sourceManager.getOrStub(77L) } answers { remembered }

        val viewModel = viewModel()
        viewModel.search("old harbor archive")
        advanceUntilIdle()
        viewModel.state.value.visibleItems shouldBe emptyList()

        // The registry's lookup of the remembered name ends, and its stub list emits.
        remembered = StubSource(77L, "Old Harbor Archive", "en")
        stubSources.value = listOf(remembered)
        advanceUntilIdle()

        viewModel.state.value.visibleItems.map { it.id } shouldContainExactly listOf(1L)
    }

    // ---- Categories ----

    @Test
    fun `rows are grouped by the categories they belong to`() = runTest(testDispatcher) {
        val reading = Category(id = 1L, name = "Reading", order = 0L, flags = 0L)
        val archive = Category(id = 2L, name = "Archive", order = 1L, flags = 0L)
        val wandering = fixtureItem(id = 1L, title = "Wandering Cartographer", categories = listOf(1L))
        val forest = fixtureItem(id = 2L, title = "Forest of Silence", categories = listOf(2L))
        // An entry can sit in two categories at once, and then it is a row in both.
        val both = fixtureItem(id = 3L, title = "Atlas of Small Roads", categories = listOf(1L, 2L))
        feed(listOf(wandering, forest, both), categories = listOf(DEFAULT_CATEGORY, reading, archive))

        val viewModel = viewModel()
        advanceUntilIdle()

        val state = viewModel.state.value
        state.items(1L).map { it.id } shouldContainExactly listOf(3L, 1L)
        state.items(2L).map { it.id } shouldContainExactly listOf(3L, 2L)
        // Counted once each, however many categories hold them.
        state.shown shouldBe 3
    }

    @Test
    fun `the default category is dropped when it holds nothing and another category exists`() =
        runTest(testDispatcher) {
            val reading = Category(id = 1L, name = "Reading", order = 0L, flags = 0L)
            feed(
                listOf(fixtureItem(id = 1L, title = "Wandering Cartographer", categories = listOf(1L))),
                categories = listOf(DEFAULT_CATEGORY, reading),
            )

            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.state.value.categories.map { it.id } shouldContainExactly listOf(1L)
        }

    @Test
    fun `the default category is kept when an entry is in no other one`() = runTest(testDispatcher) {
        val reading = Category(id = 1L, name = "Reading", order = 0L, flags = 0L)
        feed(
            listOf(
                fixtureItem(id = 1L, title = "Wandering Cartographer", categories = listOf(1L)),
                fixtureItem(id = 2L, title = "Forest of Silence", categories = listOf(0L)),
            ),
            categories = listOf(DEFAULT_CATEGORY, reading),
        )

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.state.value.categories.map { it.id } shouldContainExactly listOf(0L, 1L)
        viewModel.state.value.items(0L).map { it.id } shouldContainExactly listOf(2L)
    }

    @Test
    fun `the default category is kept when it is the only one, even with nothing in it`() =
        runTest(testDispatcher) {
            feed(emptyList(), categories = listOf(DEFAULT_CATEGORY))

            val viewModel = viewModel()
            advanceUntilIdle()

            // Otherwise the pager would have no page at all to show the empty library on.
            viewModel.state.value.categories.map { it.id } shouldContainExactly listOf(0L)
        }

    /** Phase 5 unit CT, item 6: a child's tab follows its parent's, and the parent names it. */
    @Test
    fun `a child category's tab follows its parent's and carries the parent, the pages grouped as before`() =
        runTest(testDispatcher) {
            val reading = Category(id = 1L, name = "Reading", order = 0L, flags = 0L)
            val archive = Category(id = 2L, name = "Archive", order = 1L, flags = 0L)
            val weekly = Category(id = 3L, name = "Weekly", order = 2L, flags = 0L, parentId = 1L)
            // In Weekly only: membership is flat, so the parent's page does not gather it.
            val wandering = fixtureItem(id = 1L, title = "Wandering Cartographer", categories = listOf(3L))
            val forest = fixtureItem(id = 2L, title = "Forest of Silence", categories = listOf(1L))
            feed(listOf(wandering, forest), categories = listOf(DEFAULT_CATEGORY, reading, archive, weekly))

            val viewModel = viewModel()
            advanceUntilIdle()

            val state = viewModel.state.value
            state.categories.map { it.id } shouldContainExactly listOf(1L, 3L, 2L)
            state.categoryParents shouldBe mapOf(3L to reading)
            state.items(1L).map { it.id } shouldContainExactly listOf(2L)
            state.items(3L).map { it.id } shouldContainExactly listOf(1L)
        }

    @Test
    fun `with no child category shown the tabs carry no parent`() = runTest(testDispatcher) {
        val reading = Category(id = 1L, name = "Reading", order = 0L, flags = 0L)
        val archive = Category(id = 2L, name = "Archive", order = 1L, flags = 0L)
        feed(
            listOf(fixtureItem(id = 1L, title = "Wandering Cartographer", categories = listOf(1L))),
            categories = listOf(DEFAULT_CATEGORY, reading, archive),
        )

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.state.value.categories.map { it.id } shouldContainExactly listOf(1L, 2L)
        viewModel.state.value.categoryParents shouldBe emptyMap()
    }

    @Test
    fun `a library change with the categories unchanged keeps the tab row's lists, so the row skips`() =
        runTest(testDispatcher) {
            val reading = Category(id = 1L, name = "Reading", order = 0L, flags = 0L)
            val weekly = Category(id = 3L, name = "Weekly", order = 1L, flags = 0L, parentId = 1L)
            val library = MutableStateFlow(listOf(fixtureItem(id = 1L, title = "Wandering Cartographer", categories = listOf(1L))))
            feed(library.value, categories = listOf(DEFAULT_CATEGORY, reading, weekly))
            every { getLibraryItems.subscribe() } returns library

            val viewModel = viewModel()
            advanceUntilIdle()
            val categories = viewModel.state.value.categories
            val parents = viewModel.state.value.categoryParents

            library.value = library.value + fixtureItem(id = 2L, title = "Forest of Silence", categories = listOf(3L))
            advanceUntilIdle()

            viewModel.state.value.items(3L).map { it.id } shouldContainExactly listOf(2L)
            viewModel.state.value.categories shouldBeSameInstanceAs categories
            viewModel.state.value.categoryParents shouldBeSameInstanceAs parents
        }

    @Test
    fun `the active category index is restored from the last used category`() = runTest(testDispatcher) {
        libraryPreferences.lastUsedCategory.set(2)
        feed(emptyList())

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.state.value.activeCategoryIndex shouldBe 2
    }

    @Test
    fun `moving to another category writes the index back`() = runTest(testDispatcher) {
        val reading = Category(id = 1L, name = "Reading", order = 0L, flags = 0L)
        val archive = Category(id = 2L, name = "Archive", order = 1L, flags = 0L)
        feed(
            listOf(
                fixtureItem(id = 1L, title = "Wandering Cartographer", categories = listOf(1L)),
                fixtureItem(id = 2L, title = "Forest of Silence", categories = listOf(2L)),
            ),
            categories = listOf(DEFAULT_CATEGORY, reading, archive),
        )

        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.state.value.activeCategory?.id shouldBe 1L

        viewModel.setActiveCategory(1)

        viewModel.state.value.activeCategoryIndex shouldBe 1
        viewModel.state.value.activeCategory?.id shouldBe 2L
        libraryPreferences.lastUsedCategory.get() shouldBe 1
        // Select all follows the page, so the bulk actions act on what is actually on screen.
        viewModel.selectAll()
        viewModel.state.value.selection shouldContainExactly setOf(2L)
    }

    // ---- Filters ----

    @Test
    fun `the downloaded filter keeps the entries that have a download`() = runTest(testDispatcher) {
        assertFilterKeeps(
            preference = libraryPreferences.filterDownloaded,
            matching = fixtureItem(id = 1L, title = "Wandering Cartographer", downloadCount = 2L),
            other = fixtureItem(id = 2L, title = "Forest of Silence", downloadCount = 0L),
        )
    }

    @Test
    fun `the unread filter keeps the entries with something left to read`() = runTest(testDispatcher) {
        assertFilterKeeps(
            preference = libraryPreferences.filterUnread,
            matching = fixtureItem(id = 1L, title = "Wandering Cartographer", totalChapters = 10L, readCount = 3L),
            other = fixtureItem(id = 2L, title = "Forest of Silence", totalChapters = 10L, readCount = 10L),
        )
    }

    @Test
    fun `the started filter keeps the entries that were opened`() = runTest(testDispatcher) {
        assertFilterKeeps(
            preference = libraryPreferences.filterStarted,
            matching = fixtureItem(id = 1L, title = "Wandering Cartographer", readCount = 1L),
            other = fixtureItem(id = 2L, title = "Forest of Silence", readCount = 0L),
        )
    }

    @Test
    fun `the bookmarked filter keeps the entries with a bookmark`() = runTest(testDispatcher) {
        assertFilterKeeps(
            preference = libraryPreferences.filterBookmarked,
            matching = fixtureItem(id = 1L, title = "Wandering Cartographer", bookmarkCount = 1L),
            other = fixtureItem(id = 2L, title = "Forest of Silence", bookmarkCount = 0L),
        )
    }

    @Test
    fun `the completed filter keeps the entries the source finished`() = runTest(testDispatcher) {
        assertFilterKeeps(
            preference = libraryPreferences.filterCompleted,
            matching = fixtureItem(
                id = 1L,
                title = "Wandering Cartographer",
                status = MangaStatus.COMPLETED.code.toLong(),
            ),
            other = fixtureItem(id = 2L, title = "Forest of Silence", status = MangaStatus.ONGOING.code.toLong()),
        )
    }

    @Test
    fun `the custom interval filter keeps the entries with an interval the owner set`() =
        runTest(testDispatcher) {
            libraryPreferences.customUpdateInterval.set(true)
            assertFilterKeeps(
                preference = libraryPreferences.filterIntervalCustom,
                // A negative fetch interval is how the domain records one the owner chose.
                matching = fixtureItem(id = 1L, title = "Wandering Cartographer", fetchInterval = -7),
                other = fixtureItem(id = 2L, title = "Forest of Silence", fetchInterval = 7),
            )
        }

    @Test
    fun `a stored custom interval filter is forced off while the experimental switch is off`() =
        runTest(testDispatcher) {
            feed(
                listOf(
                    fixtureItem(id = 1L, title = "Wandering Cartographer", fetchInterval = -7),
                    fixtureItem(id = 2L, title = "Forest of Silence", fetchInterval = 7),
                ),
            )
            libraryPreferences.filterIntervalCustom.set(TriState.ENABLED_IS)

            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.state.value.filters.intervalCustom shouldBe TriState.DISABLED
            viewModel.state.value.hasActiveFilters shouldBe false
            viewModel.state.value.visibleItems.map { it.id }.toSet() shouldBe setOf(1L, 2L)
        }

    @Test
    fun `turning the experimental switch on honours the stored custom interval filter without a new emission`() =
        runTest(testDispatcher) {
            feed(
                listOf(
                    fixtureItem(id = 1L, title = "Wandering Cartographer", fetchInterval = -7),
                    fixtureItem(id = 2L, title = "Forest of Silence", fetchInterval = 7),
                ),
            )
            libraryPreferences.filterIntervalCustom.set(TriState.ENABLED_IS)
            val viewModel = viewModel()
            advanceUntilIdle()

            libraryPreferences.customUpdateInterval.set(true)
            advanceUntilIdle()

            viewModel.state.value.filters.intervalCustom shouldBe TriState.ENABLED_IS
            viewModel.state.value.visibleItems.map { it.id } shouldContainExactly listOf(1L)

            libraryPreferences.customUpdateInterval.set(false)
            advanceUntilIdle()

            viewModel.state.value.filters.intervalCustom shouldBe TriState.DISABLED
            viewModel.state.value.visibleItems.map { it.id }.toSet() shouldBe setOf(1L, 2L)
        }

    @Test
    fun `the state's custom update interval follows the preference`() = runTest(testDispatcher) {
        feed(listOf(fixtureItem(id = 1L, title = "Wandering Cartographer")))
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.state.value.customUpdateInterval shouldBe false

        libraryPreferences.customUpdateInterval.set(true)
        advanceUntilIdle()

        viewModel.state.value.customUpdateInterval shouldBe true
    }

    @Test
    fun `setFilter writes the preference the sheet named`() = runTest(testDispatcher) {
        feed(listOf(fixtureItem(id = 1L, title = "Wandering Cartographer", bookmarkCount = 1L)))

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.setFilter(LibraryViewModel.LibraryFilter.BOOKMARKED, TriState.ENABLED_NOT)
        advanceUntilIdle()

        libraryPreferences.filterBookmarked.get() shouldBe TriState.ENABLED_NOT
        viewModel.state.value.filters.bookmarked shouldBe TriState.ENABLED_NOT
        viewModel.state.value.hasActiveFilters shouldBe true
        // ENABLED_NOT is "everything but", so the bookmarked entry is the one that goes.
        viewModel.state.value.isEmpty shouldBe true
    }

    // ---- Trackers (phase 3 unit T3) ----

    @Test
    fun `no signed-in tracker offers no tracker rows, and a stored tracker filter narrows nothing`() = runTest(testDispatcher) {
        trackerManager = TrackerManager(setOf(FakeOAuthTracker(1, loggedIn = false)))
        feed(
            listOf(
                fixtureItem(id = 1L, title = "Atlas of Small Roads"),
                fixtureItem(id = 2L, title = "Forest of Silence"),
            ),
        )
        trackRows.value = listOf(trackRow(mangaId = 1L, trackerId = 1L))
        libraryPreferences.filterTracking(1).set(TriState.ENABLED_IS)

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.state.value.signedInTrackers shouldBe emptyList()
        viewModel.state.value.hasActiveFilters shouldBe false
        viewModel.state.value.visibleItems.map { it.id } shouldContainExactly listOf(1L, 2L)
    }

    @Test
    fun `a signed-in tracker's filter narrows the library to its tracked entries, and off is not an active filter`() =
        runTest(testDispatcher) {
            val tracker = FakeOAuthTracker(1, name = "MyAnimeList", loggedIn = true)
            trackerManager = TrackerManager(setOf(tracker))
            feed(
                listOf(
                    fixtureItem(id = 1L, title = "Atlas of Small Roads"),
                    fixtureItem(id = 2L, title = "Forest of Silence"),
                ),
            )
            trackRows.value = listOf(trackRow(mangaId = 2L, trackerId = 1L))

            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.state.value.signedInTrackers shouldContainExactly
                listOf(LibraryViewModel.SignedInTracker(1L, "MyAnimeList"))
            viewModel.state.value.hasActiveFilters shouldBe false

            viewModel.setTrackerFilter(1L, TriState.ENABLED_IS)
            advanceUntilIdle()

            libraryPreferences.filterTracking(1).get() shouldBe TriState.ENABLED_IS
            viewModel.state.value.hasActiveFilters shouldBe true
            viewModel.state.value.visibleItems.map { it.id } shouldContainExactly listOf(2L)

            // A tracker signed out while its filter is on stops narrowing the library.
            tracker.loggedInState.value = false
            advanceUntilIdle()
            viewModel.state.value.signedInTrackers shouldBe emptyList()
            viewModel.state.value.hasActiveFilters shouldBe false
            viewModel.state.value.visibleItems.map { it.id } shouldContainExactly listOf(1L, 2L)
        }

    @Test
    fun `the tracker score sort puts an entry with no score below every scored one`() = runTest(testDispatcher) {
        trackerManager = TrackerManager(setOf(FakeOAuthTracker(1, loggedIn = true)))
        feed(
            listOf(
                fixtureItem(id = 1L, title = "Atlas of Small Roads"),
                fixtureItem(id = 2L, title = "Forest of Silence"),
                fixtureItem(id = 3L, title = "Harbor Lights"),
            ),
        )
        trackRows.value = listOf(trackRow(mangaId = 2L, trackerId = 1L, score = 7.0), trackRow(mangaId = 1L, trackerId = 1L))

        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.setSort(LibrarySort.Type.TrackerMean, LibrarySort.Direction.Descending)
        advanceUntilIdle()

        // Tracked but unscored counts as 0, which is still above an entry with no tracker at all; by
        // title alone Harbor Lights would come before Atlas of Small Roads.
        viewModel.state.value.visibleItems.map { it.id } shouldContainExactly listOf(2L, 1L, 3L)
    }

    @Test
    fun `a restored tracker score sort with no signed-in tracker sorts by title`() = runTest(testDispatcher) {
        trackerManager = TrackerManager(setOf(FakeOAuthTracker(1, loggedIn = false)))
        feed(
            listOf(
                fixtureItem(id = 1L, title = "Forest of Silence"),
                fixtureItem(id = 2L, title = "Atlas of Small Roads"),
            ),
        )
        // Scored, but by a signed-out tracker: were its score read, Atlas would come first.
        trackRows.value = listOf(trackRow(mangaId = 2L, trackerId = 1L, score = 9.0))
        libraryPreferences.sortingMode.set(LibrarySort(LibrarySort.Type.TrackerMean, LibrarySort.Direction.Descending))

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.state.value.visibleItems.map { it.id } shouldContainExactly listOf(1L, 2L)
    }

    // ---- The downloaded-only switch ----

    @Test
    fun `downloadedOnly forces the downloaded filter on`() = runTest(testDispatcher) {
        val downloaded = fixtureItem(id = 1L, title = "Wandering Cartographer", downloadCount = 4L)
        val notDownloaded = fixtureItem(id = 2L, title = "Forest of Silence", downloadCount = 0L)
        feed(listOf(downloaded, notDownloaded))

        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.state.value.visibleItems.map { it.id } shouldContainExactly listOf(2L, 1L)
        viewModel.state.value.filters.downloaded shouldBe TriState.DISABLED

        basePreferences.downloadedOnly.set(true)
        advanceUntilIdle()

        viewModel.state.value.downloadedOnly shouldBe true
        viewModel.state.value.filters.downloaded shouldBe TriState.ENABLED_IS
        viewModel.state.value.visibleItems.map { it.id } shouldContainExactly listOf(1L)
    }

    @Test
    fun `downloadedOnly with nothing downloaded empties the library, and says so`() = runTest(testDispatcher) {
        feed(
            listOf(
                fixtureItem(id = 1L, title = "Wandering Cartographer"),
                fixtureItem(id = 2L, title = "Forest of Silence"),
            ),
        )

        val viewModel = viewModel()
        advanceUntilIdle()

        basePreferences.downloadedOnly.set(true)
        advanceUntilIdle()

        // The empty state has to be the filtered one rather than "your library is empty": total is
        // what the screen reads to tell those two apart, and it counts the unfiltered feed.
        viewModel.state.value.isEmpty shouldBe true
        viewModel.state.value.total shouldBe 2
        viewModel.state.value.hasActiveFilters shouldBe true
    }

    // ---- Sort ----

    @Test
    fun `setting the sort direction reverses the projected order`() = runTest(testDispatcher) {
        feed(
            listOf(
                fixtureItem(id = 1L, title = "Atlas of Small Roads"),
                fixtureItem(id = 2L, title = "Forest of Silence"),
            ),
        )

        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.state.value.visibleItems.map { it.id } shouldContainExactly listOf(1L, 2L)

        viewModel.setSort(LibrarySort.Type.Alphabetical, LibrarySort.Direction.Descending)
        advanceUntilIdle()

        viewModel.state.value.visibleItems.map { it.id } shouldContainExactly listOf(2L, 1L)
        viewModel.state.value.sort shouldBe LibrarySort(LibrarySort.Type.Alphabetical, LibrarySort.Direction.Descending)
    }

    @Test
    fun `sorting by unread count orders by what the rows carry, not by title`() = runTest(testDispatcher) {
        feed(
            listOf(
                fixtureItem(id = 1L, title = "Atlas of Small Roads", totalChapters = 10L, readCount = 9L),
                fixtureItem(id = 2L, title = "Forest of Silence", totalChapters = 10L, readCount = 1L),
            ),
        )

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.setSort(LibrarySort.Type.UnreadCount, LibrarySort.Direction.Ascending)
        advanceUntilIdle()

        viewModel.state.value.visibleItems.map { it.id } shouldContainExactly listOf(1L, 2L)

        viewModel.setSort(LibrarySort.Type.UnreadCount, LibrarySort.Direction.Descending)
        advanceUntilIdle()

        viewModel.state.value.visibleItems.map { it.id } shouldContainExactly listOf(2L, 1L)
    }

    // ---- Display ----

    @Test
    fun `the display mode reaches state and the preference`() = runTest(testDispatcher) {
        feed(listOf(fixtureItem(id = 1L, title = "Wandering Cartographer")))

        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.state.value.displayMode shouldBe LibraryDisplayMode.default

        viewModel.setDisplayMode(LibraryDisplayMode.List)
        advanceUntilIdle()

        viewModel.state.value.displayMode shouldBe LibraryDisplayMode.List
        libraryPreferences.displayMode.get() shouldBe LibraryDisplayMode.List
    }

    @Test
    fun `the column count follows the orientation the screen reported`() = runTest(testDispatcher) {
        feed(listOf(fixtureItem(id = 1L, title = "Wandering Cartographer")))

        val viewModel = viewModel()
        advanceUntilIdle()
        // 0 is the stored default and means "as many as fit".
        viewModel.state.value.columns shouldBe 0

        viewModel.setColumns(3)
        advanceUntilIdle()
        viewModel.state.value.columns shouldBe 3
        libraryPreferences.portraitColumns.get() shouldBe 3

        viewModel.setLandscape(true)
        advanceUntilIdle()
        // The landscape count is its own preference, still at its default.
        viewModel.state.value.columns shouldBe 0

        viewModel.setColumns(6)
        advanceUntilIdle()
        viewModel.state.value.columns shouldBe 6
        libraryPreferences.landscapeColumns.get() shouldBe 6
        libraryPreferences.portraitColumns.get() shouldBe 3
    }

    @Test
    fun `the category tab and item count settings reach state`() = runTest(testDispatcher) {
        feed(listOf(fixtureItem(id = 1L, title = "Wandering Cartographer")))

        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.state.value.showCategoryTabs shouldBe true
        viewModel.state.value.showItemCount shouldBe false

        libraryPreferences.categoryTabs.set(false)
        libraryPreferences.categoryNumberOfItems.set(true)
        advanceUntilIdle()

        viewModel.state.value.showCategoryTabs shouldBe false
        viewModel.state.value.showItemCount shouldBe true
    }

    // ---- Continue reading ----

    @Test
    fun `continue reading opens the next unread chapter`() = runTest(testDispatcher) {
        feed(listOf(fixtureItem(id = 1L, title = "Wandering Cartographer")))
        coEvery { getNextChapters.await(1L, true) } returns listOf(chapterFixture(id = 99L))

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.continueReading(1L)
            advanceUntilIdle()

            awaitItem() shouldBe LibraryViewModel.Event.OpenReader(mangaId = 1L, chapterId = 99L)
        }
    }

    /**
     * Two copies of the next number, the first in reading order Group A's: the ranking sends the
     * button to Group B's (phase 4 unit SC), where the test above, unranked, gets the first copy.
     */
    @Test
    fun `continue reading on a ranked title opens the ranked group's copy of the next chapter`() =
        runTest(testDispatcher) {
            feed(listOf(fixtureItem(id = 1L, title = "Wandering Cartographer")))
            coEvery { getNextChapters.await(1L, true) } returns listOf(
                chapterFixture(id = 98L).copy(chapterNumber = 5.0, scanlator = "Group A"),
                chapterFixture(id = 99L).copy(chapterNumber = 5.0, scanlator = "Group B"),
                chapterFixture(id = 100L).copy(chapterNumber = 6.0, scanlator = "Group B"),
            )
            coEvery { getScanlatorPriority.await(1L) } returns mapOf("Group B" to 0L)

            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.events.test {
                viewModel.continueReading(1L)
                advanceUntilIdle()

                awaitItem() shouldBe LibraryViewModel.Event.OpenReader(mangaId = 1L, chapterId = 99L)
            }
        }

    @Test
    fun `continue reading with nothing unread says so instead of opening the reader`() =
        runTest(testDispatcher) {
            feed(listOf(fixtureItem(id = 1L, title = "Wandering Cartographer")))
            coEvery { getNextChapters.await(1L, true) } returns emptyList()

            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.events.test {
                viewModel.continueReading(1L)
                advanceUntilIdle()

                awaitItem() shouldBe LibraryViewModel.Event.Snackbar(R.string.chapter_no_next_unread)
            }
        }

    @Test
    fun `the continue reading button is offered only where there is something to read`() =
        runTest(testDispatcher) {
            feed(
                listOf(
                    fixtureItem(id = 1L, title = "Wandering Cartographer", totalChapters = 10L, readCount = 3L),
                    fixtureItem(id = 2L, title = "Forest of Silence", totalChapters = 10L, readCount = 10L),
                ),
            )
            libraryPreferences.showContinueReadingButton.set(true)

            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.state.value.visibleItems.single { it.id == 1L }.showContinueReading shouldBe true
            viewModel.state.value.visibleItems.single { it.id == 2L }.showContinueReading shouldBe false
        }

    // ---- The library update ----

    @Test
    fun `updateNow starts an update and emits the started snackbar`() = runTest(testDispatcher) {
        feed(emptyList())
        coEvery { libraryUpdateScheduler.startNow() } returns true

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.updateNow()
            advanceUntilIdle()

            awaitItem() shouldBe LibraryViewModel.Event.Snackbar(R.string.library_update_started)
        }
    }

    @Test
    fun `updateNow reporting an already running update emits the other snackbar`() = runTest(testDispatcher) {
        feed(emptyList())
        coEvery { libraryUpdateScheduler.startNow() } returns false

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.updateNow()
            advanceUntilIdle()

            awaitItem() shouldBe LibraryViewModel.Event.Snackbar(R.string.library_update_already_running)
        }
    }

    @Test
    fun `updateNow in a start without extensions says updates wait for a restart`() = runTest(testDispatcher) {
        feed(emptyList())
        every { libraryUpdateScheduler.isPaused } returns true
        coEvery { libraryUpdateScheduler.startNow() } returns false

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.updateNow()
            advanceUntilIdle()

            awaitItem() shouldBe LibraryViewModel.Event.Snackbar(R.string.library_update_paused)
        }
    }

    @Test
    fun `updateNow starts an update over every category, not the active one`() = runTest(testDispatcher) {
        feed(emptyList())
        coEvery { libraryUpdateScheduler.startNow(categoryId = null) } returns true

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.updateNow()
        advanceUntilIdle()

        coVerify(exactly = 1) { libraryUpdateScheduler.startNow(categoryId = null) }
    }

    @Test
    fun `updateCategory starts an update over the active category and emits the started snackbar`() =
        runTest(testDispatcher) {
            val reading = Category(id = 1L, name = "Reading", order = 0L, flags = 0L)
            feed(
                listOf(fixtureItem(id = 1L, title = "Wandering Cartographer", categories = listOf(1L))),
                categories = listOf(reading),
            )
            coEvery { libraryUpdateScheduler.startNow(categoryId = 1L) } returns true

            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.events.test {
                viewModel.updateCategory()
                advanceUntilIdle()

                awaitItem() shouldBe LibraryViewModel.Event.Snackbar(R.string.library_update_category_started)
            }
            coVerify(exactly = 1) { libraryUpdateScheduler.startNow(categoryId = 1L) }
        }

    @Test
    fun `updateCategory reporting an already running update emits the other snackbar`() = runTest(testDispatcher) {
        val reading = Category(id = 1L, name = "Reading", order = 0L, flags = 0L)
        feed(
            listOf(fixtureItem(id = 1L, title = "Wandering Cartographer", categories = listOf(1L))),
            categories = listOf(reading),
        )
        coEvery { libraryUpdateScheduler.startNow(categoryId = 1L) } returns false

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.updateCategory()
            advanceUntilIdle()

            awaitItem() shouldBe LibraryViewModel.Event.Snackbar(R.string.library_update_already_running)
        }
    }

    @Test
    fun `updateCategory targets the category of the tab moved to, not the one it started on`() =
        runTest(testDispatcher) {
            val reading = Category(id = 1L, name = "Reading", order = 0L, flags = 0L)
            val archive = Category(id = 2L, name = "Archive", order = 1L, flags = 0L)
            feed(
                listOf(
                    fixtureItem(id = 1L, title = "Wandering Cartographer", categories = listOf(1L)),
                    fixtureItem(id = 2L, title = "Forest of Silence", categories = listOf(2L)),
                ),
                categories = listOf(reading, archive),
            )
            coEvery { libraryUpdateScheduler.startNow(categoryId = 2L) } returns true

            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.state.value.activeCategory?.id shouldBe 1L

            viewModel.setActiveCategory(1)
            viewModel.state.value.activeCategory?.id shouldBe 2L

            viewModel.updateCategory()
            advanceUntilIdle()

            coVerify(exactly = 1) { libraryUpdateScheduler.startNow(categoryId = 2L) }
            coVerify(exactly = 0) { libraryUpdateScheduler.startNow(categoryId = 1L) }
        }

    @Test
    fun `updateCategory before the first projection has no category to name, so nothing starts`() =
        runTest(testDispatcher) {
            feed(emptyList())

            val viewModel = viewModel()
            // No advanceUntilIdle: the projection that would populate categories has not run yet.
            viewModel.state.value.activeCategory shouldBe null

            viewModel.updateCategory()
            advanceUntilIdle()

            coVerify(exactly = 0) { libraryUpdateScheduler.startNow(any(), any()) }
        }

    @Test
    fun `Update on a selection starts an update over the selected entries only`() = runTest(testDispatcher) {
        feed((1L..3L).map { fixtureItem(id = it, title = "Fixture $it") })
        coEvery { libraryUpdateScheduler.startNow(mangaIds = any()) } returns true

        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.toggleSelection(1L)
        viewModel.toggleSelection(3L)

        viewModel.events.test {
            viewModel.updateSelected()
            advanceUntilIdle()

            awaitItem() shouldBe LibraryViewModel.Event.Snackbar(R.string.library_update_entries_started)
        }
        coVerify(exactly = 1) { libraryUpdateScheduler.startNow(null, false, match { it.toSet() == setOf(1L, 3L) }) }
        viewModel.state.value.selection shouldBe emptySet()
    }

    @Test
    fun `Retry on the update errors starts an update over the failed entries`() = runTest(testDispatcher) {
        feed(emptyList())
        errorsFlow.value = listOf(
            updateError(4L, "Fixture 4", sourceId = 1L),
            updateError(9L, "Fixture 9", sourceId = 2L),
        )
        coEvery { libraryUpdateScheduler.startNow(mangaIds = any()) } returns false

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.retryUpdateErrors()
            advanceUntilIdle()

            awaitItem() shouldBe LibraryViewModel.Event.Snackbar(R.string.library_update_already_running)
        }
        coVerify(exactly = 1) { libraryUpdateScheduler.startNow(null, false, listOf(4L, 9L)) }
    }

    /** The ids travel in the run's input, which WorkManager caps, so a larger choice asks for fewer. */
    @Test
    fun `more chosen entries than a run can name start nothing and say how many it can`() = runTest(testDispatcher) {
        feed((1L..LibraryUpdateWorker.MAX_MANGA_IDS + 1L).map { fixtureItem(id = it, title = "Fixture $it") })

        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.selectAll()

        viewModel.events.test {
            viewModel.updateSelected()
            advanceUntilIdle()

            awaitItem() shouldBe LibraryViewModel.Event.Snackbar(
                R.string.library_update_too_many_format,
                listOf(LibraryUpdateWorker.MAX_MANGA_IDS),
            )
        }
        coVerify(exactly = 0) { libraryUpdateScheduler.startNow(any(), any(), any()) }
    }

    /**
     * More failed entries than a run can name: Retry takes the first of them. The rows of the
     * entries that run checks leave the front of the list, so the next Retry takes the rest.
     */
    @Test
    fun `Retry over more failed entries than a run can name updates as many as it can`() = runTest(testDispatcher) {
        feed(emptyList())
        errorsFlow.value = (1L..LibraryUpdateWorker.MAX_MANGA_IDS + 1L)
            .map { updateError(it, "Fixture $it", sourceId = 1L) }
        coEvery { libraryUpdateScheduler.startNow(mangaIds = any()) } returns true

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.retryUpdateErrors()
            advanceUntilIdle()

            awaitItem() shouldBe LibraryViewModel.Event.Snackbar(R.string.library_update_entries_started)
        }
        coVerify(exactly = 1) {
            libraryUpdateScheduler.startNow(null, false, (1L..LibraryUpdateWorker.MAX_MANGA_IDS).toList())
        }
    }

    // ---- The download menu (phase 3 unit AD) ----

    @Test
    fun `a next N download queues the next unread chapters not downloaded or queued, and starts`() =
        runTest(testDispatcher) {
            feed(listOf(fixtureItem(id = 1L, title = "Wandering Cartographer")))
            // Reading order, as GetNextChapters answers it: chapter 11 is downloaded and 12 queued.
            coEvery { getNextChapters.await(1L) } returns (11L..18L).map { chapterFixture(it, sourceOrder = 100 - it) }
            downloadedIds[1L] = setOf(11L)
            queueRows.value = listOf(queueEntry(chapterId = 12L, mangaId = 1L))

            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.toggleSelection(1L)
            viewModel.downloadSelected(DownloadAction.NEXT_5_CHAPTERS)
            viewModel.state.value.selection shouldBe emptySet()
            advanceUntilIdle()

            // The five after the two left out, queued in the order upstream's Downloader gives them.
            coVerify(exactly = 1) {
                downloadQueue.enqueue(
                    match { it.id == 1L },
                    match { chapters -> chapters.map { it.id } == listOf(13L, 14L, 15L, 16L, 17L) },
                    autoStart = true,
                )
            }
        }

    @Test
    fun `unread queues every one left, and bookmarked reads the bookmarked chapters instead`() =
        runTest(testDispatcher) {
            feed(
                listOf(
                    fixtureItem(id = 1L, title = "Wandering Cartographer"),
                    fixtureItem(id = 2L, title = "Paper Lanterns"),
                ),
            )
            coEvery { getNextChapters.await(1L) } returns (11L..13L).map { chapterFixture(it, sourceOrder = 100 - it) }
            coEvery { getNextChapters.await(2L) } returns emptyList()
            coEvery { getBookmarkedChaptersByMangaId.await(1L) } returns listOf(chapterFixture(21L), chapterFixture(22L))
            coEvery { getBookmarkedChaptersByMangaId.await(2L) } returns emptyList()
            downloadedIds[1L] = setOf(22L)

            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.toggleSelection(1L)
            viewModel.toggleSelection(2L)
            viewModel.downloadSelected(DownloadAction.UNREAD_CHAPTERS)
            advanceUntilIdle()

            coVerify(exactly = 1) {
                downloadQueue.enqueue(
                    match { it.id == 1L },
                    match { chapters -> chapters.map { it.id } == listOf(11L, 12L, 13L) },
                    autoStart = true,
                )
            }
            // An entry with nothing to queue is not handed to the queue at all.
            coVerify(exactly = 0) { downloadQueue.enqueue(match { it.id == 2L }, any(), any()) }

            viewModel.toggleSelection(1L)
            viewModel.downloadSelected(DownloadAction.BOOKMARKED_CHAPTERS)
            advanceUntilIdle()

            coVerify(exactly = 1) {
                downloadQueue.enqueue(
                    match { it.id == 1L },
                    match { chapters -> chapters.map { it.id } == listOf(21L) },
                    autoStart = true,
                )
            }
        }

    @Test
    fun `a selection holding a Local entry is offered no download, and one without is`() = runTest(testDispatcher) {
        feed(
            listOf(
                fixtureItem(id = 1L, title = "Wandering Cartographer"),
                fixtureItem(id = 2L, title = "Paper Lanterns", isLocal = true),
            ),
        )

        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.toggleSelection(1L)
        viewModel.state.value.selectionHasLocal shouldBe false

        viewModel.toggleSelection(2L)
        viewModel.state.value.selectionHasLocal shouldBe true
    }

    @Test
    fun `an entry a search has hidden is still downloaded with the rest of the selection`() =
        runTest(testDispatcher) {
            feed(
                listOf(
                    fixtureItem(id = 1L, title = "Wandering Cartographer"),
                    fixtureItem(id = 2L, title = "Paper Lanterns"),
                ),
            )
            coEvery { getNextChapters.await(any<Long>()) } answers { listOf(chapterFixture(firstArg<Long>() * 10)) }

            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.toggleSelection(1L)
            viewModel.toggleSelection(2L)
            viewModel.search("Lanterns")
            advanceUntilIdle()
            viewModel.state.value.visibleItems.map { it.id } shouldContainExactly listOf(2L)

            viewModel.downloadSelected(DownloadAction.NEXT_1_CHAPTER)
            advanceUntilIdle()

            coVerify(exactly = 1) { downloadQueue.enqueue(match { it.id == 1L }, any(), autoStart = true) }
            coVerify(exactly = 1) { downloadQueue.enqueue(match { it.id == 2L }, any(), autoStart = true) }
        }

    // ---- Selection ----

    @Test
    fun `toggling selection on an item turns on selection mode`() = runTest(testDispatcher) {
        feed(listOf(fixtureItem(id = 1L, title = "Wandering Cartographer")))

        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.state.value.selectionMode shouldBe false

        viewModel.toggleSelection(1L)

        viewModel.state.value.selectionMode shouldBe true
        viewModel.state.value.selection shouldContainExactly setOf(1L)

        viewModel.toggleSelection(1L)

        viewModel.state.value.selectionMode shouldBe false
        viewModel.state.value.selection shouldBe emptySet()
    }

    /**
     * Phase 4 unit MP1, R117 carry-over 3: upstream's `selectAll` adds the active category's entries to
     * the selection, so an owner can select across tabs (a source tab by tab, for a migration).
     */
    @Test
    fun `select all on a second tab keeps what was selected on the first`() = runTest(testDispatcher) {
        val viewModel = twoTabs()
        viewModel.toggleSelection(1L)

        viewModel.setActiveCategory(1)
        viewModel.selectAll()

        viewModel.state.value.selection shouldContainExactly setOf(1L, 3L, 4L)
    }

    @Test
    fun `invert selection flips the tab on screen and keeps what was selected on another`() = runTest(testDispatcher) {
        val viewModel = twoTabs()
        viewModel.toggleSelection(1L)
        viewModel.setActiveCategory(1)
        viewModel.toggleSelection(3L)

        viewModel.invertSelection()

        viewModel.state.value.selection shouldContainExactly setOf(1L, 4L)
    }

    @Test
    fun `a search still narrows what select all adds`() = runTest(testDispatcher) {
        val viewModel = twoTabs()
        viewModel.toggleSelection(1L)
        viewModel.setActiveCategory(1)
        viewModel.search("Lanterns")
        advanceUntilIdle()
        viewModel.state.value.visibleItems.map { it.id } shouldContainExactly listOf(4L)

        viewModel.selectAll()

        viewModel.state.value.selection shouldContainExactly setOf(1L, 4L)
    }

    /**
     * Upstream v0.20.4's range selection: a second long press selects every entry between it and the
     * selection, in the order the tab shows them (here the title sort), and one on a selected entry
     * deselects it alone. Before this a long press toggled one entry.
     */
    @Test
    fun `a second long press selects the entries between, in the order the tab shows them`() =
        runTest(testDispatcher) {
            val reading = Category(id = 1L, name = "Reading", order = 0L, flags = 0L)
            feed(
                listOf(
                    fixtureItem(id = 1L, title = "Echo", categories = listOf(1L)),
                    fixtureItem(id = 2L, title = "Alder", categories = listOf(1L)),
                    fixtureItem(id = 3L, title = "Cinder", categories = listOf(1L)),
                    fixtureItem(id = 4L, title = "Birch", categories = listOf(1L)),
                    fixtureItem(id = 5L, title = "Dune", categories = listOf(1L)),
                ),
                categories = listOf(DEFAULT_CATEGORY, reading),
            )
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.state.value.visibleItems.map { it.id } shouldContainExactly listOf(2L, 4L, 3L, 5L, 1L)

            viewModel.longPressEntry(categoryId = 1L, mangaId = 4L)
            viewModel.state.value.selection shouldContainExactly setOf(4L)

            viewModel.longPressEntry(categoryId = 1L, mangaId = 1L)
            viewModel.state.value.selection shouldContainExactly setOf(4L, 3L, 5L, 1L)

            viewModel.longPressEntry(categoryId = 1L, mangaId = 3L)
            viewModel.state.value.selection shouldContainExactly setOf(4L, 5L, 1L)
        }

    @Test
    fun `a range on one tab keeps what was selected on another`() = runTest(testDispatcher) {
        val viewModel = twoTabs()
        viewModel.longPressEntry(categoryId = 1L, mangaId = 1L)

        viewModel.setActiveCategory(1)
        viewModel.longPressEntry(categoryId = 2L, mangaId = 4L)
        viewModel.state.value.selection shouldContainExactly setOf(1L, 4L)

        viewModel.longPressEntry(categoryId = 2L, mangaId = 3L)
        viewModel.state.value.selection shouldContainExactly setOf(1L, 3L, 4L)
    }

    /** Tab 0 (Reading) holds entries 1 and 2, tab 1 (Archive) entries 3 and 4; tab 0 is on screen. */
    private fun TestScope.twoTabs(): LibraryViewModel {
        val reading = Category(id = 1L, name = "Reading", order = 0L, flags = 0L)
        val archive = Category(id = 2L, name = "Archive", order = 1L, flags = 0L)
        feed(
            listOf(
                fixtureItem(id = 1L, title = "Wandering Cartographer", categories = listOf(1L)),
                fixtureItem(id = 2L, title = "Forest of Silence", categories = listOf(1L)),
                fixtureItem(id = 3L, title = "Old Harbor Archive", categories = listOf(2L)),
                fixtureItem(id = 4L, title = "Lanterns at Dusk", categories = listOf(2L)),
            ),
            categories = listOf(DEFAULT_CATEGORY, reading, archive),
        )
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.state.value.activeCategory?.id shouldBe 1L
        return viewModel
    }

    @Test
    fun `marking the selection read updates every selected entry and clears the selection`() =
        runTest(testDispatcher) {
            feed(
                listOf(
                    fixtureItem(id = 1L, title = "Wandering Cartographer"),
                    fixtureItem(id = 2L, title = "Forest of Silence"),
                ),
            )
            coEvery { setReadStatus.await(any<Long>(), any<Boolean>()) } returns SetReadStatus.Result.Success(emptyList())

            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.toggleSelection(1L)
            viewModel.toggleSelection(2L)

            viewModel.markSelectedRead(true)
            advanceUntilIdle()

            coVerify(exactly = 1) { setReadStatus.await(1L, true) }
            coVerify(exactly = 1) { setReadStatus.await(2L, true) }
            viewModel.state.value.selectionMode shouldBe false
        }

    /** Phase 3 unit DR: the selection's mark read, through the real interactor, reaches the delete. */
    @Test
    fun `marking the selection read with after manually marked as read on hands the chapters it wrote to the download cleanup`() =
        runTest(testDispatcher) {
            feed(listOf(fixtureItem(id = 1L, title = "Wandering Cartographer")))
            val chapterRepository = mockk<ChapterRepository>(relaxed = true)
            coEvery { chapterRepository.getChapterByMangaId(1L, any()) } returns
                listOf(chapterFixture(10L), chapterFixture(11L).copy(read = true), chapterFixture(12L))
            val cleanup = RecordingReadChapterCleanup()

            val viewModel = viewModel(setReadStatus = deletingSetReadStatus(chapterRepository, cleanup))
            advanceUntilIdle()
            viewModel.toggleSelection(1L)
            viewModel.markSelectedRead(true)
            advanceUntilIdle()

            cleanup.marked shouldBe listOf(listOf(10L, 12L))
        }

    @Test
    fun `marking read with nothing selected leaves the collaborator untouched`() = runTest(testDispatcher) {
        feed(emptyList())

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.markSelectedRead(true)
        advanceUntilIdle()

        coVerify(exactly = 0) { setReadStatus.await(any<Long>(), any<Boolean>()) }
    }

    @Test
    fun `moving the selection to a category writes the chosen category to every selected entry`() =
        runTest(testDispatcher) {
            val category = Category(id = 5L, name = "Favourites", order = 0L, flags = 0L)
            feed(
                listOf(
                    fixtureItem(id = 1L, title = "Wandering Cartographer"),
                    fixtureItem(id = 2L, title = "Forest of Silence"),
                ),
            )
            coEvery { getCategories.await() } returns listOf(category)
            coEvery { setMangaCategories.await(any(), any()) } returns Unit

            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.toggleSelection(1L)
            viewModel.toggleSelection(2L)

            viewModel.askMoveToCategories()
            advanceUntilIdle()

            val dialog = viewModel.state.value.dialog as LibraryViewModel.Dialog.ChangeCategory
            val row = dialog.rows.single { it.id == 5L }

            viewModel.moveSelectedToCategories(listOf(row.copy(state = ToggleableState.On)))
            advanceUntilIdle()

            coVerify(exactly = 1) { setMangaCategories.await(1L, listOf(5L)) }
            coVerify(exactly = 1) { setMangaCategories.await(2L, listOf(5L)) }
            viewModel.state.value.selectionMode shouldBe false
            viewModel.state.value.dialog shouldBe null
        }

    @Test
    fun `moving the selection preserves an indeterminate category the owner never resolved`() =
        runTest(testDispatcher) {
            // manga1 already belongs to Favourites and Old; manga2 belongs only to Old. That makes
            // Favourites indeterminate across the selection and Old fully applied. The owner only
            // acts on Archive (turns it on) and Old (turns it off), leaving the Favourites row
            // exactly as the dialog found it. Writing the checked rows as each entry's whole
            // category list, which is the naive shape the production KDoc calls out, would drop
            // Favourites from manga1 even though the owner never touched that row.
            val favourites = Category(id = 5L, name = "Favourites", order = 0L, flags = 0L)
            val archive = Category(id = 6L, name = "Archive", order = 1L, flags = 0L)
            val old = Category(id = 7L, name = "Old", order = 2L, flags = 0L)
            feed(
                listOf(
                    fixtureItem(id = 1L, title = "Wandering Cartographer", categories = listOf(5L, 7L)),
                    fixtureItem(id = 2L, title = "Forest of Silence", categories = listOf(7L)),
                ),
                categories = listOf(DEFAULT_CATEGORY, favourites, archive, old),
            )
            coEvery { getCategories.await() } returns listOf(favourites, archive, old)
            coEvery { setMangaCategories.await(any(), any()) } returns Unit

            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.toggleSelection(1L)
            viewModel.toggleSelection(2L)

            viewModel.askMoveToCategories()
            advanceUntilIdle()

            val dialog = viewModel.state.value.dialog as LibraryViewModel.Dialog.ChangeCategory
            dialog.rows.single { it.id == 5L }.state shouldBe ToggleableState.Indeterminate
            dialog.rows.single { it.id == 6L }.state shouldBe ToggleableState.Off
            dialog.rows.single { it.id == 7L }.state shouldBe ToggleableState.On

            val resolved = listOf(
                dialog.rows.single { it.id == 5L }, // left indeterminate, untouched
                dialog.rows.single { it.id == 6L }.copy(state = ToggleableState.On),
                dialog.rows.single { it.id == 7L }.copy(state = ToggleableState.Off),
            )

            viewModel.moveSelectedToCategories(resolved)
            advanceUntilIdle()

            // manga1 keeps Favourites (untouched), gains Archive, loses Old.
            coVerify(exactly = 1) { setMangaCategories.await(1L, listOf(5L, 6L)) }
            // manga2 never had Favourites, gains Archive, loses Old.
            coVerify(exactly = 1) { setMangaCategories.await(2L, listOf(6L)) }
        }

    // ---- Merged titles (R163): the main entry is the row, the writes reach every member ----

    private fun member(id: Long, source: Long = id * 10) =
        Manga.create().copy(id = id, source = source, title = "Member $id")

    /** Title 1 is merged from entries 1 (main), 2 and 3; entry 9 stands alone. */
    private fun feedMergedTitle(mergedEntries: Boolean = true, categories: List<Long> = listOf(5L)) {
        libraryPreferences.mergedEntries.set(mergedEntries)
        feed(
            listOf(
                fixtureItem(id = 1L, title = "Merged title", categories = categories, members = listOf(member(1), member(2), member(3))),
                fixtureItem(id = 9L, title = "Alone"),
            ),
            categories = listOf(DEFAULT_CATEGORY, Category(id = 5L, name = "Favourites", order = 0L, flags = 0L)),
        )
    }

    @Test
    fun `marking a merged title read marks every member, and an entry on its own as before`() = runTest(testDispatcher) {
        feedMergedTitle()
        coEvery { setReadStatus.await(any<Long>(), any<Boolean>()) } returns SetReadStatus.Result.Success(emptyList())
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.toggleSelection(1L)
        viewModel.toggleSelection(9L)

        viewModel.markSelectedRead(true)
        advanceUntilIdle()

        listOf(1L, 2L, 3L, 9L).forEach { coVerify(exactly = 1) { setReadStatus.await(it, true) } }
    }

    @Test
    fun `removing a merged title takes every member out of the library`() = runTest(testDispatcher) {
        feedMergedTitle()
        coEvery { updateManga.awaitUpdateFavorite(any(), any()) } returns true
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.toggleSelection(1L)
        viewModel.toggleSelection(9L)

        viewModel.askRemoveSelected()
        viewModel.removeSelectedFromLibrary()
        advanceUntilIdle()

        listOf(1L, 2L, 3L, 9L).forEach { coVerify(exactly = 1) { updateManga.awaitUpdateFavorite(it, false) } }
        viewModel.state.value.selection shouldBe emptySet()
        viewModel.state.value.dialog shouldBe null
    }

    @Test
    fun `the remove question counts the merged titles among the selection`() = runTest(testDispatcher) {
        feedMergedTitle()
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.toggleSelection(1L)
        viewModel.toggleSelection(9L)

        viewModel.askRemoveSelected()

        viewModel.state.value.dialog shouldBe LibraryViewModel.Dialog.RemoveSelected(count = 2, mergedTitles = 1)
    }

    @Test
    fun `the remove question of entries on their own has no merged titles`() = runTest(testDispatcher) {
        feedMergedTitle()
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.toggleSelection(9L)

        viewModel.askRemoveSelected()

        viewModel.state.value.dialog shouldBe LibraryViewModel.Dialog.RemoveSelected(count = 1, mergedTitles = 0)
    }

    @Test
    fun `Update on a merged title starts a run over every member`() = runTest(testDispatcher) {
        feedMergedTitle()
        coEvery { libraryUpdateScheduler.startNow(mangaIds = any()) } returns true
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.toggleSelection(1L)
        viewModel.toggleSelection(9L)

        viewModel.updateSelected()
        advanceUntilIdle()

        coVerify(exactly = 1) { libraryUpdateScheduler.startNow(null, false, listOf(1L, 2L, 3L, 9L)) }
    }

    @Test
    fun `changing the category of a merged title writes the answer to every member`() = runTest(testDispatcher) {
        feedMergedTitle()
        val archive = Category(id = 6L, name = "Archive", order = 1L, flags = 0L)
        coEvery { getCategories.await() } returns listOf(Category(id = 5L, name = "Favourites", order = 0L, flags = 0L), archive)
        coEvery { setMangaCategories.await(any(), any()) } returns Unit
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.toggleSelection(1L)
        viewModel.askMoveToCategories()
        advanceUntilIdle()
        val dialog = viewModel.state.value.dialog as LibraryViewModel.Dialog.ChangeCategory

        viewModel.moveSelectedToCategories(listOf(dialog.rows.single { it.id == 6L }.copy(state = ToggleableState.On)))
        advanceUntilIdle()

        // The title shows in the categories of its members, so each member takes the whole answer.
        listOf(1L, 2L, 3L).forEach { coVerify(exactly = 1) { setMangaCategories.await(it, listOf(5L, 6L)) } }
    }

    @Test
    fun `the category picker reads a merged title's categories, and any member counts as in the library`() =
        runTest(testDispatcher) {
            feedMergedTitle(categories = listOf(5L, 6L))

            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.state.value.categoriesByManga shouldBe mapOf(
                1L to listOf(5L, 6L),
                2L to listOf(5L, 6L),
                3L to listOf(5L, 6L),
                9L to listOf(0L),
            )
        }

    @Test
    fun `Merge is offered on two selected entries with the switch on, and not otherwise`() = runTest(testDispatcher) {
        feedMergedTitle(mergedEntries = true)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.toggleSelection(1L)
        viewModel.state.value.canMerge shouldBe false
        viewModel.toggleSelection(9L)
        viewModel.state.value.canMerge shouldBe true

        libraryPreferences.mergedEntries.set(false)
        advanceUntilIdle()
        viewModel.state.value.canMerge shouldBe false
    }

    @Test
    fun `the merge dialog lists the selection in the order it was selected, with each source`() = runTest(testDispatcher) {
        libraryPreferences.mergedEntries.set(true)
        feed(
            listOf(
                fixtureItem(id = 1L, title = "First", source = 10L),
                fixtureItem(id = 2L, title = "Second", source = 20L),
                fixtureItem(id = 3L, title = "Third", source = 30L),
            ),
        )
        every { sourceManager.getOrStub(any()) } answers { StubSource(id = firstArg(), name = "Source ${firstArg<Long>()}", lang = "") }
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.toggleSelection(3L)
        viewModel.toggleSelection(1L)
        viewModel.toggleSelection(2L)

        viewModel.askMergeSelected()
        advanceUntilIdle()

        val dialog = viewModel.state.value.dialog as LibraryViewModel.Dialog.MergeSelected
        dialog.entries shouldContainExactly listOf(
            LibraryViewModel.MergeChoice(3L, "Third", "Source 30"),
            LibraryViewModel.MergeChoice(1L, "First", "Source 10"),
            LibraryViewModel.MergeChoice(2L, "Second", "Source 20"),
        )
    }

    @Test
    fun `the merge dialog is not opened for one entry or with the switch off`() = runTest(testDispatcher) {
        feedMergedTitle(mergedEntries = false)
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.toggleSelection(1L)
        viewModel.toggleSelection(9L)

        viewModel.askMergeSelected()
        advanceUntilIdle()

        viewModel.state.value.dialog shouldBe null
    }

    @Test
    fun `the merge dialog is not opened when fewer than two selected entries are still in the library`() =
        runTest(testDispatcher) {
            libraryPreferences.mergedEntries.set(true)
            val items = MutableStateFlow(
                listOf(fixtureItem(id = 1L, title = "First"), fixtureItem(id = 2L, title = "Second")),
            )
            feed(items.value)
            every { getLibraryItems.subscribe() } returns items
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.toggleSelection(1L)
            viewModel.toggleSelection(2L)
            viewModel.state.value.canMerge shouldBe true

            items.value = listOf(fixtureItem(id = 1L, title = "First"))
            advanceUntilIdle()
            viewModel.askMergeSelected()
            advanceUntilIdle()

            viewModel.state.value.dialog shouldBe null
        }

    @Test
    fun `a merge that fails closes the dialog, keeps the selection and says so`() = runTest(testDispatcher) {
        libraryPreferences.mergedEntries.set(true)
        feed(listOf(fixtureItem(id = 1L, title = "First"), fixtureItem(id = 2L, title = "Second")))
        coEvery { mergeEntries.await(any(), any()) } throws IllegalStateException("db busy")
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.toggleSelection(1L)
        viewModel.toggleSelection(2L)
        viewModel.askMergeSelected()
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.mergeSelected(mainId = 1L)
            advanceUntilIdle()

            awaitItem() shouldBe LibraryViewModel.Event.Snackbar(R.string.merged_failed)
            expectNoEvents()
        }
        viewModel.state.value.dialog shouldBe null
        viewModel.state.value.selection shouldBe setOf(1L, 2L)
    }

    @Test
    fun `a cancelled merge is rethrown and not reported`() = runTest(testDispatcher) {
        libraryPreferences.mergedEntries.set(true)
        feed(listOf(fixtureItem(id = 1L, title = "First"), fixtureItem(id = 2L, title = "Second")))
        coEvery { mergeEntries.await(any(), any()) } throws CancellationException("cancelled")
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.toggleSelection(1L)
        viewModel.toggleSelection(2L)
        viewModel.askMergeSelected()
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.mergeSelected(mainId = 1L)
            advanceUntilIdle()

            expectNoEvents()
        }
        viewModel.state.value.selection shouldBe setOf(1L, 2L)
    }

    @Test
    fun `confirming the merge hands the chosen main entry and the selection order to the interactor`() =
        runTest(testDispatcher) {
            libraryPreferences.mergedEntries.set(true)
            feed(listOf(fixtureItem(id = 1L, title = "First"), fixtureItem(id = 2L, title = "Second"), fixtureItem(id = 3L, title = "Third")))
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.toggleSelection(2L)
            viewModel.toggleSelection(3L)
            viewModel.toggleSelection(1L)
            viewModel.askMergeSelected()
            advanceUntilIdle()

            viewModel.mergeSelected(mainId = 3L)
            advanceUntilIdle()

            coVerify(exactly = 1) { mergeEntries.await(3L, listOf(2L, 3L, 1L)) }
            viewModel.state.value.selection shouldBe emptySet()
            viewModel.state.value.dialog shouldBe null
        }

    @Test
    fun `confirming a merge with no merge dialog open merges nothing`() = runTest(testDispatcher) {
        feedMergedTitle()
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.mergeSelected(mainId = 1L)
        advanceUntilIdle()

        coVerify(exactly = 0) { mergeEntries.await(any(), any()) }
    }

    @Test
    fun `downloading a merged title reads its merged list and queues each chapter under its owner`() =
        runTest(testDispatcher) {
            feedMergedTitle()
            coEvery { getNextChapters.await(1L) } returns listOf(
                chapterFixture(11L, sourceOrder = 3L).copy(mangaId = 1L),
                chapterFixture(21L, sourceOrder = 3L).copy(mangaId = 2L),
                chapterFixture(22L, sourceOrder = 2L).copy(mangaId = 2L),
                chapterFixture(31L, sourceOrder = 1L).copy(mangaId = 3L),
            )
            downloadedIds[3L] = setOf(31L)
            queueRows.value = listOf(queueEntry(chapterId = 11L, mangaId = 1L))
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.toggleSelection(1L)

            viewModel.downloadSelected(DownloadAction.UNREAD_CHAPTERS)
            advanceUntilIdle()

            // 11 is queued already and 31 is downloaded; entry 2's two go under entry 2, source order falling.
            coVerify(exactly = 1) {
                downloadQueue.enqueue(match { it.id == 2L }, match { list -> list.map { it.id } == listOf(21L, 22L) }, autoStart = true)
            }
            coVerify(exactly = 0) { downloadQueue.enqueue(match { it.id == 1L }, any(), any()) }
            coVerify(exactly = 0) { downloadQueue.enqueue(match { it.id == 3L }, any(), any()) }
        }

    @Test
    fun `downloading a merged title's bookmarked chapters reads every member's`() = runTest(testDispatcher) {
        feedMergedTitle()
        coEvery { getBookmarkedChaptersByMangaId.await(1L) } returns emptyList()
        coEvery { getBookmarkedChaptersByMangaId.await(2L) } returns listOf(chapterFixture(21L).copy(mangaId = 2L))
        coEvery { getBookmarkedChaptersByMangaId.await(3L) } returns listOf(chapterFixture(31L).copy(mangaId = 3L))
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.toggleSelection(1L)

        viewModel.downloadSelected(DownloadAction.BOOKMARKED_CHAPTERS)
        advanceUntilIdle()

        coVerify(exactly = 1) { downloadQueue.enqueue(match { it.id == 2L }, match { l -> l.map { it.id } == listOf(21L) }, autoStart = true) }
        coVerify(exactly = 1) { downloadQueue.enqueue(match { it.id == 3L }, match { l -> l.map { it.id } == listOf(31L) }, autoStart = true) }
    }

    @Test
    fun `a Local member's chapters are not queued in a merged title's download`() = runTest(testDispatcher) {
        libraryPreferences.mergedEntries.set(true)
        feed(
            listOf(
                fixtureItem(
                    id = 1L,
                    title = "Merged title",
                    members = listOf(member(1), member(2, source = com.shinsaikou.source.SourceIds.LOCAL)),
                ),
            ),
        )
        coEvery { getNextChapters.await(1L) } returns listOf(
            chapterFixture(11L).copy(mangaId = 1L),
            chapterFixture(21L).copy(mangaId = 2L),
        )
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.toggleSelection(1L)

        viewModel.downloadSelected(DownloadAction.UNREAD_CHAPTERS)
        advanceUntilIdle()

        coVerify(exactly = 1) { downloadQueue.enqueue(match { it.id == 1L }, any(), autoStart = true) }
        coVerify(exactly = 0) { downloadQueue.enqueue(match { it.id == 2L }, any(), any()) }
    }

    @Test
    fun `continue reading on a merged title opens the next chapter of the merged list, whichever member has it`() =
        runTest(testDispatcher) {
            feedMergedTitle()
            coEvery { getNextChapters.await(1L, true) } returns listOf(chapterFixture(id = 31L).copy(mangaId = 3L))
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.events.test {
                viewModel.continueReading(1L)
                advanceUntilIdle()

                awaitItem() shouldBe LibraryViewModel.Event.OpenReader(mangaId = 1L, chapterId = 31L)
            }
        }

    @Test
    fun `with the switch off an entry's selection acts on that entry alone`() = runTest(testDispatcher) {
        // The fold is the interactor's; with the switch off it hands the view model no members.
        libraryPreferences.mergedEntries.set(false)
        feed(listOf(fixtureItem(id = 1L, title = "One"), fixtureItem(id = 2L, title = "Two")))
        coEvery { setReadStatus.await(any<Long>(), any<Boolean>()) } returns SetReadStatus.Result.Success(emptyList())
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.toggleSelection(1L)

        viewModel.markSelectedRead(true)
        advanceUntilIdle()

        coVerify(exactly = 1) { setReadStatus.await(1L, true) }
        coVerify(exactly = 0) { setReadStatus.await(2L, any<Boolean>()) }
    }

    // ---- Manual sort (phase 4 unit MS) ----

    @Test
    fun `a drag in one tab moves the other tab only through the entry the two share`() = runTest(testDispatcher) {
        val reading = Category(id = 1L, name = "Reading", order = 0L, flags = 0L)
        val archive = Category(id = 2L, name = "Archive", order = 1L, flags = 0L)
        manualLibrary(
            listOf(
                fixtureItem(id = 1L, title = "Anvil", categories = listOf(1L), manualPosition = 1L),
                fixtureItem(id = 2L, title = "Bellows", categories = listOf(1L, 2L), manualPosition = 2L),
                fixtureItem(id = 3L, title = "Chisel", categories = listOf(2L), manualPosition = 3L),
                fixtureItem(id = 4L, title = "Drill", categories = listOf(1L), manualPosition = 4L),
                fixtureItem(id = 5L, title = "Easel", categories = listOf(2L), manualPosition = 5L),
            ),
            categories = listOf(reading, archive),
        )
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.tab(1L) shouldContainExactly listOf(1L, 2L, 4L)
        viewModel.tab(2L) shouldContainExactly listOf(2L, 3L, 5L)

        // Drill to the top of Reading. Reading's entries hold 1, 2 and 4, and take them in the new
        // order; Chisel keeps 3 between them, and Easel, past the last change, is not written.
        viewModel.drag(categoryId = 1L, mangaId = 4L, toIndex = 0)
        advanceUntilIdle()

        manualWrites shouldContainExactly listOf(listOf(4L, 1L, 3L, 2L))
        viewModel.tab(1L) shouldContainExactly listOf(4L, 1L, 2L)
        // Archive's own entries keep their positions and their order; the shared Bellows moved.
        viewModel.tab(2L) shouldContainExactly listOf(3L, 2L, 5L)
        viewModel.positions() shouldBe mapOf(1L to 2L, 2L to 4L, 3L to 3L, 4L to 1L, 5L to 5L)
    }

    @Test
    fun `a tab's unranked entries take the positions after the library's last one`() = runTest(testDispatcher) {
        val reading = Category(id = 1L, name = "Reading", order = 0L, flags = 0L)
        val archive = Category(id = 2L, name = "Archive", order = 1L, flags = 0L)
        manualLibrary(
            listOf(
                fixtureItem(id = 1L, title = "Anvil", categories = listOf(1L), manualPosition = 1L),
                fixtureItem(id = 2L, title = "Bellows", categories = listOf(1L)),
                fixtureItem(id = 3L, title = "Chisel", categories = listOf(2L), manualPosition = 2L),
                fixtureItem(id = 4L, title = "Drill", categories = listOf(1L)),
                fixtureItem(id = 5L, title = "Easel", categories = listOf(2L), manualPosition = 3L),
            ),
            categories = listOf(reading, archive),
        )
        val viewModel = viewModel()
        advanceUntilIdle()
        // Ranked first, then the unranked by the active sort, alphabetical.
        viewModel.tab(1L) shouldContainExactly listOf(1L, 2L, 4L)

        viewModel.drag(categoryId = 1L, mangaId = 4L, toIndex = 1)
        advanceUntilIdle()

        // Anvil keeps 1; Drill and Bellows follow the library's last position, 3, in the new order.
        manualWrites shouldContainExactly listOf(listOf(1L, 3L, 5L, 4L, 2L))
        viewModel.tab(1L) shouldContainExactly listOf(1L, 4L, 2L)
        viewModel.tab(2L) shouldContainExactly listOf(3L, 5L)
        viewModel.positions() shouldBe mapOf(1L to 1L, 2L to 5L, 3L to 2L, 4L to 4L, 5L to 3L)
    }

    @Test
    fun `a drag to the first place and one to the last write the tab's order`() = runTest(testDispatcher) {
        manualLibrary(
            listOf(
                fixtureItem(id = 1L, title = "Anvil", manualPosition = 1L),
                fixtureItem(id = 2L, title = "Bellows", manualPosition = 2L),
                fixtureItem(id = 3L, title = "Chisel", manualPosition = 3L),
                fixtureItem(id = 4L, title = "Drill", manualPosition = 4L),
            ),
        )
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.drag(categoryId = 0L, mangaId = 3L, toIndex = 0)
        advanceUntilIdle()
        viewModel.tab(0L) shouldContainExactly listOf(3L, 1L, 2L, 4L)

        viewModel.drag(categoryId = 0L, mangaId = 3L, toIndex = 3)
        advanceUntilIdle()
        viewModel.tab(0L) shouldContainExactly listOf(1L, 2L, 4L, 3L)

        manualWrites shouldContainExactly listOf(listOf(3L, 1L, 2L), listOf(1L, 2L, 4L, 3L))
    }

    @Test
    fun `a drop where the entry started writes nothing`() = runTest(testDispatcher) {
        manualLibrary(
            listOf(
                fixtureItem(id = 1L, title = "Anvil", manualPosition = 1L),
                fixtureItem(id = 2L, title = "Bellows", manualPosition = 2L),
            ),
        )
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.drag(categoryId = 0L, mangaId = 2L, toIndex = 1) shouldBe false
        advanceUntilIdle()

        manualWrites shouldBe emptyList()
    }

    @Test
    fun `a drop over a tab a library update reordered during the drag writes nothing`() = runTest(testDispatcher) {
        // Unranked entries follow the active sort, here total chapters, which an update changes.
        libraryPreferences.sortingMode.set(LibrarySort(LibrarySort.Type.TotalChapters, LibrarySort.Direction.Ascending))
        val library = manualLibrary(
            listOf(
                fixtureItem(id = 1L, title = "Anvil", manualPosition = 1L),
                fixtureItem(id = 2L, title = "Bellows", totalChapters = 10L),
                fixtureItem(id = 3L, title = "Chisel", totalChapters = 20L),
                fixtureItem(id = 4L, title = "Drill", totalChapters = 30L),
            ),
        )
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.tab(0L) shouldContainExactly listOf(1L, 2L, 3L, 4L)

        // The owner drags Drill between Anvil and Bellows; before the drop, an update gives Bellows
        // new chapters and the tab below the page's copy now reads Anvil, Chisel, Drill, Bellows.
        val dropped = viewModel.tab(0L).moved(mangaId = 4L, toIndex = 1)
        library.value = library.value.map { item ->
            if (item.id == 2L) item.copy(libraryManga = item.libraryManga.copy(totalChapters = 40L)) else item
        }
        advanceUntilIdle()
        viewModel.tab(0L) shouldContainExactly listOf(1L, 3L, 4L, 2L)

        // Index 1 into the tab as it now stands would write Anvil, Drill, Chisel, Bellows: Chisel
        // ahead of Bellows, which the owner never saw. The drop is refused instead.
        val wrote = viewModel.reorder(categoryId = 0L, mangaId = 4L, order = dropped)
        advanceUntilIdle()

        manualWrites shouldBe emptyList()
        wrote shouldBe false
        viewModel.tab(0L) shouldContainExactly listOf(1L, 3L, 4L, 2L)
    }

    @Test
    fun `a drop over a tab that gained an entry during the drag writes nothing`() = runTest(testDispatcher) {
        val library = manualLibrary(
            listOf(
                fixtureItem(id = 1L, title = "Anvil", manualPosition = 1L),
                fixtureItem(id = 2L, title = "Bellows", manualPosition = 2L),
                fixtureItem(id = 3L, title = "Chisel", manualPosition = 3L),
            ),
        )
        val viewModel = viewModel()
        advanceUntilIdle()

        val dropped = viewModel.tab(0L).moved(mangaId = 3L, toIndex = 0)
        // A sync lands mid-drag with an entry the other device added to the library.
        library.value = library.value + fixtureItem(id = 4L, title = "Drill", manualPosition = 4L)
        advanceUntilIdle()

        val wrote = viewModel.reorder(categoryId = 0L, mangaId = 3L, order = dropped)
        advanceUntilIdle()

        manualWrites shouldBe emptyList()
        wrote shouldBe false
    }

    @Test
    fun `a drop over a tab whose rows changed but not their order writes the drag`() = runTest(testDispatcher) {
        val library = manualLibrary(
            listOf(
                fixtureItem(id = 1L, title = "Anvil", manualPosition = 1L),
                fixtureItem(id = 2L, title = "Bellows", manualPosition = 2L),
                fixtureItem(id = 3L, title = "Chisel", manualPosition = 3L),
            ),
        )
        val viewModel = viewModel()
        advanceUntilIdle()

        val dropped = viewModel.tab(0L).moved(mangaId = 3L, toIndex = 0)
        // Bellows reads a chapter mid-drag: its badge changes, the order does not.
        library.value = library.value.map { item ->
            if (item.id == 2L) item.copy(libraryManga = item.libraryManga.copy(readCount = 4L)) else item
        }
        advanceUntilIdle()

        viewModel.reorder(categoryId = 0L, mangaId = 3L, order = dropped) shouldBe true
        advanceUntilIdle()

        manualWrites shouldContainExactly listOf(listOf(3L, 1L, 2L))
        viewModel.tab(0L) shouldContainExactly listOf(3L, 1L, 2L)
    }

    @Test
    fun `after a sync left two entries on one position the next drag numbers the library from 1 again`() =
        runTest(testDispatcher) {
            // Two devices' drags merged row by row: Drill and Easel both hold 1 (SyncManualOrderTest).
            manualLibrary(
                listOf(
                    fixtureItem(id = 1L, title = "Anvil", manualPosition = 2L),
                    fixtureItem(id = 2L, title = "Bellows", manualPosition = 3L),
                    fixtureItem(id = 3L, title = "Chisel", manualPosition = 4L),
                    fixtureItem(id = 4L, title = "Drill", manualPosition = 1L),
                    fixtureItem(id = 5L, title = "Easel", manualPosition = 1L),
                ),
            )
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.tab(0L) shouldContainExactly listOf(4L, 5L, 1L, 2L, 3L)

            viewModel.drag(categoryId = 0L, mangaId = 3L, toIndex = 2)
            advanceUntilIdle()

            // Every entry keeps its place around the one moved, and no two share a position again.
            manualWrites shouldContainExactly listOf(listOf(4L, 5L, 3L, 1L, 2L))
            viewModel.tab(0L) shouldContainExactly listOf(4L, 5L, 3L, 1L, 2L)
            viewModel.positions() shouldBe mapOf(1L to 4L, 2L to 5L, 3L to 3L, 4L to 1L, 5L to 2L)
        }

    @Test
    fun `the handle shows with manual ordering on, no search, no filter and no selection`()= runTest(testDispatcher) {
        reorderableLibrary()
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.state.value.canReorder shouldBe true
        viewModel.drag(categoryId = 0L, mangaId = 1L, toIndex = 0) shouldBe true
        advanceUntilIdle()
        manualWrites shouldContainExactly listOf(listOf(1L, 2L))
    }

    @Test
    fun `the handle is gone with manual ordering off`() = runTest(testDispatcher) {
        reorderableLibrary()
        libraryPreferences.manualSortEnabled.set(false)
        val viewModel = viewModel()
        advanceUntilIdle()

        assertNoReorder(viewModel)
    }

    @Test
    fun `the handle is gone and the manual order unused while the categories have sorts of their own`() =
        runTest(testDispatcher) {
            // The category's own sort is alphabetical, ascending: flags 0 would read descending.
            val ascending = LibrarySort(LibrarySort.Type.Alphabetical, LibrarySort.Direction.Ascending)
            reorderableLibrary(categories = listOf(DEFAULT_CATEGORY.copy(flags = ascending.flag)))
            libraryPreferences.categorizedDisplaySettings.set(true)
            val viewModel = viewModel()
            advanceUntilIdle()

            // Contract 04: the manual order applies only to a category with no sort of its own, so
            // the tab reads alphabetical though Bellows holds position 1.
            viewModel.tab(0L) shouldContainExactly listOf(1L, 2L)
            assertNoReorder(viewModel)
        }

    @Test
    fun `the handle is gone during a search`() = runTest(testDispatcher) {
        reorderableLibrary()
        val viewModel = viewModel()
        advanceUntilIdle()

        // Both titles hold an l, so the search hides nothing and the tab is still all of it.
        viewModel.search("l")
        advanceUntilIdle()

        assertNoReorder(viewModel)
    }

    @Test
    fun `the handle is gone while a filter is on`() = runTest(testDispatcher) {
        reorderableLibrary()
        libraryPreferences.filterUnread.set(TriState.ENABLED_IS)
        val viewModel = viewModel()
        advanceUntilIdle()

        assertNoReorder(viewModel)
    }

    @Test
    fun `the handle is gone during a selection`() = runTest(testDispatcher) {
        reorderableLibrary()
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.toggleSelection(1L)

        assertNoReorder(viewModel)
    }

    /** Two entries, Bellows ranked ahead of Anvil, with manual ordering on. */
    private fun reorderableLibrary(categories: List<Category> = listOf(DEFAULT_CATEGORY)) {
        manualLibrary(
            listOf(
                fixtureItem(id = 1L, title = "Anvil", manualPosition = 2L),
                fixtureItem(id = 2L, title = "Bellows", manualPosition = 1L),
            ),
            categories,
        )
    }

    /** No handle, and a reorder asked for anyway writes nothing. */
    private fun TestScope.assertNoReorder(viewModel: LibraryViewModel) {
        viewModel.state.value.canReorder shouldBe false
        // A real move, the tab's last entry to the first place, so only the refusal writes nothing.
        val tab = viewModel.tab(0L)
        tab.size shouldBe 2
        viewModel.drag(categoryId = 0L, mangaId = tab.last(), toIndex = 0) shouldBe false
        advanceUntilIdle()
        manualWrites shouldBe emptyList()
    }

    private fun LibraryViewModel.tab(categoryId: Long): List<Long> = state.value.items(categoryId).map { it.id }

    /** A drag as the page makes one: [mangaId] moved to [toIndex] in the tab as it reads now. */
    private fun LibraryViewModel.drag(categoryId: Long, mangaId: Long, toIndex: Int): Boolean =
        reorder(categoryId, mangaId, tab(categoryId).moved(mangaId, toIndex))

    private fun List<Long>.moved(mangaId: Long, toIndex: Int): List<Long> =
        toMutableList().apply { add(toIndex, removeAt(indexOf(mangaId))) }

    private fun LibraryViewModel.positions(): Map<Long, Long> =
        state.value.itemsByCategory.values.flatten().associate { it.id to it.item.manga.manualPosition }

    /**
     * Feeds [items] with manual ordering on through a feed the repository writes back into: each
     * write numbers its list from 1, as `MangaRepositoryImpl.setManualPositions` does, and the feed
     * emits the library with those positions, as the database's flow would. Returns the feed, for a
     * test that changes the library under a drag.
     */
    private fun manualLibrary(
        items: List<LibraryItem>,
        categories: List<Category> = listOf(DEFAULT_CATEGORY),
    ): MutableStateFlow<List<LibraryItem>> {
        libraryPreferences.manualSortEnabled.set(true)
        val library = MutableStateFlow(items)
        feed(items, categories)
        every { getLibraryItems.subscribe() } returns library
        coEvery { mangaRepository.setManualPositions(any()) } answers {
            val order = firstArg<List<Long>>()
            manualWrites += order
            library.value = library.value.map { item ->
                val index = order.indexOf(item.id)
                if (index == -1) {
                    item
                } else {
                    val manga = item.libraryManga.manga.copy(manualPosition = index + 1L)
                    item.copy(libraryManga = item.libraryManga.copy(manga = manga))
                }
            }
        }
        return library
    }

    // ---- Helpers ----

    /**
     * Feeds two entries, turns one filter on as "is", and asserts only the matching one survives.
     * The assertion is on the projected rows, so it fails if the filter is not applied at all as
     * well as if it is applied to the wrong field.
     */
    private fun TestScope.assertFilterKeeps(
        preference: Preference<TriState>,
        matching: LibraryItem,
        other: LibraryItem,
    ) {
        feed(listOf(matching, other))

        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.state.value.visibleItems.map { it.id }.toSet() shouldBe setOf(matching.id, other.id)

        preference.set(TriState.ENABLED_IS)
        advanceUntilIdle()

        viewModel.state.value.visibleItems.map { it.id } shouldContainExactly listOf(matching.id)
        viewModel.state.value.hasActiveFilters shouldBe true
    }

    @Test
    fun `the update error count reaches state from the error store`() = runTest(testDispatcher) {
        feed(listOf(fixtureItem(id = 1L, title = "Wandering Cartographer")))
        errorsFlow.value = listOf(updateError(mangaId = 1L, title = "Anvil", sourceId = 10L))

        val viewModel = viewModel()
        advanceUntilIdle()

        // The store starts empty in a process that did not run the update, so the read is what
        // fills it: a badge that never calls load() is a badge that is always zero.
        coVerify(exactly = 1) { errorStore.load() }
        viewModel.state.value.updateErrorCount shouldBe 1
        // Written field by field, so the projection it lands beside is untouched.
        viewModel.state.value.visibleItems.map { it.id } shouldContainExactly listOf(1L)

        errorsFlow.value = errorsFlow.value + updateError(mangaId = 2L, title = "Bellows", sourceId = 20L)
        advanceUntilIdle()

        viewModel.state.value.updateErrorCount shouldBe 2
        viewModel.state.value.visibleItems.map { it.id } shouldContainExactly listOf(1L)
    }

    @Test
    fun `showUpdateErrors opens the sheet grouped by source`() = runTest(testDispatcher) {
        feed(listOf(fixtureItem(id = 1L, title = "Wandering Cartographer")))
        errorsFlow.value = listOf(
            updateError(mangaId = 1L, title = "Zither", sourceId = 20L),
            updateError(mangaId = 2L, title = "Anvil", sourceId = 10L),
            updateError(mangaId = 3L, title = "Bellows", sourceId = 10L),
        )
        every { sourceManager.getOrStub(10L) } returns namedSource("Almanac")
        every { sourceManager.getOrStub(20L) } returns namedSource("Beacon")
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.showUpdateErrors()
        advanceUntilIdle()

        val dialog = viewModel.state.value.dialog
        dialog.shouldBeInstanceOf<LibraryViewModel.Dialog.UpdateErrors>()
        dialog.groups.map { it.sourceName } shouldContainExactly listOf("Almanac", "Beacon")
        dialog.groups[0].errors.map { it.title } shouldContainExactly listOf("Anvil", "Bellows")
        dialog.groups[1].errors.map { it.title } shouldContainExactly listOf("Zither")
    }

    @Test
    fun `showUpdateErrors carries how many entries a stopped site left unchecked`() = runTest(testDispatcher) {
        feed(listOf(fixtureItem(id = 1L, title = "Wandering Cartographer")))
        errorsFlow.value = listOf(
            updateError(mangaId = 1L, title = "Zither", sourceId = 20L, key = ErrorKey.SITE_STOPPED),
            updateError(mangaId = 2L, title = "Anvil", sourceId = 10L, key = ErrorKey.TIMEOUT),
            updateError(mangaId = 3L, title = "Bellows", sourceId = 20L, key = ErrorKey.SITE_STOPPED),
        )
        every { sourceManager.getOrStub(10L) } returns namedSource("Almanac")
        every { sourceManager.getOrStub(20L) } returns namedSource("Beacon")
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.showUpdateErrors()
        advanceUntilIdle()

        val dialog = viewModel.state.value.dialog
        dialog.shouldBeInstanceOf<LibraryViewModel.Dialog.UpdateErrors>()
        dialog.stoppedEntries shouldBe 2
        dialog.groups.map { it.sourceName } shouldContainExactly listOf("Almanac", "Beacon")
    }

    private fun updateError(
        mangaId: Long,
        title: String,
        sourceId: Long,
        key: ErrorKey = ErrorKey.NO_CHAPTERS,
    ) = LibraryUpdateError(
        mangaId = mangaId,
        title = title,
        sourceId = sourceId,
        key = key,
        detail = null,
        at = 1_000L,
    )

    private fun namedSource(name: String): Source = mockk<Source>().also { every { it.name } returns name }

    private fun feed(
        items: List<LibraryItem>,
        categories: List<Category> = listOf(DEFAULT_CATEGORY),
    ) {
        every { getLibraryItems.subscribe() } returns flowOf(items)
        every { getCategories.subscribe() } returns flowOf(categories)
        every { sourceManager.getCatalogueSources() } returns flowOf(emptyList())
        every { sourceManager.getStubSources() } returns stubSources
        every { sourceManager.getOrStub(any()) } answers { StubSource.unknown(firstArg()) }
        every { libraryUpdateScheduler.isRunning } returns flowOf(false)
    }

    /** What the registry's stub list emits: a name lookup that ended re-projects the library. */
    private val stubSources = MutableStateFlow<List<StubSource>>(emptyList())

    private fun viewModel(setReadStatus: SetReadStatus = this.setReadStatus): LibraryViewModel = LibraryViewModel(
        getLibraryItems = getLibraryItems,
        filterLibrary = filterLibrary,
        sortLibrary = sortLibrary,
        sourceManager = sourceManager,
        getCategories = getCategories,
        setMangaCategories = setMangaCategories,
        setReadStatus = setReadStatus,
        updateManga = updateManga,
        getNextChapters = getNextChapters,
        getScanlatorPriority = getScanlatorPriority,
        setDisplayMode = setDisplayMode,
        setSortModeForCategory = setSortModeForCategory,
        libraryPreferences = libraryPreferences,
        basePreferences = basePreferences,
        libraryUpdateScheduler = libraryUpdateScheduler,
        errorStore = errorStore,
        dispatchers = AppDispatchers(io = testDispatcher, default = testDispatcher, main = testDispatcher),
        downloadQueue = downloadQueue,
        downloadedChapters = downloadedChapters,
        getBookmarkedChaptersByMangaId = getBookmarkedChaptersByMangaId,
        trackerManager = trackerManager,
        trackRepository = trackRepository,
        setManualLibraryOrder = SetManualLibraryOrder(mangaRepository),
        mergeEntries = mergeEntries,
    )

    private fun chapterFixture(id: Long, sourceOrder: Long = 0L) = Chapter.create().copy(id = id, sourceOrder = sourceOrder)

    private fun queueEntry(chapterId: Long, mangaId: Long) = DownloadQueueEntry(
        id = chapterId,
        chapterId = chapterId,
        mangaId = mangaId,
        state = DownloadState.QUEUED,
        queueOrder = chapterId,
        totalPages = 0,
        downloadedPages = 0,
        errorMessage = null,
        dateAdded = 0,
        dateStarted = null,
        dateCompleted = null,
    )

    private fun fixtureItem(
        id: Long,
        title: String,
        source: Long = 1L,
        categories: List<Long> = listOf(0L),
        totalChapters: Long = 10L,
        readCount: Long = 3L,
        bookmarkCount: Long = 0L,
        downloadCount: Long = 0L,
        isLocal: Boolean = false,
        status: Long = MangaStatus.ONGOING.code.toLong(),
        fetchInterval: Int = 0,
        manualPosition: Long = 0L,
        members: List<Manga> = emptyList(),
    ) = LibraryItem(
        libraryManga = LibraryManga(
            manga = Manga.create().copy(
                id = id,
                source = source,
                title = title,
                status = status,
                fetchInterval = fetchInterval,
                manualPosition = manualPosition,
            ),
            categories = categories,
            totalChapters = totalChapters,
            readCount = readCount,
            bookmarkCount = bookmarkCount,
            latestUpload = 0L,
            chapterFetchedAt = 0L,
            lastRead = 0L,
        ),
        downloadCount = downloadCount,
        unreadCount = totalChapters - readCount,
        isLocal = isLocal,
        sourceLanguage = "en",
        members = members,
    )

    private fun trackRow(mangaId: Long, trackerId: Long, score: Double = 0.0) = Track(
        id = mangaId * 100 + trackerId,
        mangaId = mangaId,
        trackerId = trackerId,
        remoteId = 1000 + mangaId,
        libraryId = null,
        title = "Entry $mangaId",
        lastChapterRead = 0.0,
        totalChapters = 0,
        status = 1,
        score = score,
        remoteUrl = "https://tracker.example.invalid/entry/$mangaId",
        startDate = 0,
        finishDate = 0,
        private = false,
    )

    /**
     * The in-memory store hands out a new, empty preference on every call, so a preference built per
     * call, as `filterTracking(id)` is, would never read what an earlier one wrote. This keeps one per
     * key, as the platform's store reads one file.
     */
    private class KeyedPreferenceStore(
        private val delegate: PreferenceStore = InMemoryPreferenceStore(),
    ) : PreferenceStore by delegate {
        private val objects = mutableMapOf<String, Preference<*>>()

        @Suppress("UNCHECKED_CAST")
        override fun <T> getObjectFromString(
            key: String,
            defaultValue: T,
            serializer: (T) -> String,
            deserializer: (String) -> T,
        ): Preference<T> = objects.getOrPut(key) {
            delegate.getObjectFromString(key, defaultValue, serializer, deserializer)
        } as Preference<T>
    }

    private companion object {
        val DEFAULT_CATEGORY = Category(id = 0L, name = "", order = 0L, flags = 0L)
    }
}

package com.shinsaikou.ui.browse.source

import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.SourcePreferences
import com.shinsaikou.domain.source.interactor.BrowseMode
import com.shinsaikou.domain.source.interactor.BrowsePage
import com.shinsaikou.domain.source.interactor.GetSourceMangaPage
import com.shinsaikou.navigation.SourceBrowseMode
import com.shinsaikou.reader.state.IncognitoPolicy
import com.shinsaikou.source.CatalogueSource
import com.shinsaikou.source.SourceManager
import com.shinsaikou.source.model.Filter
import com.shinsaikou.source.model.FilterList
import com.shinsaikou.ui.fixture.ColdSourceRegistry
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers
import kotlinx.coroutines.Dispatchers as KotlinDispatchers

/**
 * Two kinds of decision live in this file: what the opening mode falls back to when the route asks
 * for something the source cannot give, and whether an edited filter tree actually differs from the
 * one the source handed out. The second one exists because [FilterList.equals] is unconditionally
 * false by design (see its own KDoc), so [differsFrom] is the only thing standing between an
 * untouched filter sheet and a state that thinks the owner changed something. The pagination tests
 * below them pin the list's own contract: ids are deduplicated across pages and a source with
 * nothing left to give is not asked again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SourceBrowseViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val getSourceMangaPage = mockk<GetSourceMangaPage>()
    private val sourceManager = mockk<SourceManager>()
    private val preferenceStore = InMemoryPreferenceStore()
    private val sourcePreferences = SourcePreferences(preferenceStore)
    private val libraryPreferences = LibraryPreferences(preferenceStore)
    private val favorites = MutableStateFlow<List<Manga>>(emptyList())
    private val getFavorites = mockk<GetFavorites> { every { subscribe(SOURCE_ID) } returns favorites }
    private var incognito = false
    private val incognitoPolicy = object : IncognitoPolicy {
        override fun isIncognito(sourceId: Long): Boolean = incognito
    }

    @BeforeEach
    fun setUp() {
        KotlinDispatchers.setMain(testDispatcher)
        // A registry that has finished its first load; the cold-start test passes its own.
        every { sourceManager.isInitialized } returns MutableStateFlow(true)
    }

    @AfterEach
    fun tearDown() {
        KotlinDispatchers.resetMain()
    }

    // -- differsFrom ------------------------------------------------------------------------

    @Test
    fun `differsFrom is false for two equivalent trees fresh from getFilterList`() {
        val a = FilterList(Filter.CheckBox("Colorized", false), Filter.Text("Author", ""))
        val b = a.deepCopy()

        a.differsFrom(b) shouldBe false
    }

    @Test
    fun `differsFrom is true once a filter's state is edited`() {
        val defaults = FilterList(Filter.CheckBox("Colorized", false))
        val edited = defaults.deepCopy()
        (edited.list[0] as Filter.CheckBox).state = true

        edited.differsFrom(defaults) shouldBe true
    }

    @Test
    fun `differsFrom is true when the two trees have different sizes`() {
        val defaults = FilterList(Filter.CheckBox("Colorized", false))
        val longer = FilterList(Filter.CheckBox("Colorized", false), Filter.Text("Author", ""))

        longer.differsFrom(defaults) shouldBe true
    }

    @Test
    fun `differsFrom walks into a nested Group's children`() {
        val defaults = FilterList(Filter.Group("Genres", listOf(Filter.CheckBox("Action", false))))
        val edited = defaults.deepCopy()
        val group = edited.list[0] as Filter.Group
        (group.state[0] as Filter.CheckBox).state = true

        edited.differsFrom(defaults) shouldBe true
        // An untouched copy still reads as unchanged against its own snapshot.
        defaults.deepCopy().differsFrom(defaults) shouldBe false
    }

    // -- opening mode fallback ----------------------------------------------------------------

    @Test
    fun `LATEST falls back to POPULAR on a source that does not support it`() = runTest(testDispatcher) {
        every { sourceManager.get(SOURCE_ID) } returns source(latest = false)
        coEvery { getSourceMangaPage.await(any(), any(), any()) } returns emptyPage()

        val viewModel = viewModel(mode = SourceBrowseMode.LATEST, query = null)
        advanceUntilIdle()

        viewModel.state.value.mode shouldBe SourceBrowseMode.POPULAR
    }

    @Test
    fun `SEARCH with a blank query falls back to POPULAR`() = runTest(testDispatcher) {
        every { sourceManager.get(SOURCE_ID) } returns source(latest = true)
        coEvery { getSourceMangaPage.await(any(), any(), any()) } returns emptyPage()

        val viewModel = viewModel(mode = SourceBrowseMode.SEARCH, query = "   ")
        advanceUntilIdle()

        viewModel.state.value.mode shouldBe SourceBrowseMode.POPULAR
    }

    @Test
    fun `SEARCH with real text stays SEARCH`() = runTest(testDispatcher) {
        every { sourceManager.get(SOURCE_ID) } returns source(latest = true)
        coEvery { getSourceMangaPage.await(any(), any(), any()) } returns emptyPage()

        val viewModel = viewModel(mode = SourceBrowseMode.SEARCH, query = "one piece")
        advanceUntilIdle()

        viewModel.state.value.mode shouldBe SourceBrowseMode.SEARCH
    }

    // -- submitSearch -------------------------------------------------------------------------

    @Test
    fun `submitSearch does nothing for a blank query with no filter applied`() = runTest(testDispatcher) {
        every { sourceManager.get(SOURCE_ID) } returns source()
        coEvery { getSourceMangaPage.await(any(), any(), any()) } returns emptyPage()

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.submitSearch()
        advanceUntilIdle()

        // Only the initial popular load happened; submitSearch added no second call.
        coVerify(exactly = 1) { getSourceMangaPage.await(any(), any(), any()) }
        viewModel.state.value.mode shouldBe SourceBrowseMode.POPULAR
    }

    @Test
    fun `submitSearch runs on a blank query when a filter is applied`() = runTest(testDispatcher) {
        every { sourceManager.get(SOURCE_ID) } returns
            source(filters = { FilterList(Filter.CheckBox("Colorized", false)) })
        coEvery { getSourceMangaPage.await(any(), any(), any()) } returns emptyPage()

        val viewModel = viewModel()
        advanceUntilIdle()

        val edited = viewModel.state.value.filters.deepCopy()
        (edited.list[0] as Filter.CheckBox).state = true
        viewModel.setFilters(edited)
        viewModel.state.value.filtersApplied shouldBe true

        viewModel.submitSearch()
        advanceUntilIdle()

        viewModel.state.value.mode shouldBe SourceBrowseMode.SEARCH
        coVerify {
            getSourceMangaPage.await(
                SOURCE_ID,
                match { it is BrowseMode.Search && it.query.isEmpty() && it.filters === edited },
                any(),
            )
        }
    }

    // -- clearSearch ----------------------------------------------------------------------------

    @Test
    fun `clearSearch resets the query, the mode, the filter tree and the applied flag together`() =
        runTest(testDispatcher) {
            every { sourceManager.get(SOURCE_ID) } returns
                source(filters = { FilterList(Filter.CheckBox("Colorized", false)) })
            coEvery { getSourceMangaPage.await(any(), any(), any()) } returns emptyPage()

            val viewModel = viewModel()
            advanceUntilIdle()

            val edited = viewModel.state.value.filters.deepCopy()
            (edited.list[0] as Filter.CheckBox).state = true
            viewModel.setFilters(edited)
            viewModel.setQuery("one piece")
            viewModel.submitSearch()
            advanceUntilIdle()
            viewModel.state.value.mode shouldBe SourceBrowseMode.SEARCH
            viewModel.state.value.filtersApplied shouldBe true

            viewModel.clearSearch()
            advanceUntilIdle()

            viewModel.state.value.query shouldBe ""
            viewModel.state.value.mode shouldBe SourceBrowseMode.POPULAR
            // filtersApplied lying about a popular listing is exactly what the app bar's "filters
            // applied" indicator must never do, since a popular request carries no filters at all.
            viewModel.state.value.filtersApplied shouldBe false
            // The tree itself has to be back at the source's own defaults too, not merely flagged
            // as unapplied, or the sheet would reopen still showing the edit ticked.
            val checkBox = viewModel.state.value.filters.list[0] as Filter.CheckBox
            checkBox.state shouldBe false

            // The listing that finally loads is the popular one, with no filters and no query.
            coVerify {
                getSourceMangaPage.await(SOURCE_ID, BrowseMode.Popular, any())
            }
        }

    // -- pagination ---------------------------------------------------------------------------

    @Test
    fun `the first page load fills the list`() = runTest(testDispatcher) {
        every { sourceManager.get(SOURCE_ID) } returns source()
        coEvery { getSourceMangaPage.await(any(), any(), any()) } returns
            BrowsePage(mangas = listOf(manga(1), manga(2)), hasNextPage = true, page = 1)

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.state.value.items.map { it.id } shouldContainExactly listOf(1L, 2L)
        viewModel.state.value.loading shouldBe false
    }

    @Test
    fun `a repeated id across pages is not duplicated`() = runTest(testDispatcher) {
        every { sourceManager.get(SOURCE_ID) } returns source()
        coEvery { getSourceMangaPage.await(any(), any(), any()) } returns
            BrowsePage(mangas = listOf(manga(1), manga(2)), hasNextPage = true, page = 1)

        val viewModel = viewModel()
        advanceUntilIdle()

        coEvery { getSourceMangaPage.await(any(), any(), any()) } returns
            BrowsePage(mangas = listOf(manga(2), manga(3)), hasNextPage = false, page = 2)

        viewModel.loadNextPage()
        advanceUntilIdle()

        viewModel.state.value.items.map { it.id } shouldContainExactly listOf(1L, 2L, 3L)
    }

    @Test
    fun `hasNextPage false stops loadNextPage from fetching again`() = runTest(testDispatcher) {
        every { sourceManager.get(SOURCE_ID) } returns source()
        coEvery { getSourceMangaPage.await(any(), any(), any()) } returns
            BrowsePage(mangas = listOf(manga(1)), hasNextPage = false, page = 1)

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.loadNextPage()
        advanceUntilIdle()

        coVerify(exactly = 1) { getSourceMangaPage.await(any(), any(), any()) }
    }

    @Test
    fun `a failing page sets the error, and retry clears it`() = runTest(testDispatcher) {
        every { sourceManager.get(SOURCE_ID) } returns source()
        coEvery { getSourceMangaPage.await(any(), any(), any()) } throws RuntimeException("boom")

        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.state.value.error.shouldNotBeNull()

        coEvery { getSourceMangaPage.await(any(), any(), any()) } returns
            BrowsePage(mangas = listOf(manga(1)), hasNextPage = false, page = 1)

        viewModel.retry()
        advanceUntilIdle()

        viewModel.state.value.error.shouldBeNull()
        viewModel.state.value.items.map { it.id } shouldContainExactly listOf(1L)
    }

    /**
     * Phase 4 unit MP1, R108 carry-over 5: a later page's failure keeps the entries already listed,
     * and its retry asks for that page again, as upstream's paged listing retries a failed append.
     */
    @Test
    fun `a failed second page keeps the first page's entries, and its retry loads page 2 onto them`() =
        runTest(testDispatcher) {
            every { sourceManager.get(SOURCE_ID) } returns source()
            coEvery { getSourceMangaPage.await(SOURCE_ID, BrowseMode.Popular, 1) } returns
                BrowsePage(mangas = listOf(manga(1), manga(2)), hasNextPage = true, page = 1)
            coEvery { getSourceMangaPage.await(SOURCE_ID, BrowseMode.Popular, 2) } throws RuntimeException("page 2")

            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.loadNextPage()
            advanceUntilIdle()

            viewModel.state.value.let { state ->
                state.items.map { it.id } shouldContainExactly listOf(1L, 2L)
                state.page shouldBe 1
                state.loadingMore shouldBe false
                state.nextPageError.shouldNotBeNull().message shouldBe "page 2"
                // The first page's failure is the one the listing shows in place of its entries.
                state.error.shouldBeNull()
            }

            coEvery { getSourceMangaPage.await(SOURCE_ID, BrowseMode.Popular, 2) } returns
                BrowsePage(mangas = listOf(manga(3)), hasNextPage = true, page = 2)
            viewModel.retryNextPage()
            advanceUntilIdle()

            viewModel.state.value.let { state ->
                state.items.map { it.id } shouldContainExactly listOf(1L, 2L, 3L)
                state.page shouldBe 2
                state.nextPageError.shouldBeNull()
            }
            coVerify(exactly = 1) { getSourceMangaPage.await(SOURCE_ID, BrowseMode.Popular, 1) }
            coVerify(exactly = 2) { getSourceMangaPage.await(SOURCE_ID, BrowseMode.Popular, 2) }
        }

    @Test
    fun `a later page asked for again clears its failure while it loads`() = runTest(testDispatcher) {
        every { sourceManager.get(SOURCE_ID) } returns source()
        coEvery { getSourceMangaPage.await(SOURCE_ID, BrowseMode.Popular, 1) } returns
            BrowsePage(mangas = listOf(manga(1)), hasNextPage = true, page = 1)
        coEvery { getSourceMangaPage.await(SOURCE_ID, BrowseMode.Popular, 2) } throws RuntimeException("page 2")

        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.loadNextPage()
        advanceUntilIdle()
        viewModel.state.value.nextPageError.shouldNotBeNull()

        viewModel.retryNextPage()

        viewModel.state.value.nextPageError.shouldBeNull()
        viewModel.state.value.loadingMore shouldBe true
        advanceUntilIdle()
        viewModel.state.value.nextPageError.shouldNotBeNull()
        viewModel.state.value.items.map { it.id } shouldContainExactly listOf(1L)
    }

    @Test
    fun `a new first page drops a later page's failure`() = runTest(testDispatcher) {
        every { sourceManager.get(SOURCE_ID) } returns source()
        coEvery { getSourceMangaPage.await(SOURCE_ID, BrowseMode.Popular, 1) } returns
            BrowsePage(mangas = listOf(manga(1)), hasNextPage = true, page = 1)
        coEvery { getSourceMangaPage.await(SOURCE_ID, BrowseMode.Popular, 2) } throws RuntimeException("page 2")

        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.loadNextPage()
        advanceUntilIdle()
        viewModel.state.value.nextPageError.shouldNotBeNull()

        viewModel.retry()
        viewModel.state.value.nextPageError.shouldBeNull()
        advanceUntilIdle()

        viewModel.state.value.nextPageError.shouldBeNull()
        viewModel.state.value.items.map { it.id } shouldContainExactly listOf(1L)
    }

    // -- A restore after process death (ruling R69 item 4) ----------------------------------------

    @Test
    fun `a listing restored before the registry has loaded waits, then opens the installed source`() =
        runTest(testDispatcher) {
            val registry = ColdSourceRegistry(mapOf(SOURCE_ID to source(latest = true)))
            coEvery { getSourceMangaPage.await(SOURCE_ID, BrowseMode.Latest, 1) } returns
                BrowsePage(mangas = listOf(manga(1L)), hasNextPage = false, page = 1)

            val viewModel = viewModel(mode = SourceBrowseMode.LATEST, sourceManager = registry.manager)
            advanceUntilIdle()
            // The loading row, and nothing asked of a source that is not loaded yet.
            viewModel.state.value.loading shouldBe true
            viewModel.state.value.installed shouldBe true
            coVerify(exactly = 0) { getSourceMangaPage.await(any(), any(), any()) }

            registry.finishLoad()
            advanceUntilIdle()

            viewModel.state.value.installed shouldBe true
            viewModel.state.value.sourceName shouldBe "Fixture Source"
            viewModel.state.value.mode shouldBe SourceBrowseMode.LATEST
            viewModel.state.value.items.map { it.id } shouldContainExactly listOf(1L)
        }

    // -- last used source (phase 4 unit MP2) -------------------------------------------------

    @Test
    fun `opening a listing makes its source the last used one`() = runTest(testDispatcher) {
        every { sourceManager.get(SOURCE_ID) } returns source(latest = true)
        coEvery { getSourceMangaPage.await(any(), any(), any()) } returns emptyPage()
        sourcePreferences.lastUsedSource.set(99L)

        viewModel(mode = SourceBrowseMode.LATEST)
        advanceUntilIdle()

        sourcePreferences.lastUsedSource.get() shouldBe SOURCE_ID
    }

    @Test
    fun `an incognito source is not made the last used one`() = runTest(testDispatcher) {
        every { sourceManager.get(SOURCE_ID) } returns source(latest = true)
        coEvery { getSourceMangaPage.await(any(), any(), any()) } returns emptyPage()
        sourcePreferences.lastUsedSource.set(99L)
        incognito = true

        viewModel()
        advanceUntilIdle()

        sourcePreferences.lastUsedSource.get() shouldBe 99L
    }

    @Test
    fun `the last used source is written once the registry has loaded, not before`() = runTest(testDispatcher) {
        val registry = ColdSourceRegistry(mapOf(SOURCE_ID to source(latest = true)))
        coEvery { getSourceMangaPage.await(any(), any(), any()) } returns emptyPage()
        sourcePreferences.lastUsedSource.set(99L)

        viewModel(sourceManager = registry.manager)
        advanceUntilIdle()
        sourcePreferences.lastUsedSource.get() shouldBe 99L

        registry.finishLoad()
        advanceUntilIdle()

        sourcePreferences.lastUsedSource.get() shouldBe SOURCE_ID
    }

    private fun source(
        latest: Boolean = false,
        filters: () -> FilterList = { FilterList.EMPTY },
    ): CatalogueSource = mockk {
        every { name } returns "Fixture Source"
        every { supportsLatest } returns latest
        every { homeUrl } returns null
        every { getFilterList() } answers { filters() }
    }

    // -- display mode ------------------------------------------------------------------------

    @Test
    fun `the listing opens in upstream's default compact grid with the library's column counts`() = runTest(testDispatcher) {
        coEvery { getSourceMangaPage.await(any(), any(), any()) } returns emptyPage()
        every { sourceManager.get(SOURCE_ID) } returns source()
        libraryPreferences.portraitColumns.set(3)
        libraryPreferences.landscapeColumns.set(6)

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.state.value.displayMode shouldBe LibraryDisplayMode.CompactGrid
        viewModel.state.value.portraitColumns shouldBe 3
        viewModel.state.value.landscapeColumns shouldBe 6
    }

    @Test
    fun `the menu's choice is stored under upstream's key and reaches the listing, as either column count does alone`() =
        runTest(testDispatcher) {
            coEvery { getSourceMangaPage.await(any(), any(), any()) } returns emptyPage()
            every { sourceManager.get(SOURCE_ID) } returns source()
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.setDisplayMode(LibraryDisplayMode.List)
            advanceUntilIdle()

            sourcePreferences.sourceDisplayMode.get() shouldBe LibraryDisplayMode.List
            sourcePreferences.sourceDisplayMode.key() shouldBe "pref_display_mode_catalogue"
            viewModel.state.value.displayMode shouldBe LibraryDisplayMode.List

            // Each column key alone, as the library's slider writes one of them.
            libraryPreferences.portraitColumns.set(2)
            advanceUntilIdle()
            viewModel.state.value.portraitColumns shouldBe 2

            libraryPreferences.landscapeColumns.set(5)
            advanceUntilIdle()
            viewModel.state.value.landscapeColumns shouldBe 5
        }

    @Test
    fun `an entry added to the library from its details reads as in the library without a new fetch`() =
        runTest(testDispatcher) {
            val entry = manga(1)
            coEvery { getSourceMangaPage.await(any(), any(), any()) } returns
                BrowsePage(mangas = listOf(entry, manga(2)), hasNextPage = false, page = 1)
            every { sourceManager.get(SOURCE_ID) } returns source()
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.state.value.inLibrary(entry) shouldBe false

            favorites.value = listOf(entry.copy(favorite = true))
            advanceUntilIdle()

            viewModel.state.value.inLibrary(entry) shouldBe true
            viewModel.state.value.inLibrary(manga(2)) shouldBe false
            coVerify(exactly = 1) { getSourceMangaPage.await(any(), any(), any()) }
        }

    @Test
    fun `before the favourites are read the fetched flag decides`() {
        val state = SourceBrowseViewModel.State()

        state.inLibrary(manga(1).copy(favorite = true)) shouldBe true
        state.inLibrary(manga(2)) shouldBe false
    }

    private fun emptyPage() = BrowsePage(mangas = emptyList(), hasNextPage = false, page = 1)

    private fun manga(id: Long, title: String = "Fixture $id") =
        Manga.create().copy(id = id, source = SOURCE_ID, url = "/m/$id", title = title)

    private fun viewModel(
        query: String? = null,
        mode: SourceBrowseMode = SourceBrowseMode.POPULAR,
        sourceManager: SourceManager = this.sourceManager,
    ) =
        SourceBrowseViewModel(
            sourceId = SOURCE_ID,
            query = query,
            mode = mode,
            migratingMangaId = null,
            getSourceMangaPage = getSourceMangaPage,
            sourceManager = sourceManager,
            sourcePreferences = sourcePreferences,
            libraryPreferences = libraryPreferences,
            getFavorites = getFavorites,
            getManga = mockk(),
            incognitoPolicy = incognitoPolicy,
            updateManga = mockk(),
            getCategories = mockk(),
            setMangaCategories = mockk(),
            setMangaDefaultChapterFlags = mockk(),
            dispatchers = AppDispatchers(io = testDispatcher, default = testDispatcher, main = testDispatcher),
        )

    private companion object {
        const val SOURCE_ID = 11L
    }
}

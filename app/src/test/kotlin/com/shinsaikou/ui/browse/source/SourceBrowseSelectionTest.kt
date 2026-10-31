package com.shinsaikou.ui.browse.source

import androidx.compose.ui.state.ToggleableState
import com.shinsaikou.domain.manga.interactor.UpdateManga
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.SourcePreferences
import com.shinsaikou.domain.source.interactor.BrowsePage
import com.shinsaikou.domain.source.interactor.GetSourceMangaPage
import com.shinsaikou.navigation.SourceBrowseMode
import com.shinsaikou.reader.state.IncognitoPolicy
import com.shinsaikou.source.CatalogueSource
import com.shinsaikou.source.SourceManager
import com.shinsaikou.source.model.FilterList
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers
import kotlinx.coroutines.Dispatchers as KotlinDispatchers

/**
 * Multi-select in a source's listing: the long press range over the listing's order, taps, select
 * all and invert, the selection leaving with the listing, pick mode having none, and adding the
 * selection to the library with the default category, none, or the picker. The range rule itself is
 * covered by LongPressSelectionTest; here only that browse feeds it the listing's order.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SourceBrowseSelectionTest {

    private val testDispatcher = StandardTestDispatcher()

    private val getSourceMangaPage = mockk<GetSourceMangaPage>()
    private val sourceManager = mockk<SourceManager>()
    private val preferenceStore = InMemoryPreferenceStore()
    private val sourcePreferences = SourcePreferences(preferenceStore)
    private val libraryPreferences = LibraryPreferences(preferenceStore)
    private val favorites = MutableStateFlow<List<Manga>>(emptyList())
    private val getFavorites = mockk<GetFavorites> { every { subscribe(SOURCE_ID) } returns favorites }
    private val updateManga = mockk<UpdateManga>()
    private val getCategories = mockk<GetCategories>()
    private val setMangaCategories = mockk<SetMangaCategories>()
    private val setMangaDefaultChapterFlags = mockk<SetMangaDefaultChapterFlags>()
    private val incognitoPolicy = object : IncognitoPolicy {
        override fun isIncognito(sourceId: Long): Boolean = false
    }

    // The listing's order is deliberately not id order.
    private val listing = listOf(30L, 10L, 50L, 20L, 40L)

    @BeforeEach
    fun setUp() {
        KotlinDispatchers.setMain(testDispatcher)
        every { sourceManager.isInitialized } returns MutableStateFlow(true)
        every { sourceManager.get(SOURCE_ID) } returns mockk<CatalogueSource> {
            every { name } returns "Fixture Source"
            every { supportsLatest } returns false
            every { homeUrl } returns null
            every { getFilterList() } returns FilterList.EMPTY
        }
        coEvery { getSourceMangaPage.await(any(), any(), any()) } answers {
            BrowsePage(mangas = listing.map { manga(it) }, hasNextPage = false, page = 1)
        }
        coEvery { updateManga.awaitUpdateFavorites(any(), any()) } returns true
        coEvery { setMangaCategories.await(any(), any()) } returns Unit
        coEvery { setMangaDefaultChapterFlags.await(any()) } returns Unit
        coEvery { getCategories.await() } returns emptyList()
    }

    @AfterEach
    fun tearDown() {
        KotlinDispatchers.resetMain()
    }

    // -- selecting ---------------------------------------------------------------------------

    @Test
    fun `a long press starts a selection and a second one further down selects the entries between in listing order`() =
        runTest(testDispatcher) {
            val viewModel = loaded()

            viewModel.longPressEntry(10L)
            viewModel.state.value.selection shouldBe setOf(10L)
            viewModel.state.value.selectionMode shouldBe true

            viewModel.longPressEntry(40L)

            // Listing order 30, 10, 50, 20, 40: between 10 and 40 are 50 and 20, which id order would miss.
            viewModel.state.value.selection shouldBe setOf(10L, 50L, 20L, 40L)
        }

    @Test
    fun `a tap adds an entry to the selection and a second tap removes it`() = runTest(testDispatcher) {
        val viewModel = loaded()

        viewModel.toggleSelection(50L)
        viewModel.state.value.selection shouldBe setOf(50L)

        viewModel.toggleSelection(20L)
        viewModel.state.value.selection shouldBe setOf(50L, 20L)

        viewModel.toggleSelection(50L)
        viewModel.state.value.selection shouldBe setOf(20L)

        viewModel.toggleSelection(20L)
        viewModel.state.value.selectionMode shouldBe false
    }

    @Test
    fun `select all takes every loaded entry`() = runTest(testDispatcher) {
        val viewModel = loaded()
        viewModel.toggleSelection(10L)

        viewModel.selectAll()

        viewModel.state.value.selection shouldBe listing.toSet()
    }

    @Test
    fun `invert flips the selection over the loaded entries`() = runTest(testDispatcher) {
        val viewModel = loaded()
        viewModel.toggleSelection(10L)
        viewModel.toggleSelection(20L)

        viewModel.invertSelection()

        viewModel.state.value.selection shouldBe setOf(30L, 50L, 40L)
    }

    @Test
    fun `clear empties the selection`() = runTest(testDispatcher) {
        val viewModel = loaded()
        viewModel.selectAll()

        viewModel.clearSelection()

        viewModel.state.value.selection shouldBe emptySet()
        viewModel.state.value.selectionMode shouldBe false
    }

    @Test
    fun `a new listing clears the selection`() = runTest(testDispatcher) {
        val viewModel = loaded()
        viewModel.longPressEntry(10L)

        viewModel.clearSearch()
        advanceUntilIdle()

        viewModel.state.value.selection shouldBe emptySet()
    }

    @Test
    fun `pick mode has no selection, a long press leaves it empty`() = runTest(testDispatcher) {
        val viewModel = loaded(migratingMangaId = 99L)

        viewModel.longPressEntry(10L)

        viewModel.state.value.selection shouldBe emptySet()
    }

    // -- adding ------------------------------------------------------------------------------

    @Test
    fun `with a default category set, only the selected entries outside the library are added to it, in listing order`() =
        runTest(testDispatcher) {
            coEvery { getCategories.await() } returns listOf(system(), category(5L), category(6L))
            libraryPreferences.defaultCategory.set(6)
            val viewModel = loaded()
            favorites.value = listOf(manga(50L).copy(favorite = true))
            advanceUntilIdle()
            viewModel.selectAll()

            viewModel.addSelectedToLibrary()
            advanceUntilIdle()

            coVerify(exactly = 1) { updateManga.awaitUpdateFavorites(listOf(30L, 10L, 20L, 40L), true) }
            listOf(30L, 10L, 20L, 40L).forEach { coVerify(exactly = 1) { setMangaCategories.await(it, listOf(6L)) } }
            coVerify(exactly = 0) { setMangaCategories.await(50L, any()) }
            // Upstream's add from browse gives each entry the chapter defaults; the one already in the
            // library keeps its own.
            listOf(30L, 10L, 20L, 40L).forEach { id ->
                coVerify(exactly = 1) { setMangaDefaultChapterFlags.await(match { it.id == id }) }
            }
            coVerify(exactly = 0) { setMangaDefaultChapterFlags.await(match { it.id == 50L }) }
            viewModel.events.first() shouldBe SourceBrowseViewModel.Event.Added(4)
            viewModel.state.value.selection shouldBe emptySet()
            viewModel.state.value.addCategories.shouldBeNull()
        }

    @Test
    fun `an entry the fetch flagged as favourite counts as in the library before the live set arrives`() =
        runTest(testDispatcher) {
            coEvery { getSourceMangaPage.await(any(), any(), any()) } returns BrowsePage(
                mangas = listOf(manga(1L).copy(favorite = true), manga(2L)),
                hasNextPage = false,
                page = 1,
            )
            every { getFavorites.subscribe(SOURCE_ID) } returns kotlinx.coroutines.flow.emptyFlow()
            val viewModel = loaded(expected = listOf(1L, 2L))
            viewModel.selectAll()

            viewModel.addSelectedToLibrary()
            advanceUntilIdle()

            coVerify(exactly = 1) { updateManga.awaitUpdateFavorites(listOf(2L), true) }
        }

    @Test
    fun `with the built-in category as default the entries are added with no categories`() = runTest(testDispatcher) {
        coEvery { getCategories.await() } returns listOf(system(), category(5L))
        libraryPreferences.defaultCategory.set(0)
        val viewModel = loaded()
        viewModel.toggleSelection(10L)

        viewModel.addSelectedToLibrary()
        advanceUntilIdle()

        coVerify(exactly = 1) { updateManga.awaitUpdateFavorites(listOf(10L), true) }
        coVerify(exactly = 1) { setMangaCategories.await(10L, emptyList()) }
        viewModel.state.value.addCategories.shouldBeNull()
    }

    @Test
    fun `with no user categories the entries are added with no categories, whatever the default`() =
        runTest(testDispatcher) {
            coEvery { getCategories.await() } returns listOf(system())
            libraryPreferences.defaultCategory.set(-1)
            val viewModel = loaded()
            viewModel.toggleSelection(10L)

            viewModel.addSelectedToLibrary()
            advanceUntilIdle()

            coVerify(exactly = 1) { setMangaCategories.await(10L, emptyList()) }
            viewModel.state.value.addCategories.shouldBeNull()
        }

    @Test
    fun `with no default and user categories nothing is written and the picker lists every user category unchecked`() =
        runTest(testDispatcher) {
            coEvery { getCategories.await() } returns listOf(system(), category(5L), category(6L))
            libraryPreferences.defaultCategory.set(-1)
            val viewModel = loaded()
            viewModel.toggleSelection(10L)

            viewModel.addSelectedToLibrary()
            advanceUntilIdle()

            val rows = viewModel.state.value.addCategories.shouldNotBeNull()
            rows.map { it.id } shouldContainExactly listOf(5L, 6L)
            rows.map { it.state } shouldContainExactly listOf(ToggleableState.Off, ToggleableState.Off)
            coVerify(exactly = 0) { updateManga.awaitUpdateFavorites(any(), any()) }
            coVerify(exactly = 0) { setMangaCategories.await(any(), any()) }
            coVerify(exactly = 0) { setMangaDefaultChapterFlags.await(any()) }
            viewModel.state.value.selection shouldBe setOf(10L)
        }

    @Test
    fun `confirming the picker adds every selected entry to the checked categories`() = runTest(testDispatcher) {
        coEvery { getCategories.await() } returns listOf(system(), category(5L), category(6L))
        libraryPreferences.defaultCategory.set(-1)
        val viewModel = loaded()
        viewModel.longPressEntry(10L)
        viewModel.longPressEntry(20L)
        viewModel.addSelectedToLibrary()
        advanceUntilIdle()
        val rows = viewModel.state.value.addCategories.shouldNotBeNull()

        viewModel.confirmAddCategories(rows.map { if (it.id == 6L) it.copy(state = ToggleableState.On) else it })
        advanceUntilIdle()

        coVerify(exactly = 1) { updateManga.awaitUpdateFavorites(listOf(10L, 50L, 20L), true) }
        listOf(10L, 50L, 20L).forEach { coVerify(exactly = 1) { setMangaCategories.await(it, listOf(6L)) } }
        viewModel.events.first() shouldBe SourceBrowseViewModel.Event.Added(3)
        viewModel.state.value.addCategories.shouldBeNull()
        viewModel.state.value.selection shouldBe emptySet()
    }

    @Test
    fun `dismissing the picker writes nothing and keeps the selection`() = runTest(testDispatcher) {
        coEvery { getCategories.await() } returns listOf(system(), category(5L))
        libraryPreferences.defaultCategory.set(-1)
        val viewModel = loaded()
        viewModel.toggleSelection(10L)
        viewModel.addSelectedToLibrary()
        advanceUntilIdle()

        viewModel.dismissAddCategories()
        advanceUntilIdle()

        viewModel.state.value.addCategories.shouldBeNull()
        viewModel.state.value.selection shouldBe setOf(10L)
        coVerify(exactly = 0) { updateManga.awaitUpdateFavorites(any(), any()) }
        coVerify(exactly = 0) { setMangaCategories.await(any(), any()) }
    }

    @Test
    fun `adding a selection that is all in the library reports it, writes nothing and clears the selection`() =
        runTest(testDispatcher) {
            val viewModel = loaded()
            favorites.value = listOf(manga(10L).copy(favorite = true), manga(20L).copy(favorite = true))
            advanceUntilIdle()
            viewModel.toggleSelection(10L)
            viewModel.toggleSelection(20L)

            viewModel.addSelectedToLibrary()
            advanceUntilIdle()

            viewModel.events.first() shouldBe SourceBrowseViewModel.Event.AllInLibrary
            coVerify(exactly = 0) { updateManga.awaitUpdateFavorites(any(), any()) }
            coVerify(exactly = 0) { setMangaCategories.await(any(), any()) }
            viewModel.state.value.selection shouldBe emptySet()
        }

    private fun kotlinx.coroutines.test.TestScope.loaded(
        migratingMangaId: Long? = null,
        expected: List<Long> = listing,
    ): SourceBrowseViewModel {
        val viewModel = viewModel(migratingMangaId)
        advanceUntilIdle()
        viewModel.state.value.items.map { it.id } shouldContainExactly expected
        return viewModel
    }

    private fun system() = category(Category.UNCATEGORIZED_ID)

    private fun category(id: Long) = Category(id = id, name = "Category $id", order = id, flags = 0L)

    private fun manga(id: Long) = Manga.create().copy(id = id, source = SOURCE_ID, url = "/m/$id", title = "Fixture $id")

    private fun viewModel(migratingMangaId: Long?) = SourceBrowseViewModel(
        sourceId = SOURCE_ID,
        query = null,
        mode = SourceBrowseMode.POPULAR,
        migratingMangaId = migratingMangaId,
        getSourceMangaPage = getSourceMangaPage,
        sourceManager = sourceManager,
        sourcePreferences = sourcePreferences,
        libraryPreferences = libraryPreferences,
        getFavorites = getFavorites,
        getManga = mockk(relaxed = true),
        incognitoPolicy = incognitoPolicy,
        updateManga = updateManga,
        getCategories = getCategories,
        setMangaCategories = setMangaCategories,
        setMangaDefaultChapterFlags = setMangaDefaultChapterFlags,
        dispatchers = AppDispatchers(io = testDispatcher, default = testDispatcher, main = testDispatcher),
    )

    private companion object {
        const val SOURCE_ID = 11L
    }
}

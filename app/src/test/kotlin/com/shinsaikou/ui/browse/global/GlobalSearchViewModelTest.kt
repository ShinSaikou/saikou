package com.shinsaikou.ui.browse.global

import com.shinsaikou.domain.manga.interactor.UpdateManga
import com.shinsaikou.domain.merged.interactor.AddMergedEntry
import com.shinsaikou.domain.merged.interactor.MergeEntries
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.SourcePreferences
import com.shinsaikou.domain.source.interactor.BrowseMode
import com.shinsaikou.domain.source.interactor.BrowsePage
import com.shinsaikou.domain.source.interactor.GetSourceMangaPage
import com.shinsaikou.extensions.ExtensionManager
import com.shinsaikou.extensions.model.Extension
import com.shinsaikou.extensions.source.DefaultSourceManager
import com.shinsaikou.source.CatalogueSource
import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceIds
import com.shinsaikou.source.SourceManager
import com.shinsaikou.source.SourceProvider
import com.shinsaikou.source.StubSourceNames
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
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
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import java.io.File
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers
import kotlinx.coroutines.Dispatchers as KotlinDispatchers

/**
 * A global search fans one query out to every eligible source at once, so the decisions worth
 * pinning here are which sources are eligible (language, hidden, NSFW), the order the rows render
 * in (mirroring the sources list the owner already knows from Browse), and that a slow reply from a
 * search the owner has since replaced cannot land in the new search's groups. A search restored
 * after process death runs as the process starts, so it must also wait for the source registry.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GlobalSearchViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val registryScope = CoroutineScope(SupervisorJob() + testDispatcher)

    private val getSourceMangaPage = mockk<GetSourceMangaPage>()
    private val getManga = mockk<GetManga>()
    private val sourceManager = mockk<SourceManager> {
        every { isInitialized } returns MutableStateFlow(true)
    }
    private val sourcePreferences = SourcePreferences(InMemoryPreferenceStore())
    private val extensionManager = mockk<ExtensionManager> {
        every { installedExtensions } returns MutableStateFlow<List<Extension.Installed>>(emptyList())
    }
    private val addMergedEntry = mockk<AddMergedEntry>(relaxed = true)
    private val mergeEntries = mockk<MergeEntries>(relaxed = true)
    private val favoriteIds = MutableStateFlow(emptySet<Long>())
    private val mangaRepository = mockk<MangaRepository> {
        every { getFavoriteIdsAsFlow() } returns favoriteIds
    }

    @BeforeEach
    fun setUp() {
        KotlinDispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        registryScope.cancel()
        KotlinDispatchers.resetMain()
    }

    @Test
    fun `submitSearch does nothing for a blank query`() = runTest(testDispatcher) {
        val viewModel = viewModel()

        viewModel.submitSearch()
        advanceUntilIdle()

        coVerify(exactly = 0) { getSourceMangaPage.await(any(), any(), any()) }
        viewModel.state.value.searching shouldBe false
        viewModel.state.value.groups shouldBe emptyList()
    }

    @Test
    fun `retry with nothing submitted yet does nothing`() = runTest(testDispatcher) {
        val viewModel = viewModel()

        viewModel.retry()
        advanceUntilIdle()

        coVerify(exactly = 0) { getSourceMangaPage.await(any(), any(), any()) }
    }

    @Test
    fun `a source in a disabled language is left out of the search`() = runTest(testDispatcher) {
        every { sourceManager.getCatalogueSources() } returns
            flowOf(listOf(catalogueSource(1L, "English Source", "en"), catalogueSource(2L, "French Source", "fr")))
        sourcePreferences.enabledLanguages.set(setOf("en"))
        coEvery { getSourceMangaPage.await(any(), any(), any()) } returns emptyResultPage()

        val viewModel = viewModel()
        viewModel.setQuery("one piece")
        viewModel.submitSearch()
        advanceUntilIdle()

        viewModel.state.value.groups.map { it.sourceId } shouldContainExactly listOf(1L)
    }

    @Test
    fun `all enables multi-language sources only, not every language`() = runTest(testDispatcher) {
        // Ruling R57, on the owner's restored upstream set: English plus multi-language sources.
        every { sourceManager.getCatalogueSources() } returns flowOf(
            listOf(
                catalogueSource(1L, "English Source", "en"),
                catalogueSource(2L, "Multi Source", "all"),
                catalogueSource(3L, "Japanese Source", "ja"),
            ),
        )
        sourcePreferences.enabledLanguages.set(setOf("all", "en-US", "en"))
        coEvery { getSourceMangaPage.await(any(), any(), any()) } returns emptyResultPage()

        val viewModel = viewModel()
        viewModel.setQuery("one piece")
        viewModel.submitSearch()
        advanceUntilIdle()

        viewModel.state.value.groups.map { it.sourceId } shouldContainExactlyInAnyOrder listOf(1L, 2L)
    }

    @Test
    fun `the Local source is searched whatever languages are enabled`() = runTest(testDispatcher) {
        every { sourceManager.getCatalogueSources() } returns flowOf(
            listOf(catalogueSource(SourceIds.LOCAL, "Local", "other"), catalogueSource(2L, "French Source", "fr")),
        )
        sourcePreferences.enabledLanguages.set(setOf("en"))
        coEvery { getSourceMangaPage.await(any(), any(), any()) } returns emptyResultPage()

        val viewModel = viewModel()
        viewModel.setQuery("one piece")
        viewModel.submitSearch()
        advanceUntilIdle()

        viewModel.state.value.groups.map { it.sourceId } shouldContainExactly listOf(SourceIds.LOCAL)
    }

    @Test
    fun `a hidden source is left out of the search`() = runTest(testDispatcher) {
        every { sourceManager.getCatalogueSources() } returns
            flowOf(listOf(catalogueSource(1L, "Shown", "en"), catalogueSource(2L, "Hidden", "en")))
        sourcePreferences.disabledSources.set(setOf("2"))
        coEvery { getSourceMangaPage.await(any(), any(), any()) } returns emptyResultPage()

        val viewModel = viewModel()
        viewModel.setQuery("one piece")
        viewModel.submitSearch()
        advanceUntilIdle()

        viewModel.state.value.groups.map { it.sourceId } shouldContainExactly listOf(1L)
    }

    @Test
    fun `an NSFW source is left out unless the NSFW switch is on`() = runTest(testDispatcher) {
        every { sourceManager.getCatalogueSources() } returns
            flowOf(listOf(catalogueSource(1L, "Clean", "en"), catalogueSource(2L, "Adult", "en")))
        every { extensionManager.installedExtensions } returns
            MutableStateFlow<List<Extension.Installed>>(listOf(installedExtension(nsfw = true, sourceIds = listOf(2L))))
        sourcePreferences.showNsfwSource.set(false)
        coEvery { getSourceMangaPage.await(any(), any(), any()) } returns emptyResultPage()

        val viewModel = viewModel()
        viewModel.setQuery("one piece")
        viewModel.submitSearch()
        advanceUntilIdle()

        viewModel.state.value.groups.map { it.sourceId } shouldContainExactly listOf(1L)
    }

    @Test
    fun `rows render pinned first, then the last used source, then English, then by name`() = runTest(testDispatcher) {
        every { sourceManager.getCatalogueSources() } returns flowOf(
            listOf(
                catalogueSource(1L, "Zulu", "en"),
                catalogueSource(2L, "Alpha", "en"),
                catalogueSource(3L, "Beta", "fr"),
                catalogueSource(4L, "Pinned", "fr"),
            ),
        )
        sourcePreferences.enabledLanguages.set(setOf("en", "fr"))
        sourcePreferences.pinnedSources.set(setOf("4"))
        sourcePreferences.lastUsedSource.set(1L)
        coEvery { getSourceMangaPage.await(any(), any(), any()) } returns emptyResultPage()

        val viewModel = viewModel()
        viewModel.setQuery("one piece")
        viewModel.submitSearch()
        advanceUntilIdle()

        // Pinned beats everything; among the rest the last used source beats plain ordering; among
        // what is left, English sorts before French, then it is alphabetical.
        viewModel.state.value.groups.map { it.sourceId } shouldContainExactly listOf(4L, 1L, 2L, 3L)
    }

    @Test
    fun `the Local source is asked after every extension source but the pinned ones`() = runTest(testDispatcher) {
        every { sourceManager.getCatalogueSources() } returns flowOf(
            listOf(
                catalogueSource(1L, "Zulu", "en"),
                catalogueSource(SourceIds.LOCAL, "Local", "other"),
                catalogueSource(2L, "Alpha", "fr"),
                catalogueSource(3L, "Pinned", "fr"),
            ),
        )
        sourcePreferences.enabledLanguages.set(setOf("en", "fr"))
        sourcePreferences.pinnedSources.set(setOf("3"))
        // Even the last used source, which otherwise leads the unpinned ones.
        sourcePreferences.lastUsedSource.set(SourceIds.LOCAL)
        coEvery { getSourceMangaPage.await(any(), any(), any()) } returns emptyResultPage()

        val viewModel = viewModel()
        viewModel.setQuery("one piece")
        viewModel.submitSearch()
        advanceUntilIdle()

        viewModel.state.value.groups.map { it.sourceId } shouldContainExactly listOf(3L, 1L, 2L, SourceIds.LOCAL)
    }

    @Test
    fun `Pinned asks only the pinned sources, and the choice is remembered`() = runTest(testDispatcher) {
        every { sourceManager.getCatalogueSources() } returns
            flowOf(listOf(catalogueSource(1L, "Alpha", "en"), catalogueSource(2L, "Beta", "en")))
        sourcePreferences.enabledLanguages.set(setOf("en"))
        sourcePreferences.pinnedSources.set(setOf("2"))
        coEvery { getSourceMangaPage.await(any(), any(), any()) } returns emptyResultPage()

        val viewModel = viewModel()
        advanceUntilIdle()
        // All is where a device that never chose starts.
        viewModel.state.value.pinnedOnly shouldBe false
        viewModel.setPinnedOnly(true)
        viewModel.setQuery("one piece")
        viewModel.submitSearch()
        advanceUntilIdle()

        viewModel.state.value.groups.map { it.sourceId } shouldContainExactly listOf(2L)
        coVerify(exactly = 0) { getSourceMangaPage.await(1L, any(), any()) }
        val next = viewModel()
        advanceUntilIdle()
        next.state.value.pinnedOnly shouldBe true
    }

    @Test
    fun `switching to All on a search shown asks only the sources it adds and keeps the field's text`() =
        runTest(testDispatcher) {
            every { sourceManager.getCatalogueSources() } returns
                flowOf(listOf(catalogueSource(1L, "Alpha", "en"), catalogueSource(2L, "Beta", "en")))
            sourcePreferences.enabledLanguages.set(setOf("en"))
            sourcePreferences.pinnedSources.set(setOf("1"))
            sourcePreferences.globalSearchPinnedOnly.set(true)
            coEvery { getSourceMangaPage.await(1L, any(), any()) } returns
                BrowsePage(mangas = listOf(manga(100)), hasNextPage = false, page = 1)
            coEvery { getSourceMangaPage.await(2L, any(), any()) } returns emptyResultPage()

            val viewModel = viewModel()
            viewModel.setQuery("one piece")
            viewModel.submitSearch()
            advanceUntilIdle()
            viewModel.setQuery("typed since")

            viewModel.setPinnedOnly(false)
            advanceUntilIdle()

            viewModel.state.value.groups.map { it.sourceId } shouldContainExactly listOf(1L, 2L)
            viewModel.state.value.groups.map { it.status } shouldContainExactly listOf(
                GlobalSearchViewModel.Status.Results(listOf(manga(100))),
                GlobalSearchViewModel.Status.Empty,
            )
            coVerify(exactly = 1) { getSourceMangaPage.await(1L, any(), any()) }
            coVerify(exactly = 1) { getSourceMangaPage.await(2L, any(), any()) }
            viewModel.state.value.query shouldBe "typed since"
            viewModel.state.value.submittedQuery shouldBe "one piece"

            // And back to Pinned asks nothing.
            viewModel.setPinnedOnly(true)
            advanceUntilIdle()
            viewModel.state.value.groups.map { it.sourceId } shouldContainExactly listOf(1L)
            coVerify(exactly = 1) { getSourceMangaPage.await(1L, any(), any()) }
        }

    @Test
    fun `Pinned with no pinned source to ask says so`() = runTest(testDispatcher) {
        every { sourceManager.getCatalogueSources() } returns flowOf(listOf(catalogueSource(1L, "Alpha", "en")))
        sourcePreferences.enabledLanguages.set(setOf("en"))
        sourcePreferences.globalSearchPinnedOnly.set(true)

        val viewModel = viewModel()
        viewModel.setQuery("one piece")
        viewModel.submitSearch()
        advanceUntilIdle()

        viewModel.state.value.noPinnedSources shouldBe true
        coVerify(exactly = 0) { getSourceMangaPage.await(any(), any(), any()) }
    }

    @Test
    fun `Has results hides the groups with nothing to show, and is remembered`() = runTest(testDispatcher) {
        every { sourceManager.getCatalogueSources() } returns
            flowOf(listOf(catalogueSource(1L, "Alpha", "en"), catalogueSource(2L, "Beta", "en")))
        sourcePreferences.enabledLanguages.set(setOf("en"))
        coEvery { getSourceMangaPage.await(1L, any(), any()) } returns
            BrowsePage(mangas = listOf(manga(100)), hasNextPage = false, page = 1)
        coEvery { getSourceMangaPage.await(2L, any(), any()) } returns emptyResultPage()

        val viewModel = viewModel()
        viewModel.setQuery("one piece")
        viewModel.submitSearch()
        advanceUntilIdle()
        viewModel.state.value.visibleGroups.map { it.sourceId } shouldContainExactly listOf(1L, 2L)

        viewModel.toggleHasResults()
        advanceUntilIdle()

        viewModel.state.value.visibleGroups.map { it.sourceId } shouldContainExactly listOf(1L)
        sourcePreferences.globalSearchFilterState.get() shouldBe true
        coVerify(exactly = 1) { getSourceMangaPage.await(2L, any(), any()) }
    }

    @Test
    fun `a result is marked in the library from the live favourites, its own flag until they are read`() =
        runTest(testDispatcher) {
            val viewModel = viewModel()
            val result = manga(100).copy(favorite = true)
            viewModel.state.value.inLibrary(result) shouldBe true

            advanceUntilIdle()
            viewModel.state.value.inLibrary(result) shouldBe false

            // Added from its details while the search stays on screen.
            favoriteIds.value = setOf(100L)
            advanceUntilIdle()
            viewModel.state.value.inLibrary(manga(100)) shouldBe true
        }

    @Test
    fun `migration mode asks the migration's sources whatever Pinned says`() = runTest(testDispatcher) {
        every { sourceManager.getCatalogueSources() } returns flowOf(emptyList())
        every { sourceManager.get(3L) } returns catalogueSource(3L, "Source 3", "en")
        sourcePreferences.globalSearchPinnedOnly.set(true)
        sourcePreferences.migrationSources.set(listOf(3L))
        coEvery { getManga.await(ENTRY_ID) } returns entry
        coEvery { getSourceMangaPage.await(any(), any(), any()) } returns emptyResultPage()

        val viewModel = viewModel(migratingMangaId = ENTRY_ID)
        advanceUntilIdle()
        viewModel.setPinnedOnly(false)
        advanceUntilIdle()

        viewModel.state.value.groups.map { it.sourceId } shouldContainExactly listOf(3L)
        sourcePreferences.globalSearchPinnedOnly.get() shouldBe true
    }

    @Test
    fun `a migration with no target source loaded finds nothing, never that nothing is pinned`() =
        runTest(testDispatcher) {
            every { sourceManager.get(3L) } returns null
            sourcePreferences.globalSearchPinnedOnly.set(true)
            sourcePreferences.migrationSources.set(listOf(3L))
            coEvery { getManga.await(ENTRY_ID) } returns entry

            val viewModel = viewModel(migratingMangaId = ENTRY_ID)
            advanceUntilIdle()

            // A migration has no Pinned chip, so its empty screen is the one with no results.
            viewModel.state.value.noPinnedSources shouldBe false
            viewModel.state.value.noResults shouldBe true
        }

    @Test
    fun `a source with nothing to show is Empty, not an empty Results`() = runTest(testDispatcher) {
        every { sourceManager.getCatalogueSources() } returns flowOf(listOf(catalogueSource(1L, "Fixture", "en")))
        coEvery { getSourceMangaPage.await(any(), any(), any()) } returns emptyResultPage()

        val viewModel = viewModel()
        viewModel.setQuery("nothing here")
        viewModel.submitSearch()
        advanceUntilIdle()

        viewModel.state.value.groups.single().status shouldBe GlobalSearchViewModel.Status.Empty
    }

    @Test
    fun `a reply from a search the owner has replaced does not overwrite the newer groups`() = runTest(testDispatcher) {
        every { sourceManager.getCatalogueSources() } returns flowOf(listOf(catalogueSource(1L, "Fixture", "en")))
        coEvery { getSourceMangaPage.await(1L, match { it is BrowseMode.Search && it.query == "first" }, any()) } coAnswers {
            delay(10_000)
            BrowsePage(mangas = listOf(manga(100)), hasNextPage = false, page = 1)
        }
        coEvery { getSourceMangaPage.await(1L, match { it is BrowseMode.Search && it.query == "second" }, any()) } returns
            BrowsePage(mangas = listOf(manga(200)), hasNextPage = false, page = 1)

        val viewModel = viewModel()
        viewModel.setQuery("first")
        viewModel.submitSearch()
        // Let the first search start its request, but not far enough for it to resolve.
        advanceTimeBy(1)
        runCurrent()

        viewModel.setQuery("second")
        viewModel.submitSearch()
        advanceUntilIdle()

        val group = viewModel.state.value.groups.single()
        (group.status as GlobalSearchViewModel.Status.Results).mangas.map { it.id } shouldContainExactly listOf(200L)
        coVerify(exactly = 1) {
            getSourceMangaPage.await(1L, match { it is BrowseMode.Search && it.query == "second" }, any())
        }
    }

    @Test
    fun `a search restored before the registry has loaded waits, then searches every loaded source`() = runTest(testDispatcher) {
        val extensions = FakeProvider()
        val registry = registry(localProvider(), extensions)
        sourcePreferences.enabledLanguages.set(setOf("en"))
        coEvery { getSourceMangaPage.await(any(), any(), any()) } returns emptyResultPage()

        // The restored screen hands its query back, and the search runs from init.
        val viewModel = viewModel(initialQuery = "one piece", sourceManager = registry)
        advanceUntilIdle()

        coVerify(exactly = 0) { getSourceMangaPage.await(any(), any(), any()) }
        viewModel.state.value.searching shouldBe true
        viewModel.state.value.groups shouldBe emptyList()

        extensions.sources.value = listOf(catalogueSource(EXTENSION_SOURCE_ID, "Extension Source", "en"))
        extensions.isInitialized.value = true
        advanceUntilIdle()

        viewModel.state.value.groups.map { it.sourceId } shouldContainExactly listOf(EXTENSION_SOURCE_ID, SourceIds.LOCAL)
        viewModel.state.value.searching shouldBe false
    }

    @Test
    fun `a search restored cold still runs after a failed extension load, over what did load`() = runTest(testDispatcher) {
        val extensions = FakeProvider()
        val registry = registry(localProvider(), extensions)
        coEvery { getSourceMangaPage.await(any(), any(), any()) } returns emptyResultPage()

        val viewModel = viewModel(initialQuery = "one piece", sourceManager = registry)
        advanceUntilIdle()
        // A failed first load ends with the flag set and nothing published.
        extensions.isInitialized.value = true
        advanceUntilIdle()

        viewModel.state.value.groups.map { it.sourceId } shouldContainExactly listOf(SourceIds.LOCAL)
        viewModel.state.value.searching shouldBe false
    }

    // ---- Manual search for a migration (phase 4 unit MB) ----

    @Test
    fun `migration mode searches the entry's title at once over the target sources in saved order, after the registry`() =
        runTest(testDispatcher) {
            val extensions = FakeProvider()
            val registry = registry(localProvider(), extensions)
            // The Sources page would search the English source alone; the migration's list holds a
            // hidden French source and leaves the English one out.
            sourcePreferences.enabledLanguages.set(setOf("en"))
            sourcePreferences.disabledSources.set(setOf("3"))
            sourcePreferences.migrationSources.set(listOf(3L, 2L, 9L))
            coEvery { getManga.await(ENTRY_ID) } returns entry
            coEvery { getSourceMangaPage.await(any(), any(), any()) } returns emptyResultPage()

            val viewModel = viewModel(migratingMangaId = ENTRY_ID, sourceManager = registry)
            advanceUntilIdle()

            coVerify(exactly = 0) { getSourceMangaPage.await(any(), any(), any()) }
            viewModel.state.value.query shouldBe "Harbor Lantern"
            viewModel.state.value.migratingFrom shouldBe entry

            extensions.sources.value = listOf(
                catalogueSource(1L, "English Source", "en"),
                catalogueSource(2L, "Second Source", "fr"),
                catalogueSource(3L, "Hidden Source", "fr"),
            )
            extensions.isInitialized.value = true
            advanceUntilIdle()

            viewModel.state.value.groups.map { it.sourceId } shouldContainExactly listOf(3L, 2L)
            coVerify(exactly = 1) {
                getSourceMangaPage.await(3L, match { it is BrowseMode.Search && it.query == "Harbor Lantern" }, 1)
            }
            coVerify(exactly = 0) { getSourceMangaPage.await(1L, any(), any()) }
        }

    @Test
    fun `migration mode puts the groups with results first, then the saved order, as the sources answer`() =
        runTest(testDispatcher) {
            every { sourceManager.getCatalogueSources() } returns flowOf(emptyList())
            listOf(1L, 2L, 3L).forEach { id ->
                every { sourceManager.get(id) } returns catalogueSource(id, "Source $id", "en")
            }
            sourcePreferences.migrationSources.set(listOf(1L, 2L, 3L))
            coEvery { getManga.await(ENTRY_ID) } returns entry
            coEvery { getSourceMangaPage.await(1L, any(), any()) } coAnswers {
                delay(300)
                emptyResultPage()
            }
            coEvery { getSourceMangaPage.await(2L, any(), any()) } coAnswers {
                delay(200)
                BrowsePage(mangas = listOf(manga(20)), hasNextPage = false, page = 1)
            }
            coEvery { getSourceMangaPage.await(3L, any(), any()) } coAnswers {
                delay(100)
                BrowsePage(mangas = listOf(manga(30)), hasNextPage = false, page = 1)
            }

            val viewModel = viewModel(migratingMangaId = ENTRY_ID)
            runCurrent()
            viewModel.state.value.groups.map { it.sourceId } shouldContainExactly listOf(1L, 2L, 3L)

            advanceTimeBy(150)
            viewModel.state.value.groups.map { it.sourceId } shouldContainExactly listOf(3L, 1L, 2L)

            advanceTimeBy(100)
            viewModel.state.value.groups.map { it.sourceId } shouldContainExactly listOf(2L, 3L, 1L)

            advanceUntilIdle()
            viewModel.state.value.groups.map { it.sourceId } shouldContainExactly listOf(2L, 3L, 1L)
            viewModel.state.value.groups.last().status shouldBe GlobalSearchViewModel.Status.Empty
        }

    @Test
    fun `migration mode shows every result, the entry's own row included, under its own source`() = runTest(testDispatcher) {
        every { sourceManager.getCatalogueSources() } returns flowOf(emptyList())
        every { sourceManager.get(ENTRY_SOURCE_ID) } returns catalogueSource(ENTRY_SOURCE_ID, "Own Source", "en")
        sourcePreferences.migrationSources.set(listOf(ENTRY_SOURCE_ID))
        coEvery { getManga.await(ENTRY_ID) } returns entry
        coEvery { getSourceMangaPage.await(ENTRY_SOURCE_ID, any(), any()) } returns
            BrowsePage(mangas = listOf(entry, manga(40)), hasNextPage = false, page = 1)

        val viewModel = viewModel(migratingMangaId = ENTRY_ID)
        advanceUntilIdle()

        val group = viewModel.state.value.groups.single()
        // The screen heads this group with the "▶" of the entry's own source.
        group.sourceId shouldBe viewModel.state.value.migratingFrom?.source
        (group.status as GlobalSearchViewModel.Status.Results).mangas.map { it.id } shouldContainExactly listOf(ENTRY_ID, 40L)
    }

    @Test
    fun `a result picked with no migration list below opens the migrate dialog, and dismissing closes it`() =
        runTest(testDispatcher) {
            every { sourceManager.getCatalogueSources() } returns flowOf(emptyList())
            coEvery { getManga.await(ENTRY_ID) } returns entry
            val viewModel = viewModel(migratingMangaId = ENTRY_ID)
            advanceUntilIdle()

            viewModel.showMigrateDialog(manga(40))
            viewModel.state.value.migrateTarget shouldBe manga(40)
            viewModel.dismissMigrateDialog()
            viewModel.state.value.migrateTarget shouldBe null
        }

    // ---- Merge with... (R163) ----

    private val mergeInto = Manga.create().copy(id = ENTRY_ID, source = ENTRY_SOURCE_ID, url = "/m/entry", title = "Harbor Lantern", favorite = true)

    @Test
    fun `merge mode searches the entry's title at once over the sources Global Search asks`() = runTest(testDispatcher) {
        every { sourceManager.getCatalogueSources() } returns
            flowOf(listOf(catalogueSource(1L, "English Source", "en"), catalogueSource(2L, "French Source", "fr")))
        sourcePreferences.enabledLanguages.set(setOf("en"))
        coEvery { getManga.await(ENTRY_ID) } returns mergeInto
        coEvery { getSourceMangaPage.await(any(), any(), any()) } returns emptyResultPage()

        val viewModel = viewModel(mergingMangaId = ENTRY_ID)
        advanceUntilIdle()

        viewModel.state.value.query shouldBe "Harbor Lantern"
        viewModel.state.value.mergingInto shouldBe mergeInto
        viewModel.state.value.migratingFrom shouldBe null
        viewModel.state.value.groups.map { it.sourceId } shouldContainExactly listOf(1L)
        coVerify(exactly = 1) {
            getSourceMangaPage.await(1L, match { it is BrowseMode.Search && it.query == "Harbor Lantern" }, 1)
        }
    }

    @Test
    fun `merge mode for an entry that is gone searches nothing`() = runTest(testDispatcher) {
        every { sourceManager.getCatalogueSources() } returns flowOf(listOf(catalogueSource(1L, "English Source", "en")))
        coEvery { getManga.await(ENTRY_ID) } returns null

        val viewModel = viewModel(mergingMangaId = ENTRY_ID)
        advanceUntilIdle()

        viewModel.state.value.mergingInto shouldBe null
        coVerify(exactly = 0) { getSourceMangaPage.await(any(), any(), any()) }
    }

    @Test
    fun `a result picked in merge mode asks first, naming its source, and dismissing closes it`() = runTest(testDispatcher) {
        every { sourceManager.getCatalogueSources() } returns flowOf(listOf(catalogueSource(0L, "Source Zero", "en")))
        coEvery { getManga.await(ENTRY_ID) } returns mergeInto
        coEvery { getSourceMangaPage.await(any(), any(), any()) } returns
            BrowsePage(mangas = listOf(manga(40)), hasNextPage = false, page = 1)
        val viewModel = viewModel(mergingMangaId = ENTRY_ID)
        advanceUntilIdle()

        viewModel.showMergeDialog(manga(40))

        viewModel.state.value.mergeTarget shouldBe manga(40)
        viewModel.state.value.mergeTargetSource shouldBe "Source Zero"
        coVerify(exactly = 0) { mergeEntries.await(any(), any()) }

        viewModel.dismissMergeDialog()
        viewModel.state.value.mergeTarget shouldBe null
    }

    @Test
    fun `the entry itself is no result to merge with`() = runTest(testDispatcher) {
        every { sourceManager.getCatalogueSources() } returns flowOf(emptyList())
        coEvery { getManga.await(ENTRY_ID) } returns mergeInto
        val viewModel = viewModel(mergingMangaId = ENTRY_ID)
        advanceUntilIdle()

        viewModel.showMergeDialog(mergeInto)

        viewModel.state.value.mergeTarget shouldBe null
    }

    @Test
    fun `confirming adds both entries and merges them with the search's entry as main, then returns to the entry`() =
        runTest(testDispatcher) {
            every { sourceManager.getCatalogueSources() } returns flowOf(emptyList())
            coEvery { getManga.await(ENTRY_ID) } returns mergeInto
            val viewModel = viewModel(mergingMangaId = ENTRY_ID)
            advanceUntilIdle()
            viewModel.showMergeDialog(manga(40))
            var merged = 0
            var failed = 0

            viewModel.confirmMerge(onMerged = { merged++ }, onFailed = { failed++ })
            advanceUntilIdle()

            coVerifyOrder {
                addMergedEntry.await(mergeInto)
                addMergedEntry.await(manga(40))
                mergeEntries.await(ENTRY_ID, listOf(ENTRY_ID, 40L))
            }
            merged shouldBe 1
            failed shouldBe 0
            viewModel.state.value.mergeTarget shouldBe null
        }

    @Test
    fun `a target and an entry that are not in the library yet are added to it, and one that is stays as it is`() =
        runTest(testDispatcher) {
            val updateManga = mockk<UpdateManga>(relaxed = true)
            val add = AddMergedEntry(updateManga, mockk(relaxed = true), mockk<GetCategories>(relaxed = true), mockk<SetMangaCategories>(relaxed = true), LibraryPreferences(InMemoryPreferenceStore()))
            every { sourceManager.getCatalogueSources() } returns flowOf(emptyList())
            val notYetFavourite = mergeInto.copy(favorite = false)
            coEvery { getManga.await(ENTRY_ID) } returns notYetFavourite
            val viewModel = viewModel(mergingMangaId = ENTRY_ID, addMergedEntry = add)
            advanceUntilIdle()
            viewModel.showMergeDialog(manga(40))
            viewModel.confirmMerge(onMerged = {}, onFailed = {})
            advanceUntilIdle()

            coVerify(exactly = 1) { updateManga.awaitUpdateFavorite(ENTRY_ID, true) }
            coVerify(exactly = 1) { updateManga.awaitUpdateFavorite(40L, true) }

            // The same again with both in the library already: nothing is flipped, so no date added resets.
            val inLibrary = mockk<UpdateManga>(relaxed = true)
            val addAgain = AddMergedEntry(inLibrary, mockk(relaxed = true), mockk<GetCategories>(relaxed = true), mockk<SetMangaCategories>(relaxed = true), LibraryPreferences(InMemoryPreferenceStore()))
            coEvery { getManga.await(ENTRY_ID) } returns mergeInto
            val second = viewModel(mergingMangaId = ENTRY_ID, addMergedEntry = addAgain)
            advanceUntilIdle()
            second.showMergeDialog(manga(41).copy(favorite = true))
            second.confirmMerge(onMerged = {}, onFailed = {})
            advanceUntilIdle()

            coVerify(exactly = 0) { inLibrary.awaitUpdateFavorite(any(), any()) }
        }

    @Test
    fun `a failure before the merge is reported, nothing is merged, and what was added stays`() = runTest(testDispatcher) {
        every { sourceManager.getCatalogueSources() } returns flowOf(emptyList())
        coEvery { getManga.await(ENTRY_ID) } returns mergeInto
        coEvery { addMergedEntry.await(manga(40)) } throws java.io.IOException("disk full")
        val viewModel = viewModel(mergingMangaId = ENTRY_ID)
        advanceUntilIdle()
        viewModel.showMergeDialog(manga(40))
        var merged = 0
        var failed = 0

        viewModel.confirmMerge(onMerged = { merged++ }, onFailed = { failed++ })
        advanceUntilIdle()

        merged shouldBe 0
        failed shouldBe 1
        coVerify(exactly = 1) { addMergedEntry.await(mergeInto) }
        coVerify(exactly = 0) { mergeEntries.await(any(), any()) }
    }

    @Test
    fun `a merge that throws is reported and does not return to the entry`() = runTest(testDispatcher) {
        every { sourceManager.getCatalogueSources() } returns flowOf(emptyList())
        coEvery { getManga.await(ENTRY_ID) } returns mergeInto
        coEvery { mergeEntries.await(any(), any()) } throws IllegalStateException("main is not a member")
        val viewModel = viewModel(mergingMangaId = ENTRY_ID)
        advanceUntilIdle()
        viewModel.showMergeDialog(manga(40))
        var merged = 0
        var failed = 0

        viewModel.confirmMerge(onMerged = { merged++ }, onFailed = { failed++ })
        advanceUntilIdle()

        merged shouldBe 0
        failed shouldBe 1
    }

    @Test
    fun `confirming with no result picked, or outside merge mode, does nothing`() = runTest(testDispatcher) {
        every { sourceManager.getCatalogueSources() } returns flowOf(emptyList())
        coEvery { getManga.await(ENTRY_ID) } returns mergeInto
        var called = 0

        val merging = viewModel(mergingMangaId = ENTRY_ID)
        advanceUntilIdle()
        merging.confirmMerge(onMerged = { called++ }, onFailed = { called++ })

        val plain = viewModel()
        advanceUntilIdle()
        plain.showMergeDialog(manga(40))
        plain.confirmMerge(onMerged = { called++ }, onFailed = { called++ })
        advanceUntilIdle()

        called shouldBe 0
        coVerify(exactly = 0) { addMergedEntry.await(any()) }
        coVerify(exactly = 0) { mergeEntries.await(any(), any()) }
    }

    /**
     * The app's registry, over [providers], collecting on the test's scheduler. Its scope is not
     * the test's background scope, whose work `advanceUntilIdle` does not wait for; it is cancelled
     * after each test instead, since the registry's collector never completes.
     */
    private fun registry(vararg providers: SourceProvider) = DefaultSourceManager(
        providers = providers.toSet(),
        stubNames = NoStubNames,
        scope = registryScope,
        ioDispatcher = testDispatcher,
    )

    /** The Local source's provider: one source, ready from the start. */
    private fun localProvider() = FakeProvider(listOf(catalogueSource(SourceIds.LOCAL, "Local", "other")), initialized = true)

    /** An extension host: nothing loaded and not ready until a test ends its first load. */
    private class FakeProvider(sources: List<Source> = emptyList(), initialized: Boolean = false) : SourceProvider {
        override val sources = MutableStateFlow(sources)
        override val isInitialized = MutableStateFlow(initialized)
    }

    private object NoStubNames : StubSourceNames {
        override suspend fun find(id: Long): StubSourceNames.Entry? = null
        override suspend fun remember(id: Long, name: String, lang: String) = Unit
    }

    private fun catalogueSource(sourceId: Long, sourceName: String, sourceLang: String): CatalogueSource = mockk {
        every { id } returns sourceId
        every { name } returns sourceName
        every { lang } returns sourceLang
    }

    private fun installedExtension(nsfw: Boolean, sourceIds: List<Long>) = Extension.Installed(
        packageName = "dev.example.fixture",
        name = "Fixture Extension",
        versionName = "1.0",
        versionCode = 1L,
        libVersion = 1.6,
        lang = "en",
        isNsfw = nsfw,
        sources = sourceIds.map { catalogueSource(it, "Fixture $it", "en") },
        extFile = File("fixture.ext"),
    )

    private fun manga(id: Long) = Manga.create().copy(id = id, source = 0L, url = "/m/$id", title = "Fixture $id")

    private fun emptyResultPage() = BrowsePage(mangas = emptyList(), hasNextPage = false, page = 1)

    private val entry = Manga.create().copy(id = ENTRY_ID, source = ENTRY_SOURCE_ID, url = "/m/entry", title = "Harbor Lantern")

    private fun viewModel(
        initialQuery: String? = null,
        sourceManager: SourceManager = this.sourceManager,
        migratingMangaId: Long? = null,
        mergingMangaId: Long? = null,
        addMergedEntry: AddMergedEntry = this.addMergedEntry,
    ) = GlobalSearchViewModel(
        initialQuery = initialQuery,
        migratingMangaId = migratingMangaId,
        mergingMangaId = mergingMangaId,
        getManga = getManga,
        getSourceMangaPage = getSourceMangaPage,
        sourceManager = sourceManager,
        sourcePreferences = sourcePreferences,
        extensionManager = extensionManager,
        addMergedEntry = addMergedEntry,
        mergeEntries = mergeEntries,
        mangaRepository = mangaRepository,
        dispatchers = AppDispatchers(io = testDispatcher, default = testDispatcher, main = testDispatcher),
    )

    private companion object {
        const val EXTENSION_SOURCE_ID = 42L
        const val ENTRY_ID = 500L
        const val ENTRY_SOURCE_ID = 7L
    }
}

package com.shinsaikou.ui.migration.sources

import com.shinsaikou.domain.preferences.SourcePreferences
import com.shinsaikou.source.CatalogueSource
import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceIds
import com.shinsaikou.source.SourceManager
import com.shinsaikou.source.StubSource
import com.shinsaikou.ui.migration.FakeCatalogueSource
import eu.kanade.domain.source.interactor.SetMigrateSorting
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.manga.repository.MangaRepository
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers
import kotlinx.coroutines.Dispatchers as KotlinDispatchers

/**
 * Browse's Migrate tab's model (phase 4 unit MB, design g7 final section 19.5): the sources holding
 * library entries without Local, upstream's comparator in both modes and directions, the two buttons
 * writing both sort keys, and Loading until the registry has loaded.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MigrateSourcesViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val preferences = SourcePreferences(InMemoryPreferenceStore())
    private val counts = MutableStateFlow<Map<Long, Long>>(emptyMap())
    private val mangaRepository = mockk<MangaRepository> {
        every { getSourceIdsWithFavoriteCountAsFlow() } returns counts
    }
    private val initialized = MutableStateFlow(true)
    private val live = MutableStateFlow<List<CatalogueSource>>(emptyList())
    private val stubs = MutableStateFlow<List<StubSource>>(emptyList())

    /** What the registry remembers of each missing source; a missing id with no entry is unknown. */
    private val remembered = mutableMapOf<Long, StubSource>()
    private val sourceManager = mockk<SourceManager> {
        every { isInitialized } returns initialized
        every { getCatalogueSources() } returns live
        every { getStubSources() } returns stubs
        every { getOrStub(any()) } answers {
            val id = firstArg<Long>()
            live.value.firstOrNull { it.id == id } as Source? ?: remembered[id] ?: StubSource.unknown(id)
        }
    }

    @BeforeEach
    fun setUp() {
        KotlinDispatchers.setMain(dispatcher)
        every { sourceManager.get(any()) } answers { live.value.firstOrNull { it.id == firstArg<Long>() } }
    }

    @AfterEach
    fun tearDown() {
        KotlinDispatchers.resetMain()
    }

    private fun TestScope.viewModel(): MigrateSourcesViewModel {
        val viewModel = MigrateSourcesViewModel(
            preferences = preferences,
            setMigrateSorting = SetMigrateSorting(preferences),
            mangaRepository = mangaRepository,
            sourceManager = sourceManager,
            dispatchers = AppDispatchers(dispatcher, dispatcher, dispatcher),
        )
        advanceUntilIdle()
        return viewModel
    }

    private fun MigrateSourcesViewModel.ids() = state.value.items.map { it.id }

    /** Three live sources whose names differ in case only where the collator must ignore it. */
    private fun threeLiveAndOneGone() {
        live.value = listOf(
            FakeCatalogueSource(1L, "bravo"),
            FakeCatalogueSource(2L, "Alpha"),
            FakeCatalogueSource(3L, "Charlie"),
        )
        remembered[9L] = StubSource(9L, "Zulu", "en")
        counts.value = mapOf(1L to 5L, 2L to 1L, 3L to 12L, 9L to 3L)
    }

    @Test
    fun `Local is left out and a gone source is a stub row with its remembered name and count`() = runTest(dispatcher) {
        live.value = listOf(FakeCatalogueSource(1L, "Alpha"))
        remembered[9L] = StubSource(9L, "Zulu", "ja")
        counts.value = mapOf(SourceIds.LOCAL to 40L, 1L to 2L, 9L to 7L)

        val viewModel = viewModel()

        viewModel.state.value.items shouldContainExactly listOf(
            MigrateSourcesViewModel.SourceRow(id = 9L, name = "Zulu", lang = "ja", isStub = true, count = 7L),
            MigrateSourcesViewModel.SourceRow(id = 1L, name = "Alpha", lang = "en", isStub = false, count = 2L),
        )
    }

    @Test
    fun `by name ascending puts stubs first then names ignoring case, descending reverses all of it`() = runTest(dispatcher) {
        threeLiveAndOneGone()
        val viewModel = viewModel()

        viewModel.ids() shouldContainExactly listOf(9L, 2L, 1L, 3L)

        preferences.migrationSortingDirection.set(SetMigrateSorting.Direction.DESCENDING)
        advanceUntilIdle()

        viewModel.ids() shouldContainExactly listOf(3L, 1L, 2L, 9L)
    }

    @Test
    fun `by count ascending puts stubs first then the smallest, descending puts the largest first and stubs last`() = runTest(dispatcher) {
        threeLiveAndOneGone()
        preferences.migrationSortingMode.set(SetMigrateSorting.Mode.TOTAL)
        val viewModel = viewModel()

        viewModel.ids() shouldContainExactly listOf(9L, 2L, 1L, 3L)

        preferences.migrationSortingDirection.set(SetMigrateSorting.Direction.DESCENDING)
        advanceUntilIdle()

        viewModel.ids() shouldContainExactly listOf(3L, 1L, 2L, 9L)
    }

    @Test
    fun `each button writes both sort keys and the list follows`() = runTest(dispatcher) {
        threeLiveAndOneGone()
        val viewModel = viewModel()

        viewModel.toggleSortingMode()
        advanceUntilIdle()

        preferences.migrationSortingMode.get() shouldBe SetMigrateSorting.Mode.TOTAL
        preferences.migrationSortingDirection.isSet() shouldBe true
        preferences.migrationSortingDirection.get() shouldBe SetMigrateSorting.Direction.ASCENDING
        viewModel.state.value.sortingMode shouldBe SetMigrateSorting.Mode.TOTAL

        viewModel.toggleSortingDirection()
        advanceUntilIdle()

        preferences.migrationSortingMode.get() shouldBe SetMigrateSorting.Mode.TOTAL
        preferences.migrationSortingDirection.get() shouldBe SetMigrateSorting.Direction.DESCENDING
        viewModel.state.value.sortingDirection shouldBe SetMigrateSorting.Direction.DESCENDING
        viewModel.ids() shouldContainExactly listOf(3L, 1L, 2L, 9L)
    }

    @Test
    fun `the tab stays Loading until the registry has loaded, even with sources and counts in hand`() = runTest(dispatcher) {
        threeLiveAndOneGone()
        initialized.value = false

        val viewModel = viewModel()

        viewModel.state.value.isLoading shouldBe true
        viewModel.state.value.items.shouldBeEmpty()

        initialized.value = true
        advanceUntilIdle()

        viewModel.state.value.isLoading shouldBe false
        viewModel.ids() shouldContainExactly listOf(9L, 2L, 1L, 3L)
    }

    @Test
    fun `a load that ends while the rows are read never shows its sources as Not installed`() = runTest(dispatcher) {
        // R69 item 3: before the load a source reads as missing, and here the registry's flag turns
        // true right after such a read, before the source list follows. A flag read before the rows
        // is still false then, so the tab stays Loading; a flag read after them would pair true
        // with rows read before the load.
        val alpha = FakeCatalogueSource(1L, "Alpha")
        remembered[1L] = StubSource(1L, "Alpha", "en")
        counts.value = mapOf(1L to 2L)
        initialized.value = false
        every { sourceManager.get(any()) } answers {
            if (initialized.value) {
                live.value.firstOrNull { it.id == firstArg<Long>() }
            } else {
                initialized.value = true
                null
            }
        }
        val seen = mutableListOf<MigrateSourcesViewModel.State>()
        val viewModel = MigrateSourcesViewModel(
            preferences = preferences,
            setMigrateSorting = SetMigrateSorting(preferences),
            mangaRepository = mangaRepository,
            sourceManager = sourceManager,
            dispatchers = AppDispatchers(dispatcher, dispatcher, dispatcher),
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect { seen += it } }
        advanceUntilIdle()
        initialized.value = true
        live.value = listOf(alpha)
        advanceUntilIdle()

        seen.filter { !it.isLoading }.flatMap { it.items }.filter { it.isStub }.shouldBeEmpty()
        viewModel.state.value.items shouldContainExactly listOf(
            MigrateSourcesViewModel.SourceRow(id = 1L, name = "Alpha", lang = "en", isStub = false, count = 2L),
        )
    }

    @Test
    fun `a remembered name arriving after the first list replaces the id and a reinstall drops Not installed`() = runTest(dispatcher) {
        live.value = listOf(FakeCatalogueSource(1L, "Alpha"))
        counts.value = mapOf(1L to 2L, 9L to 7L)
        val viewModel = viewModel()

        viewModel.state.value.items.first { it.id == 9L }.name shouldBe "9"

        remembered[9L] = StubSource(9L, "Zulu", "en")
        stubs.value = listOf(remembered.getValue(9L))
        advanceUntilIdle()

        viewModel.state.value.items.first { it.id == 9L }.name shouldBe "Zulu"

        live.value = live.value + FakeCatalogueSource(9L, "Zulu")
        advanceUntilIdle()

        viewModel.state.value.items.first { it.id == 9L }.isStub shouldBe false
    }
}

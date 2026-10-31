package com.shinsaikou.ui.migration.config

import com.shinsaikou.domain.preferences.SourcePreferences
import com.shinsaikou.source.CatalogueSource
import com.shinsaikou.source.SourceIds
import com.shinsaikou.source.SourceManager
import com.shinsaikou.ui.migration.FakeCatalogueSource
import com.shinsaikou.ui.migration.config.MigrationConfigViewModel.SelectionConfig
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
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
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers
import kotlinx.coroutines.Dispatchers as KotlinDispatchers

/**
 * The config screen's model (phase 4 unit MB, design g7 final section 19.4): which sources are
 * offered and selected at start, the order they are shown and searched in, and that the order is in
 * `migration_sources` after every change, so a process death or a Continue elsewhere reads it back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MigrationConfigViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val preferences = SourcePreferences(InMemoryPreferenceStore())
    private val initialized = MutableStateFlow(true)
    private var sources: List<CatalogueSource> = emptyList()
    private val sourceManager = mockk<SourceManager> {
        every { isInitialized } returns initialized
        every { getCatalogueSources() } answers { flowOf(sources) }
    }

    private val alpha = FakeCatalogueSource(1L, "Alpha")
    private val bravo = FakeCatalogueSource(2L, "Bravo")
    private val charlie = FakeCatalogueSource(3L, "Charlie")
    private val delta = FakeCatalogueSource(4L, "Delta")

    @BeforeEach
    fun setUp() {
        KotlinDispatchers.setMain(dispatcher)
        preferences.enabledLanguages.set(setOf("en"))
        sources = listOf(delta, bravo, alpha, charlie)
    }

    @AfterEach
    fun tearDown() {
        KotlinDispatchers.resetMain()
    }

    private fun TestScope.viewModel(): MigrationConfigViewModel {
        val viewModel = MigrationConfigViewModel(
            sourcePreferences = preferences,
            sourceManager = sourceManager,
            dispatchers = AppDispatchers(dispatcher, dispatcher, dispatcher),
        )
        advanceUntilIdle()
        return viewModel
    }

    private fun MigrationConfigViewModel.selected() = state.value.sources.filter { it.isSelected }.map { it.id }

    private fun MigrationConfigViewModel.shown() = state.value.sources.map { it.id }

    @Test
    fun `the saved sources are selected at start, in their saved order, ahead of the rest by name`() = runTest(dispatcher) {
        preferences.migrationSources.set(listOf(3L, 1L))
        preferences.pinnedSources.set(setOf("2"))

        val viewModel = viewModel()

        viewModel.selected() shouldContainExactly listOf(3L, 1L)
        viewModel.shown() shouldContainExactly listOf(3L, 1L, 2L, 4L)
    }

    @Test
    fun `with nothing saved the pinned sources are selected`() = runTest(dispatcher) {
        preferences.pinnedSources.set(setOf("4", "2", "not a number"))

        val viewModel = viewModel()

        viewModel.selected() shouldContainExactly listOf(2L, 4L)
    }

    @Test
    fun `with nothing saved or pinned every source not hidden is selected, in name order`() = runTest(dispatcher) {
        preferences.disabledSources.set(setOf("2"))

        val viewModel = viewModel()

        viewModel.selected() shouldContainExactly listOf(1L, 3L, 4L)
        viewModel.shown() shouldContainExactly listOf(1L, 3L, 4L, 2L)
    }

    @Test
    fun `a saved source no longer offered is not shown and leaves the key at the next save`() = runTest(dispatcher) {
        preferences.migrationSources.set(listOf(9L, 2L))

        val viewModel = viewModel()
        viewModel.shown().contains(9L) shouldBe false
        viewModel.toggleSelection(1L)

        preferences.migrationSources.get() shouldContainExactly listOf(2L, 1L)
    }

    @Test
    fun `Local and sources in a disabled language are not offered, while hidden ones are`() = runTest(dispatcher) {
        val local = FakeCatalogueSource(SourceIds.LOCAL, "Local", "other")
        val french = FakeCatalogueSource(5L, "Echo", "fr")
        sources = listOf(local, french, alpha, bravo)
        preferences.disabledSources.set(setOf("2"))

        val viewModel = viewModel()

        viewModel.shown() shouldContainExactly listOf(1L, 2L)
    }

    @Test
    fun `the four helpers select all, none, the sources not hidden and the pinned ones`() = runTest(dispatcher) {
        preferences.migrationSources.set(listOf(1L))
        preferences.disabledSources.set(setOf("3"))
        val viewModel = viewModel()
        preferences.pinnedSources.set(setOf("4"))

        viewModel.toggleSelection(SelectionConfig.All)
        viewModel.selected() shouldContainExactly listOf(1L, 2L, 3L, 4L)
        viewModel.toggleSelection(SelectionConfig.None)
        viewModel.selected() shouldContainExactly emptyList()
        viewModel.toggleSelection(SelectionConfig.Enabled)
        viewModel.selected() shouldContainExactly listOf(1L, 2L, 4L)
        viewModel.toggleSelection(SelectionConfig.Pinned)
        viewModel.selected() shouldContainExactly listOf(4L)
        preferences.migrationSources.get() shouldContainExactly listOf(4L)
    }

    @Test
    fun `the order is selected first, then their saved order, then name and short language`() = runTest(dispatcher) {
        val englishAlpha = FakeCatalogueSource(6L, "Alpha", "en")
        val allAlpha = FakeCatalogueSource(7L, "Alpha", "all")
        sources = listOf(delta, englishAlpha, allAlpha, bravo)
        preferences.enabledLanguages.set(setOf("en", "all"))
        preferences.migrationSources.set(listOf(4L, 2L))

        val viewModel = viewModel()
        // The rest sort by "name (lang)": "Alpha (all)" before "Alpha (en)".
        viewModel.shown() shouldContainExactly listOf(4L, 2L, 7L, 6L)

        // A newly selected source joins the selection at its end.
        viewModel.toggleSelection(6L)
        viewModel.shown() shouldContainExactly listOf(4L, 2L, 6L, 7L)
    }

    @Test
    fun `the key is rewritten after a toggle and after a move`() = runTest(dispatcher) {
        preferences.migrationSources.set(listOf(1L, 2L, 3L))
        val viewModel = viewModel()

        viewModel.toggleSelection(4L)
        preferences.migrationSources.get() shouldContainExactly listOf(1L, 2L, 3L, 4L)

        viewModel.moveSource(fromKey = 4L, toKey = 1L)
        preferences.migrationSources.get() shouldContainExactly listOf(4L, 1L, 2L, 3L)
        viewModel.shown() shouldContainExactly listOf(4L, 1L, 2L, 3L)
    }

    @Test
    fun `a move onto a header or an available row is ignored`() = runTest(dispatcher) {
        preferences.migrationSources.set(listOf(1L, 2L))
        val viewModel = viewModel()

        viewModel.moveSource(fromKey = 2L, toKey = "selected-header")
        viewModel.moveSource(fromKey = 2L, toKey = "available-header")
        // An available row's lazy key is its id, but it is not among the selected rows. Moved in the
        // whole list, 1 would land after 2 and the selection's order would change.
        viewModel.moveSource(fromKey = 1L, toKey = 4L)

        viewModel.shown() shouldContainExactly listOf(1L, 2L, 3L, 4L)
        preferences.migrationSources.get() shouldContainExactly listOf(1L, 2L)
    }

    @Test
    fun `the screen stays loading until the registry has loaded`() = runTest(dispatcher) {
        initialized.value = false

        val viewModel = viewModel()
        viewModel.state.value.isLoading shouldBe true
        viewModel.state.value.sources shouldBe emptyList()

        initialized.value = true
        advanceUntilIdle()
        viewModel.state.value.isLoading shouldBe false
        viewModel.shown() shouldContainExactly listOf(1L, 2L, 3L, 4L)
    }
}

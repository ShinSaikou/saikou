package com.shinsaikou.ui.browse

import com.shinsaikou.domain.preferences.SourcePreferences
import com.shinsaikou.domain.source.interactor.GetLanguagesWithSources
import com.shinsaikou.domain.source.interactor.ToggleLanguage
import com.shinsaikou.domain.source.interactor.ToggleSource
import com.shinsaikou.domain.source.interactor.ToggleSourcePin
import com.shinsaikou.extensions.ExtensionManager
import com.shinsaikou.extensions.model.Extension
import com.shinsaikou.source.CatalogueSource
import com.shinsaikou.source.SourceIds
import com.shinsaikou.source.SourceManager
import com.shinsaikou.ui.browse.source.SourcesFilterViewModel
import com.shinsaikou.ui.fixture.LaggingStateFlow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
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
import tachiyomi.domain.source.model.Source
import java.io.File
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers
import kotlinx.coroutines.Dispatchers as KotlinxDispatchers

/**
 * The Sources page used to filter on hidden ids and the query alone. Contract 08 section 5.3 also
 * drops a source outside the enabled languages and every source of an adult extension while the
 * mature sources switch is off; these cases pin both, with `"all"` as the literal code ruling R57
 * makes it.
 */
class SourcesViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val sourcesFlow = MutableStateFlow<List<CatalogueSource>>(emptyList())
    private val installedFlow = MutableStateFlow<List<Extension.Installed>>(emptyList())

    private val sourceManager = mockk<SourceManager> {
        every { getCatalogueSources() } returns sourcesFlow
        every { isInitialized } returns MutableStateFlow(true)
    }
    private val extensionManager = mockk<ExtensionManager> {
        every { installedExtensions } returns installedFlow
    }
    private val sourcePreferences = SourcePreferences(InMemoryPreferenceStore())

    @BeforeEach
    fun setUp() {
        KotlinxDispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        KotlinxDispatchers.resetMain()
    }

    private fun viewModel() = SourcesViewModel(
        sourceManager = sourceManager,
        extensionManager = extensionManager,
        sourcePreferences = sourcePreferences,
        toggleSourcePin = ToggleSourcePin(sourcePreferences),
        toggleSource = ToggleSource(sourcePreferences),
        dispatchers = AppDispatchers(io = testDispatcher, default = testDispatcher, main = testDispatcher),
    )

    private fun SourcesViewModel.ids(): List<Long> = state.value.sources.map { it.id }

    @Test
    fun `only sources in an enabled language are listed, with all a literal code`() = runTest(testDispatcher) {
        // The owner's restored upstream set: English plus multi-language sources, and nothing else.
        sourcePreferences.enabledLanguages.set(setOf("all", "en-US", "en"))
        sourcesFlow.value = listOf(
            catalogueSource(1L, "Alpha", "en"),
            catalogueSource(2L, "Beta", "all"),
            catalogueSource(3L, "Gamma", "ja"),
        )

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.ids() shouldContainExactly listOf(1L, 2L)
    }

    @Test
    fun `changing the enabled languages reaches the list without a revisit`() = runTest(testDispatcher) {
        sourcePreferences.enabledLanguages.set(setOf("en"))
        sourcesFlow.value = listOf(catalogueSource(1L, "Alpha", "en"), catalogueSource(3L, "Gamma", "ja"))

        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.ids() shouldContainExactly listOf(1L)

        sourcePreferences.enabledLanguages.set(setOf("en", "ja"))
        advanceUntilIdle()

        viewModel.ids() shouldContainExactly listOf(1L, 3L)
    }

    @Test
    fun `the Local source stays listed whatever languages are enabled`() = runTest(testDispatcher) {
        sourcePreferences.enabledLanguages.set(setOf("en"))
        sourcesFlow.value = listOf(catalogueSource(SourceIds.LOCAL, "Local", "other"))

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.ids() shouldContainExactly listOf(SourceIds.LOCAL)
    }

    @Test
    fun `an adult extension's sources are dropped while the mature sources switch is off`() = runTest(testDispatcher) {
        sourcePreferences.enabledLanguages.set(setOf("en"))
        val clean = catalogueSource(1L, "Alpha", "en")
        val adult = catalogueSource(2L, "Beta", "en")
        sourcesFlow.value = listOf(clean, adult)
        installedFlow.value = listOf(installedExtension(isNsfw = true, sources = listOf(adult)))
        sourcePreferences.showNsfwSource.set(false)

        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.ids() shouldContainExactly listOf(1L)

        sourcePreferences.showNsfwSource.set(true)
        advanceUntilIdle()

        viewModel.ids() shouldContainExactly listOf(1L, 2L)
    }

    @Test
    fun `a flag that reaches the page before the loaded list is not read as nothing installed`() =
        runTest(testDispatcher) {
            sourcePreferences.enabledLanguages.set(setOf("en"))
            val loading = LaggingStateFlow<List<CatalogueSource>>(emptyList())
            val initialized = MutableStateFlow(false)
            every { sourceManager.getCatalogueSources() } returns loading
            every { sourceManager.isInitialized } returns initialized

            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.state.value.loading shouldBe true

            // The end of the registry's first load, in its order: the list, then the flag. The
            // flag's collector inside the combine resumes first; the list's has not yet.
            loading.publish(listOf(catalogueSource(1L, "Alpha", "en")))
            initialized.value = true
            advanceUntilIdle()

            viewModel.state.value.loading shouldBe false
            viewModel.ids() shouldContainExactly listOf(1L)

            loading.deliver()
            advanceUntilIdle()
            viewModel.ids() shouldContainExactly listOf(1L)
        }

    @Test
    fun `the pin button pins a source, which moves up under the pinned label with the rest under their own`() =
        runTest(testDispatcher) {
            sourcePreferences.enabledLanguages.set(setOf("en"))
            sourcesFlow.value = listOf(
                catalogueSource(1L, "Alpha", "en"),
                catalogueSource(2L, "Beta", "en"),
                catalogueSource(3L, "Gamma", "en"),
            )
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.togglePin(3L)
            advanceUntilIdle()

            sourcePreferences.pinnedSources.get() shouldBe setOf("3")
            viewModel.state.value.items.describe() shouldContainExactly
                listOf("label PINNED", "3 pinned", "label OTHER", "1", "2")

            viewModel.togglePin(3L)
            advanceUntilIdle()

            sourcePreferences.pinnedSources.get() shouldBe emptySet()
            viewModel.state.value.items.describe() shouldContainExactly listOf("1", "2", "3")
        }

    @Test
    fun `no section label is drawn while nothing listed is pinned`() = runTest(testDispatcher) {
        sourcePreferences.enabledLanguages.set(setOf("en"))
        // A pin on a source the list does not show, one of another language here, draws no label.
        sourcePreferences.pinnedSources.set(setOf("9"))
        sourcesFlow.value = listOf(
            catalogueSource(1L, "Alpha", "en"),
            catalogueSource(2L, "Beta", "en"),
            catalogueSource(9L, "Omega", "ja"),
        )

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.state.value.items.describe() shouldContainExactly listOf("1", "2")
    }

    @Test
    fun `every listed source pinned gives the pinned label alone`() = runTest(testDispatcher) {
        sourcePreferences.enabledLanguages.set(setOf("en"))
        sourcePreferences.pinnedSources.set(setOf("1", "2"))
        sourcesFlow.value = listOf(catalogueSource(2L, "Beta", "en"), catalogueSource(1L, "Alpha", "en"))

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.state.value.items.describe() shouldContainExactly listOf("label PINNED", "1 pinned", "2 pinned")
    }

    // -- upstream's long press, Latest button and Last used group (phase 4 unit MP2) ---------------

    @Test
    fun `the last used source is listed first under its own label and again in its place`() = runTest(testDispatcher) {
        sourcePreferences.enabledLanguages.set(setOf("en"))
        sourcePreferences.lastUsedSource.set(2L)
        sourcesFlow.value = listOf(
            catalogueSource(1L, "Alpha", "en"),
            catalogueSource(2L, "Beta", "en"),
            catalogueSource(3L, "Gamma", "en"),
        )

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.state.value.items.describe() shouldContainExactly
            listOf("label LAST_USED", "2 last used", "label OTHER", "1", "2", "3")
        // Each source once in the rows the page counts, whatever the groups repeat.
        viewModel.ids() shouldContainExactly listOf(1L, 2L, 3L)
    }

    @Test
    fun `the groups follow upstream's order, last used then pinned then the rest`() = runTest(testDispatcher) {
        sourcePreferences.enabledLanguages.set(setOf("en"))
        sourcePreferences.pinnedSources.set(setOf("3"))
        sourcePreferences.lastUsedSource.set(1L)
        sourcesFlow.value = listOf(
            catalogueSource(1L, "Alpha", "en"),
            catalogueSource(2L, "Beta", "en"),
            catalogueSource(3L, "Gamma", "en"),
        )

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.state.value.items.describe() shouldContainExactly
            listOf("label LAST_USED", "1 last used", "label PINNED", "3 pinned", "label OTHER", "1", "2")
    }

    @Test
    fun `a pinned last used source stays in the pinned group and its copy keeps the pin`() = runTest(testDispatcher) {
        sourcePreferences.enabledLanguages.set(setOf("en"))
        sourcePreferences.pinnedSources.set(setOf("1", "2"))
        sourcePreferences.lastUsedSource.set(2L)
        sourcesFlow.value = listOf(catalogueSource(1L, "Alpha", "en"), catalogueSource(2L, "Beta", "en"))

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.state.value.items.describe() shouldContainExactly
            listOf("label LAST_USED", "2 pinned last used", "label PINNED", "1 pinned", "2 pinned")
    }

    @Test
    fun `a last used source that is not listed draws no group`() = runTest(testDispatcher) {
        sourcePreferences.enabledLanguages.set(setOf("en"))
        // Disabled, in a language not enabled, and filtered out by the search, in turn.
        sourcePreferences.disabledSources.set(setOf("2"))
        sourcePreferences.lastUsedSource.set(2L)
        sourcesFlow.value = listOf(
            catalogueSource(1L, "Alpha", "en"),
            catalogueSource(2L, "Beta", "en"),
            catalogueSource(9L, "Omega", "ja"),
        )

        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.state.value.items.describe() shouldContainExactly listOf("1")

        sourcePreferences.lastUsedSource.set(9L)
        advanceUntilIdle()
        viewModel.state.value.items.describe() shouldContainExactly listOf("1")

        sourcePreferences.disabledSources.set(emptySet())
        sourcePreferences.lastUsedSource.set(2L)
        viewModel.search("Alp")
        advanceUntilIdle()
        viewModel.state.value.items.describe() shouldContainExactly listOf("1")
    }

    @Test
    fun `opening a source elsewhere moves the last used group without a revisit`() = runTest(testDispatcher) {
        sourcePreferences.enabledLanguages.set(setOf("en"))
        sourcesFlow.value = listOf(catalogueSource(1L, "Alpha", "en"), catalogueSource(2L, "Beta", "en"))
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.state.value.items.describe() shouldContainExactly listOf("1", "2")

        sourcePreferences.lastUsedSource.set(1L)
        advanceUntilIdle()

        viewModel.state.value.items.describe() shouldContainExactly
            listOf("label LAST_USED", "1 last used", "label OTHER", "1", "2")
    }

    @Test
    fun `a row carries whether its source has a latest listing`() = runTest(testDispatcher) {
        sourcePreferences.enabledLanguages.set(setOf("en"))
        sourcesFlow.value = listOf(
            catalogueSource(1L, "Alpha", "en", latest = true),
            catalogueSource(2L, "Beta", "en", latest = false),
        )

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.state.value.sources.map { it.id to it.supportsLatest } shouldContainExactly
            listOf(1L to true, 2L to false)
    }

    @Test
    fun `a long press opens the options for that row, which a change to the list keeps open`() = runTest(testDispatcher) {
        sourcePreferences.enabledLanguages.set(setOf("en"))
        sourcesFlow.value = listOf(catalogueSource(1L, "Alpha", "en"), catalogueSource(2L, "Beta", "en"))
        val viewModel = viewModel()
        advanceUntilIdle()
        val row = viewModel.state.value.sources.first { it.id == 2L }

        viewModel.showSourceDialog(row)
        sourcePreferences.lastUsedSource.set(1L)
        advanceUntilIdle()

        viewModel.state.value.dialog shouldBe row

        viewModel.closeDialog()
        viewModel.state.value.dialog.shouldBeNull()
    }

    @Test
    fun `the options' Pin pins the source and Disable writes upstream's key and takes it off the list`() =
        runTest(testDispatcher) {
            sourcePreferences.enabledLanguages.set(setOf("en"))
            sourcePreferences.lastUsedSource.set(2L)
            sourcesFlow.value = listOf(catalogueSource(1L, "Alpha", "en"), catalogueSource(2L, "Beta", "en"))
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.togglePin(2L)
            advanceUntilIdle()
            sourcePreferences.pinnedSources.get() shouldBe setOf("2")

            viewModel.toggleSource(2L)
            advanceUntilIdle()

            sourcePreferences.disabledSources.get() shouldBe setOf("2")
            // Gone from its place and from Last used; the pin left behind draws no label.
            viewModel.state.value.items.describe() shouldContainExactly listOf("1")
        }

    /**
     * Phase 5 unit BF: the source filter screen writes the two keys this page already re-projects
     * on, so its toggles reach the list with no wiring of their own. Both view models are built over
     * the one preference store, as the app graph's single `SourcePreferences` makes them.
     */
    @Test
    fun `a language switched off and a source unchecked on the filter screen leave the list`() =
        runTest(testDispatcher) {
            sourcePreferences.enabledLanguages.set(setOf("en", "ja"))
            sourcesFlow.value = listOf(
                catalogueSource(1L, "Alpha", "en"),
                catalogueSource(2L, "Beta", "en"),
                catalogueSource(3L, "Gamma", "ja"),
                catalogueSource(SourceIds.LOCAL, "Local source", "other"),
            )
            val sources = viewModel()
            val filter = filterViewModel()
            advanceUntilIdle()
            sources.ids() shouldContainExactly listOf(1L, 2L, 3L, SourceIds.LOCAL)

            filter.toggleLanguage("ja")
            advanceUntilIdle()
            sources.ids() shouldContainExactly listOf(1L, 2L, SourceIds.LOCAL)

            filter.toggleSource(Source(2L, "en", "Beta", supportsLatest = false, isStub = false))
            advanceUntilIdle()
            sources.ids() shouldContainExactly listOf(1L, SourceIds.LOCAL)

            filter.toggleLanguage("ja")
            filter.toggleSource(Source(2L, "en", "Beta", supportsLatest = false, isStub = false))
            advanceUntilIdle()
            sources.ids() shouldContainExactly listOf(1L, 2L, 3L, SourceIds.LOCAL)
        }

    private fun filterViewModel() = SourcesFilterViewModel(
        preferences = sourcePreferences,
        getLanguagesWithSources = GetLanguagesWithSources(sourceManager, sourcePreferences),
        toggleSource = ToggleSource(sourcePreferences),
        toggleLanguage = ToggleLanguage(sourcePreferences),
        sourceManager = sourceManager,
        extensionManager = extensionManager,
        dispatchers = AppDispatchers(io = testDispatcher, default = testDispatcher, main = testDispatcher),
    )

    @Test
    fun `the options title is upstream's visual name and the Local source offers no Disable`() {
        val row = SourcesViewModel.SourceRow(id = 1L, name = "Alpha", lang = "en")
        val local = SourcesViewModel.SourceRow(id = SourceIds.LOCAL, name = "Local source", lang = "other")

        row.visualName shouldBe "Alpha (EN)"
        row.copy(lang = "").visualName shouldBe "Alpha"
        row.isLocal shouldBe false
        local.isLocal shouldBe true
        row.key shouldBe "1"
        row.copy(lastUsed = true).key shouldBe "1-lastused"
    }

    private fun List<SourceListItem>.describe(): List<String> = map { item ->
        when (item) {
            is SourceListItem.Label -> "label ${item.section}"
            is SourceListItem.Source -> buildString {
                append(item.row.id)
                if (item.row.pinned) append(" pinned")
                if (item.row.lastUsed) append(" last used")
            }
        }
    }

    private fun catalogueSource(
        sourceId: Long,
        sourceName: String,
        sourceLang: String,
        latest: Boolean = false,
    ): CatalogueSource = mockk {
        every { id } returns sourceId
        every { name } returns sourceName
        every { lang } returns sourceLang
        every { supportsLatest } returns latest
    }

    private fun installedExtension(isNsfw: Boolean, sources: List<CatalogueSource>) = Extension.Installed(
        packageName = "dev.example.fixture",
        name = "Fixture Extension",
        versionName = "1.0",
        versionCode = 1L,
        libVersion = 1.6,
        lang = "en",
        isNsfw = isNsfw,
        sources = sources,
        extFile = File("fixture.ext"),
    )
}

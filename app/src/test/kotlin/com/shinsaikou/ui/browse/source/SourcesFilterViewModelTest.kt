package com.shinsaikou.ui.browse.source

import com.shinsaikou.domain.preferences.SourcePreferences
import com.shinsaikou.domain.source.interactor.GetLanguagesWithSources
import com.shinsaikou.domain.source.interactor.ToggleLanguage
import com.shinsaikou.domain.source.interactor.ToggleSource
import com.shinsaikou.extensions.ExtensionManager
import com.shinsaikou.extensions.model.Extension
import com.shinsaikou.source.CatalogueSource
import com.shinsaikou.source.SourceIds
import com.shinsaikou.source.SourceManager
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.io.File
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers
import kotlinx.coroutines.Dispatchers as KotlinxDispatchers

/**
 * Upstream's three states of the source filter screen (phase 5 unit BF), and its two toggles writing
 * the keys an upstream backup carries: `source_languages` and `hidden_catalogues`.
 */
class SourcesFilterViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val sourcesFlow = MutableStateFlow<List<CatalogueSource>>(emptyList())
    private val initialized = MutableStateFlow(true)
    private val installedFlow = MutableStateFlow<List<Extension.Installed>>(emptyList())

    private val sourceManager = mockk<SourceManager> {
        every { getCatalogueSources() } returns sourcesFlow
        every { isInitialized } returns initialized
    }
    private val extensionManager = mockk<ExtensionManager> {
        every { installedExtensions } returns installedFlow
    }
    private val preferences = SourcePreferences(InMemoryPreferenceStore())

    @BeforeEach
    fun setUp() {
        KotlinxDispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        KotlinxDispatchers.resetMain()
    }

    private fun viewModel(languages: GetLanguagesWithSources = GetLanguagesWithSources(sourceManager, preferences)) =
        SourcesFilterViewModel(
            preferences = preferences,
            getLanguagesWithSources = languages,
            toggleSource = ToggleSource(preferences),
            toggleLanguage = ToggleLanguage(preferences),
            sourceManager = sourceManager,
            extensionManager = extensionManager,
            dispatchers = AppDispatchers(io = testDispatcher, default = testDispatcher, main = testDispatcher),
        )

    private fun SourcesFilterViewModel.success(): SourcesFilterViewModel.State.Success =
        state.value.shouldBeInstanceOf<SourcesFilterViewModel.State.Success>()

    private fun SourcesFilterViewModel.State.Success.describe(): List<Pair<String, List<Long>>> =
        items.map { (language, sources) -> language to sources.map { it.id } }

    private fun SourcesFilterViewModel.State.Success.languages(): List<String> = items.map { it.first }

    @Test
    fun `the state is loading until the registry has finished its first load`() = runTest(testDispatcher) {
        initialized.value = false
        sourcesFlow.value = listOf(catalogueSource(1L, "Alpha", "en"))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.state.value shouldBe SourcesFilterViewModel.State.Loading

        initialized.value = true
        advanceUntilIdle()

        viewModel.success().describe() shouldBe listOf("en" to listOf(1L))
    }

    @Test
    fun `success carries the languages with their sources, the enabled languages and the disabled sources`() =
        runTest(testDispatcher) {
            preferences.enabledLanguages.set(setOf("en"))
            preferences.disabledSources.set(setOf("2"))
            sourcesFlow.value = listOf(
                catalogueSource(1L, "Alpha", "en"),
                catalogueSource(2L, "Beta", "en"),
                catalogueSource(3L, "Gamma", "ja"),
                catalogueSource(SourceIds.LOCAL, "Local source", "other"),
            )
            val viewModel = viewModel()
            advanceUntilIdle()

            val success = viewModel.success()
            success.languages() shouldContainExactly listOf("en", "ja")
            success.describe() shouldBe listOf("en" to listOf(1L, 2L), "ja" to listOf(3L))
            success.enabledLanguages shouldBe setOf("en")
            success.disabledSources shouldBe setOf("2")
            success.isEmpty shouldBe false
        }

    @Test
    fun `with only the Local source installed the state is the empty success`() = runTest(testDispatcher) {
        sourcesFlow.value = listOf(catalogueSource(SourceIds.LOCAL, "Local source", "other"))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.success().isEmpty shouldBe true
    }

    @Test
    fun `a language's switch writes source_languages both ways and the state follows`() = runTest(testDispatcher) {
        preferences.enabledLanguages.set(setOf("all", "en"))
        sourcesFlow.value = listOf(catalogueSource(1L, "Alpha", "en"), catalogueSource(3L, "Gamma", "ja"))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.toggleLanguage("ja")
        advanceUntilIdle()
        preferences.enabledLanguages.get() shouldBe setOf("all", "en", "ja")
        viewModel.success().enabledLanguages shouldBe setOf("all", "en", "ja")

        viewModel.toggleLanguage("en")
        advanceUntilIdle()
        preferences.enabledLanguages.get() shouldBe setOf("all", "ja")
        // The enabled half first: Japanese now leads, English follows.
        viewModel.success().languages() shouldContainExactly listOf("ja", "en")
    }

    @Test
    fun `a source's checkbox writes hidden_catalogues both ways and the state follows`() = runTest(testDispatcher) {
        preferences.enabledLanguages.set(setOf("en"))
        sourcesFlow.value = listOf(catalogueSource(1L, "Alpha", "en"), catalogueSource(2L, "Beta", "en"))
        val viewModel = viewModel()
        advanceUntilIdle()
        val alpha = viewModel.success().items.single { it.first == "en" }.second.first { it.id == 1L }

        viewModel.toggleSource(alpha)
        advanceUntilIdle()
        preferences.disabledSources.get() shouldBe setOf("1")
        viewModel.success().disabledSources shouldBe setOf("1")
        // The disabled source moves after the enabled one, as in upstream.
        viewModel.success().describe() shouldBe listOf("en" to listOf(2L, 1L))

        viewModel.toggleSource(alpha)
        advanceUntilIdle()
        preferences.disabledSources.get() shouldBe emptySet()
        viewModel.success().describe() shouldBe listOf("en" to listOf(1L, 2L))
    }

    @Test
    fun `an adult extension's sources are left out while the mature sources switch is off`() =
        runTest(testDispatcher) {
            preferences.enabledLanguages.set(setOf("en"))
            val clean = catalogueSource(1L, "Alpha", "en")
            val adult = catalogueSource(2L, "Beta", "en")
            val adultOnly = catalogueSource(3L, "Gamma", "ja")
            sourcesFlow.value = listOf(clean, adult, adultOnly)
            installedFlow.value = listOf(installedExtension(isNsfw = true, sources = listOf(adult, adultOnly)))
            preferences.showNsfwSource.set(false)
            val viewModel = viewModel()
            advanceUntilIdle()

            // A language left with no source is not listed at all.
            viewModel.success().describe() shouldBe listOf("en" to listOf(1L))

            preferences.showNsfwSource.set(true)
            advanceUntilIdle()

            viewModel.success().describe() shouldBe listOf("en" to listOf(1L, 2L), "ja" to listOf(3L))
        }

    @Test
    fun `a failure in the chain is the error state`() = runTest(testDispatcher) {
        val failure = IllegalStateException("fixture failure")
        val failing = mockk<GetLanguagesWithSources> {
            every { subscribe() } returns flow { throw failure }
        }
        val viewModel = viewModel(languages = failing)
        advanceUntilIdle()

        viewModel.state.value shouldBe SourcesFilterViewModel.State.Error(failure)
    }

    private fun catalogueSource(sourceId: Long, sourceName: String, sourceLang: String): CatalogueSource = mockk {
        every { id } returns sourceId
        every { name } returns sourceName
        every { lang } returns sourceLang
        every { supportsLatest } returns false
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

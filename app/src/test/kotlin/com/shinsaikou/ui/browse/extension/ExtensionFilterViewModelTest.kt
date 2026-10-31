package com.shinsaikou.ui.browse.extension

import com.shinsaikou.domain.preferences.SourcePreferences
import com.shinsaikou.domain.source.interactor.ToggleLanguage
import com.shinsaikou.extensions.ExtensionManager
import com.shinsaikou.extensions.model.AvailableSource
import com.shinsaikou.extensions.model.Extension
import com.shinsaikou.ui.browse.GetExtensionLanguages
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
import tachiyomi.domain.extension.model.ExtensionStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.util.Locale
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers
import kotlinx.coroutines.Dispatchers as KotlinxDispatchers

/**
 * Upstream's states of the extension filter screen (phase 5 unit BX), and its switch writing the one key
 * it edits, `source_languages`, both ways.
 *
 * The default locale is pinned to English for the order assertions, since the order is by the
 * names the screen shows in the device's language.
 */
class ExtensionFilterViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val availableFlow = MutableStateFlow<List<Extension.Available>>(emptyList())
    private val extensionManager = mockk<ExtensionManager> {
        every { availableExtensions } returns availableFlow
    }
    private val preferences = SourcePreferences(InMemoryPreferenceStore())
    private var previousLocale: Locale? = null

    @BeforeEach
    fun setUp() {
        KotlinxDispatchers.setMain(testDispatcher)
        previousLocale = Locale.getDefault(Locale.Category.DISPLAY)
        Locale.setDefault(Locale.Category.DISPLAY, Locale.ENGLISH)
    }

    @AfterEach
    fun tearDown() {
        KotlinxDispatchers.resetMain()
        previousLocale?.let { Locale.setDefault(Locale.Category.DISPLAY, it) }
    }

    private fun viewModel(languages: GetExtensionLanguages = GetExtensionLanguages(preferences, extensionManager)) =
        ExtensionFilterViewModel(
            preferences = preferences,
            getExtensionLanguages = languages,
            toggleLanguage = ToggleLanguage(preferences),
            dispatchers = AppDispatchers(io = testDispatcher, default = testDispatcher, main = testDispatcher),
        )

    private fun ExtensionFilterViewModel.success(): ExtensionFilterViewModel.State.Success =
        state.value.shouldBeInstanceOf<ExtensionFilterViewModel.State.Success>()

    @Test
    fun `the state is loading until the first emission, then the languages and the enabled set`() =
        runTest(testDispatcher) {
            preferences.enabledLanguages.set(setOf("ja"))
            availableFlow.value = listOf(available("a", "en"), available("b", "ja"), available("c", "en"))
            val viewModel = viewModel()

            viewModel.state.value shouldBe ExtensionFilterViewModel.State.Loading

            advanceUntilIdle()

            val success = viewModel.success()
            success.languages shouldContainExactly listOf("ja", "en")
            success.enabledLanguages shouldBe setOf("ja")
            success.isEmpty shouldBe false
        }

    @Test
    fun `with nothing available the state is the empty success`() = runTest(testDispatcher) {
        preferences.enabledLanguages.set(setOf("en"))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.success().isEmpty shouldBe true
        viewModel.success().enabledLanguages shouldBe setOf("en")
    }

    @Test
    fun `an index fetched while the screen is open fills it`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.success().isEmpty shouldBe true

        availableFlow.value = listOf(available("a", "fr"))
        advanceUntilIdle()

        viewModel.success().languages shouldContainExactly listOf("fr")
    }

    @Test
    fun `a language's switch writes source_languages both ways and the state follows`() = runTest(testDispatcher) {
        preferences.enabledLanguages.set(setOf("all", "en"))
        availableFlow.value = listOf(available("a", "en"), available("b", "ja"), available("c", "all"))
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.success().languages shouldContainExactly listOf("all", "en", "ja")

        viewModel.toggle("ja")
        advanceUntilIdle()
        preferences.enabledLanguages.get() shouldBe setOf("all", "en", "ja")
        viewModel.success().enabledLanguages shouldBe setOf("all", "en", "ja")
        viewModel.success().languages shouldContainExactly listOf("all", "en", "ja")

        viewModel.toggle("en")
        advanceUntilIdle()
        preferences.enabledLanguages.get() shouldBe setOf("all", "ja")
        viewModel.success().enabledLanguages shouldBe setOf("all", "ja")
        // The enabled half first: Japanese now leads English.
        viewModel.success().languages shouldContainExactly listOf("all", "ja", "en")
    }

    @Test
    fun `a failure in the chain is the error state`() = runTest(testDispatcher) {
        val failure = IllegalStateException("fixture failure")
        val failing = mockk<GetExtensionLanguages> {
            every { subscribe() } returns flow { throw failure }
        }
        val viewModel = viewModel(languages = failing)
        advanceUntilIdle()

        viewModel.state.value shouldBe ExtensionFilterViewModel.State.Error(failure)
    }

    private fun available(packageSuffix: String, lang: String) = Extension.Available(
        packageName = "dev.example.fixture.$packageSuffix",
        name = "Fixture $packageSuffix",
        versionName = "1.0",
        versionCode = 1L,
        libVersion = 1.6,
        lang = lang,
        isNsfw = false,
        sources = listOf(AvailableSource(id = 2L, name = "Fixture Index Source", lang = lang, homeUrl = "")),
        apkUrl = "https://example.invalid/fixture.apk",
        iconUrl = "https://example.invalid/fixture.png",
        jarUrl = null,
        store = ExtensionStore(
            indexUrl = "https://example.invalid/index.json",
            name = "Fixture Index",
            badgeLabel = "FI",
            signingKey = "fixture-signing-key",
            contact = ExtensionStore.Contact("https://example.invalid", null),
            isLegacy = false,
            extensionListUrl = null,
        ),
    )
}

package com.shinsaikou.ui.browse

import com.shinsaikou.domain.preferences.SourcePreferences
import com.shinsaikou.domain.source.interactor.languagesWithSources
import com.shinsaikou.extensions.ExtensionManager
import com.shinsaikou.extensions.model.AvailableSource
import com.shinsaikou.extensions.model.Extension
import io.kotest.matchers.collections.shouldContainExactly
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import tachiyomi.domain.extension.model.ExtensionStore
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.source.model.Source
import java.util.Locale

/**
 * Upstream's `GetExtensionLanguages` over Saikou's extension manager (phase 5 unit BX): the languages of
 * every available extension, once each, in the order the source filter uses.
 */
class GetExtensionLanguagesTest {

    private val preferences = SourcePreferences(InMemoryPreferenceStore())
    private val availableFlow = MutableStateFlow<List<Extension.Available>>(emptyList())
    private val extensionManager = mockk<ExtensionManager> {
        every { availableExtensions } returns availableFlow
    }

    /**
     * The three-language fixture, in English: Japanese and Spanish enabled, French not, and a
     * multi-language extension. "all" heads the enabled half, then Japanese before Spanish by their
     * English names, then French.
     */
    @Test
    fun `enabled languages come first, all at their head, then each half by display name`() {
        val available = listOf(
            available("a", "fr"),
            available("b", "es"),
            available("c", "ja"),
            available("d", "all"),
        )

        extensionLanguages(available, setOf("es", "ja", "all"), Locale.ENGLISH) shouldContainExactly
            listOf("all", "ja", "es", "fr")
    }

    @Test
    fun `a language several extensions share is listed once`() {
        val available = listOf(available("a", "en"), available("b", "en"), available("c", "ja"), available("d", "en"))

        extensionLanguages(available, emptySet(), Locale.ENGLISH) shouldContainExactly listOf("en", "ja")
    }

    @Test
    fun `an extension with several languages brings each of them`() {
        val available = listOf(
            available("a", "all", sourceLangs = listOf("en", "es", "ja")),
            available("b", "de"),
        )

        // Spanish enabled; then English, German and Japanese by their English names.
        extensionLanguages(available, setOf("es"), Locale.ENGLISH) shouldContainExactly listOf("es", "en", "de", "ja")
    }

    @Test
    fun `an entry that lists no source brings its own code, as the extension list matches it`() {
        val legacy = available("a", "ko", sourceLangs = emptyList())

        extensionLanguages(listOf(legacy), emptySet(), Locale.ENGLISH) shouldContainExactly listOf("ko")
        legacy.languages() shouldContainExactly listOf("ko")
    }

    /**
     * One comparator for both filter screens: the same codes in the same preference state come out
     * of the source filter's grouping and of this list in one order.
     */
    @Test
    fun `the order is the source filter's for the same codes`() {
        val codes = listOf("fr", "es", "ja", "all", "de", "pt-BR")
        val enabled = setOf("ja", "pt-BR", "all")
        val sources = codes.mapIndexed { index, code ->
            Source(id = index + 1L, lang = code, name = "Fixture $index", supportsLatest = false, isStub = false)
        }

        val fromSourceFilter = languagesWithSources(sources, enabled, emptySet(), Locale.ENGLISH).keys.toList()
        val fromExtensionFilter = extensionLanguages(codes.map { available(it, it) }, enabled, Locale.ENGLISH)

        fromExtensionFilter shouldContainExactly fromSourceFilter
    }

    @Test
    fun `the flow follows the available extensions and source_languages`() = runTest {
        val interactor = GetExtensionLanguages(preferences, extensionManager)
        preferences.enabledLanguages.set(setOf("en"))
        availableFlow.value = listOf(available("a", "ja"), available("b", "en"))

        interactor.subscribe().first() shouldContainExactly listOf("en", "ja")

        preferences.enabledLanguages.set(setOf("ja"))
        interactor.subscribe().first() shouldContainExactly listOf("ja", "en")

        availableFlow.value = emptyList()
        interactor.subscribe().first() shouldContainExactly emptyList()
    }

    private fun available(packageSuffix: String, lang: String, sourceLangs: List<String> = listOf(lang)) =
        Extension.Available(
            packageName = "dev.example.fixture.$packageSuffix",
            name = "Fixture $packageSuffix",
            versionName = "1.0",
            versionCode = 1L,
            libVersion = 1.6,
            lang = lang,
            isNsfw = false,
            sources = sourceLangs.mapIndexed { index, sourceLang ->
                AvailableSource(id = 2L + index, name = "Fixture Index Source", lang = sourceLang, homeUrl = "")
            },
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

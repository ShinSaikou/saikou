package com.shinsaikou.domain.source.interactor

import com.shinsaikou.domain.preferences.SourcePreferences
import com.shinsaikou.source.CatalogueSource
import com.shinsaikou.source.SourceIds
import com.shinsaikou.source.SourceManager
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.source.model.Source
import java.util.Locale

class GetLanguagesWithSourcesTest {

    private val preferences = SourcePreferences(InMemoryPreferenceStore())
    private val sourcesFlow = MutableStateFlow<List<CatalogueSource>>(emptyList())
    private val sourceManager = mockk<SourceManager> {
        every { getCatalogueSources() } returns sourcesFlow
    }
    private val interactor = GetLanguagesWithSources(sourceManager, preferences)

    /**
     * The three-language fixture of the brief, in English: Japanese and Spanish enabled, French not.
     * Upstream's comparator puts the enabled half first and sorts each half by name, which here gives
     * Japanese, Spanish, French; "all" heads the enabled half whatever its name.
     */
    @Test
    fun `enabled languages come first, all at their head, then each half by display name`() {
        val sources = listOf(
            source(1L, "Alpha", "fr"),
            source(2L, "Beta", "es"),
            source(3L, "Gamma", "ja"),
            source(4L, "Delta", "all"),
        )

        val grouped = languagesWithSources(sources, setOf("es", "ja", "all"), emptySet(), Locale.ENGLISH)

        grouped.keys.toList() shouldContainExactly listOf("all", "ja", "es", "fr")
    }

    @Test
    fun `the disabled half is ordered by display name too`() {
        val sources = listOf(source(1L, "Alpha", "en"), source(2L, "Beta", "de"), source(3L, "Gamma", "it"))

        val grouped = languagesWithSources(sources, setOf("it"), emptySet(), Locale.ENGLISH)

        // Italian enabled; then English and German, disabled, by name: English, German.
        grouped.keys.toList() shouldContainExactly listOf("it", "en", "de")
    }

    /**
     * In French, Ewe is "éwé", Estonian "estonien" and French "français". A plain string compare puts
     * "éwé" after every unaccented name ("é" is past "z" in UTF-16); the collator sorts it with "e".
     */
    @Test
    fun `an accented display name sorts with its letter`() {
        val sources = listOf(source(1L, "Alpha", "fr"), source(2L, "Beta", "ee"), source(3L, "Gamma", "et"))

        val grouped = languagesWithSources(sources, emptySet(), emptySet(), Locale.FRENCH)

        grouped.keys.toList() shouldContainExactly listOf("et", "ee", "fr")
    }

    @Test
    fun `inside a language the enabled sources come first, then the disabled, each by name ignoring case`() {
        val sources = listOf(
            source(1L, "delta", "en"),
            source(2L, "Alpha", "en"),
            source(3L, "charlie", "en"),
            source(4L, "Bravo", "en"),
        )

        val grouped = languagesWithSources(sources, setOf("en"), setOf("2", "3"), Locale.ENGLISH)

        grouped.getValue("en").map { it.name } shouldContainExactly listOf("Bravo", "delta", "Alpha", "charlie")
    }

    @Test
    fun `the Local source is left out, and a language only it reports is not listed`() {
        val sources = listOf(source(SourceIds.LOCAL, "Local source", "other"), source(1L, "Alpha", "en"))

        val grouped = languagesWithSources(sources, setOf("en"), emptySet(), Locale.ENGLISH)

        grouped shouldNotContainKey "other"
        grouped.values.flatten().map { it.id } shouldContainExactly listOf(1L)
    }

    @Test
    fun `two codes with one display name keep a group each`() {
        // Both tags read as the same unknown language "zz", so their display names tie.
        val sources = listOf(source(1L, "Alpha", "zz-1"), source(2L, "Beta", "zz-2"))

        val grouped = languagesWithSources(sources, emptySet(), emptySet(), Locale.ENGLISH)

        grouped.keys.toList() shouldContainExactly listOf("zz-1", "zz-2")
    }

    @Test
    fun `the flow follows the registry and both preferences, with the live source mapped to its fields`() = runTest {
        preferences.enabledLanguages.set(setOf("en"))
        sourcesFlow.value = listOf(
            catalogueSource(1L, "Alpha", "en", latest = true),
            catalogueSource(2L, "Beta", "ja"),
            catalogueSource(SourceIds.LOCAL, "Local source", "other"),
        )

        interactor.subscribe().first() shouldBe linkedMapOf(
            "en" to listOf(Source(1L, "en", "Alpha", supportsLatest = true, isStub = false)),
            "ja" to listOf(Source(2L, "ja", "Beta", supportsLatest = false, isStub = false)),
        )

        preferences.enabledLanguages.set(setOf("ja"))
        interactor.subscribe().first().keys.toList() shouldContainExactly listOf("ja", "en")

        sourcesFlow.value = listOf(catalogueSource(3L, "Gamma", "fr"))
        interactor.subscribe().first().keys.toList() shouldContainExactly listOf("fr")
    }

    private fun source(id: Long, name: String, lang: String) =
        Source(id = id, lang = lang, name = name, supportsLatest = false, isStub = false)

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
}

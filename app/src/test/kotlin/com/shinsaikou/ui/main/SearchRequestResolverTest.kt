package com.shinsaikou.ui.main

import com.shinsaikou.domain.preferences.SourcePreferences
import com.shinsaikou.extensions.ExtensionManager
import com.shinsaikou.extensions.model.Extension
import com.shinsaikou.source.CatalogueSource
import com.shinsaikou.source.SourceIds
import com.shinsaikou.source.SourceManager
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.io.File
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers

/**
 * Which one source a search request is for. Several or none leave the query to Global Search, and
 * the lookup waits for the source registry (ruling R69 item 4). Only the sources Global Search would
 * ask count: an enabled language, not hidden.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchRequestResolverTest {

    private val testDispatcher = StandardTestDispatcher()
    private val initialized = MutableStateFlow(true)
    private val catalogue = MutableStateFlow<List<CatalogueSource>>(emptyList())
    private val installed = MutableStateFlow<List<Extension.Installed>>(emptyList())

    private val sourceManager = mockk<SourceManager> {
        every { isInitialized } returns initialized
        every { getCatalogueSources() } returns catalogue
    }
    private val extensionManager = mockk<ExtensionManager> {
        every { installedExtensions } returns installed
    }

    /** English alone, so the device's own language never decides a case. */
    private val sourcePreferences = SourcePreferences(InMemoryPreferenceStore()).apply {
        enabledLanguages.set(setOf("en"))
    }

    private val resolver = SearchRequestResolver(
        sourceManager,
        extensionManager,
        sourcePreferences,
        AppDispatchers(io = testDispatcher, default = testDispatcher, main = testDispatcher),
    )

    @Test
    fun `a request with no address and no package resolves to null without waiting for the registry`() = runTest(testDispatcher) {
        initialized.value = false

        val id = resolver.sourceSearchOf(SearchRequest("berserk"))

        id.shouldBeNull()
    }

    @Test
    fun `one source whose site matches the host is the answer`() = runTest(testDispatcher) {
        catalogue.value = listOf(source(1L, "https://one.example.org"), source(2L, "https://two.example.org"))

        resolver.sourceSearchOf(SearchRequest("q", addresses = listOf("https://two.example.org/page")))?.sourceId shouldBe 2L
    }

    @Test
    fun `a www on either side still matches the source`() = runTest(testDispatcher) {
        catalogue.value = listOf(source(1L, "https://example.org"), source(2L, "https://other.example.net"))

        resolver.sourceSearchOf(SearchRequest("q", addresses = listOf("https://www.example.org/page")))?.sourceId shouldBe 1L
    }

    @Test
    fun `two sources on the same site resolve to null`() = runTest(testDispatcher) {
        catalogue.value = listOf(source(1L, "https://example.org"), source(2L, "https://www.example.org/en"))

        resolver.sourceSearchOf(SearchRequest("q", addresses = listOf("https://example.org/page"))).shouldBeNull()
    }

    @Test
    fun `no source on the site resolves to null`() = runTest(testDispatcher) {
        catalogue.value = listOf(source(1L, "https://one.example.org"))

        resolver.sourceSearchOf(SearchRequest("q", addresses = listOf("https://elsewhere.example.net/page"))).shouldBeNull()
    }

    @Test
    fun `a source whose base URL throws is skipped rather than failing the lookup`() = runTest(testDispatcher) {
        val broken = mockk<CatalogueSource> {
            every { id } returns 9L
            every { lang } returns "en"
            every { baseUrl } throws IllegalStateException("extension code failed")
        }
        catalogue.value = listOf(broken, source(2L, "https://example.org"))

        resolver.sourceSearchOf(SearchRequest("q", addresses = listOf("https://example.org/page")))?.sourceId shouldBe 2L
    }

    @Test
    fun `a source whose base URL is not a web address is skipped`() = runTest(testDispatcher) {
        catalogue.value = listOf(source(1L, "not a url"), source(2L, "https://example.org"))

        resolver.sourceSearchOf(SearchRequest("q", addresses = listOf("https://example.org/page")))?.sourceId shouldBe 2L
    }

    @Test
    fun `an extension with one source resolves to that source`() = runTest(testDispatcher) {
        val only = source(5L, "https://example.org")
        every { sourceManager.get(5L) } returns only
        installed.value = listOf(extension("dev.example.one", only))

        resolver.sourceSearchOf(SearchRequest("q", extensionPackage = "dev.example.one"))?.sourceId shouldBe 5L
    }

    @Test
    fun `an extension with two sources resolves to null`() = runTest(testDispatcher) {
        val a = source(5L, "https://a.example.org")
        val b = source(6L, "https://b.example.org")
        every { sourceManager.get(5L) } returns a
        every { sourceManager.get(6L) } returns b
        installed.value = listOf(extension("dev.example.two", a, b))

        resolver.sourceSearchOf(SearchRequest("q", extensionPackage = "dev.example.two")).shouldBeNull()
    }

    @Test
    fun `a package that is not installed resolves to null`() = runTest(testDispatcher) {
        installed.value = listOf(extension("dev.example.one", source(5L, "https://example.org")))

        resolver.sourceSearchOf(SearchRequest("q", extensionPackage = "dev.example.missing")).shouldBeNull()
    }

    @Test
    fun `an extension package wins over a host`() = runTest(testDispatcher) {
        val viaPackage = source(5L, "https://package.example.org")
        every { sourceManager.get(5L) } returns viaPackage
        installed.value = listOf(extension("dev.example.one", viaPackage))
        catalogue.value = listOf(viaPackage, source(7L, "https://host.example.org"))

        resolver.sourceSearchOf(SearchRequest("q", addresses = listOf("https://host.example.org/page"), extensionPackage = "dev.example.one"))?.sourceId shouldBe 5L
    }

    // -- only the sources Global Search would ask -------------------------------------------------

    /** The device check's case: an extension for all languages, one source per language, one site. */
    @Test
    fun `many sources on one site with one enabled language resolve to that one`() = runTest(testDispatcher) {
        catalogue.value = listOf(
            source(1L, "https://example.org", lang = "fr"),
            source(2L, "https://example.org", lang = "en"),
            source(3L, "https://example.org", lang = "de"),
            source(4L, "https://example.org", lang = "es"),
            source(5L, "https://example.org", lang = "all"),
        )

        resolver.sourceSearchOf(SearchRequest("q", addresses = listOf("https://example.org/page")))?.sourceId shouldBe 2L
    }

    @Test
    fun `two enabled languages of the same site resolve to null`() = runTest(testDispatcher) {
        sourcePreferences.enabledLanguages.set(setOf("en", "fr"))
        catalogue.value = listOf(
            source(1L, "https://example.org", lang = "en"),
            source(2L, "https://example.org", lang = "fr"),
            source(3L, "https://example.org", lang = "de"),
        )

        resolver.sourceSearchOf(SearchRequest("q", addresses = listOf("https://example.org/page"))).shouldBeNull()
    }

    @Test
    fun `a hidden source is ignored`() = runTest(testDispatcher) {
        sourcePreferences.disabledSources.set(setOf("1"))
        catalogue.value = listOf(source(1L, "https://example.org"), source(2L, "https://example.org"))

        resolver.sourceSearchOf(SearchRequest("q", addresses = listOf("https://example.org/page")))?.sourceId shouldBe 2L
    }

    @Test
    fun `a site whose only sources are turned off resolves to null rather than to one of them`() = runTest(testDispatcher) {
        sourcePreferences.disabledSources.set(setOf("2"))
        catalogue.value = listOf(source(1L, "https://example.org", lang = "fr"), source(2L, "https://example.org"))

        resolver.sourceSearchOf(SearchRequest("q", addresses = listOf("https://example.org/page"))).shouldBeNull()
    }

    @Test
    fun `the local source is never a host match`() = runTest(testDispatcher) {
        catalogue.value = listOf(
            source(SourceIds.LOCAL, "https://example.org", lang = "other"),
            source(2L, "https://example.org"),
        )

        resolver.sourceSearchOf(SearchRequest("q", addresses = listOf("https://example.org/page")))?.sourceId shouldBe 2L
    }

    @Test
    fun `an extension filter with one enabled source of several resolves to it`() = runTest(testDispatcher) {
        val fr = source(5L, "https://example.org", lang = "fr")
        val en = source(6L, "https://example.org", lang = "en")
        val de = source(7L, "https://example.org", lang = "de")
        every { sourceManager.get(5L) } returns fr
        every { sourceManager.get(6L) } returns en
        every { sourceManager.get(7L) } returns de
        installed.value = listOf(extension("dev.example.all", fr, en, de))

        resolver.sourceSearchOf(SearchRequest("q", extensionPackage = "dev.example.all"))?.sourceId shouldBe 6L
    }

    // -- several enabled sources of one extension on one site --------------------------------------

    /** An extension's "all" and "en" sources on one site, both enabled, as on a default install. */
    private fun allAndEnglish(): Pair<CatalogueSource, CatalogueSource> {
        sourcePreferences.enabledLanguages.set(setOf("all", "en"))
        val all = source(10L, "https://example.org", lang = "all")
        val en = source(11L, "https://example.org", lang = "en")
        every { sourceManager.get(10L) } returns all
        every { sourceManager.get(11L) } returns en
        catalogue.value = listOf(all, en)
        installed.value = listOf(extension("dev.example.multi", all, en))
        return all to en
    }

    @Test
    fun `an extension's all and en sources on one site resolve to the all source`() = runTest(testDispatcher) {
        allAndEnglish()

        resolver.sourceSearchOf(SearchRequest("q", addresses = listOf("https://example.org/page")))?.sourceId shouldBe 10L
    }

    @Test
    fun `the last used of an extension's sources wins over the all source`() = runTest(testDispatcher) {
        allAndEnglish()
        sourcePreferences.lastUsedSource.set(11L)

        resolver.sourceSearchOf(SearchRequest("q", addresses = listOf("https://example.org/page")))?.sourceId shouldBe 11L
    }

    @Test
    fun `the pinned one of an extension's sources wins over the last used and the all source`() = runTest(testDispatcher) {
        allAndEnglish()
        sourcePreferences.pinnedSources.set(setOf("11"))
        sourcePreferences.lastUsedSource.set(10L)

        resolver.sourceSearchOf(SearchRequest("q", addresses = listOf("https://example.org/page")))?.sourceId shouldBe 11L
    }

    @Test
    fun `two languages of one extension with none for all, pinned or last used resolve to null`() = runTest(testDispatcher) {
        sourcePreferences.enabledLanguages.set(setOf("en", "fr"))
        val en = source(11L, "https://example.org", lang = "en")
        val fr = source(12L, "https://example.org", lang = "fr")
        catalogue.value = listOf(en, fr)
        installed.value = listOf(extension("dev.example.multi", en, fr))

        resolver.sourceSearchOf(SearchRequest("q", addresses = listOf("https://example.org/page"))).shouldBeNull()
    }

    @Test
    fun `sources of two extensions on one site resolve to null, an all source among them or not`() = runTest(testDispatcher) {
        sourcePreferences.enabledLanguages.set(setOf("all", "en"))
        val all = source(10L, "https://example.org", lang = "all")
        val en = source(20L, "https://example.org", lang = "en")
        catalogue.value = listOf(all, en)
        installed.value = listOf(extension("dev.example.one", all), extension("dev.example.two", en))

        resolver.sourceSearchOf(SearchRequest("q", addresses = listOf("https://example.org/page"))).shouldBeNull()
    }

    @Test
    fun `an extension filter naming an extension with all and en sources resolves to the all source`() =
        runTest(testDispatcher) {
            allAndEnglish()

            resolver.sourceSearchOf(SearchRequest("q", extensionPackage = "dev.example.multi")) shouldBe
                SourceSearch(10L, "q")
        }

    // -- shared text, read and resolved together ---------------------------------------------------

    /** Chrome's share of a selected word: the word in quotes, a line break, a link to the selection. */
    private val chromeSelection = "\"Vagabond\"\nhttps://en.example.org/wiki/Vagabond_(manga)#:~:text=Vagabond"

    @Test
    fun `a shared selection from a site no source reads searches its words everywhere`() = runTest(testDispatcher) {
        catalogue.value = listOf(source(1L, "https://reader.example.net"))
        val request = SearchRequests.sharedText(chromeSelection)!!

        resolver.sourceSearchOf(request).shouldBeNull()
        request.query shouldBe "Vagabond"
    }

    @Test
    fun `a shared selection from a source's site searches that source for the address`() = runTest(testDispatcher) {
        catalogue.value = listOf(source(1L, "https://en.example.org"), source(2L, "https://reader.example.net"))
        val request = SearchRequests.sharedText(chromeSelection)!!

        resolver.sourceSearchOf(request) shouldBe
            SourceSearch(1L, "https://en.example.org/wiki/Vagabond_(manga)#:~:text=Vagabond")
    }

    @Test
    fun `the first address whose site one source reads wins, even when it is not the first address`() =
        runTest(testDispatcher) {
            catalogue.value = listOf(source(1L, "https://reader.example.net"))
            val request = SearchRequests.sharedText("Vagabond https://news.example.org/a and https://reader.example.net/b")!!

            resolver.sourceSearchOf(request) shouldBe SourceSearch(1L, "https://reader.example.net/b")
        }

    @Test
    fun `an address alone from a site no source reads searches the address everywhere`() = runTest(testDispatcher) {
        catalogue.value = listOf(source(1L, "https://reader.example.net"))
        val request = SearchRequests.sharedText("https://news.example.org/story")!!

        resolver.sourceSearchOf(request).shouldBeNull()
        request.query shouldBe "https://news.example.org/story"
    }

    @Test
    fun `an extension filter keeps the request's own query for its one source`() = runTest(testDispatcher) {
        val only = source(5L, "https://example.org")
        every { sourceManager.get(5L) } returns only
        installed.value = listOf(extension("dev.example.one", only))

        resolver.sourceSearchOf(SearchRequest("vagabond", extensionPackage = "dev.example.one")) shouldBe
            SourceSearch(5L, "vagabond")
    }

    @Test
    fun `a request made before the registry is initialised completes once it flips`() = runTest(testDispatcher) {
        initialized.value = false
        catalogue.value = listOf(source(1L, "https://example.org"))
        val answer = async { resolver.sourceSearchOf(SearchRequest("q", addresses = listOf("https://example.org/page"))) }

        advanceUntilIdle()
        answer.isCompleted shouldBe false

        initialized.value = true
        advanceUntilIdle()

        answer.await()?.sourceId shouldBe 1L
    }

    @Test
    fun `a lookup waiting for the registry sees the sources loaded by the time it flips`() = runTest(testDispatcher) {
        initialized.value = false
        val answer = async { resolver.sourceSearchOf(SearchRequest("q", addresses = listOf("https://example.org/page"))) }
        advanceUntilIdle()

        // The sources arrive with the flag, as they do when the extension host finishes its load.
        catalogue.value = listOf(source(3L, "https://example.org"))
        initialized.value = true
        advanceUntilIdle()

        answer.await()?.sourceId shouldBe 3L
    }

    private fun source(sourceId: Long, url: String, lang: String = "en"): CatalogueSource = mockk {
        every { id } returns sourceId
        every { baseUrl } returns url
        every { this@mockk.lang } returns lang
    }

    private fun extension(pkg: String, vararg sources: CatalogueSource) = Extension.Installed(
        packageName = pkg,
        name = "Fixture Extension",
        versionName = "1.0",
        versionCode = 1L,
        libVersion = 1.6,
        lang = "en",
        isNsfw = false,
        sources = sources.toList(),
        extFile = File("fixture.ext"),
    )
}

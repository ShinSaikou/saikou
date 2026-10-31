package com.shinsaikou.extensions.adapter

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.shouldBe
import okhttp3.Headers
import org.junit.jupiter.api.Test
import eu.kanade.tachiyomi.source.model.FilterList as AbiFilterList

/** The headers the in-app WebView opens a source's page with: the extension's own, by lowercased name. */
class ExtensionSourceAdapterHeadersTest {

    private class FixtureHttpSource : HttpSource() {
        override val baseUrl: String = "https://example.invalid"
        override val name: String = "Fixture Source A"
        override val lang: String = "en"
        override val supportsLatest: Boolean = false

        override fun headersBuilder(): Headers.Builder = Headers.Builder()
            .add("User-Agent", "Fixture Agent/1.0")
            .add("Referer", "$baseUrl/")
            .add("Accept-Language", "en")
            .add("Accept-Language", "ja")

        override suspend fun getPopularManga(page: Int): MangasPage = throw UnsupportedOperationException()
        override suspend fun getLatestUpdates(page: Int): MangasPage = throw UnsupportedOperationException()
        override suspend fun getSearchManga(page: Int, query: String, filters: AbiFilterList): MangasPage =
            throw UnsupportedOperationException()
        override suspend fun getMangaUpdate(
            manga: SManga,
            chapters: List<SChapter>,
            fetchDetails: Boolean,
            fetchChapters: Boolean,
        ): SMangaUpdate = throw UnsupportedOperationException()
        override suspend fun getPageList(chapter: SChapter): List<Page> = throw UnsupportedOperationException()
    }

    @Test
    fun `the headers are the HttpSource delegate's, lowercased, first value of a repeat`() {
        val adapter = SourceAdapters.wrap(FixtureHttpSource(), "com.shinsaikou.fixture", 1.6)

        adapter.requestHeaders shouldBe mapOf(
            "user-agent" to "Fixture Agent/1.0",
            "referer" to "https://example.invalid/",
            "accept-language" to "en",
        )
    }

    @Test
    fun `a delegate that is not an HttpSource has no headers`() {
        val adapter = SourceAdapters.wrap(FakeSource(filters = { AbiFilterList() }), "com.shinsaikou.fixture", 1.6)

        adapter.requestHeaders shouldBe emptyMap()
    }
}

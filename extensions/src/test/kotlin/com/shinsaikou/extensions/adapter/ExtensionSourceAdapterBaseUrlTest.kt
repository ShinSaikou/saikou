package com.shinsaikou.extensions.adapter

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import eu.kanade.tachiyomi.source.model.FilterList as AbiFilterList

/** The base URL the library update budgets a site by: the website's own, only for an [HttpSource]. */
class ExtensionSourceAdapterBaseUrlTest {

    private class FixtureHttpSource(override val baseUrl: String) : HttpSource() {
        override val name: String = "Fixture Source A"
        override val lang: String = "en"
        override val supportsLatest: Boolean = false

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
    fun `the base URL is the HttpSource delegate's`() {
        val adapter = SourceAdapters.wrap(FixtureHttpSource("https://example.invalid"), "com.shinsaikou.fixture", 1.6)

        adapter.baseUrl shouldBe "https://example.invalid"
    }

    @Test
    fun `a delegate that is not an HttpSource has no base URL`() {
        val adapter = SourceAdapters.wrap(FakeSource(filters = { AbiFilterList() }), "com.shinsaikou.fixture", 1.6)

        adapter.baseUrl shouldBe null
    }
}

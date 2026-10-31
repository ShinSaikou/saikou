package com.shinsaikou.extensions.adapter

import com.shinsaikou.source.model.ChapterInfo
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource

/**
 * Nothing in the extension ABI promises `Page.index` is 0-based, and a real catalog extension
 * numbers its own pages from 1. `getPageList` re-indexes by list position so the reader always
 * gets a 0-based, contiguous list whatever the source did.
 */
class ExtensionSourceAdapterPageListTest {

    /** A lib-1.6 extension overriding `getPageList` directly, the way `HttpSource` allows. */
    private class OneBasedPageSource(
        private val pages: () -> List<Page>,
    ) : HttpSource() {
        override val baseUrl: String = "https://example.invalid"
        override val name: String = "Fixture Source A"
        override val lang: String = "en"
        override val supportsLatest: Boolean = false

        override suspend fun getPageList(chapter: SChapter): List<Page> = pages()
    }

    private fun adapterFor(source: HttpSource) = SourceAdapters.wrap(source, "com.shinsaikou.fixture", 1.6)

    @Test
    fun `a delegate's 1-based page indices come back 0-based, in order, with urls paired to the right page`() = runTest {
        val delegatePages = listOf(
            Page(index = 1, url = "/page/1", imageUrl = "https://example.invalid/1.jpg"),
            Page(index = 2, url = "/page/2", imageUrl = "https://example.invalid/2.jpg"),
            Page(index = 3, url = "/page/3", imageUrl = "https://example.invalid/3.jpg"),
        )
        val adapter = adapterFor(OneBasedPageSource { delegatePages })

        val pages = adapter.getPageList(ChapterInfo(url = "/chapter/1", name = "Chapter 1"))

        pages.map { it.index } shouldBe listOf(0, 1, 2)
        pages.map { it.number } shouldBe listOf(1, 2, 3)
        pages.map { it.url } shouldBe listOf("/page/1", "/page/2", "/page/3")
        pages.map { it.imageUrl } shouldBe listOf(
            "https://example.invalid/1.jpg",
            "https://example.invalid/2.jpg",
            "https://example.invalid/3.jpg",
        )
    }
}

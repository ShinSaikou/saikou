package com.shinsaikou.source.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MangaPageTest {

    @Test
    fun `EMPTY has no entries and no next page`() {
        MangaPage.EMPTY.mangas shouldBe emptyList()
        MangaPage.EMPTY.hasNextPage shouldBe false
    }

    @Test
    fun `it is a value type`() {
        val page = MangaPage(listOf(MangaInfo(url = "/m", title = "t")), hasNextPage = true)

        page shouldBe MangaPage(listOf(MangaInfo(url = "/m", title = "t")), hasNextPage = true)
        page.copy(hasNextPage = false).hasNextPage shouldBe false
    }
}

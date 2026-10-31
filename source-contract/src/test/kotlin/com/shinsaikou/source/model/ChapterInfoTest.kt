package com.shinsaikou.source.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ChapterInfoTest {

    @Test
    fun `the default number is the not-parsed sentinel`() {
        val chapter = ChapterInfo(url = "/c", name = "Chapter 1")

        chapter.chapterNumber shouldBe ChapterInfo.UNKNOWN_NUMBER
        chapter.hasKnownNumber() shouldBe false
    }

    @Test
    fun `a number of zero counts as known`() {
        ChapterInfo(url = "/c", name = "Prologue", chapterNumber = 0f).hasKnownNumber() shouldBe true
    }

    @Test
    fun `a parsed number counts as known`() {
        ChapterInfo(url = "/c", name = "Chapter 1", chapterNumber = 1.5f).hasKnownNumber() shouldBe true
    }

    @Test
    fun `the defaults match the ABI impl`() {
        val chapter = ChapterInfo(url = "/c", name = "Chapter 1")

        chapter.scanlator shouldBe null
        chapter.dateUpload shouldBe 0L
        chapter.memo shouldBe MangaInfo.EMPTY_MEMO
    }
}

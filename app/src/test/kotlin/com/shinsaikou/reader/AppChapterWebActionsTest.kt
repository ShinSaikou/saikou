package com.shinsaikou.reader

import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceManager
import com.shinsaikou.source.model.ChapterInfo
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

class AppChapterWebActionsTest {

    private val sourceManager = mockk<SourceManager>()
    private val dispatcher = StandardTestDispatcher()
    private val actions = AppChapterWebActions(
        sourceManager,
        Dispatchers(io = dispatcher, default = dispatcher, main = dispatcher),
    )

    @Test
    fun `the url comes from the source the manga belongs to`() = runTest(dispatcher) {
        every { sourceManager.get(SOURCE_ID) } returns source { "https://example.invalid/c/1" }

        actions.chapterUrl(manga(), chapter()) shouldBe "https://example.invalid/c/1"
    }

    @Test
    fun `a source with no web presence answers null`() = runTest(dispatcher) {
        every { sourceManager.get(SOURCE_ID) } returns source { null }

        actions.chapterUrl(manga(), chapter()) shouldBe null
    }

    @Test
    fun `a blank url is the same as no url`() = runTest(dispatcher) {
        every { sourceManager.get(SOURCE_ID) } returns source { "  " }

        actions.chapterUrl(manga(), chapter()) shouldBe null
    }

    @Test
    fun `a source that is not loaded answers null`() = runTest(dispatcher) {
        every { sourceManager.get(SOURCE_ID) } returns null

        actions.chapterUrl(manga(), chapter()) shouldBe null
    }

    @Test
    fun `a throwing source answers null instead of taking the chrome down`() = runTest(dispatcher) {
        every { sourceManager.get(SOURCE_ID) } returns source { error("the extension threw") }

        actions.chapterUrl(manga(), chapter()) shouldBe null
    }

    private fun source(url: (ChapterInfo) -> String?) = mockk<Source> {
        every { getChapterUrl(any()) } answers { url(firstArg()) }
    }

    private fun manga() = Manga.create().copy(id = 1L, source = SOURCE_ID, url = "/m/1", title = "Fixture")

    private fun chapter() = Chapter.create().copy(id = 2L, mangaId = 1L, url = "/c/1", name = "Chapter 1")

    private companion object {
        const val SOURCE_ID = 7L
    }
}

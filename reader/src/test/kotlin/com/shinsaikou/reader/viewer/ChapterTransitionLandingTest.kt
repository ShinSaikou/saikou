package com.shinsaikou.reader.viewer

import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class ChapterTransitionLandingTest {

    private val prev = loaded(id = 1, pages = 3)
    private val curr = loaded(id = 2, pages = 1)
    private val next = loaded(id = 3, pages = 3)
    private val chapters = ViewerChapters(currChapter = curr, prevChapter = prev, nextChapter = next)

    /** The paged viewer's list once both neighbours have loaded and no transition is kept. */
    private val items: List<Any> = prev.pages!! + curr.pages!! + next.pages!!

    @Test
    fun `a removed transition to the next chapter lands on its first page`() {
        pageAfterRemovedTransition(ChapterTransition.Next(curr, next), items, chapters) shouldBe next.pages!!.first()
    }

    @Test
    fun `a removed transition to the previous chapter lands on its last page`() {
        pageAfterRemovedTransition(ChapterTransition.Prev(curr, prev), items, chapters) shouldBe prev.pages!!.last()
    }

    @Test
    fun `the right to left list, which is reversed, lands on the same pages`() {
        val reversed = items.reversed()
        pageAfterRemovedTransition(ChapterTransition.Next(curr, next), reversed, chapters) shouldBe next.pages!!.first()
        pageAfterRemovedTransition(ChapterTransition.Prev(curr, prev), reversed, chapters) shouldBe prev.pages!!.last()
    }

    @Test
    fun `a transition that is still in the list is left where it is`() {
        val transition = ChapterTransition.Next(curr, next)
        pageAfterRemovedTransition(transition, curr.pages!! + transition + next.pages!!, chapters) shouldBe null
    }

    @Test
    fun `a page shown, or nothing, is not a landing`() {
        pageAfterRemovedTransition(curr.pages!!.single(), items, chapters) shouldBe null
        pageAfterRemovedTransition(null, items, chapters) shouldBe null
    }

    @Test
    fun `a transition to no chapter, or to the chapter now current, is not a landing`() {
        pageAfterRemovedTransition(ChapterTransition.Next(next, null), items, chapters) shouldBe null
        // The window moved to the chapter the transition led to: a chapter switch, which moves the
        // viewer to its own page.
        val switched = ViewerChapters(currChapter = next, prevChapter = curr, nextChapter = null)
        pageAfterRemovedTransition(ChapterTransition.Next(curr, next), items, switched) shouldBe null
    }

    @Test
    fun `a chapter that is not loaded has no page to land on`() {
        val waiting = ReaderChapter(Chapter.create().copy(id = 4, mangaId = 1))
        val window = ViewerChapters(currChapter = curr, prevChapter = null, nextChapter = waiting)
        pageAfterRemovedTransition(ChapterTransition.Next(curr, waiting), curr.pages!!, window) shouldBe null
    }

    private fun loaded(id: Long, pages: Int): ReaderChapter =
        ReaderChapter(Chapter.create().copy(id = id, mangaId = 1)).apply {
            state = ReaderChapter.State.Loaded(List(pages) { ReaderPage(it).also { page -> page.chapter = this } })
        }
}

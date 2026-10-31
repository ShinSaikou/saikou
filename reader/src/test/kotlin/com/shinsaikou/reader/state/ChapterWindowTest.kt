package com.shinsaikou.reader.state

import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ChapterWindowTest {

    private val first = loadedChapter(1)
    private val middle = loadedChapter(2)
    private val last = loadedChapter(3)
    private val session = session(first, middle, last)

    @Test
    fun `the window around a middle chapter has both neighbours`() {
        val window = ChapterWindow.around(session, middle)
        window.currChapter shouldBe middle
        window.prevChapter shouldBe first
        window.nextChapter shouldBe last
    }

    @Test
    fun `the window at either end has one neighbour`() {
        ChapterWindow.around(session, first).prevChapter shouldBe null
        ChapterWindow.around(session, first).nextChapter shouldBe middle
        ChapterWindow.around(session, last).nextChapter shouldBe null
        ChapterWindow.around(session, last).prevChapter shouldBe middle
    }

    @Test
    fun `a chapter in both windows keeps its loader`() {
        val loader = CountingLoader()
        middle.pageLoader = loader
        val old = ChapterWindow.around(session, first)
        old.ref()

        ChapterWindow.applyRefs(add = ChapterWindow.around(session, middle), release = old)

        loader.recycled shouldBe 0
        middle.pageLoader shouldBe loader
        middle.state shouldBe ReaderChapter.State.Loaded(middle.pages!!)
    }

    @Test
    fun `a chapter that leaves the window is recycled and reset to wait`() {
        val loader = CountingLoader()
        first.pageLoader = loader
        val old = ChapterWindow.around(session, first)
        old.ref()

        // first is prev of middle, so it survives one step and is dropped on the next.
        val second = ChapterWindow.around(session, middle)
        ChapterWindow.applyRefs(add = second, release = old)
        loader.recycled shouldBe 0

        ChapterWindow.applyRefs(add = ChapterWindow.around(session, last), release = second)
        loader.recycled shouldBe 1
        first.pageLoader shouldBe null
        first.state shouldBe ReaderChapter.State.Wait
    }

    @Test
    fun `releasing first would recycle a chapter that never left the screen`() {
        // The failure the ordering in applyRefs exists to prevent, written out so that reordering
        // the two lines fails a test rather than costing a reload mid-session.
        val loader = CountingLoader()
        middle.pageLoader = loader
        val old = ChapterWindow.around(session, first)
        old.ref()

        old.unref()
        ChapterWindow.around(session, middle).ref()

        loader.recycled shouldBe 1
    }
}

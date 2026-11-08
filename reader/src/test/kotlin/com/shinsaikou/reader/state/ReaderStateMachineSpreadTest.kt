package com.shinsaikou.reader.state

import com.shinsaikou.domain.manga.model.ReaderOrientation
import com.shinsaikou.domain.manga.model.ReadingMode
import com.shinsaikou.reader.pipeline.PageState
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The reducer under the Experimental two-page view (ruling R165 item 2): a spread is reported as its
 * earlier page with the later one beside it, so read marks, the slider and resume keep speaking of
 * pages and never go one off.
 */
class ReaderStateMachineSpreadTest {

    private val first = loadedChapter(1, pageCount = 3)

    /** Five pages: 0 | 1-2 | 3-4. */
    private val current = loadedChapter(2, pageCount = 5)
    private val next = waitingChapter(3)
    private val session = session(first, current, next)

    @Test
    fun `a spread keeps its earlier page as the current one and saves it as the progress`() {
        val (state, effects) = machine().reduce(
            readyState(session, current),
            ReaderEvent.PageSelected(readyPage(current, 1), alsoShown = readyPage(current, 2)),
        )
        state.currentPage shouldBe 1
        state.spreadLastPage shouldBe 2
        effects.filterIsInstance<ReaderEffect.CommitProgress>() shouldContainExactly listOf(
            ReaderEffect.CommitProgress(current, pageIndex = 1, chapterFinished = false, immediate = false),
        )
    }

    @Test
    fun `the spread that holds the last page marks the chapter read, saved as its earlier page`() {
        val (_, effects) = machine().reduce(
            readyState(session, current, currentPage = 1),
            ReaderEvent.PageSelected(readyPage(current, 3), alsoShown = readyPage(current, 4)),
        )
        effects.filterIsInstance<ReaderEffect.CommitProgress>() shouldContainExactly listOf(
            ReaderEffect.CommitProgress(
                current,
                pageIndex = 3,
                chapterFinished = true,
                immediate = true,
                readBack = listOf(current.chapter.id, first.chapter.id),
            ),
        )
    }

    @Test
    fun `the spread before the last one does not finish the chapter`() {
        val (_, effects) = machine().reduce(
            readyState(session, current),
            ReaderEvent.PageSelected(readyPage(current, 1), alsoShown = readyPage(current, 2)),
        )
        effects.filterIsInstance<ReaderEffect.CommitProgress>().single().chapterFinished shouldBe false
    }

    @Test
    fun `a last page that failed to load does not finish the chapter, and the earlier page is still saved`() {
        val last = readyPage(current, 4).apply { status = PageState.Error(Exception()) }
        val (_, effects) = machine().reduce(
            readyState(session, current, currentPage = 1),
            ReaderEvent.PageSelected(readyPage(current, 3), alsoShown = last),
        )
        effects.filterIsInstance<ReaderEffect.CommitProgress>() shouldContainExactly listOf(
            ReaderEffect.CommitProgress(current, pageIndex = 3, chapterFinished = false, immediate = false),
        )
    }

    @Test
    fun `one page reported after a spread clears the later page`() {
        val spread = readyState(session, current, currentPage = 1).copy(spreadLastPage = 2)
        val (state, _) = machine().reduce(spread, ReaderEvent.PageSelected(readyPage(current, 0)))
        state.currentPage shouldBe 0
        state.spreadLastPage shouldBe null
    }

    @Test
    fun `a spread entered in the next chapter is reported the same way`() {
        val loadedNext = loadedChapter(3, pageCount = 4)
        val ready = readyState(session(first, current, loadedNext), current, currentPage = 3).copy(spreadLastPage = 4)
        val (state, effects) = machine().reduce(
            ready,
            ReaderEvent.PageSelected(readyPage(loadedNext, 1), alsoShown = readyPage(loadedNext, 2)),
        )
        state.window?.currChapter shouldBe loadedNext
        state.currentPage shouldBe 1
        state.spreadLastPage shouldBe 2
        effects.filterIsInstance<ReaderEffect.CommitProgress>().single().pageIndex shouldBe 1
    }

    // ---- The slider: one stop per page ----

    @Test
    fun `releasing the slider on the other page of the spread on screen moves nothing`() {
        val dragged = readyState(session, current, currentPage = 2).copy(
            spreadLastPage = 2,
            scrubbing = true,
            scrubStartPage = 1,
        )
        val (state, effects) = machine().reduce(dragged, ReaderEvent.SliderReleased(2))
        // The viewer stays on 1-2 and reports nothing, so the state is back on the spread already.
        state.currentPage shouldBe 1
        state.spreadLastPage shouldBe 2
        state.movingBySlider shouldBe false
        effects shouldContainExactly listOf(ReaderEffect.MoveViewerToPage(readyPage(current, 2)))
    }

    @Test
    fun `releasing the slider on a page of another spread moves the viewer to the spread that holds it`() {
        val dragged = readyState(session, current, currentPage = 4).copy(
            spreadLastPage = 2,
            scrubbing = true,
            scrubStartPage = 1,
        )
        val (released, effects) = machine().reduce(dragged, ReaderEvent.SliderReleased(4))
        released.currentPage shouldBe 4
        released.spreadLastPage shouldBe null
        released.movingBySlider shouldBe true
        effects shouldContainExactly listOf(ReaderEffect.MoveViewerToPage(readyPage(current, 4)))

        // The viewer shows 3-4 and reports its earlier page.
        val (landed, _) = machine().reduce(
            released,
            ReaderEvent.PageSelected(readyPage(current, 3), alsoShown = readyPage(current, 4)),
        )
        landed.currentPage shouldBe 3
        landed.spreadLastPage shouldBe 4
        landed.movingBySlider shouldBe false
    }

    @Test
    fun `dragging the slider leaves the spread on screen where it is`() {
        val spread = readyState(session, current, currentPage = 1).copy(spreadLastPage = 2)
        val (state, _) = machine().reduce(spread, ReaderEvent.SliderMoved(4))
        state.currentPage shouldBe 4
        state.spreadLastPage shouldBe 2
    }

    // ---- Resume ----

    @Test
    fun `reopening on a saved spread asks the viewer for its earlier page, which that spread holds`() {
        // The progress of spread 3-4 is page 3.
        val (state, effects) = machine().reduce(
            ReaderState(session = session),
            ReaderEvent.ChapterLoaded(current, requestedPage = 3),
        )
        state.currentPage shouldBe 3
        state.spreadLastPage shouldBe null
        effects.last() shouldBe ReaderEffect.MoveViewerToPage(readyPage(current, 3))
    }

    // ---- Split pages and the mode switch ----

    @Test
    fun `with split parts in the list the last spread still finishes the chapter`() {
        // Page 1 is a tall page cut in three; the list holds 0, 1, 1', 1'', 2, 3, 4.
        splitPage(current, 1, parts = 2)
        val (_, effects) = machine().reduce(
            readyState(session, current, currentPage = 2),
            ReaderEvent.PageSelected(readyPage(current, 3), alsoShown = readyPage(current, 4)),
        )
        effects.filterIsInstance<ReaderEffect.CommitProgress>().single().chapterFinished shouldBe true
    }

    @Test
    fun `a rebuilt viewer over a split chapter goes back to the spread's earlier page, not to a part`() {
        splitPage(current, 1, parts = 2)
        val ready = readyState(session, current, currentPage = 3).copy(spreadLastPage = 4)
        machine().reduce(ready, ReaderEvent.ViewerBuilt(0)).second.last() shouldBe
            ReaderEffect.MoveViewerToPage(readyPage(current, 3))
    }

    @Test
    fun `a change of reading mode drops the later page until the new viewer reports`() {
        val spread = readyState(session, current, currentPage = 3).copy(spreadLastPage = 4)
        val (state, _) = machine().reduce(
            spread,
            ReaderEvent.PreferencesChanged(ReadingMode.WEBTOON, ReaderOrientation.FREE),
        )
        state.currentPage shouldBe 3
        state.spreadLastPage shouldBe null
    }
}

package com.shinsaikou.reader.ui

import com.shinsaikou.reader.pipeline.PageState
import com.shinsaikou.reader.state.ReaderDialog
import com.shinsaikou.reader.state.ReaderPhase
import com.shinsaikou.reader.state.ReaderState
import com.shinsaikou.reader.state.loadedChapter
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The reader's banner scoping rule. A solve started by a background library update lands in the
 * same map the banner reads, so what matters is that a reader showing a loaded page never counts as
 * waiting, whatever else is in flight.
 */
class ReaderSolveBannerTest {

    private val reading = ReaderState(
        phase = ReaderPhase.Ready,
        window = ViewerChapters(loadedChapter(1), prevChapter = null, nextChapter = null),
        currentPage = 0,
    )

    @Test
    fun `a reader on a loaded page is not waiting`() {
        reading.isWaitingOnContent(PageState.Ready) shouldBe false
    }

    @Test
    fun `a page still on its way is waiting`() {
        reading.isWaitingOnContent(PageState.Queue) shouldBe true
        reading.isWaitingOnContent(PageState.LoadPage) shouldBe true
        reading.isWaitingOnContent(PageState.DownloadImage) shouldBe true
        reading.isWaitingOnContent(null) shouldBe true
    }

    @Test
    fun `a page that failed is not waiting`() {
        reading.isWaitingOnContent(PageState.Error(IllegalStateException("gone"))) shouldBe false
    }

    @Test
    fun `the loading dialog and an adjacent chapter load wait even over a loaded page`() {
        reading.copy(dialog = ReaderDialog.Loading).isWaitingOnContent(PageState.Ready) shouldBe true
        reading.copy(loadingAdjacent = true).isWaitingOnContent(PageState.Ready) shouldBe true
    }

    @Test
    fun `no chapter window yet is waiting, and a failed session never is`() {
        ReaderState().isWaitingOnContent(null) shouldBe true
        ReaderState(phase = ReaderPhase.Failed(IllegalStateException("gone")), dialog = ReaderDialog.Loading)
            .isWaitingOnContent(null) shouldBe false
    }
}

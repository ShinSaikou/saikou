package com.shinsaikou.reader

import android.content.Context
import com.shinsaikou.domain.preferences.ReaderPreferences
import com.shinsaikou.reader.state.ReaderEvent
import com.shinsaikou.reader.state.chapter
import com.shinsaikou.reader.viewer.ReaderViewerHost
import com.shinsaikou.reader.viewer.ViewerHost
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import kotlinx.coroutines.Dispatchers as KotlinxDispatchers

/**
 * The menu after a slider release in the pager (plan unit K2 item 3, found by unit K's device run).
 *
 * `PagerViewer` hides the menu on every page selection unless `host.isScrollingThroughPages`, as
 * upstream's does. Upstream's slider moves the viewer while the flag is up, so its moves keep the menu.
 * This reader moves the viewer only on release, after the flag came down, so every slider move hid
 * the menu the owner was using. [pagerSelects] is the pager listener's two lines, run against the
 * shell's real [ReaderViewerHost] over a real view model.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderSliderMenuTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        KotlinxDispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        KotlinxDispatchers.resetMain()
    }

    @Test
    fun `a slider release keeps the menu open, and the next swipe still hides it`() = runTest(testDispatcher) {
        val viewModel = readerViewModel(openedOn(chapterId = 1L), listOf(chapter(1L)), testDispatcher)
        val host = hostOf(viewModel)
        advanceUntilIdle()
        viewModel.send(ReaderEvent.ShowMenu)
        viewModel.send(ReaderEvent.SliderMoved(3))
        viewModel.send(ReaderEvent.SliderReleased(3))
        advanceUntilIdle()

        // The release's MoveViewerToPage lands in the pager, which selects page 4.
        pagerSelects(host, viewModel.chapter(1L).pages!![3])
        advanceUntilIdle()
        viewModel.state.value.menuVisible shouldBe true
        viewModel.state.value.currentPage shouldBe 3

        // The owner's own swipe afterwards is not the slider's.
        pagerSelects(host, viewModel.chapter(1L).pages!![4])
        advanceUntilIdle()
        viewModel.state.value.menuVisible shouldBe false
        viewModel.state.value.currentPage shouldBe 4
    }

    @Test
    fun `a slider dragged back to the page the reader is on leaves the next swipe to hide the menu`() =
        runTest(testDispatcher) {
            val viewModel = readerViewModel(openedOn(chapterId = 1L), listOf(chapter(1L)), testDispatcher)
            val host = hostOf(viewModel)
            advanceUntilIdle()
            viewModel.send(ReaderEvent.ShowMenu)
            viewModel.send(ReaderEvent.SliderMoved(3))
            viewModel.send(ReaderEvent.SliderMoved(0))
            viewModel.send(ReaderEvent.SliderReleased(0))
            advanceUntilIdle()

            // The release lands on the page the pager already shows, so the pager selects nothing.
            host.isScrollingThroughPages shouldBe false
            viewModel.state.value.menuVisible shouldBe true

            pagerSelects(host, viewModel.chapter(1L).pages!![1])
            advanceUntilIdle()
            viewModel.state.value.menuVisible shouldBe false
            viewModel.state.value.currentPage shouldBe 1
        }

    /** `PagerViewer.pagerListener.onPageSelected`, less the holder bookkeeping. */
    private fun pagerSelects(host: ViewerHost, page: ReaderPage) {
        if (!host.isScrollingThroughPages) {
            host.hideMenu()
        }
        host.onPageSelected(page)
    }

    private fun TestScope.hostOf(viewModel: ReaderViewModel) = ReaderViewerHost(
        context = mockk<Context>(relaxed = true),
        scope = backgroundScope,
        readerPreferences = ReaderPreferences(InMemoryPreferenceStore()),
        state = { viewModel.state.value },
        send = viewModel::send,
        animatedPolicy = mockk(),
    )
}

package com.shinsaikou.reader

import android.content.ComponentCallbacks2
import app.cash.turbine.test
import com.shinsaikou.domain.chapter.model.PageListEmptyException
import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.reader.state.ReaderEffect
import com.shinsaikou.reader.state.ReaderEvent
import com.shinsaikou.reader.state.ReaderPhase
import com.shinsaikou.reader.state.chapter
import com.shinsaikou.source.SourceNotInstalledException
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlinx.coroutines.Dispatchers as KotlinxDispatchers

/**
 * The shell's effects reach the screen when it starts collecting late (plan unit K4 item 1).
 *
 * The session loads before `ReaderScreen`'s first composition has started its collector, and a
 * chapter that fails at once sends `Finish` within milliseconds of that. A shared flow with no replay
 * dropped every effect sent before the collector: the reader stayed open, blank, with no message
 * (unit K3, 36 of 42 counted runs on the API 26 AVD), and the entry's orientation from
 * `SessionLoaded` was lost the same way. Each test here sends everything before anything collects,
 * which is the device's order, then collects.
 *
 * The same holds across an activity recreation: the screen's collector is cancelled with the old
 * activity, and whatever the view model sends meanwhile waits for the new one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelEffectDeliveryTest {

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
    fun `a chapter that fails before the screen collects still closes the reader, saying why`() =
        runTest(testDispatcher) {
            val viewModel = readerViewModel(
                openedOn(chapterId = 1L),
                listOf(chapter(1L)),
                testDispatcher,
                resolver = resolverOf { throw SourceNotInstalledException(MISSING_SOURCE) },
            )
            advanceUntilIdle()
            viewModel.state.value.phase.shouldBeInstanceOf<ReaderPhase.Failed>()

            viewModel.effects.test {
                awaitItem().shouldBeInstanceOf<ReaderEffect.ApplyOrientation>()
                awaitItem().shouldBeInstanceOf<ReaderEffect.RebuildViewer>()
                val finish = awaitItem().shouldBeInstanceOf<ReaderEffect.Finish>()
                finish.error.shouldBeInstanceOf<SourceNotInstalledException>()
                expectNoEvents()
            }
        }

    /** Upstream's chapter loader throws its `page_list_empty_error` here; the screen names it by key (R67 rule 3). */
    @Test
    fun `a chapter whose page list is empty closes the reader with the page list empty failure`() =
        runTest(testDispatcher) {
            val viewModel = readerViewModel(
                openedOn(chapterId = 1L),
                listOf(chapter(1L)),
                testDispatcher,
                resolver = resolverOf { PlainLoader(pages = 0) },
            )
            advanceUntilIdle()
            viewModel.state.value.phase.shouldBeInstanceOf<ReaderPhase.Failed>()

            viewModel.effects.test {
                awaitItem().shouldBeInstanceOf<ReaderEffect.ApplyOrientation>()
                awaitItem().shouldBeInstanceOf<ReaderEffect.RebuildViewer>()
                val finish = awaitItem().shouldBeInstanceOf<ReaderEffect.Finish>()
                finish.message shouldBe null
                ErrorKey.of(finish.error.shouldBeInstanceOf<PageListEmptyException>()) shouldBe ErrorKey.PAGE_LIST_EMPTY
                expectNoEvents()
            }
        }

    @Test
    fun `a session that opens before the screen collects applies its orientation and shows its page`() =
        runTest(testDispatcher) {
            val viewModel = readerViewModel(openedOn(chapterId = 1L, page = 2), listOf(chapter(1L)), testDispatcher)
            advanceUntilIdle()
            viewModel.state.value.phase shouldBe ReaderPhase.Ready

            viewModel.effects.test {
                awaitItem() shouldBe ReaderEffect.ApplyOrientation(viewModel.state.value.orientation)
                awaitItem().shouldBeInstanceOf<ReaderEffect.RebuildViewer>()
                awaitItem().shouldBeInstanceOf<ReaderEffect.SetViewerChapters>()
                awaitItem().shouldBeInstanceOf<ReaderEffect.MoveViewerToPage>().page.index shouldBe 2
                expectNoEvents()
            }
        }

    @Test
    fun `an effect sent while no screen collects waits for the next screen`() = runTest(testDispatcher) {
        val viewModel = readerViewModel(openedOn(chapterId = 1L), listOf(chapter(1L)), testDispatcher)
        advanceUntilIdle()
        // The first activity's screen takes the session's effects, then goes with its activity.
        viewModel.effects.test {
            awaitItem().shouldBeInstanceOf<ReaderEffect.ApplyOrientation>()
            cancelAndIgnoreRemainingEvents()
        }

        viewModel.send(ReaderEvent.TrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
        advanceUntilIdle()

        viewModel.effects.test {
            awaitItem() shouldBe ReaderEffect.TrimViewer
            expectNoEvents()
        }
    }

    /**
     * Phase 4 unit MP1, R102 carry-over 1: the reading mode reaches the screen through the one queue
     * when the viewer is built, as upstream's `updateViewer` toasts it, read from the setting each time.
     */
    @Test
    fun `a built viewer sends its reading mode to the screen only while Show reading mode is on`() =
        runTest(testDispatcher) {
            val viewModel = readerViewModel(openedOn(chapterId = 1L), listOf(chapter(1L)), testDispatcher)
            advanceUntilIdle()
            viewModel.readerPreferences.showReadingMode.get() shouldBe true
            viewModel.effects.test {
                awaitItem().shouldBeInstanceOf<ReaderEffect.ApplyOrientation>()
                awaitItem().shouldBeInstanceOf<ReaderEffect.RebuildViewer>()
                awaitItem().shouldBeInstanceOf<ReaderEffect.SetViewerChapters>()
                awaitItem().shouldBeInstanceOf<ReaderEffect.MoveViewerToPage>()
                expectNoEvents()

                viewModel.send(ReaderEvent.ViewerBuilt(0))
                advanceUntilIdle()
                awaitItem() shouldBe ReaderEffect.ShowReadingMode(viewModel.state.value.readingMode)
                awaitItem().shouldBeInstanceOf<ReaderEffect.SetViewerChapters>()
                awaitItem().shouldBeInstanceOf<ReaderEffect.MoveViewerToPage>()
                expectNoEvents()

                viewModel.readerPreferences.showReadingMode.set(false)
                viewModel.send(ReaderEvent.ViewerBuilt(0))
                advanceUntilIdle()
                awaitItem().shouldBeInstanceOf<ReaderEffect.SetViewerChapters>()
                awaitItem().shouldBeInstanceOf<ReaderEffect.MoveViewerToPage>()
                expectNoEvents()
            }
        }

    private companion object {
        const val MISSING_SOURCE = 9L
    }
}

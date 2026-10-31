package com.shinsaikou.reader

import com.shinsaikou.reader.state.ReaderEffect
import com.shinsaikou.reader.state.ReaderEvent
import com.shinsaikou.reader.state.ReaderPhase
import com.shinsaikou.reader.state.chapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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
 * The menu's Next chapter button, once the next chapter has been preloaded (plan unit K2 item 1,
 * found by unit K's device run on the API 26 AVD).
 *
 * A preload's job stays active while it collects its loader's splits, which never complete, and
 * `ReaderViewModel.load` returned early for any chapter with an active job. So the switch the
 * button asks for was dropped: the reader stayed on the chapter it was on, with `loadingAdjacent`
 * stuck true. Both loaders here have splits that never complete, as the local and source loaders do.
 *
 * The last three tests are Next then Previous while the next chapter is still fetching (found by
 * the K2 verifier). The owner's latest switch wins, and the switch it replaced leaves nothing
 * behind: no chapter marked failed, no load error shown, no late move back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelChapterSwitchTest {

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
    fun `Next chapter moves to a chapter that is already preloaded`() = runTest(testDispatcher) {
        val viewModel = readerViewModel(openedOn(chapterId = 1L), listOf(chapter(1L), chapter(2L)), testDispatcher)
        advanceUntilIdle()

        viewModel.send(ReaderEvent.PreloadRequested(viewModel.chapter(2L)))
        advanceUntilIdle()
        viewModel.chapter(2L).state.shouldBeInstanceOf<ReaderChapter.State.Loaded>()

        viewModel.send(ReaderEvent.NextChapter)
        advanceUntilIdle()

        val state = viewModel.state.value
        state.phase shouldBe ReaderPhase.Ready
        state.currentChapter?.chapter?.id shouldBe 2L
        state.currentPage shouldBe 0
        state.loadingAdjacent shouldBe false
    }

    @Test
    fun `Next chapter pressed while the preload is still fetching moves there once it arrives`() =
        runTest(testDispatcher) {
            val gate = CompletableDeferred<Unit>()
            val resolver = resolverOf { chapter ->
                if (chapter.chapter.id == 2L) PlainLoader(beforePages = { gate.await() }) else PlainLoader()
            }
            val viewModel = readerViewModel(
                openedOn(chapterId = 1L),
                listOf(chapter(1L), chapter(2L)),
                testDispatcher,
                resolver = resolver,
            )
            advanceUntilIdle()

            viewModel.send(ReaderEvent.PreloadRequested(viewModel.chapter(2L)))
            advanceUntilIdle()
            viewModel.send(ReaderEvent.NextChapter)
            advanceUntilIdle()
            viewModel.state.value.loadingAdjacent shouldBe true
            viewModel.state.value.currentChapter?.chapter?.id shouldBe 1L

            gate.complete(Unit)
            advanceUntilIdle()

            viewModel.state.value.currentChapter?.chapter?.id shouldBe 2L
            viewModel.state.value.loadingAdjacent shouldBe false
        }

    @Test
    fun `a preload nobody switched to stays adjacent`() = runTest(testDispatcher) {
        val viewModel = readerViewModel(openedOn(chapterId = 1L), listOf(chapter(1L), chapter(2L)), testDispatcher)
        advanceUntilIdle()

        viewModel.send(ReaderEvent.PreloadRequested(viewModel.chapter(2L)))
        advanceUntilIdle()

        viewModel.state.value.currentChapter?.chapter?.id shouldBe 1L
        viewModel.state.value.window?.nextChapter?.chapter?.id shouldBe 2L
    }

    @Test
    fun `Previous pressed after Next while the next chapter is still fetching ends on the previous chapter`() =
        runTest(testDispatcher) {
            val gate = CompletableDeferred<Unit>()
            val resolver = resolverOf { chapter ->
                if (chapter.chapter.id == 3L) PlainLoader(beforePages = { gate.await() }) else PlainLoader()
            }
            val viewModel = readerViewModel(
                openedOn(chapterId = 2L),
                listOf(chapter(1L), chapter(2L), chapter(3L)),
                testDispatcher,
                resolver = resolver,
            )
            val effects = mutableListOf<ReaderEffect>()
            val collector = launch { viewModel.effects.collect { effects += it } }
            advanceUntilIdle()

            viewModel.send(ReaderEvent.PreloadRequested(viewModel.chapter(1L)))
            viewModel.send(ReaderEvent.PreloadRequested(viewModel.chapter(3L)))
            advanceUntilIdle()
            viewModel.chapter(1L).state.shouldBeInstanceOf<ReaderChapter.State.Loaded>()
            val next = viewModel.chapter(3L)

            viewModel.send(ReaderEvent.NextChapter)
            viewModel.send(ReaderEvent.PreviousChapter)
            advanceUntilIdle()
            gate.complete(Unit)
            advanceUntilIdle()

            val state = viewModel.state.value
            state.currentChapter?.chapter?.id shouldBe 1L
            state.loadingAdjacent shouldBe false
            // The window let chapter 3 go, and a released chapter waits to be loaded again.
            next.state shouldBe ReaderChapter.State.Wait
            effects.filterIsInstance<ReaderEffect.ShowMessage>() shouldBe emptyList()
            collector.cancel()
        }

    @Test
    fun `Previous pressed after Next ends on the previous chapter when the next chapter arrives first`() =
        runTest(testDispatcher) {
            val previousGate = CompletableDeferred<Unit>()
            val nextGate = CompletableDeferred<Unit>()
            val resolver = resolverOf { chapter ->
                when (chapter.chapter.id) {
                    1L -> PlainLoader(beforePages = { previousGate.await() })
                    3L -> PlainLoader(beforePages = { nextGate.await() })
                    else -> PlainLoader()
                }
            }
            val viewModel = readerViewModel(
                openedOn(chapterId = 2L),
                listOf(chapter(1L), chapter(2L), chapter(3L), chapter(4L)),
                testDispatcher,
                resolver = resolver,
            )
            advanceUntilIdle()

            viewModel.send(ReaderEvent.PreloadRequested(viewModel.chapter(3L)))
            advanceUntilIdle()
            viewModel.send(ReaderEvent.NextChapter)
            viewModel.send(ReaderEvent.PreviousChapter)
            advanceUntilIdle()

            nextGate.complete(Unit)
            advanceUntilIdle()
            viewModel.state.value.currentChapter?.chapter?.id shouldBe 2L

            previousGate.complete(Unit)
            advanceUntilIdle()
            viewModel.state.value.currentChapter?.chapter?.id shouldBe 1L
            viewModel.state.value.loadingAdjacent shouldBe false
        }

    @Test
    fun `a switch the owner replaced before its chapter loaded shows no load error`() = runTest(testDispatcher) {
        val gate = CompletableDeferred<Unit>()
        val resolver = resolverOf { chapter ->
            if (chapter.chapter.id == 3L) PlainLoader(beforePages = { gate.await() }) else PlainLoader()
        }
        val viewModel = readerViewModel(
            openedOn(chapterId = 2L),
            listOf(chapter(1L), chapter(2L), chapter(3L)),
            testDispatcher,
            resolver = resolver,
        )
        val effects = mutableListOf<ReaderEffect>()
        val collector = launch { viewModel.effects.collect { effects += it } }
        advanceUntilIdle()

        viewModel.send(ReaderEvent.PreloadRequested(viewModel.chapter(1L)))
        advanceUntilIdle()
        val next = viewModel.chapter(3L)

        // No preload of chapter 3: Next starts its load as the switch itself.
        viewModel.send(ReaderEvent.NextChapter)
        advanceUntilIdle()
        viewModel.send(ReaderEvent.PreviousChapter)
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        effects.filterIsInstance<ReaderEffect.ShowMessage>() shouldBe emptyList()
        viewModel.state.value.currentChapter?.chapter?.id shouldBe 1L
        next.state shouldBe ReaderChapter.State.Wait
        collector.cancel()
    }
}

package com.shinsaikou.reader

import com.shinsaikou.reader.pipeline.PageLoader
import com.shinsaikou.reader.pipeline.PageLoaderResolver
import com.shinsaikou.reader.state.ReaderEvent
import com.shinsaikou.reader.state.chapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlinx.coroutines.Dispatchers as KotlinxDispatchers

/**
 * A load whose chapter leaves the window while its fetch is still running (plan unit K3 item 3,
 * R74's carry-over 6).
 *
 * The window's release puts a chapter back to `Wait` and cancels its load. A cancelled job sends
 * nothing (R74 rule 6), but a fetch that does not stop for the cancellation, a blocking file read or
 * a call already on the wire, used to finish anyway and write the chapter's state from the IO thread
 * after the release: `Loaded` from a finished page list, or `Loading` with a loader nobody would
 * recycle when the resolve finished and the page list then stopped. Either way the chapter came
 * back into the window looking loaded or loading, which a preload never retries, so its transition
 * page waited for good.
 *
 * Each fetch here is held in `NonCancellable`, which is what such a fetch amounts to: it finishes
 * after the job was cancelled.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelReleasedLoadTest {

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
    fun `a page list that arrives after the window let its chapter go leaves the chapter waiting`() =
        runTest(testDispatcher) {
            val gate = CompletableDeferred<Unit>()
            var nextLoads = 0
            val resolver = resolverOf { chapter ->
                if (chapter.chapter.id == 3L && nextLoads++ == 0) {
                    PlainLoader(beforePages = { withContext(NonCancellable) { gate.await() } })
                } else {
                    PlainLoader()
                }
            }
            val viewModel = readerViewModel(
                openedOn(chapterId = 2L),
                listOf(chapter(1L), chapter(2L), chapter(3L)),
                testDispatcher,
                resolver = resolver,
            )
            advanceUntilIdle()
            val next = viewModel.chapter(3L)
            viewModel.send(ReaderEvent.PreloadRequested(next))
            advanceUntilIdle()

            // Previous moves the window to chapters 1 and 2, which releases chapter 3 mid-fetch.
            viewModel.send(ReaderEvent.PreviousChapter)
            advanceUntilIdle()
            viewModel.state.value.currentChapter?.chapter?.id shouldBe 1L
            gate.complete(Unit)
            advanceUntilIdle()

            next.state shouldBe ReaderChapter.State.Wait
            next.pageLoader shouldBe null

            // Back in the window, the chapter preloads like any other.
            viewModel.send(ReaderEvent.NextChapter)
            advanceUntilIdle()
            viewModel.send(ReaderEvent.PreloadRequested(next))
            advanceUntilIdle()
            next.state.shouldBeInstanceOf<ReaderChapter.State.Loaded>()
        }

    @Test
    fun `a loader resolved after the window let its chapter go is recycled and the chapter is not left loading`() =
        runTest(testDispatcher) {
            val gate = CompletableDeferred<Unit>()
            // Its page list stops at the first suspension point, as a cancelled fetch does.
            val late = RecordingLoader(beforePages = { yield() })
            var nextLoads = 0
            val resolver = mockk<PageLoaderResolver> {
                coEvery { resolve(any(), any(), any(), any()) } coAnswers {
                    val chapter = secondArg<ReaderChapter>()
                    if (chapter.chapter.id == 3L && nextLoads++ == 0) {
                        withContext(NonCancellable) { gate.await() }
                        late
                    } else {
                        PlainLoader()
                    }
                }
            }
            val viewModel = readerViewModel(
                openedOn(chapterId = 2L),
                listOf(chapter(1L), chapter(2L), chapter(3L)),
                testDispatcher,
                resolver = resolver,
            )
            advanceUntilIdle()
            val next = viewModel.chapter(3L)
            viewModel.send(ReaderEvent.PreloadRequested(next))
            advanceUntilIdle()

            viewModel.send(ReaderEvent.PreviousChapter)
            advanceUntilIdle()
            gate.complete(Unit)
            advanceUntilIdle()

            next.state shouldBe ReaderChapter.State.Wait
            next.pageLoader shouldBe null
            late.recycled shouldBe true

            viewModel.send(ReaderEvent.NextChapter)
            advanceUntilIdle()
            viewModel.send(ReaderEvent.PreloadRequested(next))
            advanceUntilIdle()
            next.state.shouldBeInstanceOf<ReaderChapter.State.Loaded>()
        }

    /** A [PlainLoader] that remembers being recycled. */
    private class RecordingLoader(beforePages: suspend () -> Unit) : PageLoader by PlainLoader(beforePages = beforePages) {
        var recycled = false

        override fun recycle() {
            recycled = true
        }
    }
}

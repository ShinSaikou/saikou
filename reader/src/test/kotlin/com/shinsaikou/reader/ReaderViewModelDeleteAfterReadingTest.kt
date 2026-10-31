package com.shinsaikou.reader

import com.shinsaikou.reader.state.ProgressCommitter
import com.shinsaikou.reader.state.ReaderEvent
import com.shinsaikou.reader.state.chapter
import io.kotest.matchers.shouldBe
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.verify
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
import kotlinx.coroutines.Dispatchers as KotlinxDispatchers

/**
 * The reader's two calls for the delete after reading (phase 3 unit DR), through a real
 * [ReaderViewModel]: the last page hands the committer the session's chapters from the finished one
 * back, and a finish asks the committer to delete what was staged after the finish's own flush. The
 * committer's side, which waits for that flush's write, is `ProgressCommitterTest`'s.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelDeleteAfterReadingTest {

    private val testDispatcher = StandardTestDispatcher()
    private val committer = mockk<ProgressCommitter>(relaxed = true)

    @BeforeEach
    fun setUp() {
        KotlinxDispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        KotlinxDispatchers.resetMain()
    }

    @Test
    fun `the last page hands the committer the session's chapters from the finished one back`() =
        runTest(testDispatcher) {
            val viewModel = openedOnChapterThree()

            val last = checkNotNull(viewModel.chapter(3L).pages).last()
            viewModel.send(ReaderEvent.PageSelected(last))
            advanceUntilIdle()

            verify {
                committer.onPageSelected(
                    chapter = viewModel.chapter(3L),
                    pageIndex = last.index,
                    chapterFinished = true,
                    immediate = true,
                    nextChapterId = 4L,
                    readBack = listOf(3L, 2L, 1L),
                )
            }
        }

    @Test
    fun `finishing deletes the staged chapters after asking for the flush`() = runTest(testDispatcher) {
        val viewModel = openedOnChapterThree()

        viewModel.send(ReaderEvent.Finishing)
        advanceUntilIdle()

        coVerifyOrder {
            committer.flush(viewModel.chapter(3L))
            committer.deletePendingChapters()
        }
    }

    @Test
    fun `finishing a session that never loaded still deletes what an earlier one staged`() = runTest(testDispatcher) {
        val viewModel = readerViewModel(openedOn(chapterId = 99L), listOf(chapter(1L)), testDispatcher, committer = committer)
        advanceUntilIdle()

        viewModel.send(ReaderEvent.Finishing)
        advanceUntilIdle()

        coVerify(exactly = 1) { committer.deletePendingChapters() }
    }

    private fun TestScope.openedOnChapterThree(): ReaderViewModel {
        val viewModel = readerViewModel(
            openedOn(chapterId = 3L),
            (1L..4L).map { chapter(it) },
            testDispatcher,
            committer = committer,
        )
        advanceUntilIdle()
        viewModel.state.value.currentChapter?.chapter?.id shouldBe 3L
        return viewModel
    }
}

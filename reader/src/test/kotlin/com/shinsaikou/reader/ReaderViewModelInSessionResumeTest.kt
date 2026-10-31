package com.shinsaikou.reader

import com.shinsaikou.reader.state.ReaderEvent
import com.shinsaikou.reader.state.chapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
import tachiyomi.domain.chapter.model.Chapter
import kotlinx.coroutines.Dispatchers as KotlinxDispatchers

/**
 * Where a chapter reached inside a session opens (plan unit K3 item 1, R74's carry-over 2).
 *
 * R74 rule 4 kept Next, Previous and a switch to a preloaded chapter at the chapter's requested
 * page, 0 for a chapter the session had not shown, so an unread chapter with progress reached by
 * Next opened at its first page. Now a chapter the session has not placed opens by rule 2, read
 * from its row when it loads: the page it was left on while it is not read, its first page once it
 * is. A chapter the session has shown keeps the page it was left on, incognito or not. The opening
 * chapter's rules 1 to 3 are `ReaderViewModelOpenTest`'s, unchanged.
 *
 * Each test drives the real view model and state machine; the rows, the registry and the page
 * loaders are fakes, and the progress committer is a relaxed mock that writes nothing, so a page
 * the reader keeps is one the view model kept rather than one the committer wrote.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelInSessionResumeTest {

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
    fun `Next chapter opens an unread chapter at the page it was left on`() = runTest(testDispatcher) {
        val viewModel = opened(listOf(chapter(1L), chapter(2L, lastPageRead = 3)), on = 1L)

        viewModel.send(ReaderEvent.NextChapter)
        advanceUntilIdle()

        viewModel.currentChapterId() shouldBe 2L
        viewModel.state.value.currentPage shouldBe 3
    }

    @Test
    fun `Previous chapter opens an unread chapter at the page it was left on`() = runTest(testDispatcher) {
        val viewModel = opened(listOf(chapter(1L, lastPageRead = 2), chapter(2L)), on = 2L)

        viewModel.send(ReaderEvent.PreviousChapter)
        advanceUntilIdle()

        viewModel.currentChapterId() shouldBe 1L
        viewModel.state.value.currentPage shouldBe 2
    }

    @Test
    fun `Next chapter opens a finished chapter at its first page`() = runTest(testDispatcher) {
        val viewModel = opened(listOf(chapter(1L), chapter(2L, read = true, lastPageRead = 4)), on = 1L)

        viewModel.send(ReaderEvent.NextChapter)
        advanceUntilIdle()

        viewModel.currentChapterId() shouldBe 2L
        viewModel.state.value.currentPage shouldBe 0
    }

    @Test
    fun `Next chapter to a preloaded chapter opens it at the page it was left on`() = runTest(testDispatcher) {
        val viewModel = opened(listOf(chapter(1L), chapter(2L, lastPageRead = 3)), on = 1L)
        viewModel.send(ReaderEvent.PreloadRequested(viewModel.chapter(2L)))
        advanceUntilIdle()
        viewModel.chapter(2L).state.shouldBeInstanceOf<ReaderChapter.State.Loaded>()

        viewModel.send(ReaderEvent.NextChapter)
        advanceUntilIdle()

        viewModel.currentChapterId() shouldBe 2L
        viewModel.state.value.currentPage shouldBe 3
    }

    @Test
    fun `the row is read when the chapter loads, so progress written during the session counts`() =
        runTest(testDispatcher) {
            // The session is built from rows where chapter 2 is untouched; its row then changes, as
            // a write earlier in the session (or from another screen) would change it.
            val rows = mutableMapOf(1L to chapter(1L), 2L to chapter(2L))
            val viewModel = readerViewModel(
                openedOn(chapterId = 1L),
                rows.values.toList(),
                testDispatcher,
                rows = { id -> rows[id] },
            )
            advanceUntilIdle()
            rows[2L] = chapter(2L, lastPageRead = 2)

            viewModel.send(ReaderEvent.NextChapter)
            advanceUntilIdle()

            viewModel.currentChapterId() shouldBe 2L
            viewModel.state.value.currentPage shouldBe 2
            viewModel.chapter(2L).chapter.lastPageRead shouldBe 2L
        }

    @Test
    fun `a chapter the session showed keeps the page it was left on, in incognito too`() = runTest(testDispatcher) {
        // Incognito writes no progress, so only the view model can remember where chapter 1 was left.
        val viewModel = opened(listOf(chapter(1L), chapter(2L)), on = 1L, incognito = true)
        viewModel.send(ReaderEvent.PageSelected(viewModel.page(1L, 3)))
        advanceUntilIdle()

        viewModel.send(ReaderEvent.NextChapter)
        advanceUntilIdle()
        viewModel.currentChapterId() shouldBe 2L
        viewModel.send(ReaderEvent.PreviousChapter)
        advanceUntilIdle()

        viewModel.currentChapterId() shouldBe 1L
        viewModel.state.value.currentPage shouldBe 3
    }

    @Test
    fun `a shown chapter the window let go keeps its page when it loads again, not its row's`() =
        runTest(testDispatcher) {
            // Chapter 2's row says page 5, from before this incognito session. The session opens it
            // there, the owner goes back to page 2, and two chapters on the window releases it.
            val chapters = listOf(chapter(1L), chapter(2L, lastPageRead = 4), chapter(3L), chapter(4L), chapter(5L))
            val viewModel = opened(chapters, on = 1L, incognito = true)
            viewModel.send(ReaderEvent.NextChapter)
            advanceUntilIdle()
            viewModel.state.value.currentPage shouldBe 4
            viewModel.send(ReaderEvent.PageSelected(viewModel.page(2L, 1)))
            advanceUntilIdle()

            viewModel.send(ReaderEvent.NextChapter)
            advanceUntilIdle()
            viewModel.send(ReaderEvent.NextChapter)
            advanceUntilIdle()
            viewModel.currentChapterId() shouldBe 4L
            viewModel.chapter(2L).state shouldBe ReaderChapter.State.Wait

            viewModel.send(ReaderEvent.PreviousChapter)
            advanceUntilIdle()
            viewModel.send(ReaderEvent.PreviousChapter)
            advanceUntilIdle()

            viewModel.currentChapterId() shouldBe 2L
            viewModel.state.value.currentPage shouldBe 1
        }

    @Test
    fun `a preloaded chapter the owner swipes into is shown at the page the swipe reaches`() =
        runTest(testDispatcher) {
            val viewModel = opened(listOf(chapter(1L), chapter(2L, lastPageRead = 3)), on = 1L)
            viewModel.send(ReaderEvent.PreloadRequested(viewModel.chapter(2L)))
            advanceUntilIdle()

            // Past chapter 1's transition page onto chapter 2's first page, as the pager reports it.
            viewModel.send(ReaderEvent.PageSelected(viewModel.page(2L, 0)))
            advanceUntilIdle()

            viewModel.currentChapterId() shouldBe 2L
            viewModel.state.value.currentPage shouldBe 0
        }

    private fun TestScope.opened(chapters: List<Chapter>, on: Long, incognito: Boolean = false): ReaderViewModel {
        val viewModel = readerViewModel(openedOn(chapterId = on), chapters, testDispatcher, incognito = incognito)
        advanceUntilIdle()
        viewModel.currentChapterId() shouldBe on
        return viewModel
    }

    private fun ReaderViewModel.currentChapterId(): Long? = state.value.currentChapter?.chapter?.id

    private fun ReaderViewModel.page(chapterId: Long, index: Int) = checkNotNull(chapter(chapterId).pages)[index]
}

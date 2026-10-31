package com.shinsaikou.reader

import app.cash.turbine.Event
import app.cash.turbine.test
import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.TrackPreferences
import com.shinsaikou.domain.manga.model.MangaNotFoundException
import com.shinsaikou.domain.manga.model.ReadingMode
import com.shinsaikou.reader.state.MANGA_ID
import com.shinsaikou.reader.state.ProgressCommitter
import com.shinsaikou.reader.state.ReaderEffect
import com.shinsaikou.reader.state.ReaderEvent
import com.shinsaikou.reader.state.ReaderPhase
import com.shinsaikou.reader.state.chapter
import com.shinsaikou.reader.state.manga
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
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
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers
import kotlinx.coroutines.Dispatchers as KotlinxDispatchers

/**
 * The entry's row leaving while a reader session is open (phase 4 unit BF, ruling R119 carry-over
 * 1): Clear database removes it, from a second window on a large screen. The reader followed the row
 * through the non-null flow, which throws from its collector when the row is gone, and the process
 * died with it. Now the session ends as on any other failure: the reader closes through its effect
 * queue, naming the failure by its key, and writes no progress for the entry after the row is gone.
 *
 * The repository here is a fake of the real one's two flows over one row: the non-null flow
 * throws as SQLDelight's `executeAsOne` does on an absent row, and the nullable flow emits null.
 * The interactor between them and the view model is the real [GetManga].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelEntryGoneTest {

    private val testDispatcher = StandardTestDispatcher()
    private val committer = mockk<ProgressCommitter>(relaxed = true)

    /** The entry's row as the database holds it; null once Clear database has removed it. */
    private val row = MutableStateFlow<Manga?>(manga())

    private val repository = mockk<MangaRepository> {
        coEvery { getMangaById(MANGA_ID) } answers { checkNotNull(row.value) { "ResultSet returned null" } }
        coEvery { getMangaByIdAsFlow(MANGA_ID) } returns row.map {
            it ?: throw NullPointerException("ResultSet returned null for mangas.sq:getMangaById")
        }
        every { getMangaByIdOrNullAsFlow(MANGA_ID) } returns row
    }

    @BeforeEach
    fun setUp() {
        KotlinxDispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        KotlinxDispatchers.resetMain()
    }

    @Test
    fun `the entry's row leaving an open session closes the reader, saying the entry is gone`() =
        runTest(testDispatcher) {
            val viewModel = openedOnChapterOne()

            viewModel.effects.test {
                // The session's own opening effects first.
                cancelAndConsumeRemainingEvents()
            }
            row.value = null
            advanceUntilIdle()

            val phase = viewModel.state.value.phase.shouldBeInstanceOf<ReaderPhase.Failed>()
            phase.error.shouldBeInstanceOf<MangaNotFoundException>()
            viewModel.effects.test {
                val finish = awaitItem().shouldBeInstanceOf<ReaderEffect.Finish>()
                // No message of its own: the screen names it through R67's resolver, by key.
                finish.message shouldBe null
                ErrorKey.of(finish.error.shouldBeInstanceOf<MangaNotFoundException>()) shouldBe
                    ErrorKey.MANGA_NOT_FOUND
                expectNoEvents()
            }
        }

    /**
     * A row removed while the process was dead (phase 5 unit FX, R128 carry-over 2): the reader comes
     * back from its handle to an entry the database no longer has. It closed with the generic error
     * the session load's `error(...)` raised; it now names the entry as gone, by its key.
     */
    @Test
    fun `a reader restored over an entry removed while the process was dead closes saying the entry is gone`() =
        runTest(testDispatcher) {
            row.value = null
            val viewModel = readerViewModel(
                openedOn(chapterId = 1L, page = 3),
                (1L..2L).map { chapter(it) },
                testDispatcher,
                committer = committer,
                getManga = GetManga(repository),
            )
            advanceUntilIdle()

            val phase = viewModel.state.value.phase.shouldBeInstanceOf<ReaderPhase.Failed>()
            ErrorKey.of(phase.error) shouldBe ErrorKey.MANGA_NOT_FOUND
            viewModel.effects.test {
                val finish = awaitItem().shouldBeInstanceOf<ReaderEffect.Finish>()
                finish.message shouldBe null
                ErrorKey.of(checkNotNull(finish.error)) shouldBe ErrorKey.MANGA_NOT_FOUND
                expectNoEvents()
            }
        }

    @Test
    fun `no progress is written for the entry once its row is gone`() = runTest(testDispatcher) {
        val viewModel = openedOnChapterOne()
        val pages = checkNotNull(viewModel.chapter(1L).pages)
        viewModel.send(ReaderEvent.PageSelected(pages[1]))
        advanceUntilIdle()
        verify(exactly = 1) { committer.onPageSelected(any(), any(), any(), any(), any(), any()) }

        row.value = null
        advanceUntilIdle()
        // The page turn above is still waiting for its debounced write: it is dropped, unwritten.
        verify(exactly = 1) { committer.discard() }

        // What the reader does while it closes: a page the viewer still reports, the pause and the
        // finish the activity sends.
        viewModel.send(ReaderEvent.PageSelected(pages[2]))
        viewModel.send(ReaderEvent.NextChapter)
        viewModel.send(ReaderEvent.ToggleBookmark)
        viewModel.send(ReaderEvent.Paused)
        viewModel.send(ReaderEvent.Finishing)
        advanceUntilIdle()

        verify(exactly = 1) { committer.onPageSelected(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { committer.flush(any()) }
        coVerify(exactly = 0) { committer.commitHistory(any()) }
        // The staged deletes a finish always runs still run; they name no chapter of this entry.
        coVerify(exactly = 1) { committer.deletePendingChapters() }
        viewModel.state.value.bookmarked shouldBe false
    }

    @Test
    fun `a change to the entry's row still reaches the session while the row is there`() = runTest(testDispatcher) {
        val viewModel = openedOnChapterOne()
        viewModel.state.value.mangaReadingMode shouldBe ReadingMode.DEFAULT

        row.value = manga(viewerFlags = ReadingMode.WEBTOON.flagValue.toLong())
        advanceUntilIdle()

        viewModel.state.value.phase shouldBe ReaderPhase.Ready
        viewModel.state.value.mangaReadingMode shouldBe ReadingMode.WEBTOON
        viewModel.effects.test {
            val finishes = cancelAndConsumeRemainingEvents()
                .filterIsInstance<Event.Item<ReaderEffect>>()
                .map { it.value }
                .filterIsInstance<ReaderEffect.Finish>()
            finishes shouldContainExactly emptyList()
        }
    }

    @Test
    fun `a chapter change whose history row fails its foreign key does not take the process down`() =
        runTest(testDispatcher) {
            // The row is still there when the chapter changes, and gone by the time the history
            // insert runs: Clear database landed between the two, from a second window.
            val history = mockk<HistoryRepository> {
                coEvery { upsertHistory(any()) } throws java.sql.SQLException(
                    "[SQLITE_CONSTRAINT_FOREIGNKEY] A foreign key constraint failed (FOREIGN KEY constraint failed)",
                )
            }
            val viewModel = openedOnChapterOne(realCommitter(history))

            viewModel.send(ReaderEvent.NextChapter)
            advanceUntilIdle()

            // An uncaught throw from the launched write fails this test, as it killed the process.
            coVerify(exactly = 1) { history.upsertHistory(match { it.chapterId == 1L }) }
            viewModel.state.value.phase shouldBe ReaderPhase.Ready
            viewModel.state.value.currentChapter?.chapter?.id shouldBe 2L
        }

    /** The real committer over [history], every other dependency a relaxed fake. */
    private fun TestScope.realCommitter(history: HistoryRepository) = ProgressCommitter(
        scope = this,
        updateChapter = UpdateChapter(mockk(relaxed = true)),
        upsertHistory = UpsertHistory(history),
        setReadStatus = mockk(relaxed = true),
        getChaptersByMangaId = mockk(relaxed = true),
        libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
        trackPreferences = TrackPreferences(InMemoryPreferenceStore()),
        trackProgress = mockk(relaxed = true),
        autoDownload = mockk(relaxed = true),
        readChapterCleanup = mockk(relaxed = true),
        dispatchers = AppDispatchers(io = testDispatcher, default = testDispatcher, main = testDispatcher),
    )

    private fun TestScope.openedOnChapterOne(
        committer: ProgressCommitter = this@ReaderViewModelEntryGoneTest.committer,
    ): ReaderViewModel {
        val viewModel = readerViewModel(
            openedOn(chapterId = 1L),
            (1L..2L).map { chapter(it) },
            testDispatcher,
            committer = committer,
            getManga = GetManga(repository),
        )
        advanceUntilIdle()
        viewModel.state.value.phase shouldBe ReaderPhase.Ready
        return viewModel
    }
}

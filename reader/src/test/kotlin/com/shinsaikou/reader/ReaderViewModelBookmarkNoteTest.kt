package com.shinsaikou.reader

import androidx.lifecycle.SavedStateHandle
import com.shinsaikou.reader.state.ReaderDialog
import com.shinsaikou.reader.state.ReaderEvent
import com.shinsaikou.reader.state.chapter
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import io.kotest.matchers.shouldBe
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
 * Page notes in the reader (phase 4 unit BN), through a real [ReaderViewModel] and the real note
 * interactors over [FakeBookmarkNoteRepository]: the page sheet's Note button opens a one-field
 * dialog holding the page's stored note, Save writes it, blank text removes it, and the bottom bar's
 * marker ([com.shinsaikou.reader.state.ReaderState.currentNote]) follows the page on screen across pages
 * and chapters. A note is keyed by the reader page's index, so every part of a split page reads and
 * writes its source page's note. A note dialog open when the process dies comes back with the text
 * typed into it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelBookmarkNoteTest {

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
    fun `a note written from the page sheet is stored on its page and shown by the marker`() =
        runTest(testDispatcher) {
            val notes = FakeBookmarkNoteRepository()
            val viewModel = openedOnChapterOne(notes)
            val page = pagesOf(viewModel, 1L)[2]

            noteFromThePageSheet(viewModel, page)
            viewModel.state.value.dialog shouldBe ReaderDialog.PageNote(chapterId = 1L, pageIndex = 2, note = "")

            viewModel.send(ReaderEvent.SaveNote(1L, 2, "art reference"))
            advanceUntilIdle()

            viewModel.state.value.dialog shouldBe null
            notes.stored() shouldBe listOf(Triple(1L, 2L, "art reference"))
            viewModel.state.value.currentNote shouldBe null
            viewModel.send(ReaderEvent.PageSelected(page))
            advanceUntilIdle()
            viewModel.state.value.currentNote shouldBe "art reference"
        }

    @Test
    fun `the dialog opens on the page's stored note and Save replaces it`() = runTest(testDispatcher) {
        val notes = FakeBookmarkNoteRepository(note(1L, 2L, "plot point"))
        val viewModel = openedOnChapterOne(notes)

        noteFromThePageSheet(viewModel, pagesOf(viewModel, 1L)[2])
        viewModel.state.value.dialog shouldBe ReaderDialog.PageNote(chapterId = 1L, pageIndex = 2, note = "plot point")

        viewModel.send(ReaderEvent.SaveNote(1L, 2, "plot point, again in chapter 3"))
        advanceUntilIdle()

        notes.stored() shouldBe listOf(Triple(1L, 2L, "plot point, again in chapter 3"))
    }

    @Test
    fun `saving blank text from the marker's dialog removes the note`() = runTest(testDispatcher) {
        val notes = FakeBookmarkNoteRepository(note(1L, 2L, "plot point"), note(1L, 4L, "kept"))
        val viewModel = openedOnChapterOne(notes)
        viewModel.send(ReaderEvent.PageSelected(pagesOf(viewModel, 1L)[2]))
        advanceUntilIdle()
        viewModel.state.value.currentNote shouldBe "plot point"

        viewModel.send(ReaderEvent.EditCurrentNote)
        advanceUntilIdle()
        viewModel.state.value.dialog shouldBe ReaderDialog.PageNote(chapterId = 1L, pageIndex = 2, note = "plot point")

        viewModel.send(ReaderEvent.SaveNote(1L, 2, " \n\t "))
        advanceUntilIdle()

        viewModel.state.value.dialog shouldBe null
        notes.stored() shouldBe listOf(Triple(1L, 4L, "kept"))
        viewModel.state.value.currentNote shouldBe null
    }

    @Test
    fun `the marker follows the page on screen, across a chapter switch and a note written elsewhere`() =
        runTest(testDispatcher) {
            val notes = FakeBookmarkNoteRepository(note(1L, 1L, "chapter one, page two"), note(2L, 0L, "chapter two, page one"))
            val viewModel = openedOnChapterOne(notes)
            val pages = pagesOf(viewModel, 1L)
            viewModel.state.value.currentPage shouldBe 0
            viewModel.state.value.currentNote shouldBe null

            viewModel.send(ReaderEvent.PageSelected(pages[1]))
            advanceUntilIdle()
            viewModel.state.value.currentNote shouldBe "chapter one, page two"

            viewModel.send(ReaderEvent.PageSelected(pages[3]))
            advanceUntilIdle()
            viewModel.state.value.currentNote shouldBe null

            viewModel.send(ReaderEvent.NextChapter)
            advanceUntilIdle()
            viewModel.state.value.currentChapter?.chapter?.id shouldBe 2L
            viewModel.state.value.currentPage shouldBe 0
            viewModel.state.value.currentNote shouldBe "chapter two, page one"

            // A write the reader did not make, as a sync apply is: the collector brings it in.
            notes.upsert(2L, 3L, "synced in", 0L)
            viewModel.send(ReaderEvent.PageSelected(pagesOf(viewModel, 2L)[3]))
            advanceUntilIdle()
            viewModel.state.value.currentNote shouldBe "synced in"
        }

    @Test
    fun `a part of a split page writes and shows its source page's note`() = runTest(testDispatcher) {
        val notes = FakeBookmarkNoteRepository()
        val viewModel = openedOnChapterOne(notes)
        val source = pagesOf(viewModel, 1L)[1]
        val parts = listOf(InsertPage(source, partIndex = 1), InsertPage(source, partIndex = 2))
        viewModel.send(ReaderEvent.PageSplit(source, parts))
        advanceUntilIdle()
        // The parts sit after their source page, so their places in the list are 2 and 3.
        pagesOf(viewModel, 1L).indexOf(parts[1]) shouldBe 3

        noteFromThePageSheet(viewModel, parts[1])
        viewModel.state.value.dialog shouldBe ReaderDialog.PageNote(chapterId = 1L, pageIndex = 1, note = "")
        viewModel.send(ReaderEvent.SaveNote(1L, 1, "the tall panel"))
        advanceUntilIdle()

        notes.stored() shouldBe listOf(Triple(1L, 1L, "the tall panel"))
        viewModel.send(ReaderEvent.PageSelected(parts[0]))
        advanceUntilIdle()
        viewModel.state.value.currentNote shouldBe "the tall panel"
    }

    /**
     * Verifier finding 1. A process the system kills while the note dialog is open comes back as a
     * new view model over the handle the platform restored, and has to reopen the dialog on the same
     * page with what was typed, although nothing was saved.
     */
    @Test
    fun `a note dialog left open with unsaved text reopens with that text after a process death`() =
        runTest(testDispatcher) {
            val notes = FakeBookmarkNoteRepository(note(1L, 2L, "plot point"))
            val handle = openedOn(chapterId = 1L, page = 0)
            val before = openedOnChapterOne(notes, handle)
            noteFromThePageSheet(before, pagesOf(before, 1L)[2])
            before.send(ReaderEvent.NoteTextChanged("plot point, and the"))
            before.send(ReaderEvent.NoteTextChanged("plot point, and the letter in chapter 4"))
            advanceUntilIdle()

            val restored = afterProcessDeath(handle)
            val after = openedOnChapterOne(notes, restored)

            after.state.value.dialog shouldBe
                ReaderDialog.PageNote(chapterId = 1L, pageIndex = 2, note = "plot point, and the letter in chapter 4")
            notes.stored() shouldBe listOf(Triple(1L, 2L, "plot point"))
            after.send(ReaderEvent.SaveNote(1L, 2, "plot point, and the letter in chapter 4"))
            advanceUntilIdle()
            notes.stored() shouldBe listOf(Triple(1L, 2L, "plot point, and the letter in chapter 4"))
            openedOnChapterOne(notes, afterProcessDeath(restored)).state.value.dialog shouldBe null
        }

    @Test
    fun `a note dialog closed without saving leaves nothing for a restore to reopen`() = runTest(testDispatcher) {
        val handle = openedOn(chapterId = 1L, page = 0)
        val before = openedOnChapterOne(FakeBookmarkNoteRepository(), handle)
        noteFromThePageSheet(before, pagesOf(before, 1L)[2])
        before.send(ReaderEvent.NoteTextChanged("never mind"))
        before.send(ReaderEvent.DismissDialog)
        // A change the field sends after the dialog has closed.
        before.send(ReaderEvent.NoteTextChanged("never mind, really"))
        advanceUntilIdle()

        handle.contains(ReaderIntents.KEY_NOTE_TEXT) shouldBe false
        openedOnChapterOne(FakeBookmarkNoteRepository(), afterProcessDeath(handle)).state.value.dialog shouldBe null
    }

    @Test
    fun `a restored note dialog on a chapter the entry no longer has is not reopened`() = runTest(testDispatcher) {
        val handle = openedOn(chapterId = 1L, page = 0).apply {
            set(ReaderIntents.KEY_NOTE_CHAPTER_ID, 9L)
            set(ReaderIntents.KEY_NOTE_PAGE, 2)
            set(ReaderIntents.KEY_NOTE_TEXT, "on a chapter a refresh removed")
        }

        val viewModel = openedOnChapterOne(FakeBookmarkNoteRepository(), handle)

        viewModel.state.value.dialog shouldBe null
    }

    private fun TestScope.openedOnChapterOne(
        notes: FakeBookmarkNoteRepository,
        handle: SavedStateHandle = openedOn(chapterId = 1L, page = 0),
    ): ReaderViewModel {
        val viewModel = readerViewModel(
            handle,
            listOf(chapter(1L), chapter(2L)),
            testDispatcher,
            notes = notes,
        )
        advanceUntilIdle()
        viewModel.state.value.currentChapter?.chapter?.id shouldBe 1L
        return viewModel
    }

    /**
     * A long press on [page], then the sheet's Note button, which sends the note event and nothing
     * else, as `ReaderPageActionsDialog` does: the reducer closes the sheet.
     */
    private fun TestScope.noteFromThePageSheet(viewModel: ReaderViewModel, page: ReaderPage) {
        viewModel.send(ReaderEvent.PageLongTapped(page))
        advanceUntilIdle()
        viewModel.state.value.dialog shouldBe ReaderDialog.PageActions(page)
        viewModel.send(ReaderEvent.EditNote(page))
        advanceUntilIdle()
    }

    /** A new handle holding what [handle] held, as the platform restores one from its bundle. */
    private fun afterProcessDeath(handle: SavedStateHandle): SavedStateHandle =
        SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) })

    private fun pagesOf(viewModel: ReaderViewModel, chapterId: Long): List<ReaderPage> =
        checkNotNull(viewModel.chapter(chapterId).pages)

    private fun FakeBookmarkNoteRepository.stored(): List<Triple<Long, Long, String>> =
        all.map { Triple(it.chapterId, it.pageIndex, it.note) }
}

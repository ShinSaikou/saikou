package com.shinsaikou.reader

import androidx.lifecycle.SavedStateHandle
import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.Event
import app.cash.turbine.test
import com.shinsaikou.data.AndroidDatabaseHandler
import com.shinsaikou.data.chapter.BookmarkNoteRepositoryImpl
import com.shinsaikou.domain.chapter.repository.BookmarkNoteRepository
import com.shinsaikou.i18n.R
import com.shinsaikou.reader.state.MANGA_ID
import com.shinsaikou.reader.state.ReaderDialog
import com.shinsaikou.reader.state.ReaderEffect
import com.shinsaikou.reader.state.ReaderEvent
import com.shinsaikou.reader.state.ReaderPhase
import com.shinsaikou.reader.state.chapter
import io.kotest.matchers.collections.shouldContainExactly
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
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import java.sql.SQLException
import java.util.Properties
import kotlinx.coroutines.Dispatchers as KotlinxDispatchers

/**
 * A page note saved after its chapter's row has left the database (phase 5 unit FX, R128 carry-over
 * 1): Clear database from a second window removes the entry with its chapters while the note dialog
 * is open. The note's insert then fails `bookmark_notes`' foreign key, and it was thrown, uncaught,
 * from the effect's launch, which took the process with it.
 *
 * The notes table is the real one, on the in-memory JVM driver with foreign keys on as the device's
 * driver has them, behind the real `:data` repository and the real note interactors. An uncaught
 * throw from the launched write fails the test, as it killed the process.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelNoteNotSavedTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var notes: BookmarkNoteRepositoryImpl

    @BeforeEach
    fun setUp() {
        KotlinxDispatchers.setMain(testDispatcher)
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), Database.Schema.synchronous())
        exec("PRAGMA foreign_keys = ON")
        val database = Database(
            driver = driver,
            chaptersAdapter = Chapters.Adapter(memoAdapter = MemoColumnAdapter),
            historyAdapter = History.Adapter(last_readAdapter = DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(
                genreAdapter = StringListColumnAdapter,
                update_strategyAdapter = UpdateStrategyColumnAdapter,
                memoAdapter = MemoColumnAdapter,
            ),
        )
        notes = BookmarkNoteRepositoryImpl(AndroidDatabaseHandler(database, testDispatcher))
        exec(
            """
            INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer,
                chapter_flags, cover_last_modified, date_added, memo)
            VALUES ($MANGA_ID, 1, '/manga/$MANGA_ID', 'Title', 0, 1, 0, 0, 0, 0, 0, CAST('{}' AS BLOB))
            """,
        )
        for (id in 1L..2L) {
            exec(
                """
                INSERT INTO chapters(_id, manga_id, url, name, scanlator, read, bookmark, last_page_read,
                    chapter_number, source_order, date_fetch, date_upload, memo)
                VALUES ($id, $MANGA_ID, '/$id', 'chapter $id', NULL, 0, 0, 0, $id, $id, 0, 0, CAST('{}' AS BLOB))
                """,
            )
        }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
        KotlinxDispatchers.resetMain()
    }

    @Test
    fun `a note saved after its chapter's row left is reported and its text kept`() = runTest(testDispatcher) {
        val handle = openedOn(chapterId = 1L, page = 0)
        val viewModel = openedOnChapterOne(notes, handle)
        val page = checkNotNull(viewModel.chapter(1L).pages)[2]
        viewModel.send(ReaderEvent.PageLongTapped(page))
        viewModel.send(ReaderEvent.EditNote(page))
        advanceUntilIdle()
        viewModel.state.value.dialog shouldBe ReaderDialog.PageNote(chapterId = 1L, pageIndex = 2, note = "")
        viewModel.send(ReaderEvent.NoteTextChanged("the letter in chapter 4"))
        advanceUntilIdle()
        viewModel.effects.test { cancelAndConsumeRemainingEvents() }

        // Chapter 1's row goes while the dialog is open, as Clear database takes it with its entry.
        exec("DELETE FROM chapters WHERE _id = 1")
        viewModel.send(ReaderEvent.SaveNote(1L, 2, "the letter in chapter 4"))
        advanceUntilIdle()

        messages(viewModel) shouldContainExactly listOf(ReaderEffect.ShowMessage(R.string.note_not_saved_entry_removed))
        handle.get<String>(ReaderIntents.KEY_NOTE_TEXT) shouldBe "the letter in chapter 4"
        handle.get<Long>(ReaderIntents.KEY_NOTE_CHAPTER_ID) shouldBe 1L
        handle.get<Int>(ReaderIntents.KEY_NOTE_PAGE) shouldBe 2
        count("SELECT count(*) FROM bookmark_notes") shouldBe 0L
        viewModel.state.value.dialog shouldBe null
        viewModel.state.value.phase shouldBe ReaderPhase.Ready
    }

    /**
     * Blank text removes a note. Over a chapter whose row is gone the delete matches no row, since
     * the chapter's notes went with it, and the database raises nothing: the note is gone, as asked,
     * and there is nothing to report.
     */
    @Test
    fun `a removal over a chapter whose row left raises nothing and reports nothing`() = runTest(testDispatcher) {
        notes.upsert(chapterId = 1L, pageIndex = 2L, note = "plot point", now = 0L)
        val viewModel = openedOnChapterOne(notes, openedOn(chapterId = 1L, page = 0))
        viewModel.effects.test { cancelAndConsumeRemainingEvents() }

        exec("DELETE FROM chapters WHERE _id = 1")
        viewModel.send(ReaderEvent.SaveNote(1L, 2, " "))
        advanceUntilIdle()

        messages(viewModel) shouldContainExactly emptyList()
        count("SELECT count(*) FROM bookmark_notes") shouldBe 0L
    }

    /** A removal the database refuses on a constraint says so, in the same words as a write. */
    @Test
    fun `a removal the database refuses on a constraint is reported`() = runTest(testDispatcher) {
        val refusing = RefusingRemoval(notes)
        val viewModel = openedOnChapterOne(refusing, openedOn(chapterId = 1L, page = 0))
        viewModel.effects.test { cancelAndConsumeRemainingEvents() }

        viewModel.send(ReaderEvent.SaveNote(1L, 2, ""))
        advanceUntilIdle()

        refusing.removals shouldBe 1
        messages(viewModel) shouldContainExactly listOf(ReaderEffect.ShowMessage(R.string.note_not_saved_entry_removed))
    }

    private fun TestScope.openedOnChapterOne(
        notes: BookmarkNoteRepository,
        handle: SavedStateHandle,
    ): ReaderViewModel {
        val viewModel = readerViewModel(handle, listOf(chapter(1L), chapter(2L)), testDispatcher, notes = notes)
        advanceUntilIdle()
        viewModel.state.value.currentChapter?.chapter?.id shouldBe 1L
        return viewModel
    }

    private suspend fun messages(viewModel: ReaderViewModel): List<ReaderEffect.ShowMessage> {
        var shown = emptyList<ReaderEffect.ShowMessage>()
        viewModel.effects.test {
            shown = cancelAndConsumeRemainingEvents()
                .filterIsInstance<Event.Item<ReaderEffect>>()
                .map { it.value }
                .filterIsInstance<ReaderEffect.ShowMessage>()
        }
        return shown
    }

    private fun exec(sql: String) {
        driver.execute(null, sql, 0).value
    }

    private fun count(sql: String): Long? = driver.executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor: SqlCursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null) },
        parameters = 0,
    ).value

    /**
     * The real repository, whose delete fails as the JVM driver fails a constraint: sqlite-jdbc's
     * exception is a [SQLException] carrying the primary result code.
     */
    private class RefusingRemoval(real: BookmarkNoteRepository) : BookmarkNoteRepository by real {
        var removals = 0

        override suspend fun remove(chapterId: Long, pageIndex: Long) {
            removals++
            throw SQLException("FOREIGN KEY constraint failed", null, SQLITE_CONSTRAINT)
        }
    }

    private companion object {
        const val SQLITE_CONSTRAINT = 19
    }
}

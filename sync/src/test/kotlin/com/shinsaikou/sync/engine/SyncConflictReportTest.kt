package com.shinsaikou.sync.engine

import com.shinsaikou.domain.sync.model.SyncLibraryCounts
import com.shinsaikou.sync.InMemorySyncBackend
import com.shinsaikou.sync.SyncConflict
import com.shinsaikou.sync.format.RowKind
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * The conflict report (phase 3 unit E2, the plan's conflict report bullet): the rows of a run where
 * a value this device had changed since its last pull, as the log after its last push names them,
 * was replaced by a different remote value. Every other row the run wrote is a routine merge, and
 * the run carries the library's counts before and after, for G to show.
 */
class SyncConflictReportTest {

    private val backend = InMemorySyncBackend()
    private val a = SyncTestDevice(backend)
    private val b = SyncTestDevice(backend)

    @AfterEach
    fun close() {
        a.close()
        b.close()
    }

    /** Sets a clock under the latch, so the value's own trigger does not move it again. */
    private fun SyncTestDevice.setClock(table: String, id: Long, column: String, value: Long) {
        exec("UPDATE $table SET is_syncing = 1 WHERE _id = $id")
        exec("UPDATE $table SET $column = $value WHERE _id = $id")
        exec("UPDATE $table SET is_syncing = 0 WHERE _id = $id")
    }

    @Test
    fun `a remote value replacing one this device changed is a conflict, and every other write is routine`() {
        a.buildLibrary()
        a.sync()
        b.sync()
        a.sync()
        val bManga = b.mangaId(SyncTestDevice.SOURCE, "/series/1")!!
        val bChapter11 = b.chapterId(bManga, "/series/1/chapter/11")!!

        // B edits the notes and the reading mode; A edits both later for the notes and earlier for
        // the reading mode, and reads a chapter B never touched.
        b.exec("UPDATE mangas SET notes = 'B notes', viewer = 5 WHERE _id = $bManga")
        b.setClock("mangas", bManga, "notes_modified_at", 1_000)
        b.setClock("mangas", bManga, "display_modified_at", 9_000)
        a.exec("UPDATE mangas SET notes = 'A notes', viewer = 7 WHERE _id = 1")
        a.setClock("mangas", 1, "notes_modified_at", 5_000)
        a.setClock("mangas", 1, "display_modified_at", 2_000)
        a.exec("UPDATE chapters SET read = 1 WHERE _id = 11")
        a.sync()

        val result = b.sync().shouldNotBeNull()

        result.conflicts shouldContainExactly listOf(
            // Named by the title the screens show: buildLibrary's entry carries an edited one.
            SyncConflict(kind = RowKind.MANGA, title = "Edited title", chapter = null, fields = setOf("notes")),
        )
        b.string("SELECT notes FROM mangas WHERE _id = $bManga") shouldBe "A notes"
        b.long("SELECT viewer FROM mangas WHERE _id = $bManga") shouldBe 5
        b.long("SELECT read FROM chapters WHERE _id = $bChapter11") shouldBe 1
        result.rowsWritten shouldBe 2
        result.routineMerges shouldBe 1
        result.before.readChapters + 1 shouldBe result.after.readChapters
        result.after shouldBe SyncLibraryCounts(libraryEntries = 1, categories = 2, readChapters = 2, bookmarkedChapters = 1)
    }

    @Test
    fun `a row this device did not change is never a conflict, however much the merge takes`() {
        a.buildLibrary()
        a.sync()
        b.sync()
        a.sync()
        a.exec("UPDATE mangas SET notes = 'A notes' WHERE _id = 1")
        a.exec("UPDATE chapters SET last_page_read = 12 WHERE _id = 11")
        a.sync()

        val result = b.sync().shouldNotBeNull()

        result.rowsWritten shouldBe 2
        result.conflicts shouldBe emptyList()
        result.routineMerges shouldBe 2
    }
}

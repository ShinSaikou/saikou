package com.shinsaikou.data.repository

import com.shinsaikou.data.chapter.BookmarkNoteRepositoryImpl
import com.shinsaikou.data.sync.SyncChangeLogRepositoryImpl
import com.shinsaikou.domain.sync.model.SyncChangeType
import com.shinsaikou.domain.sync.model.SyncDeletedKey
import com.shinsaikou.domain.sync.model.SyncEntity
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The table is written by the triggers in the other `.sq` files (and by a library add's statement,
 * whose tests are [MangaRepositoryLibraryAddLogTest]'s), so every case here drives a real write and
 * reads the log back through the repository.
 */
class SyncChangeLogRepositoryImplTest : RepositoryTest() {

    private val repository by lazy { SyncChangeLogRepositoryImpl(handler) }
    private val notes by lazy { BookmarkNoteRepositoryImpl(handler) }

    @BeforeEach
    fun seed() {
        insertManga(id = 1)
        insertChapter(id = 10, mangaId = 1)
        exec("DELETE FROM sync_change_log")
    }

    @Test
    fun `getLatestSeq is zero on an empty log`() = runTest {
        repository.getLatestSeq() shouldBe 0
    }

    @Test
    fun `getChangesAfter returns the rows a write logged, in seq order`() = runTest {
        exec("UPDATE mangas SET title = 'Renamed' WHERE _id = 1")
        exec("UPDATE chapters SET read = 1 WHERE _id = 10")

        val changes = repository.getChangesAfter(afterSeq = 0, limit = 100)

        changes.map { it.entity to it.entityId } shouldContainExactly listOf(
            SyncEntity.MANGA to 1L,
            SyncEntity.CHAPTER to 10L,
        )
        changes.map { it.changeType } shouldContainExactly
            listOf(SyncChangeType.UPSERT, SyncChangeType.UPSERT)
        changes.map { it.seq } shouldBe changes.map { it.seq }.sorted()
        repository.getLatestSeq() shouldBe changes.last().seq
    }

    @Test
    fun `getChangesAfter resumes from a cursor`() = runTest {
        exec("UPDATE mangas SET title = 'Renamed' WHERE _id = 1")
        val first = repository.getChangesAfter(afterSeq = 0, limit = 100).single()

        exec("UPDATE chapters SET read = 1 WHERE _id = 10")

        repository.getChangesAfter(afterSeq = first.seq, limit = 100)
            .map { it.entity } shouldContainExactly listOf(SyncEntity.CHAPTER)
    }

    @Test
    fun `getChangesAfter honours a limit below the cap`() = runTest {
        exec("UPDATE mangas SET title = 'Renamed' WHERE _id = 1")
        exec("UPDATE chapters SET read = 1 WHERE _id = 10")

        repository.getChangesAfter(afterSeq = 0, limit = 1).size shouldBe 1
    }

    @Test
    fun `getChangesAfter clamps a larger limit to five thousand rows`() = runTest {
        fillLog(rows = 6_000)

        repository.getChangesAfter(afterSeq = 0, limit = Long.MAX_VALUE).size shouldBe 5_000
    }

    @Test
    fun `getChangedIds is distinct and scoped to one entity`() = runTest {
        exec("UPDATE mangas SET title = 'A' WHERE _id = 1")
        exec("UPDATE mangas SET title = 'B' WHERE _id = 1")
        exec("UPDATE chapters SET read = 1 WHERE _id = 10")

        repository.getChangedIds(SyncEntity.MANGA, afterSeq = 0) shouldContainExactly listOf(1L)
        repository.getChangedIds(SyncEntity.CHAPTER, afterSeq = 0) shouldContainExactly listOf(10L)
        repository.getChangedIds(SyncEntity.HISTORY, afterSeq = 0) shouldBe emptyList()
    }

    @Test
    fun `prune drops rows up to and including the given seq`() = runTest {
        exec("UPDATE mangas SET title = 'A' WHERE _id = 1")
        exec("UPDATE chapters SET read = 1 WHERE _id = 10")
        val all = repository.getChangesAfter(afterSeq = 0, limit = 100)

        repository.prune(all.first().seq)

        repository.getChangesAfter(afterSeq = 0, limit = 100)
            .map { it.seq } shouldContainExactly listOf(all.last().seq)
    }

    @Test
    fun `a hard delete logs a delete with its key, so the fold by highest seq drops the row`() = runTest {
        insertChapter(id = 11, mangaId = 1)
        exec("DELETE FROM chapters WHERE _id = 11")

        val changes = repository.getChangesAfter(afterSeq = 0, limit = 100)
        val folded = changes
            .groupBy { it.entity to it.entityId }
            .mapValues { (_, grouped) -> grouped.maxBy { it.seq }.changeType }

        folded shouldBe mapOf((SyncEntity.CHAPTER to 11L) to SyncChangeType.DELETE)
        changes.last().deletedKey shouldBe SyncDeletedKey.Chapter(
            source = 1,
            mangaUrl = "https://example.invalid/manga/1",
            url = "https://example.invalid/ch/11",
        )
        changes.first().deletedKey shouldBe null
    }

    /**
     * Since schema version 2 a chapter's notes travel as one value keyed by the chapter (phase 3
     * Q5), so removing a note is an upsert of that value, never a delete of the note's own id.
     */
    @Test
    fun `removing a bookmark note logs an upsert of its chapter`() = runTest {
        notes.upsert(chapterId = 10, pageIndex = 1, note = "n", now = 100)
        notes.remove(chapterId = 10, pageIndex = 1)

        repository.getChangesAfter(afterSeq = 0, limit = 100)
            .map { Triple(it.entity, it.entityId, it.changeType) } shouldContainExactly listOf(
            Triple(SyncEntity.BOOKMARK_NOTE, 10L, SyncChangeType.UPSERT),
            Triple(SyncEntity.BOOKMARK_NOTE, 10L, SyncChangeType.UPSERT),
        )
    }

    /**
     * Carry-over 7 from the `:data` schema unit, on this unit's own table. `bookmark_notes`
     * declares `UNIQUE (chapter_id, page_index) ON CONFLICT REPLACE`, and SQLite fires delete
     * triggers for REPLACE-removed rows only under `recursive_triggers`, which is off. A plain
     * INSERT that collides therefore logs one upsert and no delete.
     *
     * At schema version 1 that upsert named the new note's `_id` and left the old id in the log as
     * an upsert no row answered. Since version 2 every note write logs its chapter (phase 3 Q5), so
     * both writes name chapter 10 and nothing in the log points at a row that is gone.
     */
    @Test
    fun `a REPLACE collision logs an upsert of the chapter for each write`() = runTest {
        insertNote(chapterId = 10, pageIndex = 1, note = "before")
        val oldId = scalar("SELECT _id FROM bookmark_notes WHERE chapter_id = 10")!!.toLong()

        insertNote(chapterId = 10, pageIndex = 1, note = "after")
        val newId = scalar("SELECT _id FROM bookmark_notes WHERE chapter_id = 10")!!.toLong()

        newId shouldBe oldId + 1
        repository.getChangesAfter(afterSeq = 0, limit = 100)
            .map { it.entityId to it.changeType } shouldContainExactly listOf(
            10L to SyncChangeType.UPSERT,
            10L to SyncChangeType.UPSERT,
        )
    }

    private fun insertNote(chapterId: Long, pageIndex: Long, note: String) = exec(
        """
        INSERT INTO bookmark_notes(chapter_id, page_index, note, date_added)
        VALUES ($chapterId, $pageIndex, '$note', 0)
        """,
    )

    /** Six thousand trigger writes would be slow; the cap is about the read, so seed it directly. */
    private fun fillLog(rows: Int) = exec(
        """
        WITH RECURSIVE counter(n) AS (
            SELECT 1 UNION ALL SELECT n + 1 FROM counter WHERE n < $rows
        )
        INSERT INTO sync_change_log(entity_type, entity_id, change_type, changed_at)
        SELECT 'manga', n, 'upsert', 0 FROM counter
        """,
    )
}

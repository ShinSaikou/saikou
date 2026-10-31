package com.shinsaikou.sync.engine

import com.shinsaikou.domain.category.CategorySyncKey
import com.shinsaikou.sync.InMemorySyncBackend
import com.shinsaikou.sync.format.BookmarkNotesRow
import com.shinsaikou.sync.format.CategoryRow
import com.shinsaikou.sync.format.ChapterKey
import com.shinsaikou.sync.format.ChapterRemovedRow
import com.shinsaikou.sync.format.ChapterRow
import com.shinsaikou.sync.format.HistoryRow
import com.shinsaikou.sync.format.MangaCategoriesRow
import com.shinsaikou.sync.format.MangaKey
import com.shinsaikou.sync.format.MangaRow
import com.shinsaikou.sync.format.SegmentReader
import com.shinsaikou.sync.format.SyncHeader
import com.shinsaikou.sync.format.SyncLayout
import com.shinsaikou.sync.format.SyncRow
import com.shinsaikou.sync.format.SyncScope
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * The push (phase 3 unit E2, the plan's first bullet): the log after `lastPushedSeq` in batches of
 * at most 5,000 raw rows, each folded by (entity, id) and written as one self-contained segment
 * named by its range, and `lastPushedSeq` and the log moved only after the read-back.
 */
class SyncEnginePushTest {

    private val backend = InMemorySyncBackend()
    private val device = SyncTestDevice(backend, exported = true)

    @AfterEach
    fun close() {
        device.close()
    }

    private val manga1 = MangaKey(SyncTestDevice.SOURCE, "/series/1")

    private fun segment(afterSeq: Long, lastSeq: Long): Pair<SyncHeader, List<SyncRow>> {
        val bytes = backend.bytes(SyncLayout.segmentPath(device.deviceId, afterSeq, lastSeq)).shouldNotBeNull()
        return SegmentReader(ByteArrayInputStream(bytes)).use { reader ->
            reader.header to generateSequence { reader.next() }.toList()
        }
    }

    private fun pushedPaths(): List<String> = runBlocking { backend.list(SyncLayout.DEVICES).map { it.path } }

    /** Runs one sync that pushes one segment, and reads that segment's rows back. */
    private fun pushOne(): List<SyncRow> {
        val afterSeq = device.preferences.lastPushedSeq.get()
        device.sync().shouldNotBeNull().segmentsPushed shouldBe 1
        return segment(afterSeq, device.preferences.lastPushedSeq.get()).second
    }

    @Test
    fun `a push writes one segment named by its range, then moves lastPushedSeq and prunes the log`() {
        device.addFavourite(1)
        device.addChapter(10, mangaId = 1)
        device.addChapter(11, mangaId = 1)
        device.latestSeq() shouldBe 3
        device.deviceId shouldBe ""

        device.sync().shouldNotBeNull().segmentsPushed shouldBe 1

        device.deviceId shouldMatch Regex("[0-9a-f-]{36}")
        pushedPaths() shouldContainExactly listOf(SyncLayout.segmentPath(device.deviceId, 0, 3))
        val (header, rows) = segment(0, 3)
        header.deviceId shouldBe device.deviceId
        header.scope shouldBe SyncScope.Segment(afterSeq = 0, lastSeq = 3, rawRows = 3)
        rows.map { it::class } shouldContainExactly listOf(MangaRow::class, ChapterRow::class, ChapterRow::class)
        (rows[0] as MangaRow).key shouldBe manga1
        rows.drop(1).map { (it as ChapterRow).key.url } shouldContainExactly
            listOf("/series/1/chapter/10", "/series/1/chapter/11")
        device.preferences.lastPushedSeq.get() shouldBe 3
        device.logCount() shouldBe 0
    }

    @Test
    fun `a batch folds every log row of one entity into one line, its highest seq`() {
        device.addFavourite(1)
        device.addChapter(10, mangaId = 1)
        device.exec("UPDATE chapters SET last_page_read = 3 WHERE _id = 10")
        device.exec("UPDATE chapters SET last_page_read = 9 WHERE _id = 10")
        device.exec("UPDATE chapters SET read = 1 WHERE _id = 10")
        // Chapter 11's last log row is its keyed delete, so it folds to a removal, not a row read.
        device.addChapter(11, mangaId = 1)
        device.exec("UPDATE chapters SET bookmark = 1 WHERE _id = 11")
        device.exec("DELETE FROM chapters WHERE _id = 11")
        val raw = device.logCount()

        device.sync()

        val (header, rows) = segment(0, raw)
        (header.scope as SyncScope.Segment).rawRows shouldBe raw.toInt()
        rows.map { it::class } shouldContainExactly
            listOf(MangaRow::class, ChapterRow::class, ChapterRemovedRow::class)
        val chapter = rows[1] as ChapterRow
        chapter.lastPageRead shouldBe 9
        chapter.read shouldBe true
        (rows[2] as ChapterRemovedRow).key.url shouldBe "/series/1/chapter/11"
    }

    @Test
    fun `a backlog over 5,000 rows is pushed as contiguous segments of at most 5,000 raw rows each`() {
        device.addFavourite(1)
        device.exec(
            """
            WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 5001)
            INSERT INTO chapters(manga_id, url, name, read, bookmark, last_page_read, chapter_number,
                source_order, date_fetch, date_upload, memo)
            SELECT 1, '/series/1/chapter/' || i, 'Chapter ' || i, 0, 0, 0, i, i, 0, 0, CAST('{}' AS BLOB) FROM n
            """,
        )
        device.latestSeq() shouldBe 5002

        device.sync().shouldNotBeNull().segmentsPushed shouldBe 2

        val (first, firstRows) = segment(0, 5000)
        first.scope shouldBe SyncScope.Segment(0, 5000, rawRows = 5000)
        firstRows.size shouldBe 5000
        val (second, secondRows) = segment(5000, 5002)
        second.scope shouldBe SyncScope.Segment(5000, 5002, rawRows = 2)
        // The second segment carries its chapters' manga again, since each segment stands alone.
        secondRows.map { it::class } shouldContainExactly listOf(MangaRow::class, ChapterRow::class, ChapterRow::class)
        device.preferences.lastPushedSeq.get() shouldBe 5002
        device.logCount() shouldBe 0
    }

    @Test
    fun `a delete whose key was not recorded is skipped, and the segment is written with no row lines`() {
        // A version 1 delete row: no key, and the row it named is gone.
        device.exec(
            "INSERT INTO sync_change_log(entity_type, entity_id, change_type, changed_at) VALUES ('manga', 99, 'delete', 5)",
        )

        device.sync().shouldNotBeNull().segmentsPushed shouldBe 1

        val (header, rows) = segment(0, 1)
        header.scope shouldBe SyncScope.Segment(0, 1, rawRows = 1)
        header.counts shouldBe emptyMap()
        rows.shouldBeEmpty()
        device.preferences.lastPushedSeq.get() shouldBe 1
        device.logCount() shouldBe 0
    }

    @Test
    fun `a failed read-back leaves lastPushedSeq and the log untouched, and the next run pushes the same range`() {
        device.addFavourite(1)
        device.addChapter(10, mangaId = 1)
        backend.dropWrites = true

        shouldThrow<IOException> { device.sync() }

        device.preferences.lastPushedSeq.get() shouldBe 0
        device.logCount() shouldBe 2
        pushedPaths().shouldBeEmpty()

        backend.dropWrites = false
        device.sync()

        pushedPaths() shouldContainExactly listOf(SyncLayout.segmentPath(device.deviceId, 0, 2))
        device.preferences.lastPushedSeq.get() shouldBe 2
        device.logCount() shouldBe 0
    }

    @Test
    fun `a hard-deleted chapter travels as chapter_removed at the time its log row recorded`() {
        device.addFavourite(1)
        device.addChapter(10, mangaId = 1)
        device.sync()
        device.exec("DELETE FROM chapters WHERE _id = 10")
        val removedAt = device.long("SELECT changed_at FROM sync_change_log WHERE entity_type = 'chapter'")!!

        val rows = pushOne()
        rows.map { it::class } shouldContainExactly listOf(MangaRow::class, ChapterRemovedRow::class)
        rows[1] shouldBe ChapterRemovedRow(ChapterKey(manga1, "/series/1/chapter/10"), removedAt)
    }

    @Test
    fun `a hard-deleted manga has no line, so its keyed delete is skipped`() {
        device.addFavourite(1)
        device.exec("DELETE FROM mangas WHERE _id = 1")

        pushOne().shouldBeEmpty()
    }

    @Test
    fun `a soft-deleted chapter is read and travels as its row with its tombstone`() {
        device.addFavourite(1)
        device.addChapter(10, mangaId = 1)
        device.sync()
        device.exec("UPDATE chapters SET deleted_at = 5000 WHERE _id = 10")

        val chapter = pushOne().filterIsInstance<ChapterRow>().single()
        chapter.deletedAt.shouldNotBeNull()
    }

    @Test
    fun `categories come first, each after its parent, then the manga, then its category set`() {
        device.addFavourite(1)
        device.addCategory(1, "Parent")
        device.addCategory(2, "Child", parentId = 1)
        device.sync()
        device.exec("INSERT INTO mangas_categories(manga_id, category_id) VALUES (1, 2)")

        val rows = pushOne()
        rows.map { it::class } shouldContainExactly
            listOf(CategoryRow::class, CategoryRow::class, MangaRow::class, MangaCategoriesRow::class)
        (rows[0] as CategoryRow).key shouldBe CategorySyncKey.of("Parent")
        (rows[1] as CategoryRow).parent shouldBe CategorySyncKey.of("Parent")
        (rows[3] as MangaCategoriesRow).categories shouldBe setOf(CategorySyncKey.of("Child"))
    }

    @Test
    fun `the system category never travels, as a row or in a category set`() {
        device.addFavourite(1)
        device.addCategory(1, "Reading")
        device.exec("INSERT INTO mangas_categories(manga_id, category_id) VALUES (1, 0)")
        device.exec("INSERT INTO mangas_categories(manga_id, category_id) VALUES (1, 1)")
        device.exec(
            "INSERT INTO sync_change_log(entity_type, entity_id, change_type, changed_at) VALUES ('category', 0, 'upsert', 5)",
        )

        val rows = pushOne()
        rows.filterIsInstance<CategoryRow>().map { it.key } shouldContainExactly listOf(CategorySyncKey.of("Reading"))
        rows.filterIsInstance<MangaCategoriesRow>().single().categories shouldBe setOf(CategorySyncKey.of("Reading"))
    }

    @Test
    fun `a chapter's line comes before its history and its notes`() {
        device.addFavourite(1)
        device.addChapter(10, mangaId = 1)
        device.sync()
        device.exec("INSERT INTO history(chapter_id, last_read, time_read) VALUES (10, 7000, 60)")
        device.exec("INSERT INTO bookmark_notes(chapter_id, page_index, note, date_added) VALUES (10, 3, 'note', 8000)")

        val rows = pushOne()
        rows.map { it::class } shouldContainExactly
            listOf(MangaRow::class, ChapterRow::class, HistoryRow::class, BookmarkNotesRow::class)
        rows[2].shouldBeInstanceOf<HistoryRow>().timeRead shouldBe 60
        rows[3].shouldBeInstanceOf<BookmarkNotesRow>().notes.single().note shouldBe "note"
    }
}

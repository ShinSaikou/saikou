package com.shinsaikou.sync.engine

import com.shinsaikou.sync.InMemorySyncBackend
import com.shinsaikou.sync.format.ChapterRow
import com.shinsaikou.sync.format.MangaCategoriesRow
import com.shinsaikou.sync.format.MangaKey
import com.shinsaikou.sync.format.MangaRow
import com.shinsaikou.sync.format.SegmentReader
import com.shinsaikou.sync.format.SyncHeader
import com.shinsaikou.sync.format.SyncLayout
import com.shinsaikou.sync.format.SyncRow
import com.shinsaikou.sync.format.SyncScope
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.manga.model.MangaUpdate
import java.io.ByteArrayInputStream

/**
 * The push's whole-entry cut (phase 4 unit NB, challenge c5 F1 and change 8 (a) and (b)): a full
 * batch ends before the rows of the entries its boundary would split, so a library add, whose rows
 * the repository writes as one transaction, reaches another device in one file. Every add goes
 * through `MangaRepositoryImpl`, as the app's does, so the log holds the trigger's rows and the
 * repository's statement's in their real order; each test checks the rows at the boundary before
 * it pushes. The existing 5,002-row split of one entry is `SyncEnginePushTest`'s.
 */
class SyncPushWholeEntryTest {

    private val backend = InMemorySyncBackend()
    private val device = SyncTestDevice(backend, exported = true)
    private val mangas = MangaRepositoryImpl(device.handler)

    @AfterEach
    fun close() {
        device.close()
    }

    private fun segment(afterSeq: Long, lastSeq: Long): Pair<SyncHeader, List<SyncRow>> {
        val bytes = backend.bytes(SyncLayout.segmentPath(device.deviceId, afterSeq, lastSeq)).shouldNotBeNull()
        return SegmentReader(ByteArrayInputStream(bytes)).use { reader ->
            reader.header to generateSequence { reader.next() }.toList()
        }
    }

    private fun key(mangaId: Long) = MangaKey(SyncTestDevice.SOURCE, "/series/$mangaId")

    /** The log row at [seq] as `entity id`. */
    private fun logRow(seq: Long): String? =
        device.string("SELECT entity_type || ' ' || entity_id FROM sync_change_log WHERE seq = $seq")

    /** Manga [id] outside the library with [count] chapters, the first [read] of them read: nothing is logged. */
    private fun browsed(id: Long, count: Int, read: Int) {
        device.exec(
            """
            INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer, chapter_flags,
                cover_last_modified, date_added, memo)
            VALUES ($id, ${SyncTestDevice.SOURCE}, '/series/$id', 'Title $id', 0, 0, 1, 0, 0, 0, 0, CAST('{}' AS BLOB))
            """,
        )
        chapters(id, count)
        device.exec("UPDATE chapters SET read = 1 WHERE manga_id = $id AND source_order <= $read")
    }

    /** [count] chapters of [mangaId], numbered from 1, in one statement: one log row each while it is a favourite. */
    private fun chapters(mangaId: Long, count: Int) = device.exec(
        """
        WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < $count)
        INSERT INTO chapters(manga_id, url, name, read, bookmark, last_page_read, chapter_number,
            source_order, date_fetch, date_upload, memo)
        SELECT $mangaId, '/series/$mangaId/chapter/' || i, 'Chapter ' || i, 0, 0, 0, i, i, 0, 0, CAST('{}' AS BLOB) FROM n
        """,
    )

    /** An entry in the library with [chapters] chapters: [chapters] + 1 log rows. */
    private fun filler(id: Long, chapters: Int) {
        device.addFavourite(id)
        chapters(id, chapters)
    }

    private fun chapterKeysOf(rows: List<SyncRow>, mangaId: Long): List<String> =
        rows.filterIsInstance<ChapterRow>().filter { it.key.manga == key(mangaId) }.map { it.key.url }

    private fun mangaKeysOf(rows: List<SyncRow>): List<MangaKey> = rows.filterIsInstance<MangaRow>().map { it.key }

    @Test
    fun `a full batch whose last row falls inside a library add ends before the add, and the next segment carries the entry and every chapter`() {
        browsed(2, count = 20, read = 2)
        filler(1, chapters = 4_989)
        device.latestSeq() shouldBe 4_990

        runBlocking { mangas.update(MangaUpdate(id = 2, favorite = true)) } shouldBe true

        // The add's rows: the two read chapters (the trigger), the entry, then the other 18 (the
        // statement); the 5,000th row of the log is one of the 18.
        logRow(4_991) shouldBe "chapter ${device.chapterId(2, "/series/2/chapter/1")}"
        logRow(4_993) shouldBe "manga 2"
        logRow(5_000)!!.substringBefore(' ') shouldBe "chapter"
        device.latestSeq() shouldBe 5_011

        device.sync().shouldNotBeNull().segmentsPushed shouldBe 2

        val (first, firstRows) = segment(0, 4_990)
        val (second, secondRows) = segment(4_990, 5_011)
        assertSoftly {
            first.scope shouldBe SyncScope.Segment(0, 4_990, rawRows = 4_990)
            mangaKeysOf(firstRows) shouldContainExactly listOf(key(1))
            chapterKeysOf(firstRows, 2).shouldBeEmpty()
            second.scope shouldBe SyncScope.Segment(4_990, 5_011, rawRows = 21)
            mangaKeysOf(secondRows) shouldContainExactly listOf(key(2))
            chapterKeysOf(secondRows, 2) shouldContainExactlyInAnyOrder (1..20).map { "/series/2/chapter/$it" }
        }
    }

    @Test
    fun `a boundary between a migration's category row for the target and the old entry's row ends the batch before the migration`() {
        migrationAcrossBoundary(fillerChapters = 4_998, lastInBatch = "manga_category 4", firstAfter = "manga 3")
    }

    @Test
    fun `a boundary between the old entry's row and the target's first chapter row ends the batch before the migration`() {
        migrationAcrossBoundary(fillerChapters = 4_997, lastInBatch = "manga 3", firstAfter = "chapter ")
    }

    /**
     * Entry 3 migrates onto entry 4 with MigrateManga's two writes, in its order: the target's
     * categories, then the favourite pair through one `updateAll`. Before them, entry 1 with
     * [fillerChapters] chapters fills the log so the batch's last row is [lastInBatch] and the row
     * after it starts with [firstAfter].
     */
    private fun migrationAcrossBoundary(fillerChapters: Int, lastInBatch: String, firstAfter: String) {
        device.addCategory(1, "Reading")
        device.addFavourite(3)
        chapters(3, 3)
        browsed(4, count = 20, read = 2)
        device.sync().shouldNotBeNull().segmentsPushed shouldBe 1
        val start = device.preferences.lastPushedSeq.get()

        filler(1, chapters = fillerChapters)
        runBlocking {
            mangas.setMangaCategories(4, listOf(1))
            mangas.updateAll(listOf(MangaUpdate(id = 3, favorite = false, dateAdded = 0), MangaUpdate(id = 4, favorite = true)))
                .shouldBe(true)
        }
        val boundary = start + 5_000
        logRow(boundary) shouldBe lastInBatch
        logRow(boundary + 1)!!.startsWith(firstAfter) shouldBe true
        val end = device.latestSeq()
        // The migration's rows: the category row, the old entry, the target's two read chapters,
        // the target, its other 18 chapters.
        val migrationStart = start + fillerChapters + 2
        logRow(migrationStart) shouldBe "manga_category 4"
        end shouldBe migrationStart + 22

        device.sync().shouldNotBeNull().segmentsPushed shouldBe 2

        val (first, firstRows) = segment(start, migrationStart - 1)
        val (second, secondRows) = segment(migrationStart - 1, end)
        assertSoftly {
            first.scope shouldBe SyncScope.Segment(start, migrationStart - 1, rawRows = fillerChapters + 1)
            mangaKeysOf(firstRows) shouldContainExactly listOf(key(1))
            second.scope shouldBe SyncScope.Segment(migrationStart - 1, end, rawRows = 23)
            mangaKeysOf(secondRows) shouldContainExactly listOf(key(3), key(4))
            secondRows.filterIsInstance<MangaRow>().map { it.favorite } shouldContainExactly listOf(false, true)
            secondRows.filterIsInstance<MangaCategoriesRow>().map { it.manga } shouldContainExactly listOf(key(4))
            chapterKeysOf(secondRows, 3).shouldBeEmpty()
            chapterKeysOf(secondRows, 4) shouldContainExactlyInAnyOrder (1..20).map { "/series/4/chapter/$it" }
        }
    }
}

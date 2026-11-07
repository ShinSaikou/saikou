package com.shinsaikou.data.repository

import com.shinsaikou.data.merged.MergedGroupRepositoryImpl
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.TimeSource

/**
 * `reconcileRead` (merged_groups.sq): raises only, stays inside the group's live chapters with a
 * recognised number, and is one set-based statement whose cost grows with the group, not its square
 * (`design/plans/merged-entries.md` section 10 asks for it to be run on a 5,000-chapter group).
 */
class MergedReadReconcileTest : RepositoryTest() {

    private val repository by lazy { MergedGroupRepositoryImpl(handler) }

    private fun group(): Long = scalar("SELECT _id FROM merged_groups")!!.toLong()

    private fun chapter(id: Long, mangaId: Long, number: Double, read: Int = 0, deletedAt: Long? = null) = exec(
        """
        INSERT INTO chapters(_id, manga_id, url, name, read, bookmark, last_page_read, chapter_number,
            source_order, date_fetch, date_upload, memo, deleted_at)
        VALUES ($id, $mangaId, '/c/$id', 'Chapter $id', $read, 0, 3, $number, $id, 0, 0, CAST('{}' AS BLOB),
            ${deletedAt ?: "NULL"})
        """,
    )

    private fun read(id: Long): String? = scalar("SELECT read FROM chapters WHERE _id = $id")

    @Test
    fun `a read copy raises the others, and nothing else moves`() = runTest {
        insertManga(id = 1)
        insertManga(id = 2)
        insertManga(id = 3)
        repository.merge(mainId = 1, mangaIds = listOf(1, 2))
        chapter(id = 10, mangaId = 1, number = 1.0, read = 1)
        chapter(id = 11, mangaId = 2, number = 1.0)
        chapter(id = 12, mangaId = 2, number = 2.0)
        chapter(id = 13, mangaId = 1, number = 2.0)
        chapter(id = 14, mangaId = 1, number = -1.0, read = 1)
        chapter(id = 15, mangaId = 2, number = -1.0)
        chapter(id = 16, mangaId = 2, number = 3.0, read = 1, deletedAt = 1_000)
        chapter(id = 17, mangaId = 1, number = 3.0)
        chapter(id = 18, mangaId = 3, number = 1.0)

        repository.reconcileRead(group())

        read(11) shouldBe "1"
        read(12) shouldBe "0"
        read(13) shouldBe "0"
        // A chapter with no recognised number has no copies.
        read(15) shouldBe "0"
        // A deleted copy raises nothing, and an entry outside the group is left alone.
        read(17) shouldBe "0"
        read(18) shouldBe "0"
        // Page progress stays per copy.
        scalar("SELECT last_page_read FROM chapters WHERE _id = 11") shouldBe "3"
    }

    @Test
    fun `it never lowers`() = runTest {
        insertManga(id = 1)
        insertManga(id = 2)
        repository.merge(mainId = 1, mangaIds = listOf(1, 2))
        chapter(id = 10, mangaId = 1, number = 1.0, read = 1)
        chapter(id = 11, mangaId = 2, number = 1.0, read = 1)

        repository.reconcileRead(group())

        read(10) shouldBe "1"
        read(11) shouldBe "1"
    }

    /**
     * Twice the chapters must take about twice the time. Each size is timed on a fresh database's
     * worth of unread copies, the fastest of three runs, once the JVM has warmed up; a quadratic
     * statement would take four times as long at each step.
     */
    @Test
    fun `a 5,000-chapter group reconciles in time linear in its chapters`() = runTest {
        insertManga(id = 1)
        insertManga(id = 2)
        repository.merge(mainId = 1, mangaIds = listOf(1, 2))
        fill(mangaId = 1, chapters = 10_000, read = 1)
        fill(mangaId = 2, chapters = 10_000, read = 0)
        // Warmed up on the whole table, which every timed size below then reads a part of.
        reconcile(10_000)

        val half = fastest(2_500)
        val full = fastest(5_000)
        val double = fastest(10_000)

        println("Merged read reconcile, ms: 2,500 copies $half, 5,000 copies $full, 10,000 copies $double")
        (full / half) shouldBeLessThan 3.2
        (double / full) shouldBeLessThan 3.2
    }

    private fun fill(mangaId: Long, chapters: Int, read: Int) = exec(
        """
        WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < $chapters)
        INSERT INTO chapters(manga_id, url, name, read, bookmark, last_page_read, chapter_number,
            source_order, date_fetch, date_upload, memo)
        SELECT $mangaId, '/c/$mangaId/' || i, 'Chapter ' || i, $read, 0, 0, i, i, 0, 0, CAST('{}' AS BLOB)
        FROM n
        """,
    )

    private suspend fun fastest(copies: Int): Double = (1..3).minOf { reconcile(copies) }

    /** Lowers [copies] of member 2 and the rest of member 1 out of reach, then times one reconcile. */
    private suspend fun reconcile(copies: Int): Double {
        exec("UPDATE chapters SET read = 0 WHERE manga_id = 2 AND chapter_number <= $copies")
        exec("UPDATE chapters SET read = 1 WHERE manga_id = 2 AND chapter_number > $copies")
        exec("DELETE FROM sync_change_log")
        val mark = TimeSource.Monotonic.markNow()
        repository.reconcileRead(group())
        val elapsed = mark.elapsedNow().inWholeMicroseconds / 1_000.0
        scalar("SELECT count(*) FROM chapters WHERE manga_id = 2 AND read = 0") shouldBe "0"
        return elapsed
    }
}

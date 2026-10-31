package com.shinsaikou.data.repository

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import java.util.Date
import kotlin.random.Random

/**
 * `library_summary` (schema version 3, phase 5 unit SC4) against the aggregate `libraryView`
 * computed before it, over the real schema and triggers, with every write going through the
 * statements the app runs. [OLD_AGGREGATE] is version 2's subquery, verbatim, joined to every manga
 * row; [SUMMARY] reads the table the same way, so a manga with no counted chapter reads 0 in both.
 */
class LibrarySummaryTest : RepositoryTest() {

    private val chapters get() = database.chaptersQueries
    private val history get() = database.historyQueries
    private val exclusions get() = database.excluded_scanlatorsQueries

    // The property: a random sequence of writes, the summary equal to the aggregate after each -----

    @Test
    fun `after every step of a random sequence of writes the summary equals the old aggregate`() = runTest {
        SEEDS.forEach { seed ->
            // A fresh library per seed: the manga deletes cascade to everything the sequence wrote.
            exec("DELETE FROM mangas")
            val random = Random(seed)
            MANGAS.forEach { insertManga(it, favorite = it != MANGAS.last()) }
            repeat(STEPS) { step ->
                val write = randomWrite(random)
                withClue("seed $seed, step $step: $write") {
                    summary() shouldContainExactly aggregate()
                }
            }
        }
    }

    /** One write of the kinds the app makes, chosen and applied; its description for the clue. */
    private suspend fun randomWrite(random: Random): String {
        val ids = chapterIds()
        val chapter = ids.randomOrNull(random)
        val manga = MANGAS.random(random)
        return when (random.nextInt(24)) {
            0, 1, 2 -> insertChapter(random, manga, latched = false)
            3 -> insertChapter(random, manga, latched = true).also { chapters.resetIsSyncingByMangaId(manga) }
            4 -> syncInsertChapter(random, manga)
            5 -> chapter?.let { update(it, read = random.nextBoolean()); "read $it" } ?: "nothing"
            6 -> chapter?.let { update(it, bookmark = random.nextBoolean()); "bookmark $it" } ?: "nothing"
            7 -> chapter?.let { update(it, dateUpload = date(random), dateFetch = date(random)); "dates $it" } ?: "nothing"
            8 -> chapter?.let { chapters.softDelete(random.nextLong(1, 10_000), listOf(it)); "soft delete $it" } ?: "nothing"
            9 -> chapter?.let { update(it, clearDeletedAt = 1); "restore $it" } ?: "nothing"
            10 -> chapter?.let { chapters.removeChaptersWithIds(listOf(it)); "hard delete $it" } ?: "nothing"
            11 -> chapter?.let { update(it, mangaId = manga); "move $it to $manga" } ?: "nothing"
            12 -> chapter?.let { update(it, scanlator = SCANLATORS.filterNotNull().random(random)); "scanlator $it" } ?: "nothing"
            13 -> SCANLATORS.filterNotNull().random(random).let { exclusions.insert(manga, it); "exclude $it on $manga" }
            14 -> SCANLATORS.filterNotNull().random(random).let { exclusions.remove(manga, listOf(it)); "include $it on $manga" }
            15 -> chapter?.let {
                history.upsert(it, Date(random.nextLong(1, 100_000)), 1)
                "history $it"
            } ?: "nothing"
            16 -> historyIds().randomOrNull(random)?.let { history.resetHistoryById(it); "reset history $it" } ?: "nothing"
            17 -> chapter?.let { syncUpdate(random, it); "sync update $it" } ?: "nothing"
            18 -> {
                history.removeResettedHistory()
                "remove reset history"
            }
            // The bulk writes the fix round's deltas serve: one statement over many chapters or
            // history rows of one entry, each row moving the counts by its own change.
            20 -> {
                val read = random.nextBoolean()
                val parity = random.nextInt(2)
                exec("UPDATE chapters SET read = ${if (read) 1 else 0} WHERE manga_id = $manga AND _id % 2 = $parity")
                "bulk read $read on $manga's chapters of parity $parity"
            }
            21 -> {
                val bookmark = random.nextBoolean()
                exec("UPDATE chapters SET bookmark = ${if (bookmark) 1 else 0}, read = 1 - read WHERE manga_id = $manga")
                "bulk bookmark $bookmark and read flipped on $manga"
            }
            22 -> if (random.nextBoolean()) {
                exec("DELETE FROM history WHERE chapter_id IN (SELECT _id FROM chapters WHERE manga_id = $manga)")
                "bulk history delete on $manga"
            } else {
                history.resetHistoryByMangaId(manga)
                "history reset on $manga"
            }
            23 -> {
                longs("SELECT _id FROM chapters WHERE manga_id = $manga ORDER BY _id").forEach {
                    history.upsert(it, Date(random.nextLong(1, 100_000)), 1)
                }
                "history on every chapter of $manga"
            }
            else -> {
                exec("DELETE FROM mangas WHERE _id = $manga")
                insertManga(manga, favorite = random.nextBoolean())
                "manga $manga deleted and added again"
            }
        }
    }

    // The triggers one by one --------------------------------------------------------------------

    @Test
    fun `a chapter moved to another manga leaves both summaries right`() = runTest {
        insertManga(1)
        insertManga(2)
        val chapter = insert(mangaId = 1, read = true, dateUpload = 50)
        insert(mangaId = 2, dateUpload = 10)
        row(1) shouldBe listOf(1L, 1L, 0L, 50L)
        update(chapter, mangaId = 2)
        row(1) shouldBe listOf(0L, 0L, 0L, 0L)
        row(2) shouldBe listOf(2L, 1L, 0L, 50L)
        summary() shouldContainExactly aggregate()
    }

    @Test
    fun `an excluded scanlator's chapters leave the counts and come back when it is included again`() = runTest {
        insertManga(1)
        insert(mangaId = 1, read = true, scanlator = "A", dateUpload = 70)
        insert(mangaId = 1, bookmark = true, scanlator = "B", dateUpload = 20)
        insert(mangaId = 1, scanlator = null, dateUpload = 30)
        row(1) shouldBe listOf(3L, 1L, 1L, 70L)
        exclusions.insert(1, "A")
        row(1) shouldBe listOf(2L, 0L, 1L, 30L)
        // A chapter of an excluded scanlator added later is not counted.
        insert(mangaId = 1, read = true, scanlator = "A", dateUpload = 90)
        row(1) shouldBe listOf(2L, 0L, 1L, 30L)
        exclusions.remove(1, listOf("A"))
        row(1) shouldBe listOf(4L, 2L, 1L, 90L)
        summary() shouldContainExactly aggregate()
    }

    @Test
    fun `a soft delete and a restore move the counts`() = runTest {
        insertManga(1)
        val chapter = insert(mangaId = 1, read = true)
        insert(mangaId = 1)
        chapters.softDelete(1_000, listOf(chapter))
        row(1) shouldBe listOf(1L, 0L, 0L, 0L)
        update(chapter, clearDeletedAt = 1)
        row(1) shouldBe listOf(2L, 1L, 0L, 0L)
        summary() shouldContainExactly aggregate()
    }

    @Test
    fun `last_read is the latest history of the counted chapters`() = runTest {
        insertManga(1)
        val first = insert(mangaId = 1)
        val second = insert(mangaId = 1, scanlator = "A")
        history.upsert(first, Date(100), 1)
        history.upsert(second, Date(300), 1)
        lastRead(1) shouldBe 300
        exclusions.insert(1, "A")
        lastRead(1) shouldBe 100
        history.resetHistoryById(historyIds().first())
        lastRead(1) shouldBe 0
        summary() shouldContainExactly aggregate()
    }

    @Test
    fun `restored chapters inserted under the latch are counted, and lowering the latch recomputes nothing`() = runTest {
        insertManga(1)
        insert(mangaId = 1, read = true, latched = true)
        insert(mangaId = 1, latched = true)
        row(1) shouldBe listOf(2L, 1L, 0L, 0L)
        exec("UPDATE library_summary SET total_count = 99 WHERE manga_id = 1")
        chapters.resetIsSyncingByMangaId(1)
        // The lowering writes neither column the update trigger names, so the planted value stays.
        row(1).first() shouldBe 99L
        database.library_summaryQueries.recomputeLibrarySummary(1)
        row(1).first() shouldBe 2L
    }

    @Test
    fun `a manga delete takes its summary row with it`() = runTest {
        insertManga(1)
        insert(mangaId = 1)
        exec("DELETE FROM mangas WHERE _id = 1")
        scalar("SELECT count(*) FROM library_summary") shouldBe "0"
    }

    @Test
    fun `the rebuild throws away a drifted table and writes the aggregate again`() = runTest {
        MANGAS.forEach { insertManga(it) }
        insert(mangaId = 1, read = true, dateUpload = 5)
        insert(mangaId = 2, bookmark = true, scanlator = "A")
        exclusions.insert(3, "A")
        insert(mangaId = 3, scanlator = "A")
        exec("UPDATE library_summary SET total_count = 7, read_count = 7")
        exec("INSERT OR REPLACE INTO library_summary(manga_id, total_count) VALUES (4, 3)")
        summary() shouldNotBe aggregate()
        rebuild()
        summary() shouldContainExactly aggregate()
    }

    // Nothing in the sync log or a row's version moves for a count -------------------------------

    @Test
    fun `a change only the summary sees moves no version and logs nothing`() = runTest {
        insertManga(1)
        val chapter = insert(mangaId = 1)
        exec("DELETE FROM sync_change_log")
        val version = scalar("SELECT version FROM mangas WHERE _id = 1")
        // date_fetch is in the summary and in no version trigger's list.
        update(chapter, dateFetch = 12_345)
        row(1)[0] shouldBe 1L
        scalar("SELECT chapter_fetched_at FROM library_summary WHERE manga_id = 1") shouldBe "12345"
        database.library_summaryQueries.recomputeLibrarySummary(1)
        rebuild()
        scalar("SELECT count(*) FROM sync_change_log") shouldBe "0"
        scalar("SELECT version FROM mangas WHERE _id = 1") shouldBe version
    }

    @Test
    fun `an override edit logs the manga once, bumps its version and stamps its clock, and under the latch does none`() {
        insertManga(1)
        exec("DELETE FROM sync_change_log")
        exec("UPDATE mangas SET title_override = 'Edited' WHERE _id = 1")
        scalar("SELECT count(*) FROM sync_change_log WHERE entity_type = 'manga' AND entity_id = 1") shouldBe "1"
        scalar("SELECT version FROM mangas WHERE _id = 1") shouldBe "1"
        val clock = scalar("SELECT details_modified_at FROM mangas WHERE _id = 1").shouldNotBeNull().toLong()
        exec("UPDATE mangas SET description_override = 'Text' WHERE _id = 1")
        scalar("SELECT count(*) FROM sync_change_log") shouldBe "2"
        scalar("SELECT details_modified_at FROM mangas WHERE _id = 1")!!.toLong() shouldBeGreaterThan clock
        // Clearing one is an edit too: the columns are nullable and compared with IS NOT.
        exec("UPDATE mangas SET title_override = NULL WHERE _id = 1")
        scalar("SELECT count(*) FROM sync_change_log") shouldBe "3"

        exec("UPDATE mangas SET is_syncing = 1 WHERE _id = 1")
        exec("UPDATE mangas SET title_override = 'Applied', details_modified_at = 5 WHERE _id = 1")
        scalar("SELECT count(*) FROM sync_change_log") shouldBe "3"
        scalar("SELECT details_modified_at FROM mangas WHERE _id = 1") shouldBe "5"
    }

    // Helpers ------------------------------------------------------------------------------------

    private suspend fun insertChapter(random: Random, manga: Long, latched: Boolean): String {
        val deleted = if (random.nextInt(5) == 0) random.nextLong(1, 10_000) else null
        val id = insert(
            mangaId = manga,
            read = random.nextBoolean(),
            bookmark = random.nextInt(4) == 0,
            scanlator = SCANLATORS.random(random),
            dateUpload = date(random),
            dateFetch = date(random),
            deletedAt = deleted,
            latched = latched,
        )
        return "insert $id on $manga${if (latched) " under the latch" else ""}${deleted?.let { ", deleted" }.orEmpty()}"
    }

    private suspend fun syncInsertChapter(random: Random, manga: Long): String {
        val url = "https://example.invalid/ch/${random.nextLong()}"
        chapters.syncInsert(
            mangaId = manga, url = url, name = "Synced", scanlator = SCANLATORS.random(random),
            read = random.nextBoolean(), bookmark = random.nextBoolean(), lastPageRead = 0, chapterNumber = 1.0,
            sourceOrder = 0, dateFetch = date(random), dateUpload = date(random), version = 0, memo = MEMO,
            volume = null, deletedAt = null, restoredAt = null, bookmarkNotesModifiedAt = null,
        )
        chapters.syncSetIsSyncing(0, scalar("SELECT _id FROM chapters WHERE url = '$url'")!!.toLong())
        return "sync insert on $manga"
    }

    private suspend fun syncUpdate(random: Random, chapter: Long) {
        exec("UPDATE chapters SET is_syncing = 1 WHERE _id = $chapter")
        chapters.syncUpdate(
            read = random.nextBoolean(), bookmark = random.nextBoolean(), lastPageRead = 0, dateFetch = date(random),
            version = 1, name = "Merged", scanlator = SCANLATORS.random(random), chapterNumber = 1.0,
            dateUpload = date(random), volume = null, deletedAt = if (random.nextInt(4) == 0) 50 else null,
            restoredAt = null, bookmarkNotesModifiedAt = null, chapterId = chapter,
        )
        chapters.syncSetIsSyncing(0, chapter)
    }

    private suspend fun insert(
        mangaId: Long,
        read: Boolean = false,
        bookmark: Boolean = false,
        scanlator: String? = null,
        dateUpload: Long = 0,
        dateFetch: Long = 0,
        deletedAt: Long? = null,
        latched: Boolean = false,
    ): Long {
        val url = "https://example.invalid/ch/${nextUrl++}"
        if (latched) {
            chapters.insertSyncing(
                mangaId, url, "Chapter", scanlator, read, bookmark, 0, 1.0, 0, dateFetch, dateUpload, 0, MEMO, null, deletedAt,
            )
        } else {
            chapters.insert(
                mangaId, url, "Chapter", scanlator, read, bookmark, 0, 1.0, 0, dateFetch, dateUpload, 0, MEMO, null, deletedAt,
            )
        }
        return scalar("SELECT _id FROM chapters WHERE url = '$url'")!!.toLong()
    }

    private suspend fun update(
        chapter: Long,
        mangaId: Long? = null,
        scanlator: String? = null,
        read: Boolean? = null,
        bookmark: Boolean? = null,
        dateFetch: Long? = null,
        dateUpload: Long? = null,
        clearDeletedAt: Long = 0,
    ) {
        chapters.update(
            mangaId = mangaId, url = null, name = null, scanlator = scanlator, read = read, bookmark = bookmark,
            lastPageRead = null, chapterNumber = null, sourceOrder = null, dateFetch = dateFetch,
            dateUpload = dateUpload, version = null, isSyncing = null, memo = null, clearVolume = 0, volume = null,
            clearDeletedAt = clearDeletedAt, deletedAt = null, chapterId = chapter,
        )
    }

    private var nextUrl = 0L

    /** The repair's two statements in its one transaction. */
    private suspend fun rebuild() = database.transaction {
        database.library_summaryQueries.clearLibrarySummary()
        database.library_summaryQueries.backfillLibrarySummary()
    }

    /** Mostly positive, sometimes negative, so a maximum over a row of zeros is tested too. */
    private fun date(random: Random): Long = random.nextLong(-50, 1_000)

    private fun chapterIds(): List<Long> = longs("SELECT _id FROM chapters ORDER BY _id")

    private fun historyIds(): List<Long> = longs("SELECT _id FROM history ORDER BY _id")

    /** total, read, bookmark and latest upload of [manga], as the view reads them. */
    private fun row(manga: Long): List<Long> = table(
        "SELECT coalesce(total_count, 0), coalesce(read_count, 0), coalesce(bookmark_count, 0), " +
            "coalesce(latest_upload, 0) FROM mangas M LEFT JOIN library_summary S ON S.manga_id = M._id WHERE M._id = $manga",
        columns = 4,
    ).single()

    private fun lastRead(manga: Long): Long = scalar("SELECT coalesce(last_read, 0) FROM library_summary WHERE manga_id = $manga")!!.toLong()

    private fun summary(): List<List<Long>> = table(SUMMARY, columns = 7)

    private fun aggregate(): List<List<Long>> = table(OLD_AGGREGATE, columns = 7)

    private fun longs(sql: String): List<Long> = table(sql, columns = 1).map { it.single() }

    private fun table(sql: String, columns: Int): List<List<Long>> = driver.executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor: SqlCursor ->
            val out = mutableListOf<List<Long>>()
            while (cursor.next().value) {
                out += (0 until columns).map { cursor.getLong(it) ?: 0L }
            }
            QueryResult.Value(out.toList())
        },
        parameters = 0,
    ).value

    private companion object {
        val SEEDS = listOf(1L, 7L, 42L, 2026L, 31_337L)
        const val STEPS = 300
        val MANGAS = listOf(1L, 2L, 3L, 4L)
        val SCANLATORS = listOf(null, "A", "B", "C")
        val MEMO = JsonObject(emptyMap())

        /** Version 2's libraryView subquery, verbatim, over every manga row. */
        const val OLD_AGGREGATE = """
            SELECT M._id, coalesce(C.total, 0), coalesce(C.readCount, 0), coalesce(C.bookmarkCount, 0),
                coalesce(C.latestUpload, 0), coalesce(C.fetchedAt, 0), coalesce(C.lastRead, 0)
            FROM mangas M
            LEFT JOIN (
                SELECT
                    chapters.manga_id,
                    count(*) AS total,
                    sum(read) AS readCount,
                    coalesce(max(chapters.date_upload), 0) AS latestUpload,
                    coalesce(max(history.last_read), 0) AS lastRead,
                    coalesce(max(chapters.date_fetch), 0) AS fetchedAt,
                    sum(chapters.bookmark) AS bookmarkCount
                FROM chapters
                LEFT JOIN excluded_scanlators
                    ON chapters.manga_id = excluded_scanlators.manga_id
                    AND chapters.scanlator = excluded_scanlators.scanlator
                LEFT JOIN history ON chapters._id = history.chapter_id
                WHERE excluded_scanlators.scanlator IS NULL
                AND chapters.deleted_at IS NULL
                GROUP BY chapters.manga_id
            ) AS C ON M._id = C.manga_id
            ORDER BY M._id
        """

        const val SUMMARY = """
            SELECT M._id, coalesce(S.total_count, 0), coalesce(S.read_count, 0), coalesce(S.bookmark_count, 0),
                coalesce(S.latest_upload, 0), coalesce(S.chapter_fetched_at, 0), coalesce(S.last_read, 0)
            FROM mangas M
            LEFT JOIN library_summary S ON S.manga_id = M._id
            ORDER BY M._id
        """
    }
}

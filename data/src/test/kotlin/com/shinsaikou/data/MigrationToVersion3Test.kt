package com.shinsaikou.data

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.data.Database
import java.io.File
import java.util.Properties
import kotlin.random.Random

/**
 * The second migration, schema version 2 to 3 (phase 5 unit SC4), on a database that starts as the
 * committed `schemas/2.db` and is filled through version 2's own triggers: a library with read,
 * bookmarked, soft-deleted and excluded chapters, history, categories, an entry out of the library
 * and a soft-deleted one. What version 2's `libraryView` and its aggregate read is taken before the
 * migration and compared after it. The migration runs to the current version, so version 4's index
 * drops ([MigrationToVersion4Test]) run after it here too.
 */
class MigrationToVersion3Test {

    @TempDir
    lateinit var dir: File

    private lateinit var driver: JdbcSqliteDriver

    private lateinit var libraryBefore: List<List<String?>>
    private lateinit var aggregateBefore: List<List<String?>>
    private lateinit var countsBefore: Map<String, String?>
    private lateinit var logBefore: List<List<String?>>
    private lateinit var versionsBefore: List<List<String?>>

    @BeforeEach
    fun setUp() {
        val file = File(dir, "saikou.db")
        File(SNAPSHOT).copyTo(file)
        driver = JdbcSqliteDriver("jdbc:sqlite:${file.path}", Properties())
        exec("PRAGMA foreign_keys = ON")
        exec("PRAGMA user_version = 2")
        fillVersion2()
        libraryBefore = rows(LIBRARY_SQL, 8)
        aggregateBefore = rows(OLD_AGGREGATE, 7)
        countsBefore = TABLES.associateWith { scalar("SELECT count(*) FROM $it") }
        logBefore = rows(LOG_SQL, 5)
        versionsBefore = rows("SELECT _id, version, last_modified_at FROM mangas ORDER BY _id", 3)

        Database.Schema.synchronous().migrate(driver, 2, Database.Schema.version).value
        exec("PRAGMA user_version = ${Database.Schema.version}")
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `the migration keeps every row, log row and version, and the schema is the current version`() {
        scalar("PRAGMA user_version") shouldBe Database.Schema.version.toString()
        TABLES.associateWith { scalar("SELECT count(*) FROM $it") } shouldBe countsBefore
        rows(LOG_SQL, 5) shouldContainExactly logBefore
        logBefore.shouldNotBeEmpty()
        rows("SELECT _id, version, last_modified_at FROM mangas ORDER BY _id", 3) shouldContainExactly versionsBefore
    }

    @Test
    fun `the backfilled summary is version 2's aggregate for every manga, and the library reads as it did`() {
        rows(SUMMARY, 7) shouldContainExactly aggregateBefore
        // Every manga holding a counted chapter has its row; the others have none, read as 0.
        scalar("SELECT count(*) FROM library_summary") shouldBe
            aggregateBefore.count { it[1] != "0" }.toString()
        rows(LIBRARY_SQL, 8) shouldContainExactly libraryBefore
        libraryBefore.size shouldBe FAVOURITES
    }

    @Test
    fun `the triggers keep the backfilled summary after the migration`() {
        exec("UPDATE chapters SET read = 1 WHERE manga_id = 3")
        exec("INSERT INTO excluded_scanlators(manga_id, scanlator) VALUES (4, 'B')")
        exec("UPDATE chapters SET deleted_at = 99 WHERE _id IN (SELECT _id FROM chapters WHERE manga_id = 5 LIMIT 2)")
        exec("DELETE FROM history WHERE chapter_id IN (SELECT _id FROM chapters WHERE manga_id = 6)")
        rows(SUMMARY, 7) shouldContainExactly rows(OLD_AGGREGATE, 7)
    }

    @Test
    fun `ED's columns arrive empty, and the views' display title is the source title until there is an override`() {
        scalar(
            "SELECT count(*) FROM mangas WHERE title_override IS NOT NULL OR description_override IS NOT NULL " +
                "OR details_modified_at IS NOT NULL",
        ) shouldBe "0"
        scalar("SELECT count(*) FROM updatesView WHERE displayTitle IS NOT mangaTitle") shouldBe "0"
        scalar("SELECT count(*) FROM historyView WHERE displayTitle IS NOT title") shouldBe "0"
        scalar("SELECT count(*) FROM updatesView") shouldNotBe "0"
        scalar("SELECT count(*) FROM historyView") shouldNotBe "0"

        // An entry with history, so in both views.
        val manga = scalar("SELECT mangaId FROM historyView WHERE favorite = 1 ORDER BY mangaId LIMIT 1")
        exec("UPDATE mangas SET title_override = 'Edited' WHERE _id = $manga")
        rows("SELECT DISTINCT displayTitle, mangaTitle FROM updatesView WHERE mangaId = $manga", 2) shouldContainExactly
            listOf(listOf("Edited", "Title $manga"))
        rows("SELECT DISTINCT displayTitle, title FROM historyView WHERE mangaId = $manga", 2) shouldContainExactly
            listOf(listOf("Edited", "Title $manga"))
    }

    /**
     * Version 2's library: [MANGAS] entries, the first [FAVOURITES] in the library but one
     * soft-deleted, the last outside it; up to 30 chapters each from a fixed seed, about one in
     * eight soft-deleted, three scanlators of which some entries exclude one, history on part of
     * the read chapters, and a category per entry.
     */
    private fun fillVersion2() {
        val random = Random(SEED)
        exec("INSERT INTO categories(_id, name, sort, flags, sync_key) VALUES (1, 'Reading', 1, 0, 'k1'), (2, 'Later', 2, 0, 'k2')")
        var chapterId = 1L
        (1L..MANGAS).forEach { manga ->
            val favourite = manga <= FAVOURITES + 1
            exec(
                """
                INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer,
                    chapter_flags, cover_last_modified, date_added, memo, deleted_at)
                VALUES ($manga, 1, 'https://example.invalid/manga/$manga', 'Title $manga', 0,
                    ${if (favourite) 1 else 0}, 0, 0, 0, 0, 10, CAST('{}' AS BLOB),
                    ${if (manga == FAVOURITES + 1L) "500" else "NULL"})
                """,
            )
            if (manga % 2 == 0L) {
                exec("INSERT INTO mangas_categories(manga_id, category_id) VALUES ($manga, ${if (manga % 4 == 0L) 2 else 1})")
            }
            if (manga % 3 == 0L) exec("INSERT INTO excluded_scanlators(manga_id, scanlator) VALUES ($manga, 'A')")
            repeat(random.nextInt(0, 30)) {
                val id = chapterId++
                val read = random.nextBoolean()
                val scanlator = listOf("NULL", "'A'", "'B'").random(random)
                val deleted = if (random.nextInt(8) == 0) "${random.nextLong(1, 1_000)}" else "NULL"
                exec(
                    """
                    INSERT INTO chapters(_id, manga_id, url, name, scanlator, read, bookmark, last_page_read,
                        chapter_number, source_order, date_fetch, date_upload, memo, deleted_at)
                    VALUES ($id, $manga, 'https://example.invalid/ch/$id', 'Chapter $id', $scanlator,
                        ${if (read) 1 else 0}, ${if (random.nextInt(5) == 0) 1 else 0}, 0, $id, $id,
                        ${random.nextLong(11, 10_000)}, ${random.nextLong(0, 10_000)}, CAST('{}' AS BLOB), $deleted)
                    """,
                )
                if (read && random.nextBoolean()) {
                    exec("INSERT INTO history(chapter_id, last_read, time_read) VALUES ($id, ${random.nextLong(1, 100_000)}, 5)")
                }
            }
        }
    }

    private fun exec(sql: String) {
        driver.execute(null, sql, 0).value
    }

    private fun rows(sql: String, columns: Int): List<List<String?>> =
        driver.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor: SqlCursor ->
                val out = mutableListOf<List<String?>>()
                while (cursor.next().value) {
                    out += (0 until columns).map { cursor.getString(it) }
                }
                QueryResult.Value(out.toList())
            },
            parameters = 0,
        ).value

    private fun scalar(sql: String): String? = rows(sql, 1).firstOrNull()?.firstOrNull()

    private companion object {
        /** The version 2 schema as it shipped, relative to the module directory. */
        const val SNAPSHOT = "src/main/sqldelight/schemas/2.db"

        const val SEED = 20_261_011L
        const val MANGAS = 40L
        const val FAVOURITES = 37

        val TABLES = listOf(
            "mangas", "chapters", "categories", "mangas_categories", "history", "manga_sync",
            "excluded_scanlators", "scanlator_priority", "bookmark_notes", "sync_change_log",
        )

        const val LOG_SQL =
            "SELECT seq, entity_type, entity_id, change_type, changed_at FROM sync_change_log ORDER BY seq"

        /** The library as its readers see it: the entry and the columns the aggregate gave. */
        const val LIBRARY_SQL =
            "SELECT _id, totalCount, readCount, latestUpload, chapterFetchedAt, lastRead, bookmarkCount, categories " +
                "FROM libraryView ORDER BY _id"

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

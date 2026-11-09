package com.shinsaikou.data

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.data.Database
import java.io.File
import java.util.Properties
import kotlin.random.Random

/**
 * The third migration, schema version 3 to 4: the chapter index diet, which drops
 * idx_chapters_url, idx_chapters_manga_id, idx_chapters_last_page_read, idx_chapters_read and
 * idx_chapters_date_fetch (migrations/3.sqm). The database starts as the committed `schemas/3.db`
 * and is filled through version 3's own triggers. Nothing but the five indexes changes, no trigger
 * fires, and the statements whose plans used them still find their chapters through an index, with
 * no full scan, once the migrated database is analyzed.
 */
class MigrationToVersion4Test {

    @TempDir
    lateinit var dir: File

    private lateinit var driver: JdbcSqliteDriver

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `the migration drops the five indexes and leaves every other object and row as it was`() {
        open()
        fillVersion3()
        val objectsBefore = rows("SELECT type, name, sql FROM sqlite_master ORDER BY type, name", 3)
        val countsBefore = TABLES.associateWith { scalar("SELECT count(*) FROM $it") }
        val logBefore = rows(LOG_SQL, 5)
        val chaptersBefore = rows(CHAPTER_CLOCKS, 4)
        val mangasBefore = rows("SELECT _id, version, last_modified_at FROM mangas ORDER BY _id", 3)
        val summaryBefore = rows("SELECT * FROM library_summary ORDER BY manga_id", 7)
        val libraryBefore = rows("SELECT _id, totalCount, readCount, bookmarkCount FROM libraryView ORDER BY _id", 4)

        migrate()

        scalar("PRAGMA user_version") shouldBe Database.Schema.version.toString()
        withoutVersion6(withoutVersion7(rows("SELECT type, name, sql FROM sqlite_master ORDER BY type, name", 3)))
            .filterNot { MERGED_ENTRIES in it[1].orEmpty() } shouldContainExactly
            objectsBefore.filterNot { it[0] == "index" && it[1] in DROPPED }
        objectsBefore.map { it[1] }.filter { it in DROPPED } shouldContainExactly DROPPED.sorted()
        TABLES.associateWith { scalar("SELECT count(*) FROM $it") } shouldBe countsBefore
        rows(LOG_SQL, 5) shouldContainExactly logBefore
        logBefore.shouldNotBeEmpty()
        rows(CHAPTER_CLOCKS, 4) shouldContainExactly chaptersBefore
        rows("SELECT _id, version, last_modified_at FROM mangas ORDER BY _id", 3) shouldContainExactly mangasBefore
        rows("SELECT * FROM library_summary ORDER BY manga_id", 7) shouldContainExactly summaryBefore
        rows("SELECT _id, totalCount, readCount, bookmarkCount FROM libraryView ORDER BY _id", 4) shouldContainExactly
            libraryBefore
    }

    @Test
    fun `a version 3 database already missing one of the indexes still upgrades`() {
        open()
        exec("DROP INDEX idx_chapters_url")
        migrate()

        scalar("PRAGMA user_version") shouldBe Database.Schema.version.toString()
        rows("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'chapters' ORDER BY name", 1)
            .map { it.single() } shouldContainExactly REMAINING
    }

    @Test
    fun `the statements that used the dropped indexes search chapters through another one`() {
        open()
        fillVersion3()
        migrate()
        exec("ANALYZE")
        val manga = scalar("SELECT manga_id FROM chapters GROUP BY manga_id ORDER BY count(*) DESC LIMIT 1")!!
        val url = scalar("SELECT url FROM chapters WHERE manga_id = $manga LIMIT 1")!!

        val problems = PLANNED.flatMap { (file, name) ->
            val statement = SqSources.statements.single { it.file == file && it.name == name }
            plan(literal(statement.sql, manga, url)).let { check("$file:$name", it) }
        } + check("mangas delete cascade", plan("DELETE FROM chapters WHERE manga_id = $manga"))
        problems.shouldBeEmpty()
    }

    @Test
    fun `the library counts read their chapters through an index, with no full scan and no sort`() {
        open()
        fillVersion3()
        migrate()
        exec("ANALYZE")
        val statement = SqSources.statements.single { it.file == "mangas.sq" && it.name == "syncCountLibrary" }

        val plan = plan(statement.sql)
        check("mangas.sq:syncCountLibrary", plan).shouldBeEmpty()
        plan.filter { "TEMP B-TREE" in it }.shouldBeEmpty()
    }

    // Fixtures and helpers -----------------------------------------------------------------------

    private fun open() {
        val file = File(dir, "saikou.db")
        File(SNAPSHOT).copyTo(file)
        driver = JdbcSqliteDriver("jdbc:sqlite:${file.path}", Properties())
        exec("PRAGMA foreign_keys = ON")
        exec("PRAGMA synchronous = OFF")
        exec("PRAGMA user_version = 3")
    }

    private fun migrate() {
        Database.Schema.synchronous().migrate(driver, 3, Database.Schema.version).value
        exec("PRAGMA user_version = ${Database.Schema.version}")
    }

    /**
     * Version 3's library: [MANGAS] entries, the last outside the library and one soft-deleted; up
     * to 80 chapters each from a fixed seed, read, bookmarked, part read, soft-deleted and from two
     * scanlators, one of which a few entries exclude; history on part of the read chapters.
     */
    private fun fillVersion3() {
        val random = Random(SEED)
        var chapterId = 1L
        (1L..MANGAS).forEach { manga ->
            exec(
                """
                INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer,
                    chapter_flags, cover_last_modified, date_added, memo, deleted_at)
                VALUES ($manga, 1, 'https://example.invalid/manga/$manga', 'Title $manga', 0,
                    ${if (manga < MANGAS) 1 else 0}, 0, 0, 0, 0, 10, CAST('{}' AS BLOB),
                    ${if (manga == MANGAS - 1) "500" else "NULL"})
                """,
            )
            if (manga % 4 == 0L) exec("INSERT INTO excluded_scanlators(manga_id, scanlator) VALUES ($manga, 'A')")
            repeat(random.nextInt(1, 80)) {
                val id = chapterId++
                val read = random.nextInt(3) == 0
                exec(
                    """
                    INSERT INTO chapters(_id, manga_id, url, name, scanlator, read, bookmark, last_page_read,
                        chapter_number, source_order, date_fetch, date_upload, memo, deleted_at)
                    VALUES ($id, $manga, 'https://example.invalid/ch/$id', 'Chapter $id',
                        ${listOf("NULL", "'A'", "'B'").random(random)}, ${if (read) 1 else 0},
                        ${if (random.nextInt(20) == 0) 1 else 0}, ${if (random.nextInt(4) == 0) random.nextInt(1, 30) else 0},
                        $id, $id, ${random.nextLong(11, 10_000)}, ${random.nextLong(0, 10_000)}, CAST('{}' AS BLOB),
                        ${if (random.nextInt(25) == 0) "${random.nextLong(1, 1_000)}" else "NULL"})
                    """,
                )
                if (read && random.nextBoolean()) {
                    exec("INSERT INTO history(chapter_id, last_read, time_read) VALUES ($id, ${random.nextLong(1, 100_000)}, 5)")
                }
            }
        }
    }

    /**
     * Every chapter line of [plan] that is a scan rather than a search, or no search at all. A walk
     * of a partial index is not a scan of chapters: it reads only the rows the index's WHERE keeps
     * (syncCountLibrary's bookmark count walks idx_chapters_manga_id_bookmark, the bookmarked ones).
     */
    private fun check(label: String, plan: List<String>): List<String> {
        val chapterLines = plan.filter { CHAPTERS.containsMatchIn(it) }
        val partial = rows(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'chapters' AND sql LIKE '% WHERE %'",
            1,
        ).map { it.single() }
        val scans = chapterLines.filter { line ->
            line.startsWith("SCAN") && partial.none { Regex("""USING (?:COVERING )?INDEX $it\b""").containsMatchIn(line) }
        }
        return when {
            chapterLines.isEmpty() -> listOf("$label reads no chapters: $plan")
            scans.isNotEmpty() -> listOf("$label scans chapters: $plan")
            chapterLines.none { SEARCH.containsMatchIn(it) } -> listOf("$label has no index search: $plan")
            else -> emptyList()
        }
    }

    private fun plan(sql: String): List<String> = rows("EXPLAIN QUERY PLAN $sql", 4).mapNotNull { it[3] }

    /** The statement with its parameters as literals: the entry, its chapter's url, and 0 for the rest. */
    private fun literal(sql: String, manga: String, url: String): String = sql
        .replace(Regex("""\bIN\s+:\w+""", RegexOption.IGNORE_CASE), "IN ($manga)")
        .replace(":mangaId", manga)
        .replace(":chapterUrl", "'$url'")
        .replace(Regex(""":\w+"""), "0")

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
        /** The version 3 schema as it shipped, relative to the module directory. */
        const val SNAPSHOT = "src/main/sqldelight/schemas/3.db"

        const val SEED = 20_261_014L
        const val MANGAS = 40L

        /** What every object of schema version 5, which the migration runs on to, has in its name. */
        const val MERGED_ENTRIES = "merged_"

        val DROPPED = listOf(
            "idx_chapters_date_fetch", "idx_chapters_last_page_read", "idx_chapters_manga_id",
            "idx_chapters_read", "idx_chapters_url",
        )

        val REMAINING = listOf(
            "idx_chapters_date_upload", "idx_chapters_deleted_at", "idx_chapters_is_syncing",
            "idx_chapters_manga_id_bookmark", "idx_chapters_manga_id_read", "idx_chapters_manga_id_url",
        )

        /**
         * Every named statement whose plan used a dropped index, on the populated database with and
         * without statistics (the report's plans, design/plans/chapter-index-diet).
         */
        val PLANNED = listOf(
            "chapters.sq" to "getChaptersByMangaId",
            "chapters.sq" to "getDeletedChaptersByMangaId",
            "chapters.sq" to "getScanlatorsByMangaId",
            "chapters.sq" to "syncGetByMangaId",
            "chapters.sq" to "getChapterByUrlAndMangaId",
            "chapters.sq" to "backupGetChaptersByMangaIds",
            "history.sq" to "getHistoryByMangaId",
            "history.sq" to "getHistoryByChapterUrlAndMangaId",
            "history.sq" to "resetHistoryByMangaId",
            "library_summary.sq" to "recomputeLibrarySummary",
            "sync_change_log.sq" to "logChaptersWithoutProgressOnFavorite",
            "bookmark_notes.sq" to "getByMangaId",
        )

        val TABLES = listOf(
            "mangas", "chapters", "categories", "mangas_categories", "history", "manga_sync",
            "excluded_scanlators", "scanlator_priority", "bookmark_notes", "sync_change_log", "library_summary",
        )

        const val LOG_SQL =
            "SELECT seq, entity_type, entity_id, change_type, changed_at FROM sync_change_log ORDER BY seq"

        const val CHAPTER_CLOCKS = "SELECT _id, version, last_modified_at, is_syncing FROM chapters ORDER BY _id"

        /** A plan line on the chapters table, unaliased or as `C`. */
        val CHAPTERS = Regex("""^(?:SCAN|SEARCH) (?:chapters|C)\b""")

        val SEARCH = Regex("""^SEARCH (?:chapters|C) USING (?:COVERING )?INDEX |^SEARCH (?:chapters|C) USING INTEGER PRIMARY KEY""")
    }
}

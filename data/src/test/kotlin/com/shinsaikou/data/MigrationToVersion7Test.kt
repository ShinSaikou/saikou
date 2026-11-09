package com.shinsaikou.data

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.data.Database
import java.io.File
import java.util.Properties
import kotlin.random.Random

/**
 * The sixth migration, schema version 6 to 7: the author, artist, status and genre overrides (R165
 * item 4, migrations/6.sqm). The database starts as the committed `schemas/6.db`, filled through
 * version 6's own triggers, and is opened the way the app opens it. Nothing but the four columns is
 * added and the two triggers that name the overrides are replaced: every existing entry reads NULL
 * in the four, no other value moves and the change log keeps exactly the rows it had. The triggers
 * then work as `mangas.sq` says: a local change of any of the six overrides moves the one details
 * clock and logs the entry, and a write under the sync latch, or one that leaves every override as
 * it was, moves nothing.
 */
class MigrationToVersion7Test {

    @TempDir
    lateinit var dir: File

    private lateinit var driver: JdbcSqliteDriver

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `the migration moves no value, changes no other object and leaves user_version at 7`() {
        openVersion6()
        fillVersion6()
        val objectsBefore = objects()
        val columnsBefore = TABLES.associateWith { columnCount(it) }
        val rowsBefore = TABLES.associateWith { dump(it, columnsBefore.getValue(it)) }
        val logBefore = dump("sync_change_log", columnCount("sync_change_log"))
        rowsBefore.getValue("mangas").shouldNotBeEmpty()
        logBefore.shouldNotBeEmpty()
        scalar("SELECT count(*) FROM mangas WHERE details_modified_at IS NOT NULL")!!.toLong() shouldBeGreaterThan 0L
        close()

        migrate()

        scalar("PRAGMA user_version") shouldBe Database.Schema.version.toString()
        (Database.Schema.version >= 7L) shouldBe true
        withoutVersion7(objects()) shouldContainExactly objectsBefore
        TABLES.associateWith { dump(it, columnsBefore.getValue(it)) } shouldBe rowsBefore
        dump("sync_change_log", columnCount("sync_change_log")) shouldContainExactly logBefore
        scalar(
            "SELECT count(*) FROM mangas WHERE author_override IS NOT NULL OR artist_override IS NOT NULL " +
                "OR status_override IS NOT NULL OR genre_override IS NOT NULL",
        ) shouldBe "0"
    }

    @Test
    fun `the migration adds exactly the four override columns and replaces the two triggers that name them`() {
        openVersion6()
        val before = objects()
        val mangaColumnsBefore = mangaColumns()
        close()

        migrate()

        objects().filterNot { it in before }.map { it[0] to it[1] } shouldContainExactlyInAnyOrder listOf(
            "table" to "mangas",
            "trigger" to "update_manga_version",
            "trigger" to "update_manga_details_modified_at",
        )
        mangaColumns() shouldContainExactly mangaColumnsBefore + listOf(
            listOf("author_override", "TEXT", "0", null),
            listOf("artist_override", "TEXT", "0", null),
            listOf("status_override", "INTEGER", "0", null),
            listOf("genre_override", "TEXT", "0", null),
        )
    }

    @Test
    fun `the migrated columns and triggers are the ones a fresh version 7 database has`() {
        openVersion6()
        close()
        migrate()
        val migratedColumns = mangaColumns()
        val migratedTriggers = replacedTriggers()
        close()

        val fresh = File(dir, "fresh.db")
        File(VERSION_7).copyTo(fresh)
        driver = JdbcSqliteDriver("jdbc:sqlite:${fresh.path}", Properties())

        migratedTriggers shouldHaveSize 2
        migratedColumns shouldContainExactly mangaColumns()
        migratedTriggers shouldContainExactly replacedTriggers()
    }

    @Test
    fun `a local change of any new override moves the details clock and logs the entry`() {
        migratedWithOneEntry()
        val start = now()
        val edits = listOf(
            "author_override = 'Edited author'",
            "artist_override = 'Edited artist'",
            "status_override = 2",
            "genre_override = 'Comedy, Drama'",
        )

        edits.forEachIndexed { index, edit ->
            val clock = detailsClock()
            exec("UPDATE mangas SET $edit WHERE _id = 1")

            detailsClock() shouldBeGreaterThan clock
            detailsClock() shouldBeGreaterThanOrEqual start
            scalar("SELECT version FROM mangas WHERE _id = 1") shouldBe "${index + 1}"
            logged() shouldBe index + 1
        }
    }

    @Test
    fun `clearing an override is an edit too, and the title and description still move the same clock`() {
        migratedWithOneEntry()
        exec("UPDATE mangas SET status_override = 5 WHERE _id = 1")
        val set = detailsClock()

        exec("UPDATE mangas SET status_override = NULL WHERE _id = 1")
        val cleared = detailsClock()
        cleared shouldBeGreaterThan set

        exec("UPDATE mangas SET title_override = 'Edited title' WHERE _id = 1")
        detailsClock() shouldBeGreaterThan cleared
        logged() shouldBe 3
    }

    @Test
    fun `a write under the sync latch moves no clock and logs nothing, and lowering the latch moves none either`() {
        migratedWithOneEntry()
        exec("UPDATE mangas SET is_syncing = 1 WHERE _id = 1")

        exec(
            """
            UPDATE mangas SET author_override = 'Pulled', artist_override = 'Pulled', status_override = 2,
                genre_override = 'Pulled', details_modified_at = 700
            WHERE _id = 1
            """,
        )
        exec("UPDATE mangas SET is_syncing = 0 WHERE _id = 1")

        detailsClock() shouldBe 700L
        scalar("SELECT version FROM mangas WHERE _id = 1") shouldBe "0"
        logged() shouldBe 0
    }

    @Test
    fun `an update that leaves every override as it was moves no clock`() {
        migratedWithOneEntry()
        exec("UPDATE mangas SET author_override = 'Edited', genre_override = 'Comedy' WHERE _id = 1")
        val clock = detailsClock()

        exec("UPDATE mangas SET author_override = 'Edited', genre_override = 'Comedy' WHERE _id = 1")
        exec("UPDATE mangas SET author = 'Source author', status = 1 WHERE _id = 1")

        detailsClock() shouldBe clock
    }

    // Fixtures and helpers -----------------------------------------------------------------------

    /** The committed version 6 database with `user_version` set, as the app's database file was. */
    private fun openVersion6() {
        val file = File(dir, "saikou.db")
        File(VERSION_6).copyTo(file)
        driver = JdbcSqliteDriver("jdbc:sqlite:${file.path}", Properties())
        exec("PRAGMA foreign_keys = ON")
        exec("PRAGMA synchronous = OFF")
        exec("PRAGMA user_version = 6")
    }

    private fun close() = driver.close()

    /** Opens the file with the schema, which migrates a database whose version is behind and sets it. */
    private fun migrate() {
        val properties = Properties().apply { setProperty("foreign_keys", "true") }
        driver = JdbcSqliteDriver("jdbc:sqlite:${File(dir, "saikou.db").path}", properties, Database.Schema.synchronous())
    }

    /** A migrated database with one library entry, id 1, never edited, and an empty change log. */
    private fun migratedWithOneEntry() {
        openVersion6()
        close()
        migrate()
        exec(
            """
            INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer,
                chapter_flags, cover_last_modified, date_added, memo)
            VALUES (1, 1, '/a', 'Title', 1, 1, 0, 0, 0, 0, 10, CAST('{}' AS BLOB))
            """,
        )
        exec("DELETE FROM sync_change_log")
    }

    /**
     * Version 6's library from a fixed seed: [MANGAS] entries, the last outside the library, with
     * source details, some with an edited title or description through version 6's triggers, and
     * chapters, some read.
     */
    private fun fillVersion6() {
        val random = Random(SEED)
        var chapterId = 1L
        (1L..MANGAS).forEach { manga ->
            exec(
                """
                INSERT INTO mangas(_id, source, url, title, author, artist, genre, status, favorite, initialized,
                    viewer, chapter_flags, cover_last_modified, date_added, memo)
                VALUES ($manga, 1, 'https://example.invalid/manga/$manga', 'Title $manga', 'Author $manga',
                    'Artist $manga', 'Action, Drama', ${random.nextInt(7)}, ${if (manga < MANGAS) 1 else 0}, 1,
                    0, 0, 0, 10, CAST('{}' AS BLOB))
                """,
            )
            if (random.nextInt(3) == 0) {
                exec("UPDATE mangas SET title_override = 'Edited $manga' WHERE _id = $manga")
            }
            if (random.nextInt(4) == 0) {
                exec("UPDATE mangas SET description_override = '' WHERE _id = $manga")
            }
            repeat(random.nextInt(1, 10)) {
                val id = chapterId++
                exec(
                    """
                    INSERT INTO chapters(_id, manga_id, url, name, read, bookmark, last_page_read,
                        chapter_number, source_order, date_fetch, date_upload, memo)
                    VALUES ($id, $manga, 'https://example.invalid/ch/$id', 'Chapter $id', 0, 0, 0,
                        $id, $id, 20, 30, CAST('{}' AS BLOB))
                    """,
                )
                if (random.nextInt(3) == 0) exec("UPDATE chapters SET read = 1 WHERE _id = $id")
            }
        }
    }

    private fun objects(): List<List<String?>> =
        rows("SELECT type, name, sql FROM sqlite_master ORDER BY type, name", 3)

    /** Name, type, not-null and default of each mangas column, in order. */
    private fun mangaColumns(): List<List<String?>> =
        rows("SELECT name, type, \"notnull\", dflt_value FROM pragma_table_info('mangas') ORDER BY cid", 4)

    private fun replacedTriggers(): List<List<String?>> =
        rows("SELECT name, sql FROM sqlite_master WHERE type = 'trigger' AND name IN $REPLACED_TRIGGERS ORDER BY name", 2)

    private fun columnCount(table: String): Int =
        rows("SELECT count(*) FROM pragma_table_info('$table')", 1).single().single()!!.toInt()

    /** The first [columns] columns of every row of [table] in storage order, each as text. */
    private fun dump(table: String, columns: Int): List<List<String?>> =
        rows("SELECT * FROM $table ORDER BY rowid", columns)

    private fun detailsClock(): Long = scalar("SELECT coalesce(details_modified_at, 0) FROM mangas WHERE _id = 1")!!.toLong()

    private fun logged(): Int =
        scalar("SELECT count(*) FROM sync_change_log WHERE entity_type = 'manga' AND entity_id = 1")!!.toInt()

    /** Now in milliseconds, cut to the whole second: the resolution of the triggers' strftime. */
    private fun now(): Long = System.currentTimeMillis() / 1000 * 1000

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
        /** The schemas as they shipped, relative to the module directory. */
        const val VERSION_6 = "src/main/sqldelight/schemas/6.db"
        const val VERSION_7 = "src/main/sqldelight/schemas/7.db"

        const val SEED = 20_261_109L
        const val MANGAS = 30L

        const val REPLACED_TRIGGERS = "('update_manga_version', 'update_manga_details_modified_at')"

        val TABLES = listOf(
            "mangas", "chapters", "categories", "mangas_categories", "history", "excluded_scanlators",
            "scanlator_priority", "bookmark_notes", "library_summary", "source_alias", "sources", "manga_sync",
            "merged_groups", "merged_members",
        )
    }
}

/**
 * [objects], rows of `sqlite_master` as (type, name, sql), as they were before migrations/6.sqm ran:
 * the four columns its `ALTER TABLE` appends cut out of the mangas table's text, and the two triggers
 * it replaces without the text that names those columns. For the earlier migrations' tests, which
 * run on to the current version.
 */
internal fun withoutVersion7(objects: List<List<String?>>): List<List<String?>> = objects.map { row ->
    val cuts = when {
        row[0] == "table" && row[1] == "mangas" -> VERSION_7_COLUMNS
        row[0] == "trigger" && row[1] in VERSION_7_TRIGGERS -> VERSION_7_TRIGGER_TEXT
        else -> return@map row
    }
    listOf(row[0], row[1], cuts.fold(row[2].orEmpty()) { sql, cut -> sql.replace(cut, "") })
}

private val VERSION_7_TRIGGERS = setOf("update_manga_version", "update_manga_details_modified_at")

/** The text SQLite's `ALTER TABLE ... ADD COLUMN` inserts into the table's DDL, one column each. */
private val VERSION_7_COLUMNS = listOf(
    ", author_override TEXT",
    ", artist_override TEXT",
    ", status_override INTEGER",
    ", genre_override TEXT",
)

/** What migrations/6.sqm's two triggers add to version 6's text of them. */
private val VERSION_7_TRIGGER_TEXT = listOf(
    ",\n    author_override, artist_override, status_override, genre_override",
    " OR\n    new.author_override IS NOT old.author_override OR new.artist_override IS NOT old.artist_override OR\n" +
        "    new.status_override IS NOT old.status_override OR new.genre_override IS NOT old.genre_override",
)

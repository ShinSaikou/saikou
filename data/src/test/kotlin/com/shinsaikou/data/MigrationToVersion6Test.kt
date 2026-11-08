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
 * The fifth migration, schema version 5 to 6: the chapters' read and bookmark clocks (R165 item 1,
 * migrations/5.sqm). The database starts as the committed `schemas/5.db`, filled through version
 * 5's own triggers, and is opened the way the app opens it. Nothing but the two columns and their
 * two triggers is added: every existing chapter reads 0 in both, no other value moves and the change
 * log keeps exactly the rows it had. The triggers then work as `chapters.sq` says: a local change of
 * the value moves its clock, past its own last value too, and a write under the sync latch, or one
 * that leaves the value as it was, moves nothing.
 */
class MigrationToVersion6Test {

    @TempDir
    lateinit var dir: File

    private lateinit var driver: JdbcSqliteDriver

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `the migration moves no value, changes no other object and leaves user_version at 6`() {
        openVersion5()
        fillVersion5()
        val objectsBefore = objects()
        val columnsBefore = TABLES.associateWith { columnCount(it) }
        val rowsBefore = TABLES.associateWith { dump(it, columnsBefore.getValue(it)) }
        val logBefore = dump("sync_change_log", columnCount("sync_change_log"))
        val libraryBefore = rows("SELECT _id, totalCount, readCount, bookmarkCount FROM libraryView ORDER BY _id", 4)
        rowsBefore.getValue("chapters").shouldNotBeEmpty()
        logBefore.shouldNotBeEmpty()
        close()

        migrate()

        scalar("PRAGMA user_version") shouldBe Database.Schema.version.toString()
        (Database.Schema.version >= 6L) shouldBe true
        withoutVersion6(objects()) shouldContainExactly objectsBefore
        TABLES.associateWith { dump(it, columnsBefore.getValue(it)) } shouldBe rowsBefore
        dump("sync_change_log", columnCount("sync_change_log")) shouldContainExactly logBefore
        rows("SELECT _id, totalCount, readCount, bookmarkCount FROM libraryView ORDER BY _id", 4) shouldContainExactly
            libraryBefore
        scalar("SELECT count(*) FROM chapters WHERE read_modified_at != 0 OR bookmark_modified_at != 0") shouldBe "0"
    }

    @Test
    fun `the migration adds exactly the two clock columns and their two triggers`() {
        openVersion5()
        val before = objects()
        val chapterColumnsBefore = chapterColumns()
        close()

        migrate()

        objects().filterNot { it in before }.map { it[0] to it[1] } shouldContainExactlyInAnyOrder listOf(
            "table" to "chapters",
            "trigger" to "update_chapter_read_modified_at",
            "trigger" to "update_chapter_bookmark_modified_at",
        )
        chapterColumns() shouldContainExactly chapterColumnsBefore + listOf(
            listOf("read_modified_at", "INTEGER", "1", "0"),
            listOf("bookmark_modified_at", "INTEGER", "1", "0"),
        )
    }

    @Test
    fun `the migrated columns and triggers are the ones a fresh version 6 database has`() {
        openVersion5()
        close()
        migrate()
        val migratedColumns = chapterColumns()
        val migratedTriggers = clockTriggers()
        close()

        val fresh = File(dir, "fresh.db")
        File(VERSION_6).copyTo(fresh)
        driver = JdbcSqliteDriver("jdbc:sqlite:${fresh.path}", Properties())

        migratedTriggers shouldHaveSize 2
        migratedColumns shouldContainExactly chapterColumns()
        migratedTriggers shouldContainExactly clockTriggers()
    }

    @Test
    fun `a local change of read or bookmark moves that clock alone`() {
        migratedWithOneChapter()
        val start = now()

        exec("UPDATE chapters SET read = 1 WHERE _id = 1")

        readClock() shouldBeGreaterThanOrEqual start
        bookmarkClock() shouldBe 0L

        exec("UPDATE chapters SET bookmark = 1 WHERE _id = 1")

        bookmarkClock() shouldBeGreaterThanOrEqual start
    }

    @Test
    fun `an unread and an unbookmark move the clock too, past its own last value within one second`() {
        migratedWithOneChapter()
        exec("UPDATE chapters SET read = 1, bookmark = 1 WHERE _id = 1")
        val read = readClock()
        val bookmark = bookmarkClock()

        exec("UPDATE chapters SET read = 0, bookmark = 0 WHERE _id = 1")

        readClock() shouldBeGreaterThan read
        bookmarkClock() shouldBeGreaterThan bookmark
    }

    @Test
    fun `a change beats a pulled clock that is ahead of this device's own`() {
        migratedWithOneChapter()
        val ahead = now() + 3_600_000
        exec("UPDATE chapters SET is_syncing = 1 WHERE _id = 1")
        exec("UPDATE chapters SET read = 1, read_modified_at = $ahead WHERE _id = 1")
        exec("UPDATE chapters SET is_syncing = 0 WHERE _id = 1")

        exec("UPDATE chapters SET read = 0 WHERE _id = 1")

        readClock() shouldBe ahead + 1
    }

    @Test
    fun `a write under the sync latch moves no clock, and lowering the latch moves none either`() {
        migratedWithOneChapter()
        exec("UPDATE chapters SET is_syncing = 1 WHERE _id = 1")

        exec(
            "UPDATE chapters SET read = 1, bookmark = 1, read_modified_at = 700, bookmark_modified_at = 800 WHERE _id = 1",
        )
        exec("UPDATE chapters SET is_syncing = 0 WHERE _id = 1")

        readClock() shouldBe 700L
        bookmarkClock() shouldBe 800L
    }

    @Test
    fun `an update that leaves read and bookmark as they were moves neither clock`() {
        migratedWithOneChapter()
        exec("UPDATE chapters SET read = 1, bookmark = 1 WHERE _id = 1")
        val read = readClock()
        val bookmark = bookmarkClock()

        exec("UPDATE chapters SET read = 1, bookmark = 1 WHERE _id = 1")
        exec("UPDATE chapters SET read = read, bookmark = bookmark, last_page_read = 9 WHERE _id = 1")
        exec("UPDATE chapters SET name = 'Renamed' WHERE _id = 1")

        readClock() shouldBe read
        bookmarkClock() shouldBe bookmark
    }

    @Test
    fun `an insert keeps the clocks it writes, 0 when it writes none`() {
        migratedWithOneChapter()

        exec(chapterInsert(2, extraColumns = ", read_modified_at, bookmark_modified_at", extraValues = ", 500, 600"))

        readClock() shouldBe 0L
        bookmarkClock() shouldBe 0L
        scalar("SELECT read_modified_at || ':' || bookmark_modified_at FROM chapters WHERE _id = 2") shouldBe "500:600"
    }

    @Test
    fun `a clock move logs nothing past the change it belongs to`() {
        migratedWithOneChapter()
        exec("DELETE FROM sync_change_log")

        exec("UPDATE chapters SET read = 1 WHERE _id = 1")

        rows("SELECT entity_type, entity_id FROM sync_change_log WHERE entity_type = 'chapter'", 2) shouldBe
            listOf(listOf("chapter", "1"))
        scalar("SELECT version FROM chapters WHERE _id = 1") shouldBe "1"
    }

    // Fixtures and helpers -----------------------------------------------------------------------

    /** The committed version 5 database with `user_version` set, as the app's database file was. */
    private fun openVersion5() {
        val file = File(dir, "saikou.db")
        File(VERSION_5).copyTo(file)
        driver = JdbcSqliteDriver("jdbc:sqlite:${file.path}", Properties())
        exec("PRAGMA foreign_keys = ON")
        exec("PRAGMA synchronous = OFF")
        exec("PRAGMA user_version = 5")
    }

    private fun close() = driver.close()

    /** Opens the file with the schema, which migrates a database whose version is behind and sets it. */
    private fun migrate() {
        val properties = Properties().apply { setProperty("foreign_keys", "true") }
        driver = JdbcSqliteDriver("jdbc:sqlite:${File(dir, "saikou.db").path}", properties, Database.Schema.synchronous())
    }

    /** A migrated database with one library entry and one unread, unbookmarked chapter, id 1. */
    private fun migratedWithOneChapter() {
        openVersion5()
        close()
        migrate()
        exec(
            """
            INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer,
                chapter_flags, cover_last_modified, date_added, memo)
            VALUES (1, 1, '/a', 'Title', 0, 1, 0, 0, 0, 0, 10, CAST('{}' AS BLOB))
            """,
        )
        exec(chapterInsert(id = 1))
    }

    private fun chapterInsert(id: Long, extraColumns: String = "", extraValues: String = "") =
        """
        INSERT INTO chapters(_id, manga_id, url, name, read, bookmark, last_page_read, chapter_number,
            source_order, date_fetch, date_upload, memo$extraColumns)
        VALUES ($id, 1, '/a/$id', 'Chapter $id', 0, 0, 0, $id, $id, 20, 30, CAST('{}' AS BLOB)$extraValues)
        """

    /**
     * Version 5's library from a fixed seed: [MANGAS] entries, the last outside the library;
     * chapters read, bookmarked, part read and soft-deleted, with history and a note on some, and
     * one merged group.
     */
    private fun fillVersion5() {
        val random = Random(SEED)
        var chapterId = 1L
        (1L..MANGAS).forEach { manga ->
            exec(
                """
                INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer,
                    chapter_flags, cover_last_modified, date_added, memo)
                VALUES ($manga, 1, 'https://example.invalid/manga/$manga', 'Title $manga', 0,
                    ${if (manga < MANGAS) 1 else 0}, 0, 0, 0, 0, 10, CAST('{}' AS BLOB))
                """,
            )
            repeat(random.nextInt(1, 30)) {
                val id = chapterId++
                val read = random.nextInt(3) == 0
                exec(
                    """
                    INSERT INTO chapters(_id, manga_id, url, name, read, bookmark, last_page_read,
                        chapter_number, source_order, date_fetch, date_upload, memo, deleted_at)
                    VALUES ($id, $manga, 'https://example.invalid/ch/$id', 'Chapter $id', ${if (read) 1 else 0},
                        ${if (random.nextInt(10) == 0) 1 else 0}, ${if (random.nextInt(4) == 0) random.nextInt(1, 30) else 0},
                        $id, $id, ${random.nextLong(11, 10_000)}, ${random.nextLong(0, 10_000)}, CAST('{}' AS BLOB),
                        ${if (random.nextInt(25) == 0) "${random.nextLong(1, 1_000)}" else "NULL"})
                    """,
                )
                // A later change, through version 5's triggers, so versions and the log move.
                if (random.nextInt(5) == 0) {
                    exec("UPDATE chapters SET read = 1 - read, bookmark = 1 - bookmark WHERE _id = $id")
                }
                if (read && random.nextBoolean()) {
                    exec("INSERT INTO history(chapter_id, last_read, time_read) VALUES ($id, ${random.nextLong(1, 100_000)}, 5)")
                }
                if (random.nextInt(15) == 0) {
                    exec("INSERT INTO bookmark_notes(chapter_id, page_index, note, date_added) VALUES ($id, 2, 'note', 7)")
                }
            }
        }
        exec(
            "INSERT INTO merged_groups(main_source, main_url, modified_at) VALUES (1, 'https://example.invalid/manga/1', 1000)",
        )
        exec(
            "INSERT INTO merged_members(member_source, member_url, group_id, position) " +
                "VALUES (1, 'https://example.invalid/manga/1', 1, 0), (1, 'https://example.invalid/manga/2', 1, 1)",
        )
    }

    private fun objects(): List<List<String?>> =
        rows("SELECT type, name, sql FROM sqlite_master ORDER BY type, name", 3)

    /** Name, type, not-null and default of each chapters column, in order. */
    private fun chapterColumns(): List<List<String?>> =
        rows("SELECT name, type, \"notnull\", dflt_value FROM pragma_table_info('chapters') ORDER BY cid", 4)

    private fun clockTriggers(): List<List<String?>> =
        rows("SELECT name, sql FROM sqlite_master WHERE type = 'trigger' AND name IN $CLOCK_TRIGGERS ORDER BY name", 2)

    private fun columnCount(table: String): Int =
        rows("SELECT count(*) FROM pragma_table_info('$table')", 1).single().single()!!.toInt()

    /** The first [columns] columns of every row of [table] in storage order, each as text. */
    private fun dump(table: String, columns: Int): List<List<String?>> =
        rows("SELECT * FROM $table ORDER BY rowid", columns)

    private fun readClock(): Long = scalar("SELECT read_modified_at FROM chapters WHERE _id = 1")!!.toLong()

    private fun bookmarkClock(): Long = scalar("SELECT bookmark_modified_at FROM chapters WHERE _id = 1")!!.toLong()

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
        const val VERSION_5 = "src/main/sqldelight/schemas/5.db"
        const val VERSION_6 = "src/main/sqldelight/schemas/6.db"

        const val SEED = 20_261_108L
        const val MANGAS = 30L

        const val CLOCK_TRIGGERS = "('update_chapter_read_modified_at', 'update_chapter_bookmark_modified_at')"

        val TABLES = listOf(
            "mangas", "chapters", "categories", "mangas_categories", "history", "excluded_scanlators",
            "scanlator_priority", "bookmark_notes", "library_summary", "source_alias", "sources", "manga_sync",
            "merged_groups", "merged_members",
        )
    }
}

/**
 * [objects], rows of `sqlite_master` as (type, name, sql), as they were before migrations/5.sqm ran:
 * without the two triggers it adds, and with the two columns its `ALTER TABLE` appends cut out of the
 * chapters table's text. For the earlier migrations' tests, which run on to the current version.
 */
internal fun withoutVersion6(objects: List<List<String?>>): List<List<String?>> = objects
    .filterNot { it[0] == "trigger" && it[1] in VERSION_6_TRIGGERS }
    .map { row ->
        if (row[0] != "table" || row[1] != "chapters") return@map row
        listOf(row[0], row[1], VERSION_6_COLUMNS.fold(row[2].orEmpty()) { sql, column -> sql.replace(column, "") })
    }

private val VERSION_6_TRIGGERS = setOf("update_chapter_read_modified_at", "update_chapter_bookmark_modified_at")

/** The text SQLite's `ALTER TABLE ... ADD COLUMN` inserts into the table's DDL, one column each. */
private val VERSION_6_COLUMNS = listOf(
    ", read_modified_at INTEGER NOT NULL DEFAULT 0",
    ", bookmark_modified_at INTEGER NOT NULL DEFAULT 0",
)

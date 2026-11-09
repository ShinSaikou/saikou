package com.shinsaikou.data

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.data.Database
import java.io.File
import java.util.Properties
import kotlin.random.Random

/**
 * The fourth migration, schema version 4 to 5: the merged entry tables (R163, migrations/4.sqm).
 * The database starts as the committed `schemas/4.db`, filled through version 4's own triggers, and
 * is opened the way the app opens it, by a driver that migrates a database whose `user_version` is
 * behind. Nothing but the two tables, their two indexes and their five triggers is added: no row of
 * any table moves, no other object changes, and the change log keeps exactly the rows it had. The
 * new tables then work as `merged_groups.sq` says: each write logs its group once, moves its clock
 * past its own last value, and stands aside under the group's sync latch.
 */
class MigrationToVersion5Test {

    @TempDir
    lateinit var dir: File

    private lateinit var driver: JdbcSqliteDriver

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `the migration moves no row, changes no other object and leaves user_version at 5`() {
        openVersion4()
        fillVersion4()
        val objectsBefore = objects()
        val columnsBefore = TABLES.associateWith { columnCount(it) }
        val rowsBefore = TABLES.associateWith { dump(it, columnsBefore.getValue(it)) }
        val logBefore = dump("sync_change_log")
        val libraryBefore = rows("SELECT _id, totalCount, readCount, bookmarkCount FROM libraryView ORDER BY _id", 4)
        rowsBefore.getValue("mangas").shouldNotBeEmpty()
        rowsBefore.getValue("chapters").shouldNotBeEmpty()
        logBefore.shouldNotBeEmpty()
        close()

        migrate()

        scalar("PRAGMA user_version") shouldBe Database.Schema.version.toString()
        (Database.Schema.version >= 5L) shouldBe true
        withoutVersion6(withoutVersion7(objects())).filterNot { MERGED in it[1].orEmpty() } shouldContainExactly objectsBefore
        TABLES.associateWith { dump(it, columnsBefore.getValue(it)) } shouldBe rowsBefore
        dump("sync_change_log") shouldContainExactly logBefore
        rows("SELECT _id, totalCount, readCount, bookmarkCount FROM libraryView ORDER BY _id", 4) shouldContainExactly
            libraryBefore
    }

    @Test
    fun `the migration adds exactly the two tables, two indexes and five triggers, empty`() {
        openVersion4()
        close()

        migrate()

        objects().filter { MERGED in it[1].orEmpty() }.map { it[0] to it[1] } shouldContainExactlyInAnyOrder listOf(
            "table" to "merged_groups",
            "table" to "merged_members",
            "index" to "sqlite_autoindex_merged_groups_1",
            "index" to "sqlite_autoindex_merged_members_1",
            "index" to "idx_merged_groups_is_syncing",
            "index" to "idx_merged_members_group_id_position",
            "trigger" to "insert_merged_group_change",
            "trigger" to "update_merged_group_change",
            "trigger" to "insert_merged_member_change",
            "trigger" to "update_merged_member_change",
            "trigger" to "delete_merged_member_change",
        )
        scalar("SELECT count(*) FROM merged_groups") shouldBe "0"
        scalar("SELECT count(*) FROM merged_members") shouldBe "0"
    }

    @Test
    fun `the migrated objects are the ones a fresh version 5 database has`() {
        openVersion4()
        close()
        migrate()
        val migrated = objects().filter { MERGED in it[1].orEmpty() }
        close()

        val fresh = File(dir, "fresh.db")
        File(VERSION_5).copyTo(fresh)
        driver = JdbcSqliteDriver("jdbc:sqlite:${fresh.path}", Properties())
        val committed = objects().filter { MERGED in it[1].orEmpty() }

        migrated.shouldNotBeEmpty()
        migrated shouldContainExactly committed
    }

    @Test
    fun `a group and its members log once and move the clock past its own last value after migrating`() {
        openVersion4()
        close()
        migrate()
        exec("DELETE FROM sync_change_log")

        exec("INSERT INTO merged_groups(main_source, main_url, modified_at) VALUES (1, '/a', 1000)")
        logOf("merged_group") shouldBe listOf(listOf("1", "upsert"))

        exec("INSERT INTO merged_members(member_source, member_url, group_id, position) VALUES (1, '/a', 1, 0)")
        val afterFirst = clock()
        afterFirst shouldBeGreaterThan 1000L
        exec("INSERT INTO merged_members(member_source, member_url, group_id, position) VALUES (1, '/b', 1, 1)")
        // Two writes in one second still order.
        clock() shouldBeGreaterThan afterFirst
        logOf("merged_group").size shouldBe 3
        scalar("SELECT count(DISTINCT entity_id) FROM sync_change_log WHERE entity_type = 'merged_group'") shouldBe "1"
    }

    @Test
    fun `a changed position, check_updates or show_all logs and moves the clock, and an unchanged one does not`() {
        openVersion4()
        close()
        migrate()
        exec("INSERT INTO merged_groups(main_source, main_url, modified_at) VALUES (1, '/a', 1000)")
        exec("INSERT INTO merged_members(member_source, member_url, group_id, position) VALUES (1, '/a', 1, 0)")

        fun quiet(sql: String) {
            val before = clock()
            exec("DELETE FROM sync_change_log")
            exec(sql)
            logOf("merged_group").shouldBeEmpty()
            clock() shouldBe before
        }

        fun loud(sql: String) {
            val before = clock()
            exec("DELETE FROM sync_change_log")
            exec(sql)
            logOf("merged_group") shouldBe listOf(listOf("1", "upsert"))
            clock() shouldBeGreaterThan before
        }

        quiet("UPDATE merged_members SET position = 0 WHERE member_url = '/a'")
        quiet("UPDATE merged_members SET check_updates = 1 WHERE member_url = '/a'")
        quiet("UPDATE merged_groups SET show_all = 0 WHERE _id = 1")
        loud("UPDATE merged_members SET position = 3 WHERE member_url = '/a'")
        loud("UPDATE merged_members SET check_updates = 0 WHERE member_url = '/a'")
        loud("UPDATE merged_groups SET show_all = 1 WHERE _id = 1")
        loud("DELETE FROM merged_members WHERE member_url = '/a'")
    }

    @Test
    fun `a group under the sync latch writes without a log row or a clock move`() {
        openVersion4()
        close()
        migrate()
        exec("DELETE FROM sync_change_log")

        exec("INSERT INTO merged_groups(main_source, main_url, show_all, modified_at, is_syncing) VALUES (1, '/a', 0, 1000, 1)")
        exec("INSERT INTO merged_members(member_source, member_url, group_id, position) VALUES (1, '/a', 1, 0)")
        exec("UPDATE merged_members SET position = 5 WHERE member_url = '/a'")
        exec("UPDATE merged_groups SET show_all = 1 WHERE _id = 1")
        exec("DELETE FROM merged_members WHERE member_url = '/a'")

        logOf("merged_group").shouldBeEmpty()
        clock() shouldBe 1000L
    }

    @Test
    fun `deleting a group removes its members and logs nothing, and a member needs a group`() {
        openVersion4()
        close()
        migrate()
        exec("INSERT INTO merged_groups(main_source, main_url, modified_at) VALUES (1, '/a', 1000)")
        exec("INSERT INTO merged_members(member_source, member_url, group_id, position) VALUES (1, '/a', 1, 0)")
        exec("DELETE FROM sync_change_log")

        exec("DELETE FROM merged_groups WHERE _id = 1")

        scalar("SELECT count(*) FROM merged_members") shouldBe "0"
        logOf("merged_group").shouldBeEmpty()
        shouldThrow<Exception> {
            exec("INSERT INTO merged_members(member_source, member_url, group_id, position) VALUES (1, '/x', 99, 0)")
        }
    }

    @Test
    fun `a main entry has one group and a member one place, by source and url`() {
        openVersion4()
        close()
        migrate()
        exec("INSERT INTO merged_groups(main_source, main_url, modified_at) VALUES (1, '/a', 1000)")
        exec("INSERT INTO merged_groups(main_source, main_url, modified_at) VALUES (1, '/b', 1000)")
        exec("INSERT INTO merged_members(member_source, member_url, group_id, position) VALUES (1, '/a', 1, 0)")

        shouldThrow<Exception> {
            exec("INSERT INTO merged_groups(main_source, main_url, modified_at) VALUES (1, '/a', 2000)")
        }
        shouldThrow<Exception> {
            exec("INSERT INTO merged_members(member_source, member_url, group_id, position) VALUES (1, '/a', 2, 0)")
        }
        // The same url on another source is another entry.
        exec("INSERT INTO merged_groups(main_source, main_url, modified_at) VALUES (2, '/a', 1000)")
    }

    @Test
    fun `the objects of version 4 keep working after the migration`() {
        openVersion4()
        close()
        migrate()
        exec("DELETE FROM sync_change_log")

        exec(
            """
            INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer,
                chapter_flags, cover_last_modified, date_added, memo)
            VALUES (1, 1, '/a', 'Title', 0, 1, 0, 0, 0, 0, 10, CAST('{}' AS BLOB))
            """,
        )

        logOf("manga").shouldNotBeEmpty()
        scalar("SELECT count(*) FROM libraryView") shouldBe "1"
    }

    // Fixtures and helpers -----------------------------------------------------------------------

    /** The committed version 4 database with `user_version` set, as the app's database file was. */
    private fun openVersion4() {
        val file = File(dir, "saikou.db")
        File(VERSION_4).copyTo(file)
        driver = JdbcSqliteDriver("jdbc:sqlite:${file.path}", Properties())
        exec("PRAGMA foreign_keys = ON")
        exec("PRAGMA synchronous = OFF")
        exec("PRAGMA user_version = 4")
    }

    private fun close() = driver.close()

    /** Opens the file with the schema, which migrates a database whose version is behind and sets it. */
    private fun migrate() {
        val properties = Properties().apply { setProperty("foreign_keys", "true") }
        driver = JdbcSqliteDriver("jdbc:sqlite:${File(dir, "saikou.db").path}", properties, Database.Schema.synchronous())
    }

    /**
     * Version 4's library from a fixed seed: [MANGAS] entries, the last outside the library and one
     * soft-deleted; chapters read, bookmarked, part read, soft-deleted and from two scanlators, one
     * of which a few entries exclude, with a priority, a note and history on some; two categories.
     */
    private fun fillVersion4() {
        val random = Random(SEED)
        var chapterId = 1L
        exec("INSERT INTO categories(_id, name, sort, flags, sync_key) VALUES (1, 'One', 1, 0, 'one'), (2, 'Two', 2, 0, 'two')")
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
            exec("INSERT INTO mangas_categories(manga_id, category_id) VALUES ($manga, ${1 + manga % 2})")
            if (manga % 4 == 0L) exec("INSERT INTO excluded_scanlators(manga_id, scanlator) VALUES ($manga, 'A')")
            if (manga % 5 == 0L) exec("INSERT INTO scanlator_priority(manga_id, scanlator, priority) VALUES ($manga, 'B', 1)")
            repeat(random.nextInt(1, 40)) {
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
                if (random.nextInt(15) == 0) {
                    exec("INSERT INTO bookmark_notes(chapter_id, page_index, note, date_added) VALUES ($id, 2, 'note', 7)")
                }
            }
        }
    }

    private fun objects(): List<List<String?>> =
        rows("SELECT type, name, sql FROM sqlite_master ORDER BY type, name", 3)

    private fun columnCount(table: String): Int =
        rows("SELECT count(*) FROM pragma_table_info('$table')", 1).single().single()!!.toInt()

    /**
     * Every row of [table] in storage order, each column as text: its first [columns] columns, those
     * it had before the migration, since a later version can append some (R165's chapter clocks).
     */
    private fun dump(table: String, columns: Int = columnCount(table)): List<List<String?>> =
        rows("SELECT * FROM $table ORDER BY rowid", columns)

    private fun logOf(entity: String): List<List<String?>> =
        rows("SELECT entity_id, change_type FROM sync_change_log WHERE entity_type = '$entity' ORDER BY seq", 2)

    private fun clock(): Long = scalar("SELECT modified_at FROM merged_groups WHERE _id = 1")!!.toLong()

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
        const val VERSION_4 = "src/main/sqldelight/schemas/4.db"
        const val VERSION_5 = "src/main/sqldelight/schemas/5.db"

        const val SEED = 20_261_107L
        const val MANGAS = 30L

        /** What every object this migration adds has in its name. */
        const val MERGED = "merged_"

        val TABLES = listOf(
            "mangas", "chapters", "categories", "mangas_categories", "history", "excluded_scanlators",
            "scanlator_priority", "bookmark_notes", "library_summary", "source_alias", "sources", "manga_sync",
        )
    }
}

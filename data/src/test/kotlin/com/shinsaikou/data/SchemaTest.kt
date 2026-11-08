package com.shinsaikou.data

import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import java.util.Date
import java.util.Properties
import com.shinsaikou.source.model.UpdateStrategy

/**
 * The current schema, version 6, created fresh on the JVM driver: the DDL the `.sq` files create,
 * the seeds, the foreign keys, the triggers, the views, the query plans and the column adapters
 * (05-data section 6.2). The migrations are [MigrationTest]'s, [MigrationToVersion3Test]'s,
 * [MigrationToVersion4Test]'s, [MigrationToVersion5Test]'s and [MigrationToVersion6Test]'s.
 */
class SchemaTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database

    @BeforeEach
    fun setUp() {
        // The schema goes through the driver constructor so it also stamps PRAGMA user_version.
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), Database.Schema.synchronous())
        exec("PRAGMA foreign_keys = ON")
        database = Database(
            driver = driver,
            chaptersAdapter = Chapters.Adapter(memoAdapter = MemoColumnAdapter),
            historyAdapter = History.Adapter(last_readAdapter = DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(
                genreAdapter = StringListColumnAdapter,
                update_strategyAdapter = UpdateStrategyColumnAdapter,
                memoAdapter = MemoColumnAdapter,
            ),
        )
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    // 1. Creates -------------------------------------------------------------------------------

    @Test
    fun `the schema creates at version 6 with every declared object`() {
        scalar("PRAGMA user_version") shouldBe "6"

        names("table") shouldContainExactly EXPECTED_TABLES.sorted()
        names("view") shouldContainExactly listOf("historyView", "libraryView", "updatesView")
        names("trigger") shouldContainExactly EXPECTED_TRIGGERS.sorted()
        // Autoindexes behind UNIQUE constraints are not named here; the audit accepts them.
        names("index").filter { it.startsWith("idx_") } shouldContainExactly EXPECTED_INDEXES.sorted()
        names("index").filterNot { it.startsWith("idx_") || it.startsWith("sqlite_autoindex") }
            .shouldBeEmpty()
    }

    // 2. DDL fidelity --------------------------------------------------------------------------

    @Test
    fun `every table has the columns section 2 declares, in order`() {
        val problems = EXPECTED_COLUMNS.mapNotNull { (table, expected) ->
            val actual = rows("PRAGMA table_info($table)", 6)
                .joinToString("\n") { row ->
                    "${row[1]}|${row[2]}|${row[3]}|${row[4].orEmpty()}|${row[5]}"
                }
            val wanted = expected.trimIndent().trim()
            if (actual == wanted) null else "$table:\nexpected:\n$wanted\nactual:\n$actual"
        }
        problems.shouldBeEmpty()
    }

    // 3. Seeds ---------------------------------------------------------------------------------

    @Test
    fun `the two seed rows exist and the system category cannot be removed`() {
        rows("SELECT _id, name, sort, flags FROM categories", 4) shouldContainExactly
            listOf(listOf("0", "", "-1", "0"))
        rows("SELECT _id, lang, name FROM sources", 3) shouldContainExactly
            listOf(listOf("0", "other", "Local source"))

        shouldThrowAny { exec("DELETE FROM categories WHERE _id = 0") }
        shouldThrowAny { exec("UPDATE categories SET deleted_at = 1 WHERE _id = 0") }
    }

    // 4. Foreign keys --------------------------------------------------------------------------

    @Test
    fun `an unknown manga id is rejected and a manga delete cascades everywhere but the manifest`() {
        shouldThrowAny { insertChapter(id = 99, mangaId = 404) }

        insertManga(id = 1, favorite = true)
        insertChapter(id = 10, mangaId = 1)
        exec("INSERT INTO categories(_id, name, sort, flags) VALUES (1, 'A', 0, 0)")
        exec("INSERT INTO mangas_categories(manga_id, category_id) VALUES (1, 1)")
        exec("INSERT INTO history(chapter_id, last_read, time_read) VALUES (10, 5, 5)")
        exec(
            """
            INSERT INTO manga_sync(manga_id, sync_id, remote_id, title, last_chapter_read,
                total_chapters, status, score, remote_url, start_date, finish_date)
            VALUES (1, 1, 1, 'T', 0.0, 0, 0, 0.0, 'https://example.invalid/t', 0, 0)
            """,
        )
        exec(
            """
            INSERT INTO download_queue(chapter_id, manga_id, queue_order, date_added)
            VALUES (10, 1, 0, 0)
            """,
        )
        exec("INSERT INTO excluded_scanlators(manga_id, scanlator) VALUES (1, 'X')")
        exec("INSERT INTO scanlator_priority(manga_id, scanlator, priority) VALUES (1, 'X', 0)")
        exec("INSERT INTO bookmark_notes(chapter_id, page_index, note, date_added) VALUES (10, 0, 'n', 0)")
        insertManifest(chapterId = 10, mangaId = 1)

        exec("DELETE FROM mangas WHERE _id = 1")

        scalar("SELECT count(*) FROM chapters") shouldBe "0"
        scalar("SELECT count(*) FROM history") shouldBe "0"
        scalar("SELECT count(*) FROM manga_sync") shouldBe "0"
        scalar("SELECT count(*) FROM download_queue") shouldBe "0"
        scalar("SELECT count(*) FROM mangas_categories") shouldBe "0"
        scalar("SELECT count(*) FROM excluded_scanlators") shouldBe "0"
        scalar("SELECT count(*) FROM scanlator_priority") shouldBe "0"
        scalar("SELECT count(*) FROM bookmark_notes") shouldBe "0"
        scalar("SELECT count(*) FROM downloads_manifest") shouldBe "1"
    }

    // 5. Version and change-log triggers ---------------------------------------------------------

    @Test
    fun `updating a favourite bumps its version once and logs one upsert`() {
        insertManga(id = 1, favorite = true)
        clearLog()

        exec("UPDATE mangas SET title = 'Renamed' WHERE _id = 1")

        scalar("SELECT version FROM mangas WHERE _id = 1") shouldBe "1"
        log() shouldContainExactly listOf(listOf("manga", "1", "upsert"))
    }

    @Test
    fun `a write with is_syncing set bumps nothing and logs nothing`() {
        insertManga(id = 1, favorite = true)
        clearLog()

        exec("UPDATE mangas SET title = 'Renamed', is_syncing = 1 WHERE _id = 1")

        scalar("SELECT version FROM mangas WHERE _id = 1") shouldBe "0"
        log().shouldBeEmpty()
    }

    @Test
    fun `marking a chapter read bumps both versions and logs the chapter`() {
        insertManga(id = 1, favorite = true)
        insertChapter(id = 10, mangaId = 1)
        clearLog()

        exec("UPDATE chapters SET read = 1 WHERE _id = 10")

        scalar("SELECT version FROM chapters WHERE _id = 10") shouldBe "1"
        scalar("SELECT version FROM mangas WHERE _id = 1") shouldBe "1"
        log() shouldContainExactly listOf(listOf("chapter", "10", "upsert"))
    }

    @Test
    fun `hard-deleting a chapter of a favourite logs a delete`() {
        insertManga(id = 1, favorite = true)
        insertChapter(id = 10, mangaId = 1)
        clearLog()

        exec("DELETE FROM chapters WHERE _id = 10")

        log() shouldContainExactly listOf(listOf("chapter", "10", "delete"))
    }

    @Test
    fun `inserting a manga that is not a favourite logs nothing`() {
        insertManga(id = 1, favorite = false)
        insertChapter(id = 10, mangaId = 1)

        log().shouldBeEmpty()
    }

    @Test
    fun `soft-deleting a manga logs a delete`() = runTest {
        insertManga(id = 1, favorite = true)
        clearLog()

        database.mangasQueries.softDelete(now = 1_700_000_000_000, mangaId = 1)

        log() shouldContainExactly listOf(listOf("manga", "1", "delete"))
    }

    /**
     * 05-data 2.18 expects a `ON CONFLICT REPLACE` to log a delete and then an upsert. SQLite only
     * fires delete triggers for rows the REPLACE resolution removes when `recursive_triggers` is
     * on, and it is off by default, so the replace logs the upsert alone. That is still correct for
     * the sync engine, which folds the log per entity taking the highest `seq`. Since schema
     * version 2 a track logs against its manga (phase 3 Q5), so the upsert names manga 1.
     */
    @Test
    fun `replacing a track row logs only the upsert, because REPLACE skips delete triggers`() {
        insertManga(id = 1, favorite = true)
        insertTrack(id = 5)
        clearLog()

        insertTrack(id = 6)

        log() shouldContainExactly listOf(listOf("track", "1", "upsert"))
        scalar("SELECT count(*) FROM manga_sync") shouldBe "1"
    }

    // 6. Timestamps ------------------------------------------------------------------------------

    @Test
    fun `the timestamp triggers write milliseconds`() {
        insertManga(id = 1, favorite = true)
        insertChapter(id = 10, mangaId = 1)

        exec("UPDATE mangas SET title = 'Renamed' WHERE _id = 1")
        exec("UPDATE chapters SET name = 'Renamed' WHERE _id = 10")
        exec("UPDATE mangas SET favorite = 0 WHERE _id = 1")

        scalar("SELECT last_modified_at FROM mangas WHERE _id = 1")!!.toLong()
            .shouldBeGreaterThan(1_000_000_000_000)
        scalar("SELECT last_modified_at FROM chapters WHERE _id = 10")!!.toLong()
            .shouldBeGreaterThan(1_000_000_000_000)
        scalar("SELECT favorite_modified_at FROM mangas WHERE _id = 1")!!.toLong()
            .shouldBeGreaterThan(1_000_000_000_000)
    }

    // 7. Views -----------------------------------------------------------------------------------

    @Test
    fun `libraryView counts chapters, skipping excluded scanlators`() {
        insertManga(id = 1, favorite = true)
        insertChapter(id = 10, mangaId = 1, read = true)
        insertChapter(id = 11, mangaId = 1, bookmark = true)
        insertChapter(id = 12, mangaId = 1, scanlator = "'S'")
        exec("INSERT INTO excluded_scanlators(manga_id, scanlator) VALUES (1, 'S')")

        rows("SELECT totalCount, readCount, bookmarkCount FROM libraryView", 3) shouldContainExactly
            listOf(listOf("2", "1", "1"))
    }

    @Test
    fun `updatesView holds only chapters fetched after the manga was added`() {
        insertManga(id = 1, favorite = true, dateAdded = 100)
        insertChapter(id = 10, mangaId = 1, dateFetch = 200)
        insertChapter(id = 11, mangaId = 1, dateFetch = 100)

        rows("SELECT chapterId FROM updatesView", 1) shouldContainExactly listOf(listOf("10"))
    }

    @Test
    fun `historyView returns the latest read chapter once per manga`() {
        insertManga(id = 1, favorite = true)
        insertChapter(id = 10, mangaId = 1)
        insertChapter(id = 11, mangaId = 1)
        exec("INSERT INTO history(chapter_id, last_read, time_read) VALUES (10, 100, 5)")
        exec("INSERT INTO history(chapter_id, last_read, time_read) VALUES (11, 200, 5)")

        rows(
            "SELECT chapterId FROM historyView WHERE readAt > 0 AND maxReadAtChapterId = chapterId",
            1,
        ) shouldContainExactly listOf(listOf("11"))
    }

    // 8. Query plans -------------------------------------------------------------------------------

    @Test
    fun `every named query plans, and only the documented ones scan a table`() {
        val tables = EXPECTED_TABLES.toSet()
        // A plan names an aliased table by its alias (`SCAN D`, not `SCAN downloads_manifest`), so
        // each statement's own aliases are mapped back to their tables. A scan target that is neither
        // a table nor one of those aliases (an alias inside a view's body, a form the regex does not
        // read) is collected and must be one of the few names below that are not tables at all, so
        // it fails here instead of vanishing.
        val unresolved = sortedSetOf<String>()
        val scanning = SqSources.statements
            .filter { it.name != null && !it.sql.startsWith("CREATE", ignoreCase = true) }
            .mapNotNull { statement ->
                val aliases = ALIAS.findAll(statement.sql)
                    .filter { it.groupValues[1].lowercase() in tables }
                    .associate { it.groupValues[2] to it.groupValues[1].lowercase() }
                val plan = rows("EXPLAIN QUERY PLAN ${bindDummies(statement.sql)}", 4)
                    .mapNotNull { it[3] }
                    .filterNot { VALUES_ROWS.containsMatchIn(it) }
                val targets = plan.mapNotNull { SCAN.find(it)?.groupValues?.get(1) }
                unresolved += targets.filter { it !in tables && it !in aliases }
                val scanned = targets.map { aliases[it] ?: it }
                    .filter { it in tables }
                    .distinct()
                if (scanned.isEmpty()) null else "${statement.file}:${statement.name} scans $scanned"
            }
            .sorted()

        // First, so a name the map lost is named here rather than as a missing scan below.
        unresolved shouldContainExactly NOT_TABLES
        scanning shouldContainExactly EXPECTED_SCANS.sorted()
    }

    // 9. Adapters ------------------------------------------------------------------------------

    @Test
    fun `the column adapters round-trip`() = runTest {
        val memo = Json.decodeFromString<JsonObject>("""{"a":{"b":[1,2]},"c":"d"}""")
        database.mangasQueries.insert(
            source = 1, url = "https://example.invalid/manga/1", artist = null, author = null,
            description = null, genre = emptyList(), title = "Title", status = 0,
            thumbnailUrl = null, favorite = false, lastUpdate = null, nextUpdate = null,
            initialized = false, viewer = 0, chapterFlags = 0, coverLastModified = 0, dateAdded = 0,
            updateStrategy = UpdateStrategy.ONLY_FETCH_ONCE, calculateInterval = 0,
            lastModifiedAt = 0, version = 0, notes = "", memo = memo, manualPosition = 0,
            deletedAt = null,
        )
        val manga = database.mangasQueries.getMangaById(1).awaitAsOne()
        manga.genre shouldBe emptyList()
        manga.memo shouldBe memo
        manga.update_strategy shouldBe UpdateStrategy.ONLY_FETCH_ONCE
        scalar("SELECT update_strategy FROM mangas WHERE _id = 1") shouldBe "1"

        database.chaptersQueries.insert(
            mangaId = 1, url = "https://example.invalid/ch/1", name = "Chapter 1", scanlator = null,
            read = false, bookmark = false, lastPageRead = 0, chapterNumber = 1.0, sourceOrder = 0,
            dateFetch = 0, dateUpload = 0, version = 0, memo = memo, volume = null, deletedAt = null,
        )
        database.historyQueries.upsert(chapterId = 1, readAt = null, readDuration = 0)
        database.historyQueries.getHistoryByMangaId(1).awaitAsOne().last_read shouldBe null

        database.historyQueries.upsert(chapterId = 1, readAt = Date(1_700_000_000_000), readDuration = 3)
        database.historyQueries.getHistoryByMangaId(1).awaitAsOne().last_read shouldBe
            Date(1_700_000_000_000)
    }

    // Fixtures and helpers -----------------------------------------------------------------------

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

    private fun names(type: String): List<String> =
        rows("SELECT name FROM sqlite_master WHERE type = '$type' ORDER BY name", 1)
            .map { it.single()!! }

    private fun log(): List<List<String?>> =
        rows("SELECT entity_type, entity_id, change_type FROM sync_change_log ORDER BY seq", 3)

    private fun clearLog() = exec("DELETE FROM sync_change_log")

    private fun insertManga(id: Long, favorite: Boolean, dateAdded: Long = 0) = exec(
        """
        INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer,
            chapter_flags, cover_last_modified, date_added, memo)
        VALUES ($id, 1, 'https://example.invalid/manga/$id', 'Title $id', 0, ${favorite.toSql()},
            0, 0, 0, 0, $dateAdded, CAST('{}' AS BLOB))
        """,
    )

    private fun insertChapter(
        id: Long,
        mangaId: Long,
        read: Boolean = false,
        bookmark: Boolean = false,
        scanlator: String = "NULL",
        dateFetch: Long = 0,
    ) = exec(
        """
        INSERT INTO chapters(_id, manga_id, url, name, scanlator, read, bookmark, last_page_read,
            chapter_number, source_order, date_fetch, date_upload, memo)
        VALUES ($id, $mangaId, 'https://example.invalid/ch/$id', 'Chapter $id', $scanlator,
            ${read.toSql()}, ${bookmark.toSql()}, 0, 1.0, 0, $dateFetch, 0, CAST('{}' AS BLOB))
        """,
    )

    private fun insertTrack(id: Long) = exec(
        """
        INSERT INTO manga_sync(_id, manga_id, sync_id, remote_id, title, last_chapter_read,
            total_chapters, status, score, remote_url, start_date, finish_date)
        VALUES ($id, 1, 1, 1, 'T', 0.0, 0, 0, 0.0, 'https://example.invalid/t', 0, 0)
        """,
    )

    private fun insertManifest(chapterId: Long, mangaId: Long) = exec(
        """
        INSERT INTO downloads_manifest(chapter_id, manga_id, source_id, manga_title, manga_url,
            chapter_url, chapter_name, chapter_dir_name, page_count, date_downloaded)
        VALUES ($chapterId, $mangaId, 1, 'Title', 'https://example.invalid/manga/$mangaId',
            'https://example.invalid/ch/$chapterId', 'Chapter', 'dir', 1, 0)
        """,
    )

    private fun Boolean.toSql() = if (this) 1 else 0

    /** `:name` becomes a literal so `EXPLAIN QUERY PLAN` can parse the statement unbound. */
    private fun bindDummies(sql: String): String = sql
        .replace(Regex("""\bIN\s+:\w+""", RegexOption.IGNORE_CASE), "IN (NULL)")
        .replace(Regex(""":\w+"""), "NULL")

    private companion object {
        val SCAN = Regex("""\bSCAN (\w+)""")

        /**
         * A multi-row insert's own `VALUES` tuples, `SCAN 10-ROW VALUES CLAUSE` for chapters.sq's
         * insertSyncingTen (phase 5 unit SC2): rows of the statement, not of a table, so the line is
         * not a scan this test counts.
         */
        val VALUES_ROWS = Regex("""\bSCAN \d+-ROW VALUES CLAUSE\b""")

        /**
         * A table and the alias after it, `FROM downloads_manifest D` or `JOIN chapters AS C`. The
         * alias is read in a lookahead, so an unaliased table's following JOIN is not taken as its
         * alias and consumed. A keyword read as an alias (`FROM mangas WHERE`) maps a name no plan
         * prints, which is harmless.
         */
        val ALIAS = Regex("""\b(?:FROM|JOIN)\s+(\w+)(?=\s+(?:AS\s+)?(\w+))""", RegexOption.IGNORE_CASE)

        /**
         * Scan targets that are not tables, as the JVM driver prints them: `SCAN CONSTANT ROW` for a
         * statement that reads no table, and historyView's `max_last_read` subquery.
         */
        val NOT_TABLES = listOf("CONSTANT", "max_last_read")

        /**
         * The fifteen tables of 05-data 2.1 to 2.15, the library's per-entry counts of schema
         * version 3 (phase 5 unit SC4), the merged entries of version 5 (R163), and the AUTOINCREMENT
         * bookkeeping table.
         */
        val EXPECTED_TABLES = listOf(
            "bookmark_notes", "categories", "chapters", "download_queue", "downloads_manifest",
            "excluded_scanlators", "extension_store", "history", "library_summary", "manga_sync", "mangas",
            "mangas_categories", "merged_groups", "merged_members", "scanlator_priority", "source_alias",
            "sources", "sqlite_sequence", "sync_change_log",
        )

        val EXPECTED_INDEXES = listOf(
            "idx_bookmark_notes_chapter_id",
            "idx_categories_deleted_at",
            "idx_categories_is_syncing",
            "idx_categories_parent_id",
            "idx_categories_sort",
            "idx_categories_sync_key",
            "idx_chapters_date_upload",
            "idx_chapters_deleted_at",
            "idx_chapters_is_syncing",
            "idx_chapters_manga_id_bookmark",
            "idx_chapters_manga_id_read",
            "idx_chapters_manga_id_url",
            "idx_download_queue_manga_id",
            "idx_download_queue_queue_order",
            "idx_download_queue_state",
            "idx_downloads_manifest_manga_id",
            "idx_downloads_manifest_source_id",
            "idx_excluded_scanlators_manga_id",
            "idx_excluded_scanlators_scanlator",
            "idx_history_last_read",
            "idx_manga_sync_manga_id",
            "idx_mangas_categories_category_id",
            "idx_mangas_categories_manga_id",
            "idx_mangas_deleted_at",
            "idx_mangas_favorite",
            "idx_mangas_is_syncing",
            "idx_mangas_next_update",
            "idx_mangas_source_url",
            "idx_mangas_url",
            "idx_merged_groups_is_syncing",
            "idx_merged_members_group_id_position",
            "idx_scanlator_priority_manga_id_priority",
            "idx_source_alias_new_source_id",
            "idx_sync_change_log_changed_at",
            "idx_sync_change_log_entity",
        )

        val EXPECTED_TRIGGERS = listOf(
            "bind_track_modified_at",
            "delete_bookmark_note_change",
            "delete_category_change",
            "delete_chapter_change",
            "delete_excluded_scanlator_change",
            "delete_history_change",
            "delete_manga_category_update_version",
            "delete_manga_change",
            "delete_merged_member_change",
            "delete_scanlator_priority_change",
            "delete_track_change",
            "insert_category_change",
            "insert_chapter_change",
            "insert_excluded_scanlator_change",
            "insert_manga_category_update_version",
            "insert_manga_change",
            "insert_merged_group_change",
            "insert_merged_member_change",
            "insert_scanlator_priority_change",
            "library_summary_chapter_delete",
            "library_summary_chapter_flags",
            "library_summary_chapter_insert",
            "library_summary_chapter_update",
            "library_summary_exclusion_delete",
            "library_summary_exclusion_insert",
            "library_summary_exclusion_update",
            "library_summary_history_delete",
            "library_summary_history_insert",
            "library_summary_history_update",
            "log_manga_progress_on_favorite",
            "system_category_delete_trigger",
            "system_category_soft_delete_trigger",
            "update_bookmark_note_change",
            "update_category_modified_at",
            "update_category_restored_at",
            "update_category_version",
            "update_chapter_bookmark_modified_at",
            "update_chapter_read_modified_at",
            "update_chapter_restored_at",
            "update_chapter_version",
            "update_excluded_scanlator_change",
            "update_history_change",
            "update_last_favorited_at_mangas",
            "update_last_modified_at_chapters",
            "update_last_modified_at_mangas",
            "update_manga_details_modified_at",
            "update_manga_display_modified_at",
            "update_manga_notes_modified_at",
            "update_manga_position_modified_at",
            "update_manga_restored_at",
            "update_manga_version",
            "update_manga_version_from_chapter",
            "update_merged_group_change",
            "update_merged_member_change",
            "update_scanlator_priority_change",
            "update_track_change",
            "upsert_bookmark_note_change",
            "upsert_history_change",
            "upsert_track_change",
        )

        /** `name|type|notnull|default|pk`, transcribed from 05-data section 2. */
        val EXPECTED_COLUMNS: Map<String, String> = mapOf(
            "sources" to """
                _id|INTEGER|1||1
                lang|TEXT|1||0
                name|TEXT|1||0
            """,
            "categories" to """
                _id|INTEGER|1||1
                name|TEXT|1||0
                sort|INTEGER|1||0
                flags|INTEGER|1||0
                parent_id|INTEGER|0||0
                version|INTEGER|1|0|0
                is_syncing|INTEGER|1|0|0
                deleted_at|INTEGER|0||0
                modified_at|INTEGER|0||0
                restored_at|INTEGER|0||0
                sync_key|TEXT|0||0
            """,
            "mangas" to """
                _id|INTEGER|1||1
                source|INTEGER|1||0
                url|TEXT|1||0
                artist|TEXT|0||0
                author|TEXT|0||0
                description|TEXT|0||0
                genre|TEXT|0||0
                title|TEXT|1||0
                status|INTEGER|1||0
                thumbnail_url|TEXT|0||0
                favorite|INTEGER|1||0
                last_update|INTEGER|0||0
                next_update|INTEGER|0||0
                initialized|INTEGER|1||0
                viewer|INTEGER|1||0
                chapter_flags|INTEGER|1||0
                cover_last_modified|INTEGER|1||0
                date_added|INTEGER|1||0
                update_strategy|INTEGER|1|0|0
                calculate_interval|INTEGER|1|0|0
                last_modified_at|INTEGER|1|0|0
                favorite_modified_at|INTEGER|0||0
                version|INTEGER|1|0|0
                is_syncing|INTEGER|1|0|0
                notes|TEXT|1|''|0
                memo|BLOB|1||0
                manual_position|INTEGER|1|0|0
                deleted_at|INTEGER|0||0
                display_modified_at|INTEGER|0||0
                notes_modified_at|INTEGER|0||0
                position_modified_at|INTEGER|0||0
                categories_modified_at|INTEGER|0||0
                exclusions_modified_at|INTEGER|0||0
                priority_modified_at|INTEGER|0||0
                trackers_modified_at|INTEGER|0||0
                restored_at|INTEGER|0||0
                title_override|TEXT|0||0
                description_override|TEXT|0||0
                details_modified_at|INTEGER|0||0
            """,
            "library_summary" to """
                manga_id|INTEGER|1||1
                total_count|INTEGER|1|0|0
                read_count|INTEGER|1|0|0
                bookmark_count|INTEGER|1|0|0
                latest_upload|INTEGER|1|0|0
                chapter_fetched_at|INTEGER|1|0|0
                last_read|INTEGER|1|0|0
            """,
            "mangas_categories" to """
                _id|INTEGER|1||1
                manga_id|INTEGER|1||0
                category_id|INTEGER|1||0
            """,
            "chapters" to """
                _id|INTEGER|1||1
                manga_id|INTEGER|1||0
                url|TEXT|1||0
                name|TEXT|1||0
                scanlator|TEXT|0||0
                read|INTEGER|1||0
                bookmark|INTEGER|1||0
                last_page_read|INTEGER|1||0
                chapter_number|REAL|1||0
                source_order|INTEGER|1||0
                date_fetch|INTEGER|1||0
                date_upload|INTEGER|1||0
                last_modified_at|INTEGER|1|0|0
                version|INTEGER|1|0|0
                is_syncing|INTEGER|1|0|0
                memo|BLOB|1||0
                volume|REAL|0||0
                deleted_at|INTEGER|0||0
                bookmark_notes_modified_at|INTEGER|0||0
                restored_at|INTEGER|0||0
                read_modified_at|INTEGER|1|0|0
                bookmark_modified_at|INTEGER|1|0|0
            """,
            "excluded_scanlators" to """
                manga_id|INTEGER|1||0
                scanlator|TEXT|1||0
            """,
            "history" to """
                _id|INTEGER|1||1
                chapter_id|INTEGER|1||0
                last_read|INTEGER|0||0
                time_read|INTEGER|1||0
            """,
            "manga_sync" to """
                _id|INTEGER|1||1
                manga_id|INTEGER|1||0
                sync_id|INTEGER|1||0
                remote_id|INTEGER|1||0
                library_id|INTEGER|0||0
                title|TEXT|1||0
                last_chapter_read|REAL|1||0
                total_chapters|INTEGER|1||0
                status|INTEGER|1||0
                score|REAL|1||0
                remote_url|TEXT|1||0
                start_date|INTEGER|1||0
                finish_date|INTEGER|1||0
                private|INTEGER|1|0|0
            """,
            "extension_store" to """
                index_url|TEXT|1||1
                name|TEXT|1||0
                badge_label|TEXT|1||0
                signing_key|TEXT|1||0
                contact_website|TEXT|1||0
                contact_discord|TEXT|0||0
                is_legacy|INTEGER|1||0
                extension_list_url|TEXT|0||0
            """,
            "scanlator_priority" to """
                _id|INTEGER|1||1
                manga_id|INTEGER|1||0
                scanlator|TEXT|1||0
                priority|INTEGER|1|0|0
            """,
            "merged_groups" to """
                _id|INTEGER|1||1
                main_source|INTEGER|1||0
                main_url|TEXT|1||0
                show_all|INTEGER|1|0|0
                modified_at|INTEGER|1||0
                is_syncing|INTEGER|1|0|0
            """,
            "merged_members" to """
                member_source|INTEGER|1||1
                member_url|TEXT|1||2
                group_id|INTEGER|1||0
                position|INTEGER|1||0
                check_updates|INTEGER|1|1|0
            """,
            "bookmark_notes" to """
                _id|INTEGER|1||1
                chapter_id|INTEGER|1||0
                page_index|INTEGER|1||0
                note|TEXT|1|''|0
                date_added|INTEGER|1||0
            """,
            "download_queue" to """
                _id|INTEGER|1||1
                chapter_id|INTEGER|1||0
                manga_id|INTEGER|1||0
                state|INTEGER|1|0|0
                queue_order|INTEGER|1||0
                total_pages|INTEGER|1|0|0
                downloaded_pages|INTEGER|1|0|0
                error_message|TEXT|0||0
                date_added|INTEGER|1||0
                date_started|INTEGER|0||0
                date_completed|INTEGER|0||0
            """,
            "downloads_manifest" to """
                _id|INTEGER|1||1
                chapter_id|INTEGER|1||0
                manga_id|INTEGER|1||0
                source_id|INTEGER|1||0
                manga_title|TEXT|1||0
                manga_url|TEXT|1||0
                chapter_url|TEXT|1||0
                chapter_name|TEXT|1||0
                scanlator|TEXT|0||0
                chapter_dir_name|TEXT|1||0
                page_count|INTEGER|1||0
                size_bytes|INTEGER|1|0|0
                date_downloaded|INTEGER|1||0
            """,
            "source_alias" to """
                _id|INTEGER|1||1
                old_source_id|INTEGER|1||0
                new_source_id|INTEGER|1||0
                date_added|INTEGER|1||0
            """,
            "sync_change_log" to """
                seq|INTEGER|1||1
                entity_type|TEXT|1||0
                entity_id|INTEGER|1||0
                change_type|TEXT|1||0
                changed_at|INTEGER|1||0
                key_source|INTEGER|0||0
                key_manga_url|TEXT|0||0
                key_chapter_url|TEXT|0||0
                key_category|TEXT|0||0
            """,
        )

        /**
         * The queries that scan a table, and nothing else. A whole-table read has no other plan;
         * the rest are noted individually. A new query that scans fails this test.
         */
        val EXPECTED_SCANS = listOf(
            // Whole-table reads and whole-table writes: there is nothing to narrow.
            "categories.sq:getCategories scans [categories]",
            "categories.sq:updateAllFlags scans [categories]",
            "download_queue.sq:getAll scans [download_queue]",
            "downloads_manifest.sq:countsByManga scans [downloads_manifest]",
            "downloads_manifest.sq:getAll scans [downloads_manifest]",
            "downloads_manifest.sq:getTotalSizeBytes scans [downloads_manifest]",
            "extension_store.sq:getAll scans [extension_store]",
            "extension_store.sq:getCount scans [extension_store]",
            "history.sq:getReadDuration scans [history]",
            "history.sq:removeAllHistory scans [history]",
            "library_summary.sq:clearLibrarySummary scans [library_summary]",
            "manga_sync.sq:getTracks scans [manga_sync]",
            "mangas.sq:getAllManga scans [mangas]",
            "mangas.sq:resetViewerFlags scans [mangas]",
            "merged_groups.sq:getAllGroups scans [merged_groups]",
            "merged_groups.sq:getAllMembers scans [merged_members]",
            // Every group, once: read from the members, which are few, in the index's own order, each
            // finding its entry by key. Merged entries cost what is merged, not the library.
            "merged_groups.sq:getGroupCounts scans [merged_members]",
            "merged_groups.sq:getLiveMembers scans [merged_members]",
            "source_alias.sq:getAll scans [source_alias]",
            "sources.sq:findAll scans [sources]",
            // "read = 1 OR last_page_read != 0" is a disjunction of two partial indexes, which
            // SQLite will not combine; the subquery reads every chapter once.
            "mangas.sq:deleteNonLibraryManga scans [chapters]",
            // Clear database's delete of the rows its confirmation froze keeps the same keep read
            // subquery (phase 4 unit ST), read once per batch of ids.
            "mangas.sq:deleteNonLibraryMangaByIds scans [chapters]",
            "mangas.sq:getReadMangaNotInLibrary scans [chapters]",
            // historyView aggregates over every chapter before filtering, so the aggregate feeding
            // it is a scan by construction. libraryView reads its chapter counts from
            // library_summary by key since schema version 3 (phase 5 unit SC4); the category
            // links it groups are still read whole.
            "view/historyView.sq:getLatestHistory scans [chapters]",
            "view/historyView.sq:history scans [chapters]",
            "view/libraryView.sq:library scans [mangas_categories]",
            // A LEFT JOIN kept for its null-extended rows cannot be reordered, so the aliased
            // manifest (D) is the outer loop and is read whole; the condition is on the joined side.
            "downloads_manifest.sq:getWithoutChapter scans [downloads_manifest]",
            // The same shape. The storage screen's list of downloads not in the library reads it
            // through OrphanDownloads (phase 4 unit ST), once each time the list or the Data section
            // is shown, so a read of the whole manifest is the plan it wants.
            "downloads_manifest.sq:getOrphans scans [downloads_manifest]",
            // Nothing indexes the folder name. The download engine, the copy from upstream's folder and
            // a migration read it once per folder they take (once per entry for a migration), and
            // only the matching rows are built, where each used to build the whole manifest.
            "downloads_manifest.sq:getByChapterDirNames scans [downloads_manifest]",
        )
    }
}

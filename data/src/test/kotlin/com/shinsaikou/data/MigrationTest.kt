package com.shinsaikou.data

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.shinsaikou.data.sync.SyncChangeLogRepositoryImpl
import com.shinsaikou.domain.category.CategorySyncKey
import com.shinsaikou.domain.sync.model.SyncDeletedKey
import eu.kanade.tachiyomi.util.lang.Hash
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.model.ChapterUpdate
import java.io.File
import java.util.Properties

/**
 * The first migration, schema version 1 to 2 (phase 3 unit C), on a database that starts as the
 * committed `schemas/1.db`: the schema that shipped at 49c3732. Each test fills it through the
 * version 1 triggers, as the owner's devices did, then migrates it the way the app's driver does and
 * runs [DatabaseInitializer], whose start-up transaction backfills the categories' sync keys. The
 * migration runs to the current version, so version 3's (phase 5 unit SC4) runs after it here too;
 * [MigrationToVersion3Test] is the 2 to 3 case on its own.
 */
class MigrationTest {

    @TempDir
    lateinit var dir: File

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var handler: DatabaseHandler

    /** The log as version 1 left it, read before the migration. */
    private lateinit var logBefore: List<List<String?>>
    private lateinit var countsBefore: Map<String, String?>

    @BeforeEach
    fun setUp() {
        val file = File(dir, "saikou.db")
        File(SNAPSHOT).copyTo(file)
        driver = JdbcSqliteDriver("jdbc:sqlite:${file.path}", Properties())
        exec("PRAGMA foreign_keys = ON")
        // The app's driver stamped version 1 when it created the database; the snapshot is unstamped.
        exec("PRAGMA user_version = 1")
        fillVersion1()
        logBefore = rows(LOG_SQL, 5)
        countsBefore = TABLES.associateWith { scalar("SELECT count(*) FROM $it") }

        Database.Schema.synchronous().migrate(driver, 1, Database.Schema.version).value
        exec("PRAGMA user_version = ${Database.Schema.version}")
        database = Database(
            driver = driver,
            chaptersAdapter = Chapters.Adapter(memoAdapter = MemoColumnAdapter),
            historyAdapter = History.Adapter(last_readAdapter = DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(
                genreAdapter = StringListColumnAdapter,
                update_strategyAdapter = UpdateStrategyColumnAdapter,
                memoAdapter = MemoColumnAdapter,
                genre_overrideAdapter = StringListColumnAdapter,
            ),
        )
        handler = AndroidDatabaseHandler(database, Dispatchers.Unconfined)
        runTest { DatabaseInitializer(handler, driver).initialize() }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    // The migration keeps what version 1 held ------------------------------------------------------

    @Test
    fun `the migration keeps every row and every log row, and the schema is the current version`() {
        scalar("PRAGMA user_version") shouldBe Database.Schema.version.toString()
        TABLES.associateWith { scalar("SELECT count(*) FROM $it") } shouldBe countsBefore
        rows(LOG_SQL, 5) shouldContainExactly logBefore
        logBefore.size shouldBeGreaterThan 0
        scalar(
            """
            SELECT count(*) FROM sync_change_log WHERE key_source IS NOT NULL OR key_manga_url IS NOT NULL
                OR key_chapter_url IS NOT NULL OR key_category IS NOT NULL
            """,
        ) shouldBe "0"
        // No clock moves until an edit after the migration.
        scalar(
            """
            SELECT count(*) FROM mangas WHERE coalesce(display_modified_at, notes_modified_at,
                position_modified_at, categories_modified_at, exclusions_modified_at,
                priority_modified_at, trackers_modified_at, restored_at) IS NOT NULL
            """,
        ) shouldBe "0"
        scalar("SELECT count(*) FROM chapters WHERE coalesce(bookmark_notes_modified_at, restored_at) IS NOT NULL")
            .shouldBe("0")
        scalar("SELECT count(*) FROM categories WHERE coalesce(modified_at, restored_at) IS NOT NULL") shouldBe "0"
    }

    // The backfill -------------------------------------------------------------------------------

    @Test
    fun `the backfill keys every category from its name with Kotlin's case rules`() = runTest {
        key(0).shouldBeNull()
        key(1) shouldBe CategorySyncKey.of("Reading")
        key(4) shouldBe CategorySyncKey.of("Dropped")
        key(5) shouldBe CategorySyncKey.of("ärger")
        // SQLite's lower() folds ASCII only, so a key derived in SQL would differ for this name.
        val sqlLowered = scalar("SELECT lower(name) FROM categories WHERE _id = 5")!!
        sqlLowered shouldBe "Ärger"
        key(5) shouldNotBe Hash.sha256(sqlLowered)
    }

    @Test
    fun `of a deleted and a live row with one name, the live one takes the derived key`() = runTest {
        key(3) shouldBe CategorySyncKey.of("Manhwa")
        key(3) shouldBe CategorySyncKey.of(" manhwa ")
        val deleted = key(2).shouldNotBeNull()
        deleted shouldNotBe key(3)
        deleted shouldMatch UUID

        // A second start finds nothing left to key and changes nothing.
        val keysBefore = rows("SELECT _id, sync_key FROM categories ORDER BY _id", 2)
        DatabaseInitializer(handler, driver).initialize()
        rows("SELECT _id, sync_key FROM categories ORDER BY _id", 2) shouldContainExactly keysBefore
    }

    @Test
    fun `creating a category whose key a deleted row holds restores that row under the new name`() = runTest {
        val repository = CategoryRepositoryImpl(handler)
        val deletedAt = scalar("SELECT deleted_at FROM categories WHERE _id = 4")!!.toLong()
        val version = scalar("SELECT version FROM categories WHERE _id = 4")!!.toLong()
        clearLog()

        repository.insert(Category(id = 0, name = "DROPPED", order = 9, flags = 64))

        // One re-create is one change: one upsert, one version bump.
        log() shouldContainExactly listOf(listOf("category", "4", "upsert"))
        scalar("SELECT version FROM categories WHERE _id = 4") shouldBe "${version + 1}"
        scalar("SELECT count(*) FROM categories") shouldBe "6"
        rows("SELECT name, sort, flags, deleted_at, sync_key FROM categories WHERE _id = 4", 5) shouldContainExactly
            listOf(listOf("DROPPED", "9", "64", null, CategorySyncKey.of("Dropped")))
        scalar("SELECT restored_at FROM categories WHERE _id = 4")!!.toLong() shouldBeGreaterThan deletedAt
    }

    // A category write the start-up backfill has not reached yet keys the existing rows first. Each
    // test returns the categories to the state the migration leaves before DatabaseInitializer runs.

    @Test
    fun `a create before the start-up backfill leaves the derived key on the live row`() = runTest {
        unkeyCategories()

        CategoryRepositoryImpl(handler).insert(Category(id = 0, name = "MANHWA", order = 5, flags = 0))
        DatabaseInitializer(handler, driver).initialize()

        key(3) shouldBe CategorySyncKey.of("manhwa")
        key(2).shouldNotBeNull() shouldMatch UUID
        scalar("SELECT sync_key FROM categories WHERE name = 'MANHWA'").shouldNotBeNull() shouldMatch UUID
    }

    @Test
    fun `a rename before the start-up backfill keeps the key of the name the row had`() = runTest {
        unkeyCategories()

        CategoryRepositoryImpl(handler).updateName(1, "Later")
        DatabaseInitializer(handler, driver).initialize()

        key(1) shouldBe CategorySyncKey.of("Reading")
    }

    @Test
    fun `a delete before the start-up backfill leaves the derived key where it was owed`() = runTest {
        exec("INSERT INTO categories(_id, name, sort, flags) VALUES (7, 'ärger', 6, 0)")
        unkeyCategories()

        CategoryRepositoryImpl(handler).delete(5)
        DatabaseInitializer(handler, driver).initialize()

        // Of two live rows the lower id was owed the key; deleting it afterwards would have kept it.
        key(5) shouldBe CategorySyncKey.of("ärger")
        key(7).shouldNotBeNull() shouldMatch UUID
    }

    @Test
    fun `a restore before the start-up backfill leaves the derived key on the row that was live`() = runTest {
        exec("INSERT INTO categories(_id, name, sort, flags) VALUES (8, 'dropped', 6, 0)")
        unkeyCategories()

        CategoryRepositoryImpl(handler).restore(listOf(4L))
        DatabaseInitializer(handler, driver).initialize()

        key(8) shouldBe CategorySyncKey.of("Dropped")
        key(4).shouldNotBeNull() shouldMatch UUID
    }

    // The five child tables log against their parent ---------------------------------------------

    @Test
    fun `every insert, update and delete of a child table logs its parent and moves the parent's clock`() {
        CHILD_WRITES.forEach { write ->
            clearLog()
            write.statements.forEach { (sql, movesClock) ->
                val before = scalar(write.clockSql)?.toLong()
                exec(sql)
                val after = scalar(write.clockSql)?.toLong()
                if (movesClock) after.shouldNotBeNull() shouldBeGreaterThan (before ?: 0L) else after shouldBe before
            }
            log() shouldContainExactly write.statements.map { listOf(write.entity, write.parentId, "upsert") }
        }
    }

    @Test
    fun `a child write under the parent's is_syncing logs nothing and moves no clock`() {
        exec("UPDATE mangas SET is_syncing = 1 WHERE _id = 3")
        exec("UPDATE chapters SET is_syncing = 1 WHERE _id = 30")
        clearLog()

        CHILD_WRITES.forEach { write ->
            write.statements.forEach { (sql, _) -> exec(sql) }
            scalar(write.clockSql).shouldBeNull()
        }

        log().shouldBeEmpty()
        scalar("SELECT version FROM mangas WHERE _id = 3") shouldBe "0"
    }

    // The owner's clocks -------------------------------------------------------------------------

    @Test
    fun `each clock is set by an owner edit, untouched while is_syncing = 1, and strictly increasing`() {
        OWNER_CLOCKS.forEach { clock ->
            exec(clock.latch)
            exec(clock.toggleA)
            withClue("${clock.name}, latched") { scalar(clock.clockSql).shouldBeNull() }
            exec(clock.unlatch)

            exec(clock.toggleB)
            val first = withClue("${clock.name}, first edit") { scalar(clock.clockSql).shouldNotBeNull().toLong() }
            first shouldBeGreaterThan RECENT
            exec(clock.toggleA)
            val second = scalar(clock.clockSql)!!.toLong()
            withClue("${clock.name}, second edit") { second shouldBeGreaterThan first }

            // A stored clock ahead of the wall clock stands for two edits inside one millisecond, or
            // a clock that stepped back: the next edit is previous + 1, never now.
            val ahead = second + 1_000_000_000L
            exec(clock.setClock(ahead))
            exec(clock.toggleB)
            withClue("${clock.name}, edit behind the stored clock") { scalar(clock.clockSql) shouldBe "${ahead + 1}" }
        }
    }

    @Test
    fun `trackers_modified_at moves on a bind and an unbind, never on progress`() {
        val before = scalar("SELECT trackers_modified_at FROM mangas WHERE _id = 1")
        clearLog()

        exec("UPDATE manga_sync SET last_chapter_read = 3.0 WHERE manga_id = 1")
        // A progress write through insert: the pair is bound, so REPLACE swaps the row (R82).
        exec(trackInsertSql(mangaId = 1, syncId = 1, lastChapterRead = 5.0))

        scalar("SELECT trackers_modified_at FROM mangas WHERE _id = 1") shouldBe before
        scalar("SELECT last_chapter_read FROM manga_sync WHERE manga_id = 1") shouldBe "5.0"
        log() shouldContainExactly listOf(listOf("track", "1", "upsert"), listOf("track", "1", "upsert"))

        exec(trackInsertSql(mangaId = 1, syncId = 2))
        val bound = scalar("SELECT trackers_modified_at FROM mangas WHERE _id = 1").shouldNotBeNull().toLong()
        bound shouldBeGreaterThan RECENT
        exec("DELETE FROM manga_sync WHERE manga_id = 1 AND sync_id = 2")
        scalar("SELECT trackers_modified_at FROM mangas WHERE _id = 1")!!.toLong() shouldBeGreaterThan bound
    }

    @Test
    fun `an undelete and a library add stamp the manga's restored_at past its tombstone, never under is_syncing`() {
        val tombstone = System.currentTimeMillis() + 1_000_000_000L
        exec("UPDATE mangas SET deleted_at = $tombstone, favorite = 0 WHERE _id IN (1, 3)")
        scalar("SELECT restored_at FROM mangas WHERE _id = 1").shouldBeNull()

        exec("UPDATE mangas SET deleted_at = NULL WHERE _id = 1")
        scalar("SELECT restored_at FROM mangas WHERE _id = 1") shouldBe "${tombstone + 1}"

        // A library add stamps even a live row: another device may hold a tombstone this one never saw.
        exec("UPDATE mangas SET favorite = 1 WHERE _id = 1")
        scalar("SELECT restored_at FROM mangas WHERE _id = 1") shouldBe "${tombstone + 2}"
        exec("UPDATE mangas SET favorite = 0 WHERE _id = 1")
        scalar("SELECT restored_at FROM mangas WHERE _id = 1") shouldBe "${tombstone + 2}"

        // A library add over a row that still holds a tombstone passes it too.
        exec("UPDATE mangas SET favorite = 1 WHERE _id = 3")
        scalar("SELECT restored_at FROM mangas WHERE _id = 3") shouldBe "${tombstone + 1}"

        exec("UPDATE mangas SET is_syncing = 1, favorite = 0, deleted_at = $tombstone WHERE _id = 2")
        exec("UPDATE mangas SET favorite = 1, deleted_at = NULL WHERE _id = 2")
        scalar("SELECT restored_at FROM mangas WHERE _id = 2").shouldBeNull()
    }

    @Test
    fun `a soft delete stamps deleted_at past the row's last restore on mangas, chapters and categories`() = runTest {
        val now = System.currentTimeMillis()
        val restored = now + 1_000_000_000L
        exec("UPDATE mangas SET restored_at = $restored WHERE _id = 1")
        exec("UPDATE chapters SET restored_at = $restored WHERE _id = 10")
        exec("UPDATE categories SET restored_at = $restored WHERE _id = 1")

        database.mangasQueries.softDelete(now = now, mangaId = 1)
        database.chaptersQueries.softDelete(now = now, chapterIds = listOf(10L, 30L))
        database.categoriesQueries.softDelete(now = now, categoryId = 1)
        database.categoriesQueries.softDelete(now = now, categoryId = 3)
        database.mangasQueries.softDelete(now = now, mangaId = 3)

        scalar("SELECT deleted_at FROM mangas WHERE _id = 1") shouldBe "${restored + 1}"
        scalar("SELECT deleted_at FROM chapters WHERE _id = 10") shouldBe "${restored + 1}"
        scalar("SELECT deleted_at FROM categories WHERE _id = 1") shouldBe "${restored + 1}"
        // With no restore, or one in the past, the tombstone is the app's own time.
        scalar("SELECT deleted_at FROM mangas WHERE _id = 3") shouldBe "$now"
        scalar("SELECT deleted_at FROM chapters WHERE _id = 30") shouldBe "$now"
        scalar("SELECT deleted_at FROM categories WHERE _id = 3") shouldBe "$now"
        scalar("SELECT favorite FROM mangas WHERE _id = 1") shouldBe "0"
    }

    @Test
    fun `restoring a chapter or a category stamps restored_at past the tombstone, never under is_syncing`() = runTest {
        val tombstone = System.currentTimeMillis() + 1_000_000_000L
        exec("UPDATE chapters SET deleted_at = $tombstone WHERE _id IN (30, 11)")
        exec("UPDATE categories SET deleted_at = $tombstone WHERE _id = 1")

        ChapterRepositoryImpl(handler).update(ChapterUpdate(id = 30L, clearDeletedAt = true))
        CategoryRepositoryImpl(handler).restore(listOf(1L))
        scalar("SELECT restored_at FROM chapters WHERE _id = 30") shouldBe "${tombstone + 1}"
        scalar("SELECT restored_at FROM categories WHERE _id = 1") shouldBe "${tombstone + 1}"

        // The repository's update clears is_syncing itself, so the latched clear is the sync apply's
        // own write, as plain SQL.
        exec("UPDATE chapters SET is_syncing = 1 WHERE _id = 11")
        exec("UPDATE chapters SET deleted_at = NULL WHERE _id = 11")
        scalar("SELECT deleted_at FROM chapters WHERE _id = 11").shouldBeNull()
        scalar("SELECT restored_at FROM chapters WHERE _id = 11").shouldBeNull()
    }

    // Guards and deleted keys ----------------------------------------------------------------------

    @Test
    fun `a hard delete under is_syncing logs nothing`() {
        exec("UPDATE mangas SET is_syncing = 1 WHERE _id = 1")
        exec("UPDATE chapters SET is_syncing = 1 WHERE _id IN (10, 30)")
        exec("UPDATE categories SET is_syncing = 1 WHERE _id = 5")
        clearLog()

        exec("DELETE FROM history WHERE chapter_id = 10")
        exec("DELETE FROM manga_sync WHERE manga_id = 1")
        exec("DELETE FROM bookmark_notes WHERE chapter_id = 10")
        exec("DELETE FROM chapters WHERE _id = 30")
        exec("DELETE FROM categories WHERE _id = 5")
        exec("DELETE FROM mangas WHERE _id = 1")

        log().shouldBeEmpty()
        scalar("SELECT count(*) FROM mangas WHERE _id = 1") shouldBe "0"
    }

    @Test
    fun `every hard delete the push must name records the row's natural key`() = runTest {
        val categoryKey = key(5)!!
        clearLog()

        exec("DELETE FROM history WHERE chapter_id = 10")
        exec("DELETE FROM chapters WHERE _id = 11")
        exec("DELETE FROM categories WHERE _id = 5")
        // The manga's chapter 30 cascades with it and logs nothing: the manga's delete covers it.
        exec("DELETE FROM mangas WHERE _id = 3")

        SyncChangeLogRepositoryImpl(handler).getChangesAfter(afterSeq = 0, limit = 100)
            .map { Triple(it.entity.key, it.entityId, it.deletedKey) } shouldContainExactly listOf(
            Triple("history", 10L, SyncDeletedKey.Chapter(1, mangaUrl(1), chapterUrl(10))),
            Triple("chapter", 11L, SyncDeletedKey.Chapter(1, mangaUrl(1), chapterUrl(11))),
            Triple("category", 5L, SyncDeletedKey.Category(categoryKey)),
            Triple("manga", 3L, SyncDeletedKey.Manga(1, mangaUrl(3))),
        )
    }

    // Progress before an add -------------------------------------------------------------------------

    @Test
    fun `adding a manga to the library logs every chapter that carries progress`() {
        clearLog()

        exec("UPDATE mangas SET favorite = 1 WHERE _id = 2")

        log().sortedBy { it.joinToString() } shouldContainExactly listOf(
            listOf("chapter", "20", "upsert"),
            listOf("chapter", "21", "upsert"),
            listOf("chapter", "22", "upsert"),
            listOf("manga", "2", "upsert"),
        )

        // Removing one logs the manga alone.
        clearLog()
        exec("UPDATE mangas SET favorite = 0 WHERE _id = 1")
        log() shouldContainExactly listOf(listOf("manga", "1", "upsert"))
    }

    @Test
    fun `an add written under is_syncing logs no chapter`() {
        exec("UPDATE mangas SET is_syncing = 1 WHERE _id = 2")
        clearLog()

        exec("UPDATE mangas SET favorite = 1 WHERE _id = 2")

        log().shouldBeEmpty()
    }

    // Fixtures -----------------------------------------------------------------------------------

    /**
     * Written through the version 1 schema and its triggers. Manga 1 and 3 are favourites; manga 2
     * was read from browse and never added. Categories 2 and 3 share a name in two cases and 2 is
     * deleted; 4 is deleted alone; 5 has a non-ASCII name.
     */
    private fun fillVersion1() {
        insertManga(id = 1, favorite = true)
        insertManga(id = 2, favorite = false)
        insertManga(id = 3, favorite = true)
        insertChapter(id = 10, mangaId = 1)
        insertChapter(id = 11, mangaId = 1)
        insertChapter(id = 20, mangaId = 2, read = true)
        insertChapter(id = 21, mangaId = 2, bookmark = true)
        insertChapter(id = 22, mangaId = 2, lastPageRead = 5)
        insertChapter(id = 23, mangaId = 2)
        insertChapter(id = 30, mangaId = 3)
        exec("INSERT INTO categories(_id, name, sort, flags) VALUES (1, 'Reading', 0, 0)")
        exec("INSERT INTO categories(_id, name, sort, flags, deleted_at) VALUES (2, 'Manhwa', 1, 0, 1000)")
        exec("INSERT INTO categories(_id, name, sort, flags) VALUES (3, 'manhwa', 2, 0)")
        exec("INSERT INTO categories(_id, name, sort, flags, deleted_at) VALUES (4, 'Dropped', 3, 0, 2000)")
        exec("INSERT INTO categories(_id, name, sort, flags) VALUES (5, 'ÄRGER', 4, 0)")
        exec("INSERT INTO mangas_categories(manga_id, category_id) VALUES (1, 1)")
        exec("INSERT INTO history(chapter_id, last_read, time_read) VALUES (10, 100, 5)")
        exec("INSERT INTO excluded_scanlators(manga_id, scanlator) VALUES (1, 'X')")
        exec("INSERT INTO scanlator_priority(manga_id, scanlator, priority) VALUES (1, 'X', 0)")
        exec("INSERT INTO bookmark_notes(chapter_id, page_index, note, date_added) VALUES (10, 0, 'n', 0)")
        exec(trackInsertSql(mangaId = 1, syncId = 1))
        exec("UPDATE chapters SET read = 1 WHERE _id = 11")
    }

    /** Writes to one child table of manga 3 or of its chapter 30, each flagged by whether it moves the clock. */
    private class ChildWrite(
        val entity: String,
        val parentId: String,
        val clockSql: String,
        val statements: List<Pair<String, Boolean>>,
    )

    private val CHILD_WRITES = listOf(
        ChildWrite(
            entity = "manga_category",
            parentId = "3",
            clockSql = "SELECT categories_modified_at FROM mangas WHERE _id = 3",
            statements = listOf(
                "INSERT INTO mangas_categories(manga_id, category_id) VALUES (3, 1)" to true,
                "DELETE FROM mangas_categories WHERE manga_id = 3" to true,
            ),
        ),
        ChildWrite(
            entity = "excluded_scanlator",
            parentId = "3",
            clockSql = "SELECT exclusions_modified_at FROM mangas WHERE _id = 3",
            statements = listOf(
                "INSERT INTO excluded_scanlators(manga_id, scanlator) VALUES (3, 'Y')" to true,
                "UPDATE excluded_scanlators SET scanlator = 'Z' WHERE manga_id = 3" to true,
                "DELETE FROM excluded_scanlators WHERE manga_id = 3" to true,
            ),
        ),
        ChildWrite(
            entity = "scanlator_priority",
            parentId = "3",
            clockSql = "SELECT priority_modified_at FROM mangas WHERE _id = 3",
            statements = listOf(
                "INSERT INTO scanlator_priority(manga_id, scanlator, priority) VALUES (3, 'Y', 1)" to true,
                "UPDATE scanlator_priority SET priority = 2 WHERE manga_id = 3" to true,
                "DELETE FROM scanlator_priority WHERE manga_id = 3" to true,
            ),
        ),
        ChildWrite(
            entity = "track",
            parentId = "3",
            clockSql = "SELECT trackers_modified_at FROM mangas WHERE _id = 3",
            statements = listOf(
                trackInsertSql(mangaId = 3, syncId = 2) to true,
                trackInsertSql(mangaId = 3, syncId = 2, lastChapterRead = 4.0) to false,
                "UPDATE manga_sync SET score = 7.0 WHERE manga_id = 3" to false,
                "DELETE FROM manga_sync WHERE manga_id = 3" to true,
            ),
        ),
        ChildWrite(
            entity = "bookmark_note",
            parentId = "30",
            clockSql = "SELECT bookmark_notes_modified_at FROM chapters WHERE _id = 30",
            statements = listOf(
                "INSERT INTO bookmark_notes(chapter_id, page_index, note, date_added) VALUES (30, 1, 'a', 0)" to true,
                "UPDATE bookmark_notes SET note = 'b' WHERE chapter_id = 30" to true,
                "DELETE FROM bookmark_notes WHERE chapter_id = 30" to true,
            ),
        ),
    )

    /** One owner clock: two writes that each change its value, and the latch that silences them. */
    private class OwnerClock(
        val name: String,
        private val table: String,
        private val id: Long,
        private val column: String,
        val toggleA: String,
        val toggleB: String,
    ) {
        val clockSql = "SELECT $column FROM $table WHERE _id = $id"
        val latch = "UPDATE $table SET is_syncing = 1 WHERE _id = $id"
        val unlatch = "UPDATE $table SET is_syncing = 0 WHERE _id = $id"
        fun setClock(value: Long) = "UPDATE $table SET $column = $value WHERE _id = $id"
    }

    private val OWNER_CLOCKS = listOf(
        OwnerClock(
            "display", "mangas", 3, "display_modified_at",
            toggleA = "UPDATE mangas SET viewer = 1 WHERE _id = 3",
            toggleB = "UPDATE mangas SET viewer = 2, chapter_flags = 4 WHERE _id = 3",
        ),
        OwnerClock(
            "notes", "mangas", 3, "notes_modified_at",
            toggleA = "UPDATE mangas SET notes = 'a' WHERE _id = 3",
            toggleB = "UPDATE mangas SET notes = 'b' WHERE _id = 3",
        ),
        OwnerClock(
            "position", "mangas", 3, "position_modified_at",
            toggleA = "UPDATE mangas SET manual_position = 1 WHERE _id = 3",
            toggleB = "UPDATE mangas SET manual_position = 2 WHERE _id = 3",
        ),
        OwnerClock(
            "categories", "mangas", 3, "categories_modified_at",
            toggleA = "INSERT INTO mangas_categories(manga_id, category_id) VALUES (3, 1)",
            toggleB = "DELETE FROM mangas_categories WHERE manga_id = 3",
        ),
        OwnerClock(
            "exclusions", "mangas", 3, "exclusions_modified_at",
            toggleA = "INSERT INTO excluded_scanlators(manga_id, scanlator) VALUES (3, 'Y')",
            toggleB = "DELETE FROM excluded_scanlators WHERE manga_id = 3",
        ),
        OwnerClock(
            "priority", "mangas", 3, "priority_modified_at",
            toggleA = "INSERT INTO scanlator_priority(manga_id, scanlator, priority) VALUES (3, 'Y', 1)",
            toggleB = "DELETE FROM scanlator_priority WHERE manga_id = 3",
        ),
        OwnerClock(
            "trackers", "mangas", 3, "trackers_modified_at",
            toggleA = trackInsertSql(mangaId = 3, syncId = 2),
            toggleB = "DELETE FROM manga_sync WHERE manga_id = 3",
        ),
        OwnerClock(
            "bookmark notes", "chapters", 30, "bookmark_notes_modified_at",
            toggleA = "INSERT INTO bookmark_notes(chapter_id, page_index, note, date_added) VALUES (30, 1, 'a', 0)",
            toggleB = "DELETE FROM bookmark_notes WHERE chapter_id = 30",
        ),
        OwnerClock(
            "category", "categories", 1, "modified_at",
            toggleA = "UPDATE categories SET name = 'Reading A', parent_id = 3 WHERE _id = 1",
            toggleB = "UPDATE categories SET name = 'Reading B', flags = 8, sort = 7, parent_id = NULL WHERE _id = 1",
        ),
    )

    // Helpers ------------------------------------------------------------------------------------

    private fun key(categoryId: Long): String? = scalar("SELECT sync_key FROM categories WHERE _id = $categoryId")

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

    private fun log(): List<List<String?>> =
        rows("SELECT entity_type, entity_id, change_type FROM sync_change_log ORDER BY seq", 3)

    private fun clearLog() = exec("DELETE FROM sync_change_log")

    /** The keys as the migration leaves them, before the start-up backfill; no trigger reads the column. */
    private fun unkeyCategories() = exec("UPDATE categories SET sync_key = NULL")

    private fun insertManga(id: Long, favorite: Boolean) = exec(
        """
        INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer,
            chapter_flags, cover_last_modified, date_added, memo)
        VALUES ($id, 1, '${mangaUrl(id)}', 'Title $id', 0, ${if (favorite) 1 else 0},
            0, 0, 0, 0, 0, CAST('{}' AS BLOB))
        """,
    )

    private fun insertChapter(
        id: Long,
        mangaId: Long,
        read: Boolean = false,
        bookmark: Boolean = false,
        lastPageRead: Long = 0,
    ) = exec(
        """
        INSERT INTO chapters(_id, manga_id, url, name, scanlator, read, bookmark, last_page_read,
            chapter_number, source_order, date_fetch, date_upload, memo)
        VALUES ($id, $mangaId, '${chapterUrl(id)}', 'Chapter $id', NULL, ${if (read) 1 else 0},
            ${if (bookmark) 1 else 0}, $lastPageRead, 1.0, 0, 0, 0, CAST('{}' AS BLOB))
        """,
    )

    private companion object {
        /** The version 1 schema as it shipped, relative to the module directory. */
        const val SNAPSHOT = "src/main/sqldelight/schemas/1.db"

        const val LOG_SQL =
            "SELECT seq, entity_type, entity_id, change_type, changed_at FROM sync_change_log ORDER BY seq"

        /** Epoch milliseconds in 2020: any clock a trigger writes today is past it. */
        const val RECENT = 1_577_836_800_000L

        val UUID = Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")

        val TABLES = listOf(
            "mangas", "chapters", "categories", "mangas_categories", "history", "manga_sync",
            "excluded_scanlators", "scanlator_priority", "bookmark_notes", "sync_change_log",
        )

        fun mangaUrl(id: Long) = "https://example.invalid/manga/$id"

        fun chapterUrl(id: Long) = "https://example.invalid/ch/$id"

        fun trackInsertSql(mangaId: Long, syncId: Long, lastChapterRead: Double = 0.0) =
            """
            INSERT INTO manga_sync(manga_id, sync_id, remote_id, title, last_chapter_read,
                total_chapters, status, score, remote_url, start_date, finish_date)
            VALUES ($mangaId, $syncId, 1, 'T', $lastChapterRead, 0, 0, 0.0, 'https://example.invalid/t', 0, 0)
            """
    }
}

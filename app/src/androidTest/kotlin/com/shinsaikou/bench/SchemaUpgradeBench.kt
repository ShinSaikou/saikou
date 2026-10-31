package com.shinsaikou.bench

import android.os.SystemClock
import android.util.Log
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConfiguration
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver
import com.eygraber.sqldelight.androidx.driver.SqliteJournalMode
import com.eygraber.sqldelight.androidx.driver.SqliteSync
import com.shinsaikou.SaikouApp
import com.shinsaikou.di.AppGraph
import com.shinsaikou.sync.SyncLibrarySeeder
import com.shinsaikou.util.awaitStartupPrune
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.data.Database
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.model.ChapterUpdate
import java.io.File

/**
 * The device stages of the schema version 2 to 3 upgrade (phase 5 unit SC4) that
 * [com.shinsaikou.data.SchemaUpgradeTest] does not run itself. Each returns at once with a `Skipped:`
 * line unless `saikou.upgrade.stage` names it, so a run of every class runs nothing here.
 *
 * `seed`, under the build before schema version 3 on an empty install: [SyncLibrarySeeder]'s library
 * of 2,000 entries, as `InPlaceUpgradeSeedTest` seeds it, plus what the summary and
 * `SchemaUpgradeTest` need beyond it: two soft-deleted chapters and a scanlator excluded over two
 * more on each of the first [VARIED] entries, and a deleted category holding its derived key (step
 * 8's tombstone). It then logs `SchemaUpgradeTest`'s two arguments, read the way that test reads the
 * library after the upgrade.
 *
 * `time`, under the version 3 build, after `adb install -r` over a version 2 library: opens the copy
 * of that library's database the host made before the install (`saikou.upgrade.copy`, a path) through
 * the production driver and schema, so its first statement runs the migration, then opens it again,
 * migrated; the difference is the migration's own time on that library. The application's own
 * database, which this process's start migrates too, is opened first so the two do not overlap.
 *
 * `rollback`, under the version 3 build, with the same copy argument as `time`: copies that copy,
 * plants a trigger of a name the migration creates near its end, and opens the new copy through the
 * production driver. The driver runs the create or migrate and the `user_version` write in one
 * `BEGIN IMMEDIATE` transaction and rolls it back on a throw (SC4's fix round), so the open fails and
 * the file keeps version 2 with its schema and row counts; without the planted trigger it migrates.
 *
 * `summary`, under any build: what version 3's summary triggers cost one statement on one long
 * entry (SC4's fix round), in a database of its own in the cache folder, created through the
 * production schema and then written on a bare connection of the bundled SQLite, so every trigger of
 * the schema fires as in the app and nothing else runs. Each case seeds a fresh file with one entry
 * ([SummaryCase]), times its statement in a transaction of its own, and checks the summary row
 * against `libraryView`'s old aggregate.
 */
@RunWith(AndroidJUnit4::class)
class SchemaUpgradeBench {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app: SaikouApp get() = instrumentation.targetContext.applicationContext as SaikouApp
    private val graph: AppGraph get() = app.graph

    @Test
    fun seedsAVersionTwoLibraryForTheUpgrade() {
        if (InPlaceUpgrade.stage() != STAGE_SEED) return InPlaceUpgrade.skip("seedsAVersionTwoLibraryForTheUpgrade", STAGE_SEED)
        runBlocking(Dispatchers.IO) {
            assertTrue("the library is not empty, so this is not a fresh install", graph.mangaRepository.getFavorites().isEmpty())
            // The start's prune runs on the application's scope; the figures below are taken after it.
            app.awaitStartupPrune()
            val library = SyncLibrarySeeder.seed(graph)
            library.mangaIds.take(VARIED).forEach { mangaId ->
                val chapters = graph.chapterRepository.getChapterByMangaId(mangaId).sortedBy { it.chapterNumber }
                graph.chapterRepository.softDelete(chapters.takeLast(2).map { it.id }, System.currentTimeMillis())
                chapters.drop(2).take(2).forEach {
                    graph.chapterRepository.update(ChapterUpdate(id = it.id, scanlator = EXCLUDED_SCANLATOR))
                }
                graph.database.excluded_scanlatorsQueries.insert(mangaId, EXCLUDED_SCANLATOR)
            }
            val categories = graph.categoryRepository
            categories.insert(Category(id = 0, name = TOMBSTONE, order = categories.getAll().size.toLong(), flags = 0))
            categories.delete(categories.getAll().single { it.name == TOMBSTONE }.id)

            val db = graph.database
            val mangas = db.mangasQueries.getAllManga().awaitAsList()
            var categoryRows = 0L
            (0L..MAX_CATEGORY_ID).forEach { id -> if (db.categoriesQueries.getCategory(id).awaitAsOneOrNull() != null) categoryRows++ }
            val counts = listOf(
                "mangas" to mangas.size.toLong(),
                "chapters" to mangas.sumOf {
                    db.chaptersQueries.getChaptersByMangaId(it._id, 0L).awaitAsList().size.toLong() +
                        db.chaptersQueries.getDeletedChaptersByMangaId(it._id).awaitAsList().size.toLong()
                },
                "categories" to categoryRows,
                "mangas_categories" to mangas.sumOf { db.categoriesQueries.getCategoriesByMangaId(it._id).awaitAsList().size.toLong() },
                "history" to mangas.sumOf { db.historyQueries.getHistoryByMangaId(it._id).awaitAsList().size.toLong() },
                "manga_sync" to db.manga_syncQueries.getTracks().awaitAsList().size.toLong(),
            ).joinToString(";") { (table, count) -> "$table=$count" }
            val log = db.sync_change_logQueries.getAfter(0, Long.MAX_VALUE).awaitAsList()
            Log.i(LOG_TAG, "seeded ${library.mangaIds.size} entries, $VARIED of them varied, and a deleted category")
            Log.i(LOG_TAG, "arguments: saikou.upgrade.counts=$counts saikou.upgrade.log=${log.size}:${log.maxOfOrNull { it.seq } ?: 0}")
        }
    }

    @Test
    fun timesTheMigrationOfACopy() {
        if (InPlaceUpgrade.stage() != STAGE_TIME) return InPlaceUpgrade.skip("timesTheMigrationOfACopy", STAGE_TIME)
        val path = InstrumentationRegistry.getArguments().getString(COPY_ARGUMENT)
        assertTrue("no $COPY_ARGUMENT argument naming the copy", !path.isNullOrBlank() && File(path).isFile)
        runBlocking(Dispatchers.IO) {
            // The application's own database first, so its migration, which this process's start
            // runs over the same library, is done before the copy's is timed.
            val ownStartedAt = SystemClock.elapsedRealtime()
            graph.database.categoriesQueries.getCategory(0).awaitAsOneOrNull()
            val own = SystemClock.elapsedRealtime() - ownStartedAt
            val before = version(path!!)
            val migrating = open(path)
            val migrated = open(path)
            assertEquals("the copy's version after the open", Database.Schema.version, version(path))
            Log.i(
                LOG_TAG,
                "migration of $path from version $before: the first open and statement took ${migrating.first} ms, " +
                    "the second ${migrated.first} ms, so the migration took ${migrating.first - migrated.first} ms; " +
                    "${migrated.second} summary rows (the application's own database answered in $own ms first)",
            )
        }
    }

    @Test
    fun aFailedMigrationLeavesVersionTwo() {
        if (InPlaceUpgrade.stage() != STAGE_ROLLBACK) return InPlaceUpgrade.skip("aFailedMigrationLeavesVersionTwo", STAGE_ROLLBACK)
        val path = InstrumentationRegistry.getArguments().getString(COPY_ARGUMENT)
        assertTrue("no $COPY_ARGUMENT argument naming the copy", !path.isNullOrBlank() && File(path).isFile)
        val file = File(File(path!!).parentFile, "rollback-copy.db")
        listOf("", "-wal", "-shm").forEach { File("${file.path}$it").delete() }
        listOf("", "-wal").forEach { suffix ->
            File("$path$suffix").takeIf { it.isFile }?.copyTo(File("${file.path}$suffix"))
        }
        assertEquals("the copy's version before the open", 2L, version(file.path))

        // A trigger of the name the migration creates near its end, so every statement before it
        // has run when CREATE TRIGGER fails.
        val connection = BundledSQLiteDriver().open(file.path)
        try {
            connection.execSQL("CREATE TRIGGER $PLANTED_TRIGGER AFTER UPDATE OF title ON mangas BEGIN SELECT 1; END")
        } finally {
            connection.close()
        }
        val before = schemaAndCounts(file.path)

        val failure = runCatching { runBlocking(Dispatchers.IO) { open(file.path) } }.exceptionOrNull()
        val cause = generateSequence(failure) { it.cause }.firstOrNull { it.message?.contains(PLANTED_TRIGGER) == true }
        assertTrue("the open did not fail on the planted trigger: $failure", cause != null)
        assertEquals("the version after the failed migration", 2L, version(file.path))
        assertEquals("the schema and row counts after the failed migration", before, schemaAndCounts(file.path))

        val retry = BundledSQLiteDriver().open(file.path)
        try {
            retry.execSQL("DROP TRIGGER $PLANTED_TRIGGER")
        } finally {
            retry.close()
        }
        val migrated = runBlocking(Dispatchers.IO) { open(file.path) }
        assertEquals("the version after the second open", Database.Schema.version, version(file.path))
        Log.i(
            LOG_TAG,
            "rollback: the migration failed with \"${cause?.message}\", the copy stayed version 2 with " +
                "${before.size} schema objects and row counts unchanged; without the planted trigger it migrated " +
                "to ${Database.Schema.version} with ${migrated.second} summary rows",
        )
        listOf("", "-wal", "-shm").forEach { File("${file.path}$it").delete() }
    }

    /** Every schema object's text, then each table's row count, read on a bare connection of the bundled SQLite. */
    private fun schemaAndCounts(path: String): List<String> {
        val connection = BundledSQLiteDriver().open(path)
        try {
            val objects = connection
                .prepare("SELECT type || ' ' || name || ': ' || coalesce(sql, '') FROM sqlite_master ORDER BY type, name")
                .use { select -> buildList { while (select.step()) add(select.getText(0)) } }
            val tables = connection.prepare("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")
                .use { select -> buildList { while (select.step()) add(select.getText(0)) } }
            return objects + tables.map { table ->
                connection.prepare("SELECT count(*) FROM \"$table\"").use { select ->
                    select.step()
                    "$table=${select.getLong(0)}"
                }
            }
        } finally {
            connection.close()
        }
    }

    @Test
    fun timesTheSummaryTriggersOnALongEntry() {
        if (InPlaceUpgrade.stage() != STAGE_SUMMARY) return InPlaceUpgrade.skip("timesTheSummaryTriggersOnALongEntry", STAGE_SUMMARY)
        val file = File(instrumentation.targetContext.cacheDir, "summary-bench.db")
        SummaryCase.entries.forEach { case ->
            val runs = (1..SUMMARY_RUNS).map { summaryRun(file, case) }
            val millis = runs.map { it.first }.sorted()
            val exact = runs.all { it.second }
            Log.i(
                LOG_TAG,
                "summary bench: ${case.label}: median ${millis[millis.size / 2]} ms of $millis, " +
                    "the summary ${if (exact) "equals" else "DIFFERS FROM"} the aggregate",
            )
            assertTrue("${case.label}: the summary is not the aggregate", exact)
        }
        listOf("", "-wal", "-shm").forEach { File("${file.path}$it").delete() }
    }

    /** One fresh file, one seeded entry, [case]'s statement timed; the time and whether the summary is exact. */
    private fun summaryRun(file: File, case: SummaryCase): Pair<Long, Boolean> {
        listOf("", "-wal", "-shm").forEach { File("${file.path}$it").delete() }
        runBlocking(Dispatchers.IO) { open(file.path) }
        val connection = BundledSQLiteDriver().open(file.path)
        try {
            connection.execSQL("PRAGMA foreign_keys = ON")
            connection.execSQL("BEGIN")
            connection.execSQL(
                "INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer, chapter_flags, " +
                    "cover_last_modified, date_added, memo) VALUES (1, 1, '/m', 'Bench', 0, 1, 1, 0, 0, 0, 0, x'')",
            )
            connection.prepare(
                "INSERT INTO chapters(manga_id, url, name, scanlator, read, bookmark, last_page_read, chapter_number, " +
                    "source_order, date_fetch, date_upload, memo) VALUES (1, ?, ?, ?, 0, 0, 0, ?, ?, ?, ?, x'')",
            ).use { insert ->
                repeat(case.chapters) { i ->
                    insert.bindText(1, "/c$i")
                    insert.bindText(2, "Chapter $i")
                    insert.bindText(3, if (i % EXCLUDED_EVERY == 0) "Group B" else "Group A")
                    insert.bindDouble(4, i.toDouble())
                    insert.bindLong(5, i.toLong())
                    insert.bindLong(6, 1_000L + i)
                    insert.bindLong(7, 2_000L + i)
                    insert.step()
                    insert.reset()
                }
            }
            connection.execSQL("INSERT INTO excluded_scanlators(manga_id, scanlator) VALUES (1, 'Group B')")
            case.history?.let { lastRead ->
                val ids = connection.prepare("SELECT _id FROM chapters WHERE manga_id = 1 ORDER BY _id").use { select ->
                    buildList { while (select.step()) add(select.getLong(0)) }
                }
                connection.prepare("INSERT INTO history(chapter_id, last_read, time_read) VALUES (?, ?, 0)").use { insert ->
                    ids.forEachIndexed { index, id ->
                        insert.bindLong(1, id)
                        insert.bindLong(2, lastRead(index))
                        insert.step()
                        insert.reset()
                    }
                }
            }
            connection.execSQL("COMMIT")

            val startedAt = SystemClock.elapsedRealtime()
            connection.execSQL("BEGIN")
            connection.execSQL(case.statement)
            connection.execSQL("COMMIT")
            val millis = SystemClock.elapsedRealtime() - startedAt

            val summary = connection.prepare(
                "SELECT total_count, read_count, bookmark_count, latest_upload, chapter_fetched_at, last_read " +
                    "FROM library_summary WHERE manga_id = 1",
            ).use { select -> if (select.step()) (0..5).map { select.getLong(it) } else emptyList() }
            val aggregate = connection.prepare(OLD_AGGREGATE).use { select ->
                if (select.step()) (0..5).map { select.getLong(it) } else emptyList()
            }
            return millis to (summary == aggregate)
        } finally {
            connection.close()
        }
    }

    /** One entry of [chapters] chapters (every tenth of an excluded scanlator) and the statement timed over it. */
    private enum class SummaryCase(val label: String, val chapters: Int, val statement: String, val history: ((Int) -> Long)?) {
        READ_1000("read-1000", 1_000, MARK_READ, null),
        READ_5000("read-5000", 5_000, MARK_READ, null),
        BOOKMARK_5000("bookmark-5000", 5_000, "UPDATE chapters SET bookmark = 1 WHERE manga_id = 1", null),

        /** History on every chapter, last_read in a fixed shuffled order. */
        HISTORY_DELETE_2000("history-delete-2000", 2_000, DELETE_HISTORY, { index -> 1_000_000L + SHUFFLED[index] }),

        /** last_read falling as the rows go in: each delete removes the current latest. */
        HISTORY_DELETE_2000_DESC("history-delete-2000-desc", 2_000, DELETE_HISTORY, { index -> 1_000_000L - index }),

        /** `resetHistoryByMangaId`'s shape over the entry's history, last_read shuffled. */
        HISTORY_RESET_2000(
            "history-reset-2000", 2_000,
            "UPDATE history SET last_read = 0, time_read = 0 WHERE chapter_id IN (SELECT _id FROM chapters WHERE manga_id = 1)",
            { index -> 1_000_000L + SHUFFLED[index] },
        ),

        /** `removeResettedHistory`, the start-up sweep's delete, after such a reset. */
        HISTORY_DELETE_ZEROS_2000("history-delete-zeros-2000", 2_000, "DELETE FROM history WHERE last_read = 0", { 0L }),
    }

    /** Opens [path] as the application opens its database and runs one statement; the time and the summary's rows. */
    private suspend fun open(path: String): Pair<Long, Long> {
        val startedAt = SystemClock.elapsedRealtime()
        val driver = driver(path)
        try {
            val rows = driver.executeQuery(null, "SELECT count(*) FROM library_summary", { cursor ->
                QueryResult.AsyncValue { cursor.next().await(); cursor.getLong(0) ?: 0L }
            }, 0).await()
            return SystemClock.elapsedRealtime() - startedAt to rows
        } finally {
            driver.close()
        }
    }

    /**
     * `PRAGMA user_version` of [path], read on a bare connection of the bundled SQLite so nothing
     * migrates. The framework's SQLite could not open the copy on API 26 (`SQLITE_CANTOPEN`).
     */
    private fun version(path: String): Long {
        val connection = BundledSQLiteDriver().open(path)
        try {
            return connection.prepare("PRAGMA user_version").use { statement ->
                statement.step()
                statement.getLong(0)
            }
        } finally {
            connection.close()
        }
    }

    /** `DatabaseBindings.provideSqlDriver`'s driver and configuration over a file path. */
    private fun driver(path: String): SqlDriver = AndroidxSqliteDriver(
        driver = BundledSQLiteDriver(),
        databaseType = AndroidxSqliteDatabaseType.File(path),
        schema = Database.Schema,
        configuration = AndroidxSqliteConfiguration(
            isForeignKeyConstraintsEnabled = true,
            journalMode = SqliteJournalMode.WAL,
            sync = SqliteSync.Normal,
        ),
    )

    private companion object {
        const val LOG_TAG = "SaikouSchemaUpgrade"
        const val STAGE_SEED = "seed"
        const val STAGE_TIME = "time"
        const val STAGE_SUMMARY = "summary"
        const val STAGE_ROLLBACK = "rollback"

        /** The trigger 2.sqm creates after the summary, its triggers and the new manga version trigger. */
        const val PLANTED_TRIGGER = "update_manga_details_modified_at"
        const val SUMMARY_RUNS = 3
        const val EXCLUDED_EVERY = 10
        const val MARK_READ = "UPDATE chapters SET read = 1 WHERE manga_id = 1"
        const val DELETE_HISTORY = "DELETE FROM history WHERE chapter_id IN (SELECT _id FROM chapters WHERE manga_id = 1)"
        val SHUFFLED: List<Int> = (0 until 2_000).shuffled(kotlin.random.Random(20_261_011))

        /** `libraryView`'s aggregate before schema version 3, for the one entry. */
        const val OLD_AGGREGATE = """
            SELECT count(C._id), coalesce(sum(C.read), 0), coalesce(sum(C.bookmark), 0),
                coalesce(max(C.date_upload), 0), coalesce(max(C.date_fetch), 0), coalesce(max(H.last_read), 0)
            FROM mangas M
            LEFT JOIN chapters C ON C.manga_id = M._id AND C.deleted_at IS NULL AND (
                C.scanlator IS NULL
                OR C.scanlator NOT IN (SELECT ES.scanlator FROM excluded_scanlators ES WHERE ES.manga_id = C.manga_id))
            LEFT JOIN history H ON H.chapter_id = C._id
            WHERE M._id = 1 GROUP BY M._id
        """
        const val COPY_ARGUMENT = "saikou.upgrade.copy"
        const val VARIED = 20
        const val EXCLUDED_SCANLATOR = "Upgrade check group"
        const val TOMBSTONE = "Upgrade check tombstone"
        const val MAX_CATEGORY_ID = 10_000L
    }
}

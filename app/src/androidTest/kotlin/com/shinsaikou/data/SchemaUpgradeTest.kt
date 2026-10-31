package com.shinsaikou.data

import android.Manifest
import android.os.Build
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.shinsaikou.SaikouApp
import com.shinsaikou.di.AppGraph
import com.shinsaikou.domain.category.CategorySyncKey
import com.shinsaikou.domain.sync.model.SyncChangeType
import com.shinsaikou.domain.sync.model.SyncEntity
import com.shinsaikou.sync.SyncPreferences
import com.shinsaikou.ui.main.MainActivity
import com.shinsaikou.util.awaitStartupPrune
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.data.Categories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.manga.model.MangaUpdate

/**
 * An upgrade of the schema on a device, by this build installed with `adb install -r` over a library
 * an older build wrote, whose start migrated the database. The instrumentation launches the
 * application, so the migration under test is the one `SaikouApp` runs, through the production
 * driver. Two upgrades have been driven:
 * - version 1 to 2 (phase 3 unit C): a library a 49c3732 build wrote; the start also backfilled the
 *   categories' sync keys;
 * - version 2 to 3 (phase 5 unit SC4): a library the build before schema version 3 wrote through
 *   `SchemaUpgradeBench`'s `seed` stage, which logs this test's two arguments.
 *
 * The expected figures come from the database as the old build left it, read on the host before
 * the upgrade and passed as arguments:
 * - `saikou.upgrade.counts`: `table=count` pairs separated by `;` for `mangas`, `chapters`,
 *   `categories`, `mangas_categories`, `history` and `manga_sync`.
 * - `saikou.upgrade.log`: `<rows>:<highest seq>` of `sync_change_log`.
 *
 * With no sync backend configured, which is the fixture's case and the owner's devices' before their
 * first sign-in, the application's start prunes the whole change log after the migration and the
 * backfill (E2's prune, R87, kept by the coordinator's ruling in unit I's fix round 1), so step 2
 * checks the prune's outcome instead of the rows the old build logged. That the migration keeps
 * those rows and logs none with a deleted key was proved on both AVDs with the prune left out
 * (unit I, `evidence/phase3-sync-rows/device-session-c/schema-noprune-*.txt`).
 *
 * Without them the test logs a `Skipped:` line and returns, and it reads them straight from the
 * runner's arguments rather than through [com.shinsaikou.util.Args], because under `saikou.strict` a
 * gate run, which never upgrades anything, must not fail for their absence. It then edits the
 * library it checked (a rename, a category move, a read, a favourite flip and a category re-created
 * over a deleted one), so it belongs only on a device set up for it.
 */
@RunWith(AndroidJUnit4::class)
class SchemaUpgradeTest {

    /** Step 9 opens [MainActivity], which asks for `POST_NOTIFICATIONS`; the dialog would pause it. */
    @get:Rule
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(*NOTIFICATION_PERMISSIONS)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private val app: SaikouApp get() = instrumentation.targetContext.applicationContext as SaikouApp
    private val graph: AppGraph get() = app.graph

    @Test
    fun anUpgradedLibraryKeepsItsRowsAndLogsAsPhaseThreeDecides(): Unit = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val counts = arguments.getString(COUNTS_ARGUMENT)
        val log = arguments.getString(LOG_ARGUMENT)
        if (counts.isNullOrBlank() || log.isNullOrBlank()) {
            // Spelled out, because gate.sh's tablet step expects this line and a tools test reads it here.
            Log.i(
                LOG_TAG,
                "Skipped: anUpgradedLibraryKeepsItsRowsAndLogsAsPhaseThreeDecides: no saikou.upgrade.counts " +
                    "or saikou.upgrade.log argument",
            )
            return@runBlocking
        }
        val db = graph.database
        val expected = counts.split(';').associate { pair ->
            val (table, count) = pair.split('=')
            table.trim() to count.trim().toLong()
        }

        // The start-up backfill runs on the application's scope; wait for it rather than run it.
        val categories = awaitKeyedCategories(expected.getValue("categories").toInt())

        // 1. The library the old build wrote is all here.
        val mangas = db.mangasQueries.getAllManga().awaitAsList()
        val chapters = mangas.flatMap { manga ->
            db.chaptersQueries.getChaptersByMangaId(manga._id, 0L).awaitAsList()
                .map { ChapterRow(it._id, it.manga_id, it.read, it.deleted_at) } +
                db.chaptersQueries.getDeletedChaptersByMangaId(manga._id).awaitAsList()
                    .map { ChapterRow(it._id, it.manga_id, it.read, it.deleted_at) }
        }
        // Every membership is to a live category: a category delete drops its memberships.
        val memberships = mangas.sumOf {
            db.categoriesQueries.getCategoriesByMangaId(it._id).awaitAsList().size.toLong()
        }
        val actual = mapOf(
            "mangas" to mangas.size.toLong(),
            "chapters" to chapters.size.toLong(),
            "categories" to categories.size.toLong(),
            "mangas_categories" to memberships,
            "history" to mangas.sumOf { db.historyQueries.getHistoryByMangaId(it._id).awaitAsList().size.toLong() },
            "manga_sync" to db.manga_syncQueries.getTracks().awaitAsList().size.toLong(),
        )
        assertEquals("the library after the upgrade", expected, actual)
        Log.i(LOG_TAG, "counts after the upgrade: $actual")

        // 1b. Schema version 3 (phase 5 unit SC4): every library entry's counts, which the view reads
        // from library_summary, are the old view's aggregate, worked out here from the entry's rows:
        // its live chapters less those of an excluded scanlator, and their latest history.
        val library = db.libraryViewQueries.library().awaitAsList()
        val wrong = library.mapNotNull { entry ->
            val counted = db.chaptersQueries.getChaptersByMangaId(entry._id, 1L).awaitAsList()
            val ids = counted.mapTo(HashSet()) { it._id }
            val lastRead = db.historyQueries.getHistoryByMangaId(entry._id).awaitAsList()
                .filter { it.chapter_id in ids }
                .maxOfOrNull { it.last_read?.time ?: 0L } ?: 0L
            val aggregate = listOf(
                counted.size.toLong(),
                counted.count { it.read }.toLong(),
                counted.count { it.bookmark }.toLong(),
                counted.maxOfOrNull { it.date_upload } ?: 0L,
                counted.maxOfOrNull { it.date_fetch } ?: 0L,
                lastRead,
            )
            val summary = listOf(
                entry.totalCount, entry.readCount, entry.bookmarkCount, entry.latestUpload, entry.chapterFetchedAt, entry.lastRead,
            )
            if (summary == aggregate) null else "${entry._id}: summary $summary, aggregate $aggregate"
        }
        assertTrue("library entries whose counts are not the aggregate: ${wrong.take(WRONG_SHOWN)}", wrong.isEmpty())
        assertTrue("the library is empty", library.isNotEmpty())
        Log.i(LOG_TAG, "the summary is the aggregate for all ${library.size} library entries")

        // 2. With no backend configured, the start's prune left no log row at or below the old
        // build's latest seq, and nothing marks the log exported. With one, the log kept every row it
        // had, and version 1's rows carry no deleted key.
        val (logRows, logMaxSeq) = log.split(':').map { it.trim().toLong() }
        val sync = SyncPreferences(graph.preferenceStore)
        if (sync.backendId.get().isEmpty()) {
            app.awaitStartupPrune()
            val left = db.sync_change_logQueries.getAfter(0, LIMIT).awaitAsList().map { it.seq }.filter { it <= logMaxSeq }
            assertTrue("the start's prune left log rows up to seq $logMaxSeq: $left", left.isEmpty())
            assertEquals("the log is marked exported to", "", sync.exportedBackend.get())
            assertNull("the log is marked mid-export", sync.exportProgress.get())
            Log.i(LOG_TAG, "the change log after the start's prune: no row up to seq $logMaxSeq, which held $logRows")
        } else {
            val kept = db.sync_change_logQueries.getAfter(0, Long.MAX_VALUE).awaitAsList().filter { it.seq <= logMaxSeq }
            assertEquals("the change log rows up to seq $logMaxSeq", logRows, kept.size.toLong())
            assertTrue(
                "a version 1 log row carries a deleted key",
                kept.all {
                    it.key_source == null && it.key_manga_url == null && it.key_chapter_url == null &&
                        it.key_category == null
                },
            )
        }

        // 3. Every category but the system one is keyed, and a name shared by several rows gives its
        // derived key to one of them, a live one when there is one.
        assertNull("the system category was keyed", categories.single { it._id == 0L }.sync_key)
        val keyed = categories.filter { it._id != 0L }
        assertEquals("sync keys are not unique", keyed.size, keyed.map { it.sync_key }.toSet().size)
        keyed.groupBy { CategorySyncKey.of(it.name) }.forEach { (derived, rows) ->
            val holders = rows.filter { it.sync_key == derived }
            assertEquals("rows named ${rows.map { it.name }} hold their derived key", 1, holders.size)
            if (rows.any { it.deleted_at == null }) {
                assertNull("a deleted row holds the key a live one shares", holders.single().deleted_at)
            }
        }
        Log.i(LOG_TAG, "categories keyed: ${keyed.map { "${it._id}:${it.name}:${it.deleted_at != null}" }}")

        // 4. A category edit logs the category and moves its clock; the key stays.
        val category = keyed.first { it.deleted_at == null }
        var cursor = graph.syncChangeLogRepository.getLatestSeq()
        graph.categoryRepository.updateName(category._id, "${category.name} upgraded")
        var changes = graph.syncChangeLogRepository.getChangesAfter(cursor, LIMIT)
        assertEquals(
            listOf(Triple(SyncEntity.CATEGORY, category._id, SyncChangeType.UPSERT)),
            changes.map { Triple(it.entity, it.entityId, it.changeType) },
        )
        val renamed = db.categoriesQueries.getCategory(category._id).awaitAsOne()
        assertNotNull("the rename stamped no modified_at", renamed.modified_at)
        assertEquals("the rename changed the sync key", category.sync_key, renamed.sync_key)

        // 5. Moving a favourite into it logs the manga's category set and stamps its clock.
        // A favourite with an unread chapter to read, whose other chapters carry progress.
        val manga = mangas.first { entry ->
            entry.favorite && entry.deleted_at == null &&
                chapters.any { it.mangaId == entry._id && !it.read && it.deletedAt == null }
        }
        cursor = graph.syncChangeLogRepository.getLatestSeq()
        graph.mangaRepository.setMangaCategories(manga._id, listOf(category._id))
        changes = graph.syncChangeLogRepository.getChangesAfter(cursor, LIMIT)
        assertTrue(
            "a category move logged $changes",
            changes.isNotEmpty() && changes.all { it.entity == SyncEntity.MANGA_CATEGORY && it.entityId == manga._id },
        )
        assertNotNull(
            "the move stamped no categories_modified_at",
            db.mangasQueries.getMangaById(manga._id).awaitAsOne().categories_modified_at,
        )

        // 6. A chapter read logs the chapter.
        val chapter = chapters.first { it.mangaId == manga._id && !it.read && it.deletedAt == null }
        cursor = graph.syncChangeLogRepository.getLatestSeq()
        graph.chapterRepository.update(ChapterUpdate(id = chapter.id, read = true))
        changes = graph.syncChangeLogRepository.getChangesAfter(cursor, LIMIT)
        assertEquals(
            listOf(Triple(SyncEntity.CHAPTER, chapter.id, SyncChangeType.UPSERT)),
            changes.map { Triple(it.entity, it.entityId, it.changeType) },
        )

        // 7. Taking the manga out of the library and adding it back logs every chapter it holds, soft
        // deleted ones included: the migrated trigger the chapters with progress, the repository's
        // statement the rest (phase 4 unit NB), each once.
        graph.mangaRepository.update(MangaUpdate(id = manga._id, favorite = false))
        cursor = graph.syncChangeLogRepository.getLatestSeq()
        graph.mangaRepository.update(MangaUpdate(id = manga._id, favorite = true))
        changes = graph.syncChangeLogRepository.getChangesAfter(cursor, LIMIT)
        val held = db.chaptersQueries.syncGetByMangaId(manga._id).awaitAsList()
        val withProgress = held.count { it.read || it.bookmark || it.last_page_read != 0L }
        assertTrue("the manga has no chapter with progress to log", withProgress > 0)
        val logged = changes.filter { it.entity == SyncEntity.CHAPTER }.map { it.entityId }
        assertEquals("the chapters the add logged", held.map { it._id }.sorted(), logged.sorted())
        assertTrue(
            "the add did not log the manga",
            changes.any { it.entity == SyncEntity.MANGA && it.entityId == manga._id },
        )
        Log.i(LOG_TAG, "the add of manga ${manga._id} logged ${held.size} chapters, $withProgress with progress")

        // 8. Creating a category over a deleted row's key restores that row, as one change: one
        // upsert and one version bump through the migrated trigger.
        val tombstone = keyed.first { deleted ->
            deleted.deleted_at != null && deleted.sync_key == CategorySyncKey.of(deleted.name)
        }
        cursor = graph.syncChangeLogRepository.getLatestSeq()
        graph.categoryRepository.insert(
            Category(id = 0, name = tombstone.name, order = keyed.size.toLong(), flags = tombstone.flags),
        )
        changes = graph.syncChangeLogRepository.getChangesAfter(cursor, LIMIT)
        assertEquals(
            listOf(Triple(SyncEntity.CATEGORY, tombstone._id, SyncChangeType.UPSERT)),
            changes.map { Triple(it.entity, it.entityId, it.changeType) },
        )
        val recreated = db.categoriesQueries.getCategory(tombstone._id).awaitAsOne()
        assertNull("the re-create left the row deleted", recreated.deleted_at)
        assertNotNull("the re-create stamped no restored_at", recreated.restored_at)
        assertEquals("the re-create's version bumps", tombstone.version + 1, recreated.version)
        Log.i(LOG_TAG, "re-creating ${tombstone.name} restored category ${tombstone._id} as one change")

        // 9. The app opens on the upgraded library, and the library's items are every entry the view
        // holds. The battery prompt and the crash prompt are answered, as the device rows answer them.
        graph.basePreferences.batteryExemptionRequested.set(true)
        graph.crashLog.markExported()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals("the main screen did not open", Lifecycle.State.RESUMED, scenario.state)
            val items = graph.getLibraryItems.subscribe().first()
            assertEquals("the library's items", db.libraryViewQueries.library().awaitAsList().size, items.size)
            Log.i(LOG_TAG, "the app opened on ${items.size} library entries")
        }
    }

    /**
     * Every category row, deleted ones included, once the start-up sweep has keyed them, or a
     * failure after [KEY_TIMEOUT_MILLIS]. No query lists deleted categories, so the rows are read by
     * id until [expected] are found; ids are rowids, so no row lies past the last id tried.
     */
    private suspend fun awaitKeyedCategories(expected: Int): List<Categories> {
        val deadline = System.currentTimeMillis() + KEY_TIMEOUT_MILLIS
        while (true) {
            val all = mutableListOf<Categories>()
            var id = 0L
            while (all.size < expected && id <= MAX_CATEGORY_ID) {
                graph.database.categoriesQueries.getCategory(id++).awaitAsOneOrNull()?.let { all += it }
            }
            if (all.none { it._id != 0L && it.sync_key == null }) return all
            assertTrue("the start-up backfill left categories unkeyed: $all", System.currentTimeMillis() < deadline)
            delay(POLL_MILLIS)
        }
    }

    /** The columns this test reads of a chapter, live or deleted, whichever query returned it. */
    private data class ChapterRow(val id: Long, val mangaId: Long, val read: Boolean, val deletedAt: Long?)

    private companion object {
        const val LOG_TAG = "SaikouSchemaUpgrade"
        const val COUNTS_ARGUMENT = "saikou.upgrade.counts"
        const val LOG_ARGUMENT = "saikou.upgrade.log"
        const val LIMIT = 5_000L
        const val KEY_TIMEOUT_MILLIS = 60_000L
        const val POLL_MILLIS = 250L
        const val MAX_CATEGORY_ID = 10_000L
        const val WRONG_SHOWN = 10

        /** Empty below API 33, where `POST_NOTIFICATIONS` is not a runtime permission. */
        val NOTIFICATION_PERMISSIONS: Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                emptyArray()
            }
    }
}

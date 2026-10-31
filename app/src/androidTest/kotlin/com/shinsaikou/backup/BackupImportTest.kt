package com.shinsaikou.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shinsaikou.SaikouApp
import com.shinsaikou.backup.importer.ImportError
import com.shinsaikou.backup.importer.ImportReport
import com.shinsaikou.di.AppGraph
import com.shinsaikou.tracking.TrackerIds
import com.shinsaikou.util.Args
import com.shinsaikou.util.Fixtures
import eu.kanade.tachiyomi.data.backup.BackupDecoder
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import logcat.logcat
import okio.source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.Preference
import java.io.File

/**
 * The owner's real backup, restored into a real database on a device. Contract 01 section 8.3.
 *
 * `:backup` already has unit tests over a synthetic file built in the test, which prove the decoder
 * and the batching against data the test wrote itself. What they cannot prove is the thing this
 * one exists for: that a file written by another application, years of it, with categories, source
 * settings, tracker rows and preference keys from a version of that application nobody here has
 * read, lands in Saikou's schema with nothing lost. The only honest oracle for that is the file, so
 * every count asserted below is read out of the file rather than written down here.
 *
 * The file is decoded twice on purpose. The importer decodes it to restore it; the test decodes it
 * to know what "restored" should mean. A single decode shared between the two would make the
 * importer's own reading of the file the standard its result is judged against, which is no
 * standard at all.
 *
 * ### What it changes on the device
 *
 * Everything. A restore is a restore: the device's library, categories, preferences and index rows
 * are the owner's afterwards. That is intentional, and it is why the test hangs off
 * `saikou.backup.file`, which only the gate supplies. Without the argument the body returns before
 * it touches anything, which keeps `connectedDebugAndroidTest` on a developer's device harmless;
 * under `saikou.strict` [Args] throws instead of returning null (R28), so the gate cannot pass this
 * by skipping it.
 */
@RunWith(AndroidJUnit4::class)
class BackupImportTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val app = context.applicationContext as SaikouApp

    private val graph: AppGraph get() = app.graph

    @Test
    fun theOwnersBackupRestoresEverythingTheFileCarries() {
        val path = Args.backupFile ?: return

        val file = copyToCache(path)
        // The parser is the companion default, the same value `:backup` binds into the graph, so
        // the test reads the file with exactly the settings the importer reads it with.
        val expected = BackupDecoder(context, ProtoBuf).decode(file.source())

        val report = runBlocking { graph.backupImporter.import(file, RestoreOptions(), progress = {}) }
        logReport(expected, report)

        assertTheRunFinished(expected, report)
        assertTheErrorReportIsClean(report)
        assertTheIndexRowsAreBack(expected, report)
        assertThePreferencesAreBack(expected, report)
        assertTheLibraryRowsAreBack(expected)
        assertTheViewerFlagsAreBack(expected)
        assertTheTrackerRowsAreBack(expected, report)
    }

    /**
     * `/data/local/tmp` is world-executable but not world-readable, so the app can open a pushed
     * file by its exact path and cannot list the directory around it. That is enough to copy it,
     * and the copy is what the importer is handed: the importer opens the file more than once
     * (hash, decode) and a path the process may lose access to between the two is a failure mode
     * worth not having.
     */
    private fun copyToCache(path: String): File {
        val source = File(path)
        assertTrue(
            "the file named by saikou.backup.file is not readable at that device path",
            source.isFile && source.canRead(),
        )
        val target = File(Fixtures.cacheDir(), COPY_NAME)
        target.delete()
        source.copyTo(target, overwrite = true)
        return target
    }

    /**
     * The accounting identity first, because every count below is meaningless if the run did not
     * finish. The identity is only checked on a fresh run: a resumed run reports the work it did
     * itself, so its inserted plus merged plus failed is short by whatever an earlier attempt
     * committed before it died.
     */
    private fun assertTheRunFinished(expected: Backup, report: ImportReport) {
        assertEquals(
            "the importer read a different number of entries than the file carries",
            expected.backupManga.size,
            report.mangaTotal,
        )
        assertFalse("the import reported itself cancelled", report.cancelled)
        assertEquals(
            "${report.mangaFailed} of ${report.mangaTotal} entries failed to restore",
            0,
            report.mangaFailed,
        )
        if (!report.resumed) {
            assertEquals(
                "inserted + merged + failed does not account for every entry in the file",
                report.mangaTotal,
                report.mangaInserted + report.mangaMerged + report.mangaFailed,
            )
        }
    }

    /**
     * Only the kinds that mean data was lost. A tracker this build has no tracker class for is
     * recorded and skipped by design, a preference whose stored type disagrees with the file is one
     * setting rather than a failed restore, and a batch fallback is the recovery working: the chunk
     * threw, the rows were retried one at a time, and whatever actually failed is recorded under
     * its own kind. None of the three fails the run. Nothing here prints an error's subject, which
     * is a title, a preference key or an index address.
     */
    private fun assertTheErrorReportIsClean(report: ImportReport) {
        val fatal = report.errors.filter { it.kind in LOSSY_ERROR_KINDS }
        val summary = fatal.groupingBy { it.kind }.eachCount()
        assertTrue("the import lost rows: $summary", fatal.isEmpty())
    }

    /** The index rows, which are what makes the restored library refreshable at all. */
    private fun assertTheIndexRowsAreBack(expected: Backup, report: ImportReport) {
        val stores = expected.backupExtensionStores
        assertEquals(
            "the importer upserted a different number of index rows than the file carries",
            stores.size,
            report.extensionStoresUpserted,
        )
        if (stores.isEmpty()) return

        val live = runBlocking { graph.extensionStoreRepository.getAll() }.map { it.indexUrl }.toSet()
        val missing = stores.count { it.indexUrl !in live }
        assertEquals("$missing of ${stores.size} index rows are absent after the restore", 0, missing)
    }

    /**
     * Every key the file carries has to be readable back out of the preference store, which is what
     * carries the reader settings among the rest. Three exclusions, all of them the restorer's own
     * documented behaviour rather than a concession made to get the test green: app state never
     * travels, the legacy index key is consumed into the index table instead of written, and a key
     * whose stored type disagrees with the file is refused with a recorded error.
     */
    private fun assertThePreferencesAreBack(expected: Backup, report: ImportReport) {
        if (expected.backupPreferences.isEmpty()) return

        val refused = report.errors
            .filter { it.kind == ImportError.Kind.PREFERENCE_TYPE_MISMATCH || it.kind == ImportError.Kind.PREFERENCE }
            .map { it.subject }
            .toSet()

        val wanted = expected.backupPreferences
            .map { upstreamKeyToSaikouKey(it.key) }
            .filterNot { Preference.isAppState(it) }
            .filterNot { it == LEGACY_INDEX_PREFERENCE_KEY }
            .filterNot { it in refused }

        val live = graph.preferenceStore.getAll().keys
        val missing = wanted.count { it !in live }
        assertEquals("$missing of ${wanted.size} preference keys are absent after the restore", 0, missing)
        assertTrue(
            "the file carries ${wanted.size} preference keys and the importer wrote none",
            wanted.isEmpty() || report.preferencesWritten > 0,
        )
    }

    /**
     * Row counts read back out of the database rather than off the report's counters, because the
     * counters are the importer's own account of itself. A bounded sample, since the file can carry
     * thousands of entries and every check here is a query: the first [ROW_SAMPLE] entries that
     * carry chapters, which is enough to catch a writer that dropped the chapter list.
     */
    private fun assertTheLibraryRowsAreBack(expected: Backup) {
        val sample = expected.backupManga.filter { it.chapters.isNotEmpty() }.take(ROW_SAMPLE)
        if (sample.isEmpty()) return

        val shortfall = runBlocking {
            sample.count { entry ->
                val stored = graph.mangaRepository.getMangaByUrlAndSourceId(entry.url, entry.source)
                stored == null ||
                    graph.chapterRepository.getChapterByMangaId(stored.id).size < entry.chapters.size
            }
        }
        assertEquals(
            "$shortfall of ${sample.size} sampled entries are missing rows after the restore",
            0,
            shortfall,
        )
    }

    /**
     * The per-entry viewer flag, which is the one column a reader setting lives in rather than in
     * the preference store. Only entries that carry a non-default flag are checked; an owner who
     * never overrode a reading mode has nothing here to restore and the check is empty, which is
     * why the count is logged.
     */
    private fun assertTheViewerFlagsAreBack(expected: Backup) {
        val flagged = expected.backupManga.filter { it.flags() != 0 }
        logcat(LOG_TAG, LogPriority.INFO) { "entries carrying a viewer flag: ${flagged.size}" }
        if (flagged.isEmpty()) return

        val wrong = runBlocking {
            flagged.count { entry ->
                val stored = graph.mangaRepository.getMangaByUrlAndSourceId(entry.url, entry.source)
                stored == null || stored.viewerFlags != entry.flags().toLong()
            }
        }
        assertEquals(
            "$wrong of ${flagged.size} entries lost their viewer flag in the restore",
            0,
            wrong,
        )
    }

    /**
     * Tracker rows split in three by the file's own ids: a tracker this build serves, a known
     * tracker it does not serve (the rows travel anyway, so a later build finds them), and an id
     * outside the known set (the rows are dropped and recorded). The third set is asserted exactly,
     * because an id silently landing in the second set would restore rows no tracker can ever read.
     */
    private fun assertTheTrackerRowsAreBack(expected: Backup, report: ImportReport) {
        val ids = expected.backupManga.flatMap { entry -> entry.tracking.map { it.syncId.toLong() } }
        assertEquals(
            "the importer skipped a different set of unknown tracker ids than the file carries",
            ids.filterNot { it in TrackerIds.ALL }.toSortedSet(),
            report.unknownTrackers.toSortedSet(),
        )

        val sample = expected.backupManga
            .filter { entry -> entry.tracking.any { it.syncId.toLong() in TrackerIds.ALL } }
            .take(ROW_SAMPLE)
        if (sample.isEmpty()) return

        val missing = runBlocking {
            sample.count { entry ->
                val stored = graph.mangaRepository.getMangaByUrlAndSourceId(entry.url, entry.source)
                val wanted = entry.tracking.count { it.syncId.toLong() in TrackerIds.ALL }
                stored == null || graph.trackRepository.getTracksByMangaId(stored.id).size < wanted
            }
        }
        assertEquals(
            "$missing of ${sample.size} sampled entries lost tracker rows in the restore",
            0,
            missing,
        )
    }

    /** Counts only. A title, an address or a source name never reaches a log line. */
    private fun logReport(expected: Backup, report: ImportReport) {
        logcat(LOG_TAG, LogPriority.INFO) {
            "file: ${expected.backupManga.size} entries, ${expected.backupCategories.size} categories, " +
                "${expected.backupSources.size} sources, ${expected.backupPreferences.size} preferences, " +
                "${expected.backupSourcePreferences.size} source preference files, " +
                "${expected.backupExtensionStores.size} index rows"
        }
        logcat(LOG_TAG, LogPriority.INFO) {
            "restore: inserted ${report.mangaInserted}, merged ${report.mangaMerged}, " +
                "failed ${report.mangaFailed}, chapters ${report.chaptersWritten}, " +
                "history ${report.historyWritten}, tracks ${report.tracksWritten}, " +
                "categories ${report.categoriesCreated}, preferences ${report.preferencesWritten}, " +
                "source preference files ${report.sourcePreferenceFilesWritten}, " +
                "index rows ${report.extensionStoresUpserted}, resumed ${report.resumed}, " +
                "errors ${report.errors.size} in ${report.finishedAt - report.startedAt} ms"
        }
    }

    /** `viewer_flags` when the file has it, else the pre-flags `viewer` column, as the mapper reads it. */
    private fun BackupManga.flags(): Int = viewer_flags ?: viewer

    /**
     * The same key translation as `com.shinsaikou.backup.UpstreamPreferenceKeys.toSaikou` in the `:backup`
     * module. That object is internal to `:backup`, and androidTest here only gets friend access to
     * `:app`'s own internals, not another module's, so the mapping is duplicated rather than the
     * production visibility widened for a test. The four literal keys below are copied from
     * `backup/src/main/kotlin/com/shinsaikou/backup/UpstreamPreferenceKeys.kt`.
     */
    private fun upstreamKeyToSaikouKey(key: String): String = when (key) {
        "pref_chapter_swipe_start_action" -> "pref_chapter_swipe_end_action"
        "pref_chapter_swipe_end_action" -> "pref_chapter_swipe_start_action"
        "pref_filter_library_interval_custom" -> "pref_filter_library_interval_custom_v2"
        else -> key
    }

    private companion object {
        const val LOG_TAG = "SaikouBackupImport"

        const val COPY_NAME = "owner-backup"

        /** Upstream's set of index addresses, folded into the index table instead of the store. */
        const val LEGACY_INDEX_PREFERENCE_KEY = "extension_repos"

        /** Per-entry database checks are one query each, so they run over a bounded sample. */
        const val ROW_SAMPLE = 25

        val LOSSY_ERROR_KINDS = setOf(
            ImportError.Kind.MANGA_ROW,
            ImportError.Kind.CHAPTERS,
            ImportError.Kind.HISTORY,
            ImportError.Kind.TRACKING,
            ImportError.Kind.CATEGORY,
            ImportError.Kind.CATEGORIES_OF_MANGA,
            ImportError.Kind.EXCLUDED_SCANLATORS,
            ImportError.Kind.EXTENSION_STORE,
        )
    }
}

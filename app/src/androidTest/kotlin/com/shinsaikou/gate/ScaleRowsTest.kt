package com.shinsaikou.gate

import android.Manifest
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.work.WorkInfo
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.db.QueryResult
import com.shinsaikou.SaikouApp
import com.shinsaikou.data.DatabaseBindings
import com.shinsaikou.di.AppGraph
import com.shinsaikou.i18n.R
import com.shinsaikou.navigation.SettingsSection
import com.shinsaikou.sync.SyncDigest
import com.shinsaikou.sync.SyncLibrarySeeder
import com.shinsaikou.sync.SyncOutcome
import com.shinsaikou.sync.SyncEngine
import com.shinsaikou.sync.SyncPreferences
import com.shinsaikou.sync.SyncSafetyBackup
import com.shinsaikou.sync.SyncScheduler
import com.shinsaikou.ui.main.MainActivity
import com.shinsaikou.ui.settings.labelRes
import com.shinsaikou.util.GateFragments
import com.shinsaikou.util.StagePreferences
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import okio.sink
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.manga.interactor.FetchInterval
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.time.Clock

/**
 * Saikou measured at 40,000 entries: phase 5 unit SC0's rows (`design/plans/phase5-start.md` section
 * 4.1), the numbers SC1 to SC6 each move one of.
 *
 * Every method skips with a `Skipped:` line unless `saikou.scale.40k` is `true`, read directly as
 * [GateRowsTest]'s argument-gated methods read theirs, so the gate's device steps pass through this
 * class in seconds and strict mode does not turn the absence into a failure. `tools/ci/scale40k.sh`
 * runs each method in an `am instrument` of its own, so a process that dies takes one row with it,
 * and names the device with `saikou.device.id` and the library with `saikou.scale.entries` and
 * `saikou.scale.chapters` (40,000 and 50 by default; the low-RAM run may pass less).
 *
 * **Nothing is asserted against a target.** A method publishes what happened through
 * [GateFragments.publish], `/data/local/tmp/saikou-gate/<id>.json`, which `collect.py
 * --instrumentation` pulls; one that cannot finish publishes the value `"failed"` with the reason in
 * its note, an `OutOfMemoryError` included, and returns, so the suite stays green and the row says
 * how far it got. The note of every row names the sizes and what was timed.
 *
 * **Each method seeds the library itself when it is not there** ([SyncLibrarySeeder] at the sizes
 * given, eight categories, a track on one entry in fifty, written under the `is_syncing` latch so
 * the change log holds no row per chapter), checked by the seeder source's entry count and the last
 * entry's chapters. The runner seeds once, with [theSeedIsTimed], so every later method opens a
 * library that is already on disk; a method that had to seed says so, because its process is then
 * warm. The library goes with the runner's uninstall, never a 40,000-entry delete.
 *
 * The restore is the exception: it runs first, on an empty install, and the runner clears the app's
 * data after it, because its 40,000 entries are favourites, which `deleteNonLibraryManga` never
 * removes, and a delete of their 2,000,000 chapters would log a tombstone each.
 */
@RunWith(AndroidJUnit4::class)
class ScaleRowsTest {

    /** Outermost: [MainActivity] asks for `POST_NOTIFICATIONS` and the dialog would block the run. */
    @get:Rule(order = 0)
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(*NOTIFICATION_PERMISSIONS)

    /** Empty: each method that opens the activity launches it through [ActivityScenario] itself. */
    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private val arguments get() = InstrumentationRegistry.getArguments()

    private val context = instrumentation.targetContext

    private val app = context.applicationContext as SaikouApp

    private val graph: AppGraph get() = app.graph

    private val entries: Int get() = arguments.getString(ENTRIES_ARGUMENT)?.toIntOrNull() ?: DEFAULT_ENTRIES

    private val chapters: Int get() = arguments.getString(CHAPTERS_ARGUMENT)?.toIntOrNull() ?: DEFAULT_CHAPTERS

    private val batch: Int get() = arguments.getString(BATCH_ARGUMENT)?.toIntOrNull() ?: DEFAULT_BATCH

    private val sizes: String get() = "$entries entries of $chapters chapters"

    /** `seed_40k_ms`: [SyncLibrarySeeder] at the run's sizes on an empty install, its cost recorded. */
    @Test
    fun theSeedIsTimed() = row("theSeedIsTimed", "seed_40k_ms") { device ->
        val disk = GateFragments.shell("df /data").trim().lines().lastOrNull()?.trim().orEmpty()
        val already = runBlocking(Dispatchers.IO) { seeded() }
        if (already) {
            failed("seed_40k_ms", device, "ms", "the library was already seeded before this method ran, so nothing was timed; df /data: $disk")
            return@row
        }
        val logBefore = runBlocking(Dispatchers.IO) { logSeq() }
        HeapSampler("seed").use { sampler ->
            val startedAt = SystemClock.elapsedRealtime()
            val library = runBlocking(Dispatchers.IO) { seed() }
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            val logAfter = runBlocking(Dispatchers.IO) { logSeq() }
            publish(
                "seed_40k_ms", device, elapsed.toString(), "ms",
                "SyncLibrarySeeder.seed of $sizes, ${SyncLibrarySeeder.CATEGORIES} categories, a track on one entry in " +
                    "${SyncLibrarySeeder.TRACK_EVERY}, $batch entries per transaction, chapters, history, memberships and " +
                    "tracks under the is_syncing latch (Latch.RAISED); timed from the call to its return: " +
                    "${library.chaptersInserted} chapters inserted; change log sequence $logBefore before and $logAfter " +
                    "after; ${databaseSize()}; df /data before the seed: $disk; ${sampler.summary()}",
            )
        }
    }

    /**
     * `library_open_ms_40k`: [MainActivity] launched on the Library tab, timed from the launch to
     * the first seeded entry's grid cell in the semantics tree. The runner calls it three times
     * with `am force-stop` between and `saikou.scale.sample` 1/3, 2/3 and 3/3, so each launch is in a
     * fresh process; the samples are kept in the app's files and the third publishes their median.
     */
    @Test
    fun theLibraryOpenIsTimed() = row("theLibraryOpenIsTimed", "library_open_ms_40k") { device ->
        val warm = ensureSeeded() + ensureManifest()
        prepareMain()
        val startedAt = SystemClock.elapsedRealtime()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            awaitNode(seededCell(), OPEN_TIMEOUT_MILLIS)
            val shownAt = SystemClock.elapsedRealtime()
            val elapsed = shownAt - startedAt
            Log.i(TAG, "library open split: ${projectionSplit(startedAt, shownAt)}")
            val samples = sample("library_open_ms_40k", elapsed) ?: return@row
            publish(
                "library_open_ms_40k", device, median(samples).toString(), "ms",
                "MainActivity launched cold on the Library tab over $sizes, timed from ActivityScenario.launch to the " +
                    "first seeded entry's grid cell in the semantics tree; median of ${samples.size} launches, each in a " +
                    "fresh process after am force-stop: ${samples.joinToString()} ms; this launch's split, from the launch: " +
                    "${projectionSplit(startedAt, shownAt)}$warm",
            )
        } finally {
            scenario.close()
        }
    }

    /**
     * `library_change_ms_40k`: with the library open, the first unread chapter of the first entry
     * marked read through `chapterRepository.updateAll`, timed to the next emission of
     * `mangaRepository.getLibraryMangaAsFlow()` (collected here) that carries the entry's new unread
     * count; the time to the grid's unread badge showing it goes in the note.
     */
    @Test
    fun theLibraryChangeIsTimed() = row("theLibraryChangeIsTimed", "library_change_ms_40k") { device ->
        val warm = ensureSeeded() + ensureManifest()
        prepareMain()
        val target = runBlocking(Dispatchers.IO) {
            checkNotNull(graph.mangaRepository.getMangaByUrlAndSourceId(SyncLibrarySeeder.url(0), SyncLibrarySeeder.SOURCE_ID)) {
                "the first seeded entry is not in the database"
            }
        }
        val unread = runBlocking(Dispatchers.IO) {
            graph.chapterRepository.getChapterByMangaId(target.id).filter { !it.read }.sortedBy { it.chapterNumber }
        }
        check(unread.isNotEmpty()) { "${target.title} has no unread chapter left to mark" }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            awaitNode(cellWithBadge(target.title, unread.size), OPEN_TIMEOUT_MILLIS)
            val emittedAt = AtomicLong(-1L)
            val subscribed = AtomicBoolean(false)
            val expected = (unread.size - 1).toLong()
            scope.launch {
                graph.mangaRepository.getLibraryMangaAsFlow().collect { library ->
                    subscribed.set(true)
                    val row = library.firstOrNull { it.manga.id == target.id }
                    if (row != null && row.unreadCount == expected) emittedAt.compareAndSet(-1L, SystemClock.elapsedRealtime())
                }
            }
            waitFor(OPEN_TIMEOUT_MILLIS, "the library flow's first emission") { subscribed.get() }

            val startedAt = SystemClock.elapsedRealtime()
            runBlocking(Dispatchers.IO) { graph.chapterRepository.updateAll(listOf(ChapterUpdate(id = unread.first().id, read = true))) }
            waitFor(CHANGE_TIMEOUT_MILLIS, "the library flow's emission with $expected unread") { emittedAt.get() > 0 }
            val flowMillis = emittedAt.get() - startedAt
            var badgeAt = -1L
            val badge = try {
                awaitNode(cellWithBadge(target.title, unread.size - 1), CHANGE_TIMEOUT_MILLIS)
                badgeAt = SystemClock.elapsedRealtime()
                "${badgeAt - startedAt} ms"
            } catch (e: ComposeTimeoutException) {
                "not seen within $CHANGE_TIMEOUT_MILLIS ms"
            }
            val split = if (badgeAt > 0) projectionSplit(startedAt, badgeAt) else "no badge, no split"
            val reads = libraryReads()
            Log.i(TAG, "library change split: $split; $reads")
            publish(
                "library_change_ms_40k", device, flowMillis.toString(), "ms",
                "Library tab open over $sizes; chapter ${unread.first().chapterNumber} of '${target.title}' marked read " +
                    "through chapterRepository.updateAll, timed to the getLibraryMangaAsFlow emission (a second collector " +
                    "beside the screen's) carrying $expected unread: $flowMillis ms; to the grid's '$expected unread' " +
                    "badge: $badge; the screen's split, from the write: $split; afterwards, $reads$warm",
            )
        } finally {
            scope.cancel()
            scenario.close()
        }
    }

    /**
     * `library_search_ms_40k`: a query matching 40 entries typed into the library's search field,
     * timed from the input to the grid showing a matching entry of the first tab and not its first
     * entry, which does not match, less the view model's 250 ms debounce; median of three.
     */
    @Test
    fun theLibrarySearchIsTimed() = row("theLibrarySearchIsTimed", "library_search_ms_40k") { device ->
        val warm = ensureSeeded() + ensureManifest()
        prepareMain()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val first = cell(SyncLibrarySeeder.title(0))
            val match = cell(SyncLibrarySeeder.title(SEARCH_MATCH))
            awaitNode(first, OPEN_TIMEOUT_MILLIS)
            compose.onAllNodes(hasContentDescription(context.getString(R.string.action_search))).onFirst().performClick()
            awaitNode(hasSetTextAction(), UI_TIMEOUT_MILLIS)
            val splits = mutableListOf<String>()
            val samples = (1..SEARCH_RUNS).map {
                compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput(SEARCH_QUERY)
                val startedAt = SystemClock.elapsedRealtime()
                waitFor(SEARCH_TIMEOUT_MILLIS, "the filtered grid") { exists(match) && !exists(first) }
                val shownAt = SystemClock.elapsedRealtime()
                splits += projectionSplit(startedAt, shownAt)
                val elapsed = shownAt - startedAt - SEARCH_DEBOUNCE_MILLIS
                compose.onAllNodes(hasSetTextAction()).onFirst().performTextClearance()
                waitFor(SEARCH_TIMEOUT_MILLIS, "the unfiltered grid") { exists(first) }
                elapsed
            }
            publish(
                "library_search_ms_40k", device, median(samples).toString(), "ms",
                "Library tab open over $sizes; the query $SEARCH_QUERY (40 entries) typed into the search field with one " +
                    "performTextInput, timed to '${SyncLibrarySeeder.title(SEARCH_MATCH)}' shown and " +
                    "'${SyncLibrarySeeder.title(0)}' gone, less the $SEARCH_DEBOUNCE_MILLIS ms debounce; median of " +
                    "$SEARCH_RUNS: ${samples.joinToString()} ms; each input's split, from the input, the debounce " +
                    "included: ${splits.joinToString(" | ")}$warm",
            )
        } finally {
            scenario.close()
        }
    }

    /**
     * `restore_40k_ms` and `restore_40k_oom_count`: the runner's `big_backup.py` file, pushed to
     * [BACKUP_DEVICE_PATH] and copied into the cache here, restored through the production importer
     * with library entries and categories (the install is empty, so the file's categories overwrite
     * nothing, and an entry's memberships restore only with them), timed from the call to its
     * report. The runner clears the app's data afterwards (see the class comment).
     */
    @Test
    fun theRestoreIsTimed() = row("theRestoreIsTimed", "restore_40k_ms", "restore_40k_oom_count") { device ->
        val path = arguments.getString(BACKUP_ARGUMENT)?.takeIf { it.isNotBlank() } ?: BACKUP_DEVICE_PATH
        val source = File(path)
        check(source.isFile && source.canRead()) { "no readable backup at $path" }
        val file = File(context.cacheDir, RESTORE_FILE_NAME)
        file.delete()
        source.copyTo(file, overwrite = true)
        val favouritesBefore = runBlocking(Dispatchers.IO) { graph.mangaRepository.getFavorites().size }
        val logBefore = runBlocking(Dispatchers.IO) { logSeq() }
        HeapSampler("restore").use { sampler ->
            var outOfMemory = 0
            var failure: String? = null
            val startedAt = SystemClock.elapsedRealtime()
            val report = try {
                runBlocking {
                    graph.backupImporter.import(
                        file,
                        RestoreOptions(
                            libraryEntries = true,
                            categories = true,
                            appSettings = false,
                            extensionStores = false,
                            sourceSettings = false,
                        ),
                        progress = {},
                    )
                }
            } catch (error: OutOfMemoryError) {
                outOfMemory = 1
                failure = "OutOfMemoryError: ${error.message}"
                null
            } catch (error: Exception) {
                failure = error.toString()
                null
            }
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            file.delete()
            val logAfter = runCatching { runBlocking(Dispatchers.IO) { logSeq() } }.getOrDefault(-1L)
            val outcome = report?.let {
                "${it.mangaTotal} entries read, ${it.mangaInserted} inserted, ${it.mangaMerged} merged, ${it.mangaFailed} " +
                    "failed, ${it.chaptersWritten} chapters, ${it.historyWritten} history rows, ${it.tracksWritten} tracks, " +
                    "${it.categoriesCreated} categories, ${it.errors.size} errors"
            } ?: "no report: $failure"
            val method = "BackupImporter.import of big_backup.py's file (${source.length()} bytes, $sizes asked of the " +
                "runner) on an install holding $favouritesBefore favourites, library entries and categories only, " +
                "timed from the call to its report: $outcome; change log sequence $logBefore before and $logAfter after; " +
                "${databaseSize()}; ${sampler.summary()}"
            publish("restore_40k_ms", device, if (report == null) FAILED else elapsed.toString(), "ms", method)
            publish("restore_40k_oom_count", device, outOfMemory.toString(), "count", method)
        }
    }

    /**
     * `export_40k_ms`: the production exporter over the seeded library, `build` with the default
     * [BackupOptions] (read, encode and gzip into its staging file, then read that file back) and
     * `write` (the copy into a cache file), timed from the first call to the second's return; the
     * file's size and the two halves in the note.
     */
    @Test
    fun theExportIsTimed() = row("theExportIsTimed", "export_40k_ms") { device ->
        val warm = ensureSeeded()
        val file = File(context.cacheDir, EXPORT_FILE_NAME)
        file.delete()
        HeapSampler("export").use { sampler ->
            try {
                val startedAt = SystemClock.elapsedRealtime()
                runBlocking { graph.backupExporter.build(BackupOptions()) }.use { staged ->
                    val built = SystemClock.elapsedRealtime() - startedAt
                    val bytes = runBlocking { graph.backupExporter.write(staged, file.sink()) }
                    val elapsed = SystemClock.elapsedRealtime() - startedAt
                    publish(
                        "export_40k_ms", device, elapsed.toString(), "ms",
                        "BackupExporter.build(BackupOptions()) then write into a cache file over $sizes: " +
                            "${staged.mangaCount} entries, $bytes bytes; build (read, encode and gzip into the " +
                            "staging file, read back) $built ms, write (copy) ${elapsed - built} ms; " +
                            "${sampler.summary()}$warm",
                    )
                }
            } catch (error: OutOfMemoryError) {
                failed("export_40k_ms", device, "ms", "OutOfMemoryError: ${error.message}; ${sampler.summary()}$warm")
            } finally {
                file.delete()
            }
        }
    }

    /**
     * `fingerprint_40k_ms` and `fingerprint_40k_oom`: [GateRowsTest.theLibraryFingerprintTimesATenThousandEntryLibrary]'s
     * steps at the seeded size. Signed in to the WebDAV fixture the runner started (the row is
     * hidden with no backend, R101 item 1): when the device is not signed in yet, the sign-in is
     * stored with the periodic run off and no first run, so nothing pushes while the fingerprint
     * reads; when it is, every sync run is waited out first. Then More, Settings, Sync, the row
     * tapped and timed until its computing text goes, and the row read: only a fingerprint shown is
     * a time. A compute still going at the timeout, or a row that says a sync is running or ran, is
     * `"failed"` with that in the note, with any `OutOfMemoryError` line of this process from the
     * log; a death is the runner's to record.
     *
     * The automatic sync is cancelled and waited out when the method starts and again once the
     * screen is up, before the tap. At this size the sync has never succeeded, so every process start
     * finds the last success old and `SyncTriggers.onAppStart` starts one: `computeFingerprint`
     * answers a tap during a run with "A sync is running" and computes nothing (the second attempt's
     * 4 GB row read 106 ms that way), and a run left to finish fills the heap first (the third
     * attempt's wait for it died of memory). The row times the digest alone; the note says how many
     * runs were cancelled.
     */
    @Test
    fun theFingerprintIsTimed() = row("theFingerprintIsTimed", "fingerprint_40k_ms", "fingerprint_40k_oom") { device ->
        val warm = ensureSeeded()
        val signIn = signInArguments() ?: run {
            failed("fingerprint_40k_ms", device, "ms", "no saikou.sync.pair.url, .user or .password argument")
            failed("fingerprint_40k_oom", device, "count", "no saikou.sync.pair.url, .user or .password argument")
            return@row
        }
        val signedIn = runBlocking(Dispatchers.IO) {
            if (configured()) {
                "already signed in, ${cancelAutomaticSync()} automatic sync run(s) cancelled at the start"
            } else {
                storeSignIn(signIn)
                "sign-in stored with no first run"
            }
        }
        prepareMain()
        HeapSampler("fingerprint").use { sampler ->
            withMain {
                clickFirst(hasText(context.getString(R.string.tab_more)) and isSelectable())
                val settingsRow = hasText(context.getString(R.string.label_settings)) and hasClickAction()
                compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(settingsRow)
                clickFirst(settingsRow)
                clickFirst(hasText(context.getString(SettingsSection.SYNC.labelRes)) and hasClickAction() and isSelectable().not())
                val fingerprintRow = hasText(context.getString(R.string.sync_fingerprint)) and hasClickAction()
                awaitNode(fingerprintRow, UI_TIMEOUT_MILLIS)
                val cancelled = runBlocking(Dispatchers.IO) { cancelAutomaticSync() }
                val computing = hasText(context.getString(R.string.sync_fingerprint_computing))
                val startedAt = SystemClock.elapsedRealtime()
                compose.onAllNodes(fingerprintRow).onFirst().performClick()
                val done = try {
                    compose.waitUntil(FINGERPRINT_TIMEOUT_MILLIS) { compose.onAllNodes(computing).fetchSemanticsNodes().isEmpty() }
                    true
                } catch (e: ComposeTimeoutException) {
                    false
                }
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                val resultTail = context.resources.getQuantityString(R.plurals.sync_fingerprint_result_format, 2, "x", "2")
                    .substringAfter("2")
                val unshown = when {
                    !done -> "still computing after $FINGERPRINT_TIMEOUT_MILLIS ms"
                    exists(hasText(context.getString(R.string.sync_fingerprint_sync_running))) -> "the row said a sync is running"
                    exists(hasText(context.getString(R.string.sync_fingerprint_sync_ran))) -> "the row said a sync ran meanwhile"
                    exists(hasText(resultTail, substring = true)) -> null
                    else -> "no fingerprint shown when the computing text went"
                }
                val oom = outOfMemoryLines()
                val method = "Sync settings' Library fingerprint over $sizes ($signedIn; $cancelled more cancelled " +
                    "once the screen was up), timed from the tap to its computing text gone and " +
                    "the fingerprint shown${unshown?.let { ": $it" }.orEmpty()}; " +
                    "${sampler.summary()}${if (oom.isEmpty()) "" else "; ${oom.first()}"}$warm"
                publish("fingerprint_40k_ms", device, if (unshown == null) elapsed.toString() else FAILED, "ms", method)
                publish("fingerprint_40k_oom", device, if (oom.isEmpty()) "0" else "1", "count", method)
            }
        }
    }

    /**
     * `sync_first_push_40k_ms`: the device's first sync over the seeded library, a full export,
     * pushed to the runner's WebDAV fixture. Signed in as Sync settings signs in
     * (`SyncSession.signedIn`, which starts the first run), or, when the sign-in is already stored
     * and no run has succeeded, Sync now; timed until no `sync-once` work is waiting or running and
     * the run record holds a run since the start. The files on the fixture afterwards are the parts
     * count in the note, and the row's `split` says where the time went ([runSplit]).
     */
    @Test
    fun theFirstPushIsTimed() = row("theFirstPushIsTimed", "sync_first_push_40k_ms") { device ->
        val warm = ensureSeeded()
        val signIn = signInArguments() ?: run {
            failed("sync_first_push_40k_ms", device, "ms", "no saikou.sync.pair.url, .user or .password argument")
            return@row
        }
        HeapSampler("first-push").use { sampler ->
            val logged = timingLines().size
            val (elapsed, how) = runBlocking(Dispatchers.IO) { timedFirstRun(signIn) }
            val split = runSplit(elapsed, logged)
            val record = graph.syncRunRecord
            val report = record.reports.get().firstOrNull()
            val files = runBlocking(Dispatchers.IO) { remoteFiles() }
            val ok = record.outcome() == SyncOutcome.Success && report?.exported == true
            val method = "The first sync of $sizes to the runner's WebDAV fixture ($how), timed to sync-once idle: outcome " +
                "${record.outcome()}, exported ${report?.exported}, library ${report?.after?.libraryEntries} entries after; " +
                "$files; ${sampler.summary()}$warm"
            publish("sync_first_push_40k_ms", device, if (ok) elapsed.toString() else FAILED, "ms", method, split)
        }
    }

    /**
     * `sync_join_40k_ms`: on a second device with an empty library (the runner's `saikou_api26_2g`),
     * signed in to the same fixture account as [theFirstPushIsTimed]'s device, and its first run
     * (the safety backup, the pull of that export, its own export) timed to idle as there, its
     * `split` as there. Seeds nothing; the entries the run left are read back into the note.
     */
    @Test
    fun theJoinIsTimed() = row("theJoinIsTimed", "sync_join_40k_ms") { device ->
        val signIn = signInArguments() ?: run {
            failed("sync_join_40k_ms", device, "ms", "no saikou.sync.pair.url, .user or .password argument")
            return@row
        }
        val before = runBlocking(Dispatchers.IO) { graph.mangaRepository.getFavorites().size }
        HeapSampler("join").use { sampler ->
            val logged = timingLines().size
            val (elapsed, how) = runBlocking(Dispatchers.IO) { timedFirstRun(signIn) }
            val split = runSplit(elapsed, logged)
            val record = graph.syncRunRecord
            val after = runBlocking(Dispatchers.IO) { graph.mangaRepository.getFavorites().size }
            val report = record.reports.get().firstOrNull()
            val ok = record.outcome() == SyncOutcome.Success
            val method = "A device holding $before favourites signed in to the fixture account a $sizes device exported " +
                "to ($how), its first run timed to sync-once idle: outcome ${record.outcome()}, ${report?.routineMerges} " +
                "rows merged, $after favourites read back after; ${sampler.summary()}"
            publish("sync_join_40k_ms", device, if (ok) elapsed.toString() else FAILED, "ms", method, split)
        }
    }

    /**
     * `update_queue_build_ms_40k`: `FlatLibraryUpdate.buildQueue` for a manual run of the whole
     * library (no category, `automatic` false, today's fetch window), the narrowest public call that
     * builds the queue without fetching; median of three calls in one process.
     */
    @Test
    fun theUpdateQueueBuildIsTimed() = row("theUpdateQueueBuildIsTimed", "update_queue_build_ms_40k") { device ->
        val warm = ensureSeeded()
        val zone = TimeZone.currentSystemDefault()
        val window = FetchInterval(GetChaptersByMangaId(graph.chapterRepository))
            .getWindow(Clock.System.now().toLocalDateTime(zone).date, zone)
        var size = 0
        val samples = (1..QUEUE_RUNS).map {
            val startedAt = SystemClock.elapsedRealtime()
            size = runBlocking(Dispatchers.IO) { graph.libraryUpdate.buildQueue(categoryId = null, automatic = false, window = window) }.size
            SystemClock.elapsedRealtime() - startedAt
        }
        publish(
            "update_queue_build_ms_40k", device, median(samples).toString(), "ms",
            "FlatLibraryUpdate.buildQueue(categoryId = null, automatic = false) over $sizes, $size entries queued; median " +
                "of $QUEUE_RUNS calls in one process: ${samples.joinToString()} ms$warm",
        )
    }

    /**
     * `download_next_ms_at_5000`: 5,000 queue rows for the first seeded entries' chapters, enqueued
     * through the download queue repository, then `DownloadEngine.run`'s next-row read
     * (`queueRepository.getActive()` and its `firstOrNull` over the failed set) timed over 100
     * calls; the mean per call. No download runs: nothing starts the engine, and the rows are
     * removed afterwards.
     */
    @Test
    fun theDownloadNextRowIsTimed() = row("theDownloadNextRowIsTimed", "download_next_ms_at_5000") { device ->
        val warm = ensureSeeded()
        val queue = graph.downloadQueueRepository
        val leftover = runBlocking(Dispatchers.IO) { queue.getActive().size }
        val rows = runBlocking(Dispatchers.IO) {
            buildList {
                var index = 0
                while (size < QUEUE_ROWS && index < entries) {
                    val manga = graph.mangaRepository.getMangaByUrlAndSourceId(SyncLibrarySeeder.url(index++), SyncLibrarySeeder.SOURCE_ID)
                        ?: continue
                    graph.chapterRepository.getChapterByMangaId(manga.id).sortedBy { it.chapterNumber }
                        .take(QUEUE_ROWS - size).forEach { add(it.mangaId to it.id) }
                }
            }
        }
        try {
            val enqueueStarted = SystemClock.elapsedRealtime()
            runBlocking(Dispatchers.IO) { rows.forEach { (mangaId, chapterId) -> queue.enqueue(chapterId, mangaId, System.currentTimeMillis()) } }
            val enqueued = SystemClock.elapsedRealtime() - enqueueStarted
            val failed = emptySet<Long>()
            val startedAt = SystemClock.elapsedRealtime()
            var next: Long? = null
            runBlocking(Dispatchers.IO) {
                repeat(NEXT_ROW_CALLS) { next = queue.getActive().firstOrNull { it.chapterId !in failed }?.chapterId }
            }
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            val mean = elapsed.toDouble() / NEXT_ROW_CALLS
            publish(
                "download_next_ms_at_5000", device, "%.2f".format(java.util.Locale.ROOT, mean), "ms",
                "${rows.size} queue rows enqueued through DownloadQueueRepository.enqueue ($enqueued ms, $leftover active " +
                    "before) over $sizes, then DownloadEngine.run's next-row read, getActive().firstOrNull { it.chapterId !in " +
                    "failed } with an empty failed set, $NEXT_ROW_CALLS calls in $elapsed ms; the mean per call; next " +
                    "chapter $next; no download ran and the rows were removed$warm",
            )
        } finally {
            runBlocking(Dispatchers.IO) { rows.map { it.second }.chunked(REMOVE_CHUNK).forEach { queue.remove(it) } }
        }
    }

    /**
     * `stats_open_ms_40k`: Statistics opened from More, timed from the tap to the overview's "In
     * library" total in the semantics tree; median of three, back to More between.
     */
    @Test
    fun theStatsOpenIsTimed() = row("theStatsOpenIsTimed", "stats_open_ms_40k") { device ->
        val warm = ensureSeeded()
        prepareMain()
        withMain { scenario ->
            clickFirst(hasText(context.getString(R.string.tab_more)) and isSelectable())
            val statsRow = hasText(context.getString(R.string.label_stats)) and hasClickAction()
            val total = hasText(context.getString(R.string.in_library))
            var count = "unread"
            val samples = (1..STATS_RUNS).map {
                awaitNode(statsRow, UI_TIMEOUT_MILLIS)
                val startedAt = SystemClock.elapsedRealtime()
                compose.onAllNodes(statsRow).onFirst().performClick()
                awaitNode(total, STATS_TIMEOUT_MILLIS)
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                count = if (exists(hasText("$entries"))) "$entries" else "not $entries"
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                elapsed
            }
            publish(
                "stats_open_ms_40k", device, median(samples).toString(), "ms",
                "Statistics opened from More over $sizes, timed from the tap to the overview's In library total ($count " +
                    "shown); median of $STATS_RUNS: ${samples.joinToString()} ms$warm",
            )
        }
    }

    /**
     * `fingerprint_write_stall_ms` (phase 5 unit SC6; beside the rows, not a target of section 4.1):
     * how long one chapter marked read, as the reader marks it (`ChapterUpdate(read = true,
     * lastPageRead = 0)` through the chapter repository), waits while the library fingerprint
     * computes. The fingerprint is read as Sync settings reads it ([fingerprint]), with no screen and
     * no sign-in, so no sync run competes with it. The write starts [STALL_WRITE_DELAY_MILLIS] ms
     * after the fingerprint, on this thread while the fingerprint runs on another; the value is its
     * time to return. The note gives the same write with nothing else running (the median of
     * [STALL_IDLE_WRITES] chapters), when the write returned against when the fingerprint ended, and
     * the fingerprint's own time and rows.
     */
    @Test
    fun theFingerprintWriteStallIsTimed() = row("theFingerprintWriteStallIsTimed", "fingerprint_write_stall_ms") { device ->
        val warm = ensureSeeded()
        val unread = runBlocking(Dispatchers.IO) {
            val manga = checkNotNull(
                graph.mangaRepository.getMangaByUrlAndSourceId(SyncLibrarySeeder.url(STALL_ENTRY), SyncLibrarySeeder.SOURCE_ID),
            ) { "seeded entry $STALL_ENTRY is not in the database" }
            graph.chapterRepository.getChapterByMangaId(manga.id).filter { !it.read }.sortedBy { it.chapterNumber }
        }
        check(unread.size > STALL_IDLE_WRITES) { "seeded entry $STALL_ENTRY has ${unread.size} unread chapters left" }
        val idle = unread.take(STALL_IDLE_WRITES).map { markedRead(it.id) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        HeapSampler("fingerprint-stall").use { sampler ->
            try {
                val startedAt = SystemClock.elapsedRealtime()
                val endedAt = AtomicLong(-1L)
                val digest = scope.async { fingerprint().also { endedAt.set(SystemClock.elapsedRealtime()) } }
                Thread.sleep(STALL_WRITE_DELAY_MILLIS)
                val runningAtWrite = !digest.isCompleted
                val writeAt = SystemClock.elapsedRealtime()
                val stall = markedRead(unread[STALL_IDLE_WRITES].id)
                val returnedAt = writeAt + stall
                val result = runBlocking { withTimeout(FINGERPRINT_TIMEOUT_MILLIS) { digest.await() } }
                val fingerprintMillis = endedAt.get() - startedAt
                val returned = (endedAt.get() - returnedAt).let { if (it >= 0) "$it ms before" else "${-it} ms after" }
                val method = "one chapter marked read (ChapterUpdate(read = true, lastPageRead = 0) through " +
                    "ChapterRepository.update) ${writeAt - startedAt} ms after the fingerprint started, over $sizes; it " +
                    "returned in $stall ms, $returned the fingerprint ended; with nothing else running the same write " +
                    "took a median of ${median(idle)} ms (${idle.joinToString()} ms); the fingerprint ($FINGERPRINT_READ) " +
                    "took $fingerprintMillis ms over ${result.rows} rows; ${sampler.summary()}$warm"
                if (runningAtWrite) {
                    publish("fingerprint_write_stall_ms", device, stall.toString(), "ms", method)
                } else {
                    failed("fingerprint_write_stall_ms", device, "ms", "the fingerprint ended before the write started: $method")
                }
            } finally {
                scope.cancel()
            }
        }
    }

    /** The library fingerprint as `SyncSettingsViewModel.computeFingerprint` reads it, [FINGERPRINT_READ]. */
    private suspend fun fingerprint(): SyncDigest.Result = graph.syncStoreSnapshot.read { SyncDigest.of(it) }

    /** [chapterId] marked read as the reader marks a finished chapter; the write's time to return in ms. */
    private fun markedRead(chapterId: Long): Long {
        val startedAt = SystemClock.elapsedRealtime()
        runBlocking(Dispatchers.IO) { graph.chapterRepository.update(ChapterUpdate(id = chapterId, read = true, lastPageRead = 0)) }
        return SystemClock.elapsedRealtime() - startedAt
    }

    /** The fixture account the runner's WebDAV server holds. */
    private data class SignIn(val url: String, val user: String, val password: String)

    /** The method running now, for the notes. */
    private var method = ""

    /**
     * One row's method: the skip without [SCALE_ARGUMENT] or a device, the row's earlier fragments
     * removed so a death leaves none, and anything the body throws, an [OutOfMemoryError] included,
     * published as `"failed"` for every row it had not published yet. The skip is the line gate.sh's
     * tablet step expects, `Skipped: theSeedIsTimed: no saikou.scale.40k argument` and the same for
     * each method.
     */
    private fun row(name: String, vararg ids: String, body: (device: String) -> Unit) {
        if (arguments.getString(SCALE_ARGUMENT) != "true") return skip(name, "no saikou.scale.40k argument")
        val device = arguments.getString(GateFragments.DEVICE_ARGUMENT)
        if (device.isNullOrBlank()) return skip(name, "no ${GateFragments.DEVICE_ARGUMENT} argument")
        method = name
        ids.forEach(GateFragments::remove)
        Log.i(TAG, "Started: $name over $sizes on $device")
        try {
            body(device)
        } catch (error: Throwable) {
            Log.e(TAG, "$name did not finish", error)
            val reason = "did not finish: $error" + outOfMemoryLines().firstOrNull()?.let { "; $it" }.orEmpty()
            ids.filterNot(::published).forEach { id ->
                runCatching { failed(id, device, if (id.endsWith("_ms") || "_ms_" in id) "ms" else "count", reason) }
            }
        }
        // The first push's sign-in and sync state are set with apply() as it ends, and the
        // fingerprint's method reads them in the next process (phase 5 unit TG4).
        StagePreferences.commit()
        Log.i(TAG, "Finished: $name")
    }

    /** Writes the line a gate step counts, then returns: an early return must say why. */
    private fun skip(name: String, reason: String) {
        Log.i(TAG, "Skipped: $name: $reason")
    }

    private fun publish(
        id: String,
        device: String,
        value: String,
        unit: String,
        note: String,
        split: Map<String, Long> = emptyMap(),
    ) {
        GateFragments.publish(id, device, value, unit, "ScaleRowsTest.$method: $note", split)
        Log.i(TAG, "published $id on $device: $value $unit${if (split.isEmpty()) "" else " split $split"}")
    }

    private fun failed(id: String, device: String, unit: String, reason: String) = publish(id, device, FAILED, unit, reason)

    private fun published(id: String): Boolean =
        GateFragments.shell("ls ${GateFragments.DEVICE_GATE_DIRECTORY}").lines().any { it.trim() == "$id.json" }

    /** "" when the library was already there; otherwise what seeding it in this process cost. */
    private fun ensureSeeded(): String = runBlocking(Dispatchers.IO) {
        if (seeded()) return@runBlocking ""
        val startedAt = SystemClock.elapsedRealtime()
        seed()
        "; the library was seeded in this process first (${SystemClock.elapsedRealtime() - startedAt} ms), so it was warm"
    }

    /**
     * With `saikou.scale.manifest` `n` (unit SC4's sessions pass it; the runner does not), the
     * download manifest holds the chapters of every [MANIFEST_EVERY]th seeded entry, up to `n`
     * entries, as rows only (no file is written), so the library's `subscribeCountsByManga` reads a
     * heavy downloader's manifest; written once, before anything is timed, and said in the note.
     */
    private fun ensureManifest(): String {
        val wanted = arguments.getString(MANIFEST_ARGUMENT)?.toIntOrNull()?.takeIf { it > 0 } ?: return ""
        return runBlocking(Dispatchers.IO) {
            val queries = graph.database.downloads_manifestQueries
            val held = queries.countsByManga().awaitAsList().sumOf { it.count }
            if (held > 0) return@runBlocking "; the download manifest held $held rows"
            val startedAt = SystemClock.elapsedRealtime()
            var rows = 0L
            (0 until entries step MANIFEST_EVERY).take(wanted).chunked(MANIFEST_BATCH).forEach { batch ->
                graph.database.transaction {
                    batch.forEach { index ->
                        val manga = graph.database.mangasQueries
                            .getMangaByUrlAndSource(SyncLibrarySeeder.url(index), SyncLibrarySeeder.SOURCE_ID).awaitAsOne()
                        graph.database.chaptersQueries.getChaptersByMangaId(manga._id, 0L).awaitAsList().forEach { chapter ->
                            queries.upsert(
                                chapterId = chapter._id,
                                mangaId = manga._id,
                                sourceId = manga.source,
                                mangaTitle = manga.title,
                                mangaUrl = manga.url,
                                chapterUrl = chapter.url,
                                chapterName = chapter.name,
                                scanlator = chapter.scanlator,
                                chapterDirName = "scale_${chapter._id}",
                                pageCount = MANIFEST_PAGES,
                                sizeBytes = 0L,
                                now = chapter.date_fetch,
                            )
                            rows++
                        }
                    }
                }
            }
            "; the download manifest was written with $rows rows first (every ${MANIFEST_EVERY}th entry's chapters, " +
                "${SystemClock.elapsedRealtime() - startedAt} ms)"
        }
    }

    /** The seeder source holds every entry and the last one has its chapters. */
    private suspend fun seeded(): Boolean {
        val count = graph.database.mangasQueries.countBySourceId(SyncLibrarySeeder.SOURCE_ID).awaitAsOne()
        if (count < entries) return false
        val last = graph.mangaRepository.getMangaByUrlAndSourceId(SyncLibrarySeeder.url(entries - 1), SyncLibrarySeeder.SOURCE_ID)
            ?: return false
        return graph.chapterRepository.getChapterByMangaId(last.id).isNotEmpty()
    }

    private suspend fun seed(): SyncLibrarySeeder.Library = SyncLibrarySeeder.seed(
        graph,
        entries = entries,
        chapters = chapters,
        batch = batch,
        tracks = true,
        latch = SyncLibrarySeeder.Latch.RAISED,
    )

    /** The change log's highest sequence: on an install nothing has pruned, its row count. */
    private suspend fun logSeq(): Long = graph.database.sync_change_logQueries.getLatestSeq().awaitAsOne()

    private fun databaseSize(): String {
        val database = context.getDatabasePath(DatabaseBindings.DATABASE_NAME)
        return "database file ${database.length()} bytes, WAL ${File(database.path + "-wal").length()} bytes"
    }

    /** No dialog over the first frame, and the unread badge on, which [theLibraryChangeIsTimed] reads. */
    private fun prepareMain() {
        graph.basePreferences.batteryExemptionRequested.set(true)
        graph.crashLog.markExported()
        graph.libraryPreferences.unreadBadge.set(true)
    }

    /** [MainActivity] on the Library tab once its title is up, closed after [body]. */
    private fun withMain(body: (ActivityScenario<MainActivity>) -> Unit) {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            awaitNode(hasText(context.getString(R.string.library_title)), OPEN_TIMEOUT_MILLIS)
            body(scenario)
        } finally {
            scenario.close()
        }
    }

    /**
     * The library screen's projections between [from] and [to] (uptime ms), from the view model's
     * DEBUG lines (`LibraryProjection:`, phase 5 unit SC4's item 1), as offsets from [from]: when
     * the projection's inputs arrived (the query, its mapping and the other inputs before it), how
     * long the projection took, when the state was set on Main, and the rest to [to], the frame.
     * Only the last projection before [to] drew what [to] saw; the earlier ones are listed with it.
     */
    private fun projectionSplit(from: Long, to: Long): String {
        val lines = GateFragments.shell("logcat -d -v raw -b main").lines().filter { PROJECTION_LOG in it }
        val projections = mutableListOf<String>()
        var pending: List<Long>? = null
        var lastState = -1L
        lines.forEach { line ->
            PROJECTED.find(line)?.let { match -> pending = match.groupValues.drop(1).map { it.toLong() } }
            STATE_SET.find(line)?.let { match ->
                val stateAt = match.groupValues[1].toLong()
                val projected = pending
                pending = null
                if (projected != null && stateAt in from..to) {
                    val (entries, inputs, millis, filter) = projected
                    projections += "$entries entries: inputs +${inputs - from}, projected in $millis (filter $filter), " +
                        "state +${stateAt - from}"
                    lastState = stateAt
                }
            }
        }
        if (projections.isEmpty()) return "no projection logged in the window"
        return "${projections.joinToString("; ")}; frame ${to - lastState} ms after the last state"
    }

    /**
     * The library read taken apart, each the median of [READ_RUNS] runs on IO: the view's query
     * with the cursor stepped and no column read (the SQL), the repository's read (the SQL and the
     * mapping into rows), and the screen's [com.shinsaikou.domain.library.interactor.GetLibraryItems]
     * first emission (the read, the download counts and the items).
     */
    private fun libraryReads(): String = runBlocking(Dispatchers.IO) {
        suspend fun timed(block: suspend () -> Unit): Long {
            val startedAt = SystemClock.elapsedRealtime()
            block()
            return SystemClock.elapsedRealtime() - startedAt
        }
        runCatching {
            var rows = 0L
            val sql = median(
                (1..READ_RUNS).map {
                    timed {
                        rows = graph.database.libraryViewQueries.library().execute { cursor ->
                            QueryResult.AsyncValue {
                                var count = 0L
                                while (cursor.next().await()) count++
                                count
                            }
                        }.await()
                    }
                },
            )
            val mapped = median((1..READ_RUNS).map { timed { graph.mangaRepository.getLibraryManga() } })
            val items = median((1..READ_RUNS).map { timed { graph.getLibraryItems.subscribe().first() } })
            var manifest = 0L
            val counts = median(
                (1..READ_RUNS).map {
                    timed { manifest = graph.downloadManifestRepository.subscribeCountsByManga().first().values.sum() }
                },
            )
            "the library read alone (medians of $READ_RUNS): the query stepped over $rows rows $sql ms, the " +
                "repository's read with its mapping $mapped ms, GetLibraryItems' first emission $items ms, " +
                "subscribeCountsByManga's first emission over $manifest manifest rows $counts ms"
        }.getOrElse { "the library read alone was not timed: $it" }
    }

    /** Any seeded entry's grid cell: its title as text or cover description, clickable, not the search field. */
    private fun seededCell(): SemanticsMatcher = SemanticsMatcher("a seeded entry's cell") { node ->
        val texts = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        texts.any { it.startsWith(SyncLibrarySeeder.TITLE_PREFIX) }
    } and hasClickAction() and hasSetTextAction().not()

    /** [title]'s grid cell, by its text or its cover's description. */
    private fun cell(title: String): SemanticsMatcher = SemanticsMatcher("the cell of $title") { node ->
        val texts = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        title in texts
    } and hasClickAction() and hasSetTextAction().not()

    /** [title]'s cell showing the unread badge for [unread]. */
    private fun cellWithBadge(title: String, unread: Int): SemanticsMatcher =
        cell(title) and hasContentDescription(context.getString(R.string.library_badge_unread, unread))

    private fun exists(matcher: SemanticsMatcher): Boolean = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun awaitNode(matcher: SemanticsMatcher, timeoutMillis: Long) {
        compose.waitUntil(timeoutMillis) { exists(matcher) }
    }

    private fun clickFirst(matcher: SemanticsMatcher) {
        awaitNode(matcher, UI_TIMEOUT_MILLIS)
        compose.onAllNodes(matcher).onFirst().performClick()
    }

    /** Polls [condition] every [POLL_MILLIS] ms; fails naming [what] after [timeoutMillis]. */
    private fun waitFor(timeoutMillis: Long, what: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (!condition()) {
            if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("$what not within $timeoutMillis ms")
            Thread.sleep(UI_POLL_MILLIS)
        }
    }

    /**
     * [elapsed] added to the samples of [id] kept in the app's files under `saikou.scale.sample` `k/n`
     * (the first sample starts the file), and every sample once `k` is `n`, else null. Without the
     * argument, [elapsed] alone.
     */
    private fun sample(id: String, elapsed: Long): List<Long>? {
        val spec = arguments.getString(SAMPLE_ARGUMENT)?.takeIf { it.isNotBlank() } ?: return listOf(elapsed)
        val (k, n) = spec.split('/').map { it.trim().toInt() }
        val file = File(context.filesDir, "$SAMPLES_DIRECTORY/$id")
        file.parentFile?.mkdirs()
        if (k == 1) file.writeText("")
        file.appendText("$elapsed\n")
        Log.i(TAG, "sample $k of $n for $id: $elapsed ms")
        return if (k >= n) file.readLines().filter { it.isNotBlank() }.map { it.trim().toLong() } else null
    }

    private fun median(samples: List<Long>): Long = samples.sorted().let { sorted ->
        if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
    }

    private fun signInArguments(): SignIn? {
        val url = arguments.getString("saikou.sync.pair.url")
        val user = arguments.getString("saikou.sync.pair.user")
        val password = arguments.getString("saikou.sync.pair.password")
        if (url.isNullOrBlank() || user.isNullOrBlank() || password.isNullOrBlank()) return null
        return SignIn(url, user, password)
    }

    private fun configured(): Boolean = SyncPreferences(graph.preferenceStore).backendId.get().isNotEmpty()

    /**
     * Cancels the `sync-once` work, waits until none is waiting or running, and collects the heap a
     * cancelled run left. Returns how many requests were waiting or running.
     */
    private suspend fun cancelAutomaticSync(): Int {
        val waiting = graph.workManager.getWorkInfosForUniqueWork(SyncScheduler.WORK_NAME_ONCE).get().count { it.state in PENDING }
        graph.workManager.cancelUniqueWork(SyncScheduler.WORK_NAME_ONCE)
        awaitNoSyncWork(SYNC_TIMEOUT_MILLIS)
        Runtime.getRuntime().gc()
        return waiting
    }

    /** The fixture's sign-in checked and stored with the periodic run off, as `sync_3000_no_oom` stores it. */
    private suspend fun storeSignIn(signIn: SignIn): String {
        graph.syncWorkPreferences.intervalHours.set(0)
        graph.syncScheduler.setup()
        val status = graph.webDavAccount.signIn(signIn.url, signIn.user, signIn.password)
        check(status.reachable && status.authenticated) { "the fixture refused the sign-in: $status" }
        val backend = checkNotNull(graph.webDavAccount.backend()) { "the stored sign-in builds no backend" }
        SyncPreferences(graph.preferenceStore).backendId.set(backend.id)
        return backend.id
    }

    /**
     * The device's first sync run, started as a sign-in in Sync settings starts it, or with Sync
     * now when the sign-in is already stored, and waited for as `SyncPairTest.awaitRun` waits: until
     * no `sync-once` work is waiting or running and a run has taken the lock since the start, or
     * WorkManager holds a retry. Returns the elapsed time from the start and how it started.
     */
    private suspend fun timedFirstRun(signIn: SignIn): Pair<Long, String> {
        awaitNoSyncWork(SYNC_TIMEOUT_MILLIS)
        val record = graph.syncRunRecord
        val wall = Clock.System.now().toEpochMilliseconds()
        val startedAt: Long
        val how: String
        if (configured()) {
            startedAt = SystemClock.elapsedRealtime()
            check(graph.syncScheduler.syncNow()) { "Sync now enqueued nothing" }
            how = "the sign-in was stored already; Sync now"
        } else {
            graph.syncWorkPreferences.intervalHours.set(0)
            graph.syncScheduler.setup()
            val status = graph.webDavAccount.signIn(signIn.url, signIn.user, signIn.password)
            check(status.reachable && status.authenticated) { "the fixture refused the sign-in: $status" }
            val backend = checkNotNull(graph.webDavAccount.backend()) { "the stored sign-in builds no backend" }
            startedAt = SystemClock.elapsedRealtime()
            graph.syncSession.signedIn(backend)
            how = "signed in through SyncSession.signedIn, which starts the first run"
        }
        val ended = withTimeoutOrNull(SYNC_TIMEOUT_MILLIS) {
            while (true) {
                val infos = onceWork()
                if (infos.any { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0 }) break
                if (infos.none { it.state in PENDING } && record.lastAttemptAt.get() >= wall) break
                delay(POLL_MILLIS)
            }
            true
        }
        check(ended == true) {
            "no sync run since the start ended within $SYNC_TIMEOUT_MILLIS ms: ${onceWork().map { "${it.state} after ${it.runAttemptCount}" }}"
        }
        return (SystemClock.elapsedRealtime() - startedAt) to how
    }

    private fun onceWork(): List<WorkInfo> = graph.workManager.getWorkInfosForUniqueWork(SyncScheduler.WORK_NAME_ONCE).get()

    private suspend fun awaitNoSyncWork(timeoutMillis: Long) {
        val idle = withTimeoutOrNull(timeoutMillis) {
            while (onceWork().any { it.state in PENDING }) delay(POLL_MILLIS)
            true
        }
        check(idle == true) { "sync-once work was still pending after $timeoutMillis ms" }
    }

    /** What the fixture holds after a push, through the device's own backend. */
    private suspend fun remoteFiles(): String = runCatching {
        val files = checkNotNull(graph.webDavAccount.backend()) { "no backend" }.list("")
        "${files.size} files, ${files.sumOf { it.size }} bytes on the fixture"
    }.getOrElse { "the fixture's files could not be listed: $it" }

    /** This process's log lines of the engine's run timings ([SyncEngine.RUN_TIMINGS]), oldest first. */
    private fun timingLines(): List<String> = runCatching {
        GateFragments.shell("logcat -d -v brief --pid=${android.os.Process.myPid()}")
            .lines().filter { SyncEngine.RUN_TIMINGS in it }
    }.getOrDefault(emptyList())

    /**
     * Where the [elapsed] ms of the run just timed went (phase 5 unit SC3), from the debug lines this
     * process logged: each step of the engine's last run as [com.shinsaikou.sync.engine.SyncTimings] names
     * it (ms), the safety backup's ms (0 when none was written), `other` for the rest of [elapsed]
     * (WorkManager's start, the sign-in's own calls, the wait for idle), and `runsLogged`, the engine
     * runs logged since [loggedBefore] lines, which is 1 when the timed run was the only one. Empty
     * when the log holds no new line, which the row's reader takes as "no split".
     */
    private fun runSplit(elapsed: Long, loggedBefore: Int): Map<String, Long> {
        val lines = timingLines()
        val last = lines.drop(loggedBefore).lastOrNull() ?: return emptyMap()
        val split = linkedMapOf<String, Long>()
        TIMING_PAIR.findAll(last.substringAfter(SyncEngine.RUN_TIMINGS)).forEach { match ->
            split[match.groupValues[1]] = match.groupValues[2].toLong()
        }
        val safetyBackup = runCatching {
            GateFragments.shell("logcat -d -v brief --pid=${android.os.Process.myPid()}")
                .lines().lastOrNull { SyncSafetyBackup.WRITTEN_IN in it }
                ?.substringAfter(SyncSafetyBackup.WRITTEN_IN)?.trim()?.toLongOrNull()
        }.getOrNull() ?: 0L
        split["safetyBackup"] = safetyBackup
        split["other"] = elapsed - (split["run"] ?: 0L) - safetyBackup
        split["runsLogged"] = (lines.size - loggedBefore).toLong()
        return split
    }

    /** This process's `OutOfMemoryError` lines in the log, at most three. */
    private fun outOfMemoryLines(): List<String> = runCatching {
        GateFragments.shell("logcat -d -v brief --pid=${android.os.Process.myPid()}")
            .lines().filter { "OutOfMemoryError" in it }.map { it.trim() }.take(3)
    }.getOrDefault(emptyList())

    /**
     * The process's PSS in kB, read in the process from `/proc/self` by [Debug.getPss]. Not through
     * `dumpsys meminfo`, which asks the measured process's own binder thread to write the dump: at
     * 40,000 entries that allocation, on a heap with under 1% free, was what died first in the first
     * run's push (`IApplicationThread.onTransact` reading the dump's file descriptor).
     */
    private fun pss(): Long = Debug.getPss()

    /**
     * The used Java heap every [HEAP_SAMPLE_MILLIS] ms with no GC first, so the peak can overstate
     * the live heap, and the process's PSS every [PSS_SAMPLE_MILLIS] ms, both logged under [TAG]
     * for the evidence and their peaks given to the row's note.
     */
    private inner class HeapSampler(private val label: String) : AutoCloseable {
        private val runtime = Runtime.getRuntime()
        private val peak = AtomicLong()
        private val peakPss = AtomicLong()
        private val running = AtomicBoolean(true)
        private val startedAt = SystemClock.elapsedRealtime()

        /** When the sampler met an [OutOfMemoryError] and stopped, in ms from the start; -1 while it ran. */
        private val stoppedByOutOfMemory = AtomicLong(-1)

        /**
         * An [OutOfMemoryError] here ends the sampling and nothing else: uncaught on this thread it
         * would kill the process (the first run's export and fingerprint died in this loop), and the
         * row would lose the method's own catch and note. The heap the method is filling meets the
         * error on its own thread too.
         */
        private val sampler = thread(name = "scale-memory-$label", isDaemon = true) {
            var lastPss = 0L
            try {
                while (running.get()) {
                    val used = runtime.totalMemory() - runtime.freeMemory()
                    peak.accumulateAndGet(used, ::maxOf)
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastPss >= PSS_SAMPLE_MILLIS) {
                        lastPss = now
                        val kb = pss()
                        peakPss.accumulateAndGet(kb, ::maxOf)
                        Log.i(TAG, "memory $label t=${now - startedAt}ms: heap used ${used / MB} MB; PSS $kb kB")
                    }
                    try {
                        Thread.sleep(HEAP_SAMPLE_MILLIS)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            } catch (_: OutOfMemoryError) {
                stoppedByOutOfMemory.set(SystemClock.elapsedRealtime() - startedAt)
            }
        }

        fun summary(): String {
            val memoryClass = context.getSystemService(android.app.ActivityManager::class.java).memoryClass
            return "peak used Java heap ${peak.get() / MB} MB of ${runtime.maxMemory() / MB} MB (every $HEAP_SAMPLE_MILLIS ms, " +
                "no GC first), peak PSS ${peakPss.get() / 1024} MB (every ${PSS_SAMPLE_MILLIS / 1000} s), memoryClass " +
                "$memoryClass MB" + stoppedByOutOfMemory.get().let { if (it < 0) "" else "; the sampler met an OutOfMemoryError at t=$it ms" }
        }

        override fun close() {
            running.set(false)
            sampler.interrupt()
            sampler.join(SAMPLER_JOIN_MILLIS)
            Log.i(TAG, "memory $label done: ${summary()}")
        }
    }

    private companion object {
        const val TAG = "SaikouScaleRows"

        /** One `key=<ms>` pair of [SyncEngine.RUN_TIMINGS]'s line. */
        val TIMING_PAIR = Regex("(\\w+)=(\\d+)")

        const val SCALE_ARGUMENT = "saikou.scale.40k"
        const val ENTRIES_ARGUMENT = "saikou.scale.entries"
        const val CHAPTERS_ARGUMENT = "saikou.scale.chapters"
        const val BATCH_ARGUMENT = "saikou.scale.batch"

        /** `k/n`: this launch is sample k of n of a row the runner calls n times in fresh processes. */
        const val SAMPLE_ARGUMENT = "saikou.scale.sample"

        /** Where the runner pushes `big_backup.py`'s file; the argument names another path. */
        const val BACKUP_ARGUMENT = "saikou.scale.backup"
        const val BACKUP_DEVICE_PATH = "/data/local/tmp/saikou-scale/backup.tachibk"

        const val DEFAULT_ENTRIES = 40_000
        const val DEFAULT_CHAPTERS = 50

        /** Entries per seed transaction: 25,000 chapter rows at 50 chapters, a few MB of heap. */
        const val DEFAULT_BATCH = 500

        /** A fragment's value is a JSON literal; a row that did not finish carries this string. */
        const val FAILED = "\"failed\""

        const val SAMPLES_DIRECTORY = "scale-samples"
        const val RESTORE_FILE_NAME = "scale-restore.tachibk"
        const val EXPORT_FILE_NAME = "scale-export.tachibk"

        /** Four phrases of ten titles each: entries 0010 to 0049 of the seeder's names. */
        const val SEARCH_QUERY = "\"entry 001\" || \"entry 002\" || \"entry 003\" || \"entry 004\""

        /** A matching entry on the first tab (16 mod 8 is entry 0's category). */
        const val SEARCH_MATCH = 16
        const val SEARCH_RUNS = 3

        /** `LibraryViewModel.SEARCH_DEBOUNCE_MS`, which is private there. */
        const val SEARCH_DEBOUNCE_MILLIS = 250L

        const val QUEUE_RUNS = 3
        const val READ_RUNS = 3

        /** Entries whose chapters [ensureManifest] records, every [MANIFEST_EVERY]th, [MANIFEST_BATCH] per transaction. */
        const val MANIFEST_ARGUMENT = "saikou.scale.manifest"
        const val MANIFEST_EVERY = 20
        const val MANIFEST_BATCH = 100
        const val MANIFEST_PAGES = 20L

        /** `LibraryViewModel`'s DEBUG lines, which [projectionSplit] reads. */
        const val PROJECTION_LOG = "LibraryProjection:"
        val PROJECTED = Regex("""LibraryProjection: (\d+) entries, inputs at (\d+), projected in (\d+) ms, filtered in (\d+) ms""")
        val STATE_SET = Regex("""LibraryProjection: state set at (\d+)""")
        const val QUEUE_ROWS = 5_000
        const val NEXT_ROW_CALLS = 100
        const val REMOVE_CHUNK = 500
        const val STATS_RUNS = 3

        /** A seeded entry no other method writes to, whose unread chapters the stall method marks read. */
        const val STALL_ENTRY = 2
        const val STALL_IDLE_WRITES = 5

        /** Long enough for the fingerprint to be reading pages, a small part of its time at any size here. */
        const val STALL_WRITE_DELAY_MILLIS = 2_000L

        /** How [fingerprint] reads, for the note. */
        const val FINGERPRINT_READ = "SyncDigest.of in SyncStoreSnapshot.read, BEGIN DEFERRED on a read-only connection of its own"

        const val OPEN_TIMEOUT_MILLIS = 300_000L
        const val CHANGE_TIMEOUT_MILLIS = 300_000L
        const val SEARCH_TIMEOUT_MILLIS = 120_000L
        const val STATS_TIMEOUT_MILLIS = 300_000L
        const val UI_TIMEOUT_MILLIS = 60_000L
        const val FINGERPRINT_TIMEOUT_MILLIS = 600_000L
        const val SYNC_TIMEOUT_MILLIS = 5_400_000L
        const val POLL_MILLIS = 200L
        const val UI_POLL_MILLIS = 10L

        const val HEAP_SAMPLE_MILLIS = 100L
        const val PSS_SAMPLE_MILLIS = 10_000L
        const val SAMPLER_JOIN_MILLIS = 2_000L
        const val MB = 1024L * 1024L

        val PENDING = setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED, WorkInfo.State.RUNNING)

        val NOTIFICATION_PERMISSIONS: Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                emptyArray()
            }
    }
}

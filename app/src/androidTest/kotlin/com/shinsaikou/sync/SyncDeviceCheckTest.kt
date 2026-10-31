package com.shinsaikou.sync

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import com.shinsaikou.SaikouApp
import com.shinsaikou.backup.export.BackupFileNames
import com.shinsaikou.di.AppGraph
import com.shinsaikou.util.StagePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.time.Duration.Companion.minutes

/**
 * The steps of unit G's device check that only the app's own process can take (plan unit G: "stops
 * the worker part way (a WorkManager cancel ...), starts it again, and confirms the second run
 * resumes"). None is a test: each returns at once unless `saikou.sync.check` names it, strict mode
 * or not, so the gate's full `:app` suite runs through them as no-ops. The orchestration (the
 * fixture, the sign-in through the UI, the job timeout, the cold start) is the device check's own,
 * recorded in unit G's report; M's check reruns it.
 *
 * - `seed`: [SyncLibrarySeeder.seed], the generated library.
 * - `mark-read`: [SyncLibrarySeeder.markMoreRead] for `saikou.sync.count` chapters, so the log holds
 *   rows past the first export and a later pull has segments to resume through.
 * - `cancel`: Sync now (or, with `saikou.sync.start=wait`, nothing: the owner signs in through the
 *   UI meanwhile), then `WorkManager.cancelUniqueWork` on `sync-once` once the run has gone as far
 *   as `saikou.sync.until` says (`parts:N`, the export's written parts; `entries:N`, the library's
 *   entries; `cursor`, any pull cursor past where it started), then waits for the cancel.
 * - `enqueue-later`: a `sync-once` request delayed by ten minutes, for a cold start to force.
 * - `order`: during a fresh join (after `pm clear`, the owner signs in through the UI while this
 *   runs), when the safety backup is complete against when the first remote row lands, on this
 *   process's one clock (fix round 1 item 4). M's check reuses it.
 * - `report`: the sync state, logged.
 *
 * Everything is logged under [TAG] with the sync preferences as they stand, so logcat is the record.
 * Running this starts the app's process fresh (`am instrument` restarts the target), which ends any
 * run that process had in progress; the report says so where it matters.
 */
@RunWith(AndroidJUnit4::class)
class SyncDeviceCheckTest {

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SaikouApp
    private val graph: AppGraph get() = app.graph
    private val arguments get() = InstrumentationRegistry.getArguments()

    private fun asked(step: String): Boolean {
        val asked = arguments.getString("saikou.sync.check") == step
        if (!asked) Log.i(TAG, "Skipped: saikou.sync.check is not $step")
        return asked
    }

    /**
     * Each step runs in a process of its own and a later one reads the sync state this one left, so
     * every preference set is on disk before the process is killed (phase 5 unit TG4).
     */
    @After
    fun commitPreferences() = StagePreferences.commit()

    @Test
    fun seed() {
        if (!asked("seed")) return
        runBlocking(Dispatchers.IO) {
            val library = SyncLibrarySeeder.seed(graph)
            Log.i(TAG, "seeded entries=${library.mangaIds.size} chaptersInserted=${library.chaptersInserted}")
            Log.i(TAG, "state ${state()}")
        }
    }

    @Test
    fun markRead() {
        if (!asked("mark-read")) return
        val count = checkNotNull(arguments.getString("saikou.sync.count")?.toIntOrNull()) { "saikou.sync.count is missing" }
        runBlocking(Dispatchers.IO) {
            val marked = SyncLibrarySeeder.markMoreRead(graph, count)
            Log.i(TAG, "marked read=$marked latestSeq=${graph.syncChangeLogRepository.getLatestSeq()}")
            Log.i(TAG, "state ${state()}")
        }
    }

    @Test
    fun cancel() {
        if (!asked("cancel")) return
        val until = checkNotNull(arguments.getString("saikou.sync.until")) { "saikou.sync.until is missing" }
        runBlocking(Dispatchers.IO) {
            val start = snapshot()
            // Finished work stays listed under its name until WorkManager prunes it; only a run
            // enqueued from here on is the one to stop.
            val earlier = graph.workManager.getWorkInfosForUniqueWork(SyncScheduler.WORK_NAME_ONCE).get().map { it.id }.toSet()
            Log.i(TAG, "cancel start ${state()}")
            // "wait" leaves the start to someone else: a sign-in through the UI while this runs in the
            // app's process, whose first run is then the one stopped.
            if (arguments.getString("saikou.sync.start") != "wait") {
                assertTrue("Sync now enqueued nothing: no backend is configured", graph.syncScheduler.syncNow())
            }
            withTimeout(10.minutes) {
                while (!reached(until, start)) {
                    val infos = graph.workManager.getWorkInfosForUniqueWork(SyncScheduler.WORK_NAME_ONCE).get()
                        .filter { it.id !in earlier }
                    check(infos.none { it.state == WorkInfo.State.SUCCEEDED || it.state == WorkInfo.State.FAILED }) {
                        "the run ended before $until: ${infos.map { it.state }}"
                    }
                    delay(POLL_MS)
                }
            }
            Log.i(TAG, "cancel reached $until ${state()}")
            graph.workManager.cancelUniqueWork(SyncScheduler.WORK_NAME_ONCE).result.get()
            withTimeout(2.minutes) {
                graph.workManager.getWorkInfosForUniqueWorkFlow(SyncScheduler.WORK_NAME_ONCE)
                    .first { infos ->
                        val current = infos.filter { it.id !in earlier }
                        current.isNotEmpty() && current.all { it.state == WorkInfo.State.CANCELLED }
                    }
            }
            // The worker's coroutine unwinds after WorkManager marks the work cancelled, and nothing
            // outside the session says when its lock is free (R39), so this waits until the state the
            // run writes has stood still for a second.
            withTimeout(2.minutes) {
                var last = snapshot()
                var still = 0
                while (still < SETTLED_POLLS) {
                    delay(POLL_MS)
                    val now = snapshot()
                    still = if (now == last) still + 1 else 0
                    last = now
                }
            }
            Log.i(TAG, "cancel done ${state()}")
        }
    }

    /**
     * A `sync-once` request that waits [LATER] before it may run, so a JobScheduler job for it exists
     * that `cmd jobscheduler run -f` can start in a cold process. A periodic request cannot serve:
     * WorkManager delays a periodic run forced before its schedule ("being executed before
     * schedule") and does no work. This process's own force stop at the end of the instrumentation
     * drops the job from JobScheduler; the app's next start reschedules it from WorkManager's table.
     */
    @Test
    fun enqueueLater() {
        if (!asked("enqueue-later")) return
        runBlocking(Dispatchers.IO) {
            graph.syncScheduler.syncSoon(LATER)
            Log.i(TAG, "enqueued sync-once in $LATER ${state()}")
        }
    }

    /**
     * Samples about every [ORDER_POLL_MS] until both have happened: the library's entry count first,
     * then whether the safety backup is recorded (`SyncSafetyBackup` records the name only after the
     * stream is closed), then the safety file's size. Reading the count first makes the check sound:
     * a sample that sees a row the join added and then an unrecorded backup is a row that landed
     * before the backup was complete, whatever happened between the two reads. The times are
     * milliseconds on `SystemClock.elapsedRealtime` since the step started; the widest gap between
     * samples is logged, since two events inside one gap cannot be ordered by the times alone.
     */
    @Test
    fun order() {
        if (!asked("order")) return
        runBlocking(Dispatchers.IO) {
            val preferences = graph.syncWorkPreferences
            val folder = File(app.filesDir, AUTOMATIC_BACKUP_FOLDER)
            val startEntries = graph.syncStore.libraryCounts().libraryEntries
            check(preferences.safetyBackupFile.get().isEmpty()) { "a safety backup is already recorded: not a fresh join" }
            val start = SystemClock.elapsedRealtime()
            Log.i(TAG, "order start wall=${System.currentTimeMillis()} entries=$startEntries folder=$folder ${state()}")
            var fileSeen: Long? = null
            var fileNonEmpty: Long? = null
            var complete: Long? = null
            var completeSize = -1L
            var firstRow: Long? = null
            var entriesAtFirstRow = 0L
            var rowBeforeComplete = false
            var samples = 0
            var maxGap = 0L
            var previous = start
            withTimeout(10.minutes) {
                while (firstRow == null || complete == null) {
                    val entries = graph.syncStore.libraryCounts().libraryEntries
                    val recorded = preferences.safetyBackupFile.get()
                    val size = folder.listFiles()
                        ?.filter { BackupFileNames.SYNC_BACKUP_REGEX.matches(it.name) }
                        ?.maxOfOrNull { it.length() }
                    val now = SystemClock.elapsedRealtime()
                    val at = now - start
                    samples++
                    maxGap = maxOf(maxGap, now - previous)
                    previous = now
                    if (size != null && fileSeen == null) fileSeen = at
                    if (size != null && size > 0 && fileNonEmpty == null) fileNonEmpty = at
                    if (recorded.isNotEmpty() && complete == null) {
                        complete = at
                        completeSize = File(folder, recorded).length()
                    }
                    if (entries > startEntries && firstRow == null) {
                        firstRow = at
                        entriesAtFirstRow = entries
                        if (recorded.isEmpty()) rowBeforeComplete = true
                    }
                    delay(ORDER_POLL_MS)
                }
            }
            Log.i(
                TAG,
                "order result fileFirstSeenMs=$fileSeen fileNonEmptyMs=$fileNonEmpty completeMs=$complete " +
                    "completeSize=$completeSize firstRemoteRowMs=$firstRow entriesAtFirstRow=$entriesAtFirstRow " +
                    "rowSeenBeforeComplete=$rowBeforeComplete samples=$samples maxGapMs=$maxGap",
            )
            Log.i(TAG, "order end ${state()}")
            assertTrue("a remote row landed before the safety backup was complete", !rowBeforeComplete)
        }
    }

    @Test
    fun report() {
        if (!asked("report")) return
        runBlocking(Dispatchers.IO) { Log.i(TAG, "state ${state()}") }
    }

    private data class Snapshot(val parts: Int, val entries: Long, val cursors: Map<String, Long>)

    private suspend fun snapshot(): Snapshot {
        val preferences = SyncPreferences(graph.preferenceStore)
        return Snapshot(
            parts = preferences.exportProgress.get()?.parts ?: 0,
            entries = graph.syncStore.libraryCounts().libraryEntries,
            cursors = preferences.pullCursors.get(),
        )
    }

    private suspend fun reached(until: String, start: Snapshot): Boolean {
        val now = snapshot()
        return when {
            until.startsWith("parts:") -> now.parts >= until.removePrefix("parts:").toInt()
            until.startsWith("entries:") -> now.entries >= until.removePrefix("entries:").toLong()
            until == "cursor" -> now.cursors.any { (device, seq) -> seq > (start.cursors[device] ?: Long.MIN_VALUE) }
            else -> error("saikou.sync.until is not parts:N, entries:N or cursor: $until")
        }
    }

    private suspend fun state(): String {
        val preferences = SyncPreferences(graph.preferenceStore)
        val record = graph.syncRunRecord
        val counts = graph.syncStore.libraryCounts()
        val infos = graph.workManager.getWorkInfosForUniqueWork(SyncScheduler.WORK_NAME_ONCE).get() +
            graph.workManager.getWorkInfosForUniqueWork(SyncScheduler.WORK_NAME_PERIODIC).get()
        return listOf(
            "backend=${preferences.backendId.get()}",
            "device=${preferences.deviceId.get()}",
            "exported=${preferences.exportedBackend.get()}",
            "exportProgress=${preferences.exportProgress.get()}",
            "lastPushedSeq=${preferences.lastPushedSeq.get()}",
            "latestSeq=${graph.syncChangeLogRepository.getLatestSeq()}",
            "cursors=${preferences.pullCursors.get()}",
            "counts=$counts",
            "outcome=${record.outcome()}",
            "reports=${record.reports.get().size}",
            "safetyBackup=${graph.syncWorkPreferences.safetyBackupFile.get()}",
            "work=${infos.map { "${it.tags.firstOrNull { tag -> tag.startsWith("sync") }}:${it.state}" }}",
        ).joinToString(" ")
    }

    private companion object {
        const val TAG = "SyncDeviceCheck"
        const val POLL_MS = 50L
        const val SETTLED_POLLS = 20
        const val ORDER_POLL_MS = 20L

        /** `AutomaticBackupFiles`' app-private folder, which a fresh install uses (no folder chosen). */
        const val AUTOMATIC_BACKUP_FOLDER = "backups"
        val LATER = 10.minutes
    }
}

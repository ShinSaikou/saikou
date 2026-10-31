package com.shinsaikou.sync

import com.shinsaikou.domain.download.AutoDownload
import com.shinsaikou.domain.preferences.SyncRestorePreferences
import com.shinsaikou.domain.source.repository.SourceAliasRepository
import com.shinsaikou.domain.sync.model.SyncDeletedKey
import com.shinsaikou.domain.sync.model.SyncEntity
import com.shinsaikou.domain.sync.model.SyncLibraryCounts
import com.shinsaikou.domain.sync.repository.SyncChangeLogRepository
import com.shinsaikou.domain.sync.repository.SyncStore
import com.shinsaikou.sync.engine.LocalChanges
import com.shinsaikou.sync.engine.SegmentApply
import com.shinsaikou.sync.engine.SyncCompaction
import com.shinsaikou.sync.engine.SyncPull
import com.shinsaikou.sync.engine.SyncPush
import com.shinsaikou.sync.engine.SyncTimings
import com.shinsaikou.sync.format.ChapterKey
import com.shinsaikou.sync.format.MalformedSyncFileException
import com.shinsaikou.sync.format.MangaKey
import com.shinsaikou.sync.format.RowKind
import com.shinsaikou.sync.format.SyncFormat
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import logcat.logcat
import java.io.IOException
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

/**
 * Turns the change log into segments and other devices' files into merged rows (phase 3 unit E2),
 * over one [SyncBackend]. A run pulls, pushes, then deletes and compacts.
 *
 * Runs never overlap: the engine is one instance per process and holds one [Mutex] for a whole
 * run; a run that finds it held returns null at once, without work (Q10). Every file applied and
 * every file pushed moves its cursor as soon as it is done, so a run stopped anywhere resumes where
 * it stopped: a full export under the same export id, a pull at the file, or the export part, it
 * had reached.
 *
 * After a restore ([SyncRestorePreferences.restoredSinceSync]) the run first forgets every pull
 * cursor and this device's full export, so it pulls every other device again from its newest
 * snapshot or export, and pushes a new full export before any segment. The flag is consumed into
 * that state before the pull, so a restore that finishes while the run is under way sets it again
 * and the next run repeats the work.
 *
 * A file of a newer format stops the run with [com.shinsaikou.sync.format.NewerFormatException] before
 * any of its rows is read, and moves no cursor (Q4). A backend failure is the [IOException] it is,
 * and a file that is not a well-formed one is a [MalformedSyncFileException]; each ends the run with
 * every cursor where the last finished file left it.
 *
 * The chapters the pull inserted go to [AutoDownload] once it ends, finished or stopped, after
 * every transaction that holds them has committed and so outside the `is_syncing` latch: what the
 * library update on the other device would have downloaded there is downloaded here (phase 3 unit
 * AD). [SyncPull.newChapterIds] leaves out a snapshot's rows, a first pull's export and entries new
 * to the library.
 */
@SingleIn(AppScope::class)
@Inject
class SyncEngine(
    private val store: SyncStore,
    private val changeLog: SyncChangeLogRepository,
    sourceAliases: SourceAliasRepository,
    private val preferences: SyncPreferences,
    private val restorePreferences: SyncRestorePreferences,
    merge: MergeEngine,
    private val autoDownload: AutoDownload,
) {

    private val mutex = Mutex()
    private val segmentApply = SegmentApply(store, sourceAliases, merge)

    /** One run against [backend], or null when another run holds the lock. */
    suspend fun run(backend: SyncBackend): SyncRunResult? {
        if (!mutex.tryLock()) return null
        val timings = SyncTimings()
        val started = TimeSource.Monotonic.markNow()
        try {
            val deviceId = deviceId()
            if (restorePreferences.restoredSinceSync.get()) forgetAfterRestore()
            val before = store.libraryCounts()

            val pull = SyncPull(backend, deviceId, preferences, segmentApply, localChanges(), timings)
            try {
                timings.time(SyncTimings.Step.PULL) { pull.run() }
            } finally {
                queuePulledChapters(pull.newChapterIds)
            }
            if (pull.result.skipped > 0) preferences.skippedRows.set(true)

            // The full export, when one is due, is taken after this complete pull (Q3).
            val push = SyncPush(backend, deviceId, store, changeLog, preferences, timings)
            timings.time(SyncTimings.Step.PUSH) { push.run() }

            // A device that skipped a row or stopped at a gap holds less than its cursors claim.
            val compaction = SyncCompaction(backend, deviceId, store, preferences)
            if (!pull.stoppedAtGap && !preferences.skippedRows.get()) {
                timings.time(SyncTimings.Step.COMPACTION) { compaction.run() }
            }

            return SyncRunResult(
                segmentsApplied = pull.segmentsApplied,
                exportsApplied = pull.exportsApplied,
                snapshotApplied = pull.snapshotApplied,
                rowsWritten = pull.result.written,
                rowsSkipped = pull.result.skipped,
                stoppedAtGap = pull.stoppedAtGap,
                segmentsPushed = push.segmentsPushed,
                exported = push.exported,
                compacted = compaction.compacted,
                filesDeleted = compaction.filesDeleted,
                conflicts = pull.result.conflicts,
                before = before,
                after = store.libraryCounts(),
            )
        } finally {
            timings.add(SyncTimings.Step.RUN, started.elapsedNow().inWholeNanoseconds)
            // A run that failed or was cancelled logs how far its time went too.
            logcat(LogPriority.DEBUG) { "$RUN_TIMINGS ${timings.line()}" }
            mutex.unlock()
        }
    }

    /**
     * With no backend configured, drops the whole change log: nothing will read it, and the next
     * first push is a full export, which carries every row the log named. Called at app start. A
     * run in progress (it holds the lock) has a backend, so this returns at once.
     */
    suspend fun pruneLogWithoutBackend() {
        if (preferences.backendId.get().isNotEmpty() || !mutex.tryLock()) return
        try {
            changeLog.prune(changeLog.getLatestSeq())
            preferences.exportedBackend.delete()
            preferences.exportProgress.delete()
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Sign-out's part in the engine (phase 3 unit G): this device forgets the configured backend and
     * everything it knew of it, its id there, its pull cursors and part marks, its pushed seq, its
     * full export (done or in progress) and the skipped-row mark, so the next sign-in is a new device on whatever
     * backend it names: a new id, a full pull, and a full export. Changing the backend is a sign-out
     * followed by a sign-in, so this is the one reset. The old id's files stay on the server; no
     * device deletes another's (Q3).
     *
     * Takes the run's lock, so a run in progress finishes first and a run that starts meanwhile
     * finds the lock held and does nothing. The change log is left as it is: with no backend the
     * start-up prune drops it, and the next full export carries every row it named.
     */
    suspend fun forgetBackend() {
        mutex.withLock {
            preferences.backendId.delete()
            preferences.deviceId.delete()
            preferences.pullCursors.delete()
            preferences.pulledExportParts.delete()
            preferences.lastPushedSeq.delete()
            preferences.exportedBackend.delete()
            preferences.exportProgress.delete()
            preferences.skippedRows.delete()
        }
    }

    /**
     * Whether the change log holds a row after `lastPushedSeq`, which the next run would push. Reads
     * only. A full export raises `lastPushedSeq` to the log's counter, past every row it carried.
     */
    suspend fun hasUnpushedChanges(): Boolean = changeLog.getLatestSeq() > preferences.lastPushedSeq.get()

    /**
     * A failure is logged and ends nothing: the rows are committed, and a download the rule would
     * have queued is one the owner can still start.
     */
    private suspend fun queuePulledChapters(chapterIds: List<Long>) {
        if (chapterIds.isEmpty()) return
        try {
            autoDownload.queuePulledChapters(chapterIds)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN) { "Could not queue ${chapterIds.size} pulled chapters: ${e.javaClass.name}" }
        }
    }

    /** Generated under the run's lock, so two first runs cannot each pick one (Q10). */
    private fun deviceId(): String = preferences.deviceId.get().ifEmpty {
        Uuid.random().toString().also(preferences.deviceId::set)
    }

    /**
     * A restore merged rows without logging them, and this device's cursors are past what the other
     * devices sent before it: every cursor goes, with the part marks of exports being pulled (the
     * restore replaced rows those parts had merged), and so does the full export, done or in
     * progress.
     */
    private fun forgetAfterRestore() {
        preferences.pullCursors.delete()
        preferences.pulledExportParts.delete()
        preferences.exportedBackend.delete()
        preferences.exportProgress.delete()
        preferences.skippedRows.delete()
        restorePreferences.restoredSinceSync.set(false)
    }

    /**
     * The rows changed here since the last pull: the log after `lastPushedSeq`, by entity, and the
     * chapters hard-deleted since, by the key their log rows recorded (the latest time for a key).
     */
    private suspend fun localChanges(): LocalChanges {
        val afterSeq = preferences.lastPushedSeq.get()
        val removed = mutableMapOf<ChapterKey, Long>()
        var seq = afterSeq
        while (true) {
            val batch = changeLog.getChangesAfter(seq, SyncFormat.MAX_RAW_ROWS.toLong())
            if (batch.isEmpty()) break
            batch.forEach { change ->
                val key = change.deletedKey as? SyncDeletedKey.Chapter ?: return@forEach
                if (change.entity == SyncEntity.CHAPTER) {
                    removed[ChapterKey(MangaKey(key.source, key.mangaUrl), key.url)] = change.changedAt
                }
            }
            seq = batch.last().seq
        }
        return LocalChanges(SyncEntity.entries.associateWith { changeLog.getChangedIds(it, afterSeq).toSet() }, removed)
    }

    companion object {
        /**
         * What the debug line each run ends with starts with; `key=<ms>` pairs follow, one per
         * step (phase 5 unit SC3). The 40,000-entry bench finds the line by it.
         */
        const val RUN_TIMINGS = "Sync run timings in ms:"
    }
}

/**
 * What one run did.
 *
 * [rowsWritten] counts the rows the pull's merges changed and [conflicts] the true conflicts among
 * them: rows where a value this device had changed since its last pull was replaced by a different
 * remote value. The rest are [routineMerges]. [before] and [after] are the library's counts at the
 * run's start and end. [stoppedAtGap] says a device's listing shows files past the point its chain
 * reached, which the next run retries (Q3); [rowsSkipped] counts rows the pull could not store,
 * after which this device never compacts.
 */
data class SyncRunResult(
    val segmentsApplied: Int,
    val exportsApplied: Int,
    val snapshotApplied: Boolean,
    val rowsWritten: Int,
    val rowsSkipped: Int,
    val stoppedAtGap: Boolean,
    val segmentsPushed: Int,
    val exported: Boolean,
    val compacted: Boolean,
    val filesDeleted: Int,
    val conflicts: List<SyncConflict>,
    val before: SyncLibraryCounts,
    val after: SyncLibraryCounts,
) {
    val routineMerges: Int get() = rowsWritten - conflicts.size
}

/**
 * One true conflict of a run, for the conflict report G shows by title and field. [kind] is the
 * row's kind; [title] is its manga's title, or the category's name for a category; [chapter] is the
 * chapter's name for a chapter, its history or its notes; [fields] names each value the remote side
 * replaced, as [MergeEngine.Merged.fieldsFromRemote] does (a collection by its own name).
 */
data class SyncConflict(
    val kind: RowKind,
    val title: String,
    val chapter: String?,
    val fields: Set<String>,
)

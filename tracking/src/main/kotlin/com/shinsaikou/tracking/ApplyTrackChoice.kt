package com.shinsaikou.tracking

import com.shinsaikou.domain.database.TransactionRunner
import com.shinsaikou.domain.merged.interactor.ReconcileMergedRead
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository

/**
 * Apply of "Compare with MyAnimeList" (phase 4 unit TC): the owner's choices, written row by row in
 * list order, each only while what the owner was shown still holds.
 *
 * Each row takes [DelayedTrackingStore.whileSending] once, for the whole row, and releases it before
 * the next, so the retry worker and a track sheet edit run between two rows (R103 item 5). Inside
 * the hold this calls only the tracker's own [ListComparingTracker.readEntry] and
 * [Tracker.setRemoteLastChapterRead], the repositories, the store's `current` and `remove`, and
 * [RemoveTrack.removeLocal]; never another holder of the lock. The order is the worker's: the lock,
 * then the network, then a transaction. Each row's read of the service is cancellable (a cancelled
 * read changed nothing); what follows it runs non-cancellable, as [EditTrack] does, so a row is never
 * half done.
 *
 * Every send is a compare and set: the entry is read again and the number is sent only while the
 * service still shows the number and status the owner saw, built on the entry as the service holds
 * it now, with the service's dates only. A service already at the number is done with no send. The
 * queue loses only the entry the row acted on ([DelayedTrackingStore.remove], never `clear`).
 *
 * "Mark as read" marks forward only, among the chapters the owner was shown, through
 * [ChapterRepository.updateAll] with `read` alone: no history, no download deleted (a sync pull never
 * deletes, R99 item 7, and upstream's tracker path deletes nothing), no push (the service holds the
 * number already). In a merged entry those marks are one copy's, so the row's reconcile
 * ([ReconcileMergedRead]) follows it, outside the hold; a failed reconcile is logged, since the marks
 * stand and the entry's next open heals it.
 *
 * Failures follow [CompareFailures]: a row-specific one fails its row and the run goes on; any other
 * stops the run, and the rows not reached are [ApplyResult.NotReached].
 */
@Inject
class ApplyTrackChoice(
    private val trackRepository: TrackRepository,
    private val chapterRepository: ChapterRepository,
    private val mangaRepository: MangaRepository,
    private val transactionRunner: TransactionRunner,
    private val store: DelayedTrackingStore,
    private val removeTrack: RemoveTrack,
    private val reconcileMergedRead: ReconcileMergedRead,
) {

    /**
     * Applies [rows] in order on [tracker]. [cancelled] set to true stops the run between rows, or
     * during a row's read of the service; [onRowStart] is told the index of each row as it starts.
     * The caller passes the rows whose choice changes something; a [CompareChoice.DONT_CHANGE] row
     * writes nothing and reads as done.
     */
    suspend fun run(
        tracker: ListComparingTracker,
        rows: List<ApplyRow>,
        cancelled: StateFlow<Boolean> = MutableStateFlow(false),
        onRowStart: (Int) -> Unit = {},
    ): ApplyRun = withIOContext {
        val results = MutableList<ApplyResult>(rows.size) { ApplyResult.NotReached }
        var stop: CompareFailure.Stop? = null
        var wasCancelled = false
        for ((index, row) in rows.withIndex()) {
            if (cancelled.value) {
                wasCancelled = true
                break
            }
            onRowStart(index)
            val result = applyRow(tracker, row, cancelled)
            if (result == null) {
                wasCancelled = true
                break
            }
            results[index] = result
            if (result is ApplyResult.Done && result.marked > 0) reconcile(row.entry)
            val failure = (result as? ApplyResult.Failed)?.failure
            if (failure is CompareFailure.Stop) {
                stop = failure
                break
            }
        }
        ApplyRun(results, stop, wasCancelled)
    }

    private suspend fun reconcile(entry: ComparedEntry) {
        try {
            reconcileMergedRead.await(entry.manga.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Could not reconcile the merged read marks of entry ${entry.manga.id}" }
        }
    }

    /** The row's result, or null when the caller cancelled while its read was on the wire. */
    private suspend fun applyRow(tracker: ListComparingTracker, row: ApplyRow, cancelled: StateFlow<Boolean>): ApplyResult? {
        if (row.choice == CompareChoice.DONT_CHANGE) return ApplyResult.Done()
        return try {
            store.whileSending {
                val entry = row.entry
                when (row.choice) {
                    CompareChoice.DONT_CHANGE -> ApplyResult.Done()
                    CompareChoice.MARK_READ -> markRead(tracker, entry, cancelled)
                    CompareChoice.UPDATE_REMOTE -> send(tracker, entry, entry.target, cancelled)
                    CompareChoice.SET_REMOTE_BACK -> send(tracker, entry, entry.app, cancelled)
                    CompareChoice.ADD_BACK -> addBack(tracker, entry, cancelled)
                    CompareChoice.REMOVE_TRACKING -> removeTracking(tracker, entry, cancelled)
                    CompareChoice.KEEP_REMOTE -> keepRemote(entry)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ApplyResult.Failed(CompareFailures.classify(e))
        }
    }

    /** The design's 8.2: one read of the service, then four checks and the marks in one transaction. */
    private suspend fun markRead(tracker: ListComparingTracker, entry: ComparedEntry, cancelled: StateFlow<Boolean>): ApplyResult? {
        val read = readCancellable(tracker, entry.track, cancelled) ?: return null
        return withNonCancellableContext<ApplyResult> {
            val answer = (read as? EntryRead.OnList)?.track ?: return@withNonCancellableContext ApplyResult.ChangedSince
            val shown = entry.listed ?: return@withNonCancellableContext ApplyResult.ChangedSince
            if (!shows(shown, answer)) {
                storeAnswer(answer, entry)
                return@withNonCancellableContext ApplyResult.ChangedSince
            }
            transactionRunner.inTransaction<ApplyResult> {
                if (boundRow(entry) == null) return@inTransaction ApplyResult.ChangedSince
                if (!mangaRepository.getMangaById(entry.manga.id).favorite) return@inTransaction ApplyResult.ChangedSince
                val chapters = chapterRepository.getChapterByMangaId(entry.manga.id)
                if (CompareRules.numbers(chapters).maxRead != entry.maxRead) return@inTransaction ApplyResult.ChangedSince
                val listed = entry.toMark.mapTo(mutableSetOf()) { it.id }
                val marking = CompareRules.toMark(chapters, entry.maxRead, shown.chaptersRead).filter { it.id in listed }
                if (marking.isEmpty()) return@inTransaction ApplyResult.ChangedSince
                chapterRepository.updateAll(marking.map { ChapterUpdate(id = it.id, read = true) })
                storeAnswer(answer, entry)
                ApplyResult.Done(marked = marking.size, listed = listed.size)
            }
        }
    }

    /**
     * The design's 8.3, "Update MyAnimeList" and "Set MyAnimeList back": [number] sent on the entry
     * as the service holds it now, only while it still shows what the owner saw.
     */
    private suspend fun send(
        tracker: ListComparingTracker,
        entry: ComparedEntry,
        number: Int,
        cancelled: StateFlow<Boolean>,
    ): ApplyResult? {
        val bound = boundRow(entry) ?: return ApplyResult.ChangedSince
        if (maxRead(entry) != entry.maxRead) return ApplyResult.ChangedSince
        val read = readCancellable(tracker, bound, cancelled) ?: return null
        return withNonCancellableContext<ApplyResult> {
            val answer = (read as? EntryRead.OnList)?.track ?: return@withNonCancellableContext ApplyResult.ChangedSince
            val shown = entry.listed ?: return@withNonCancellableContext ApplyResult.ChangedSince
            when {
                // An earlier Apply whose answer was lost, or the other device's push, got there first.
                answer.lastChapterRead.toInt() == number -> {
                    storeAnswer(answer, entry)
                    store.remove(entry.manga.id, entry.track.trackerId, entry.track.remoteId, entry.maxRead)
                    ApplyResult.Done()
                }
                !shows(shown, answer) -> {
                    storeAnswer(answer, entry)
                    ApplyResult.ChangedSince
                }
                else -> sendNumber(tracker, answer, number, entry)
            }
        }
    }

    /** The design's 8.4: the stored row sent back to the list, only while the list still lacks it. */
    private suspend fun addBack(tracker: ListComparingTracker, entry: ComparedEntry, cancelled: StateFlow<Boolean>): ApplyResult? {
        val bound = boundRow(entry) ?: return ApplyResult.ChangedSince
        if (maxRead(entry) != entry.maxRead) return ApplyResult.ChangedSince
        val read = readCancellable(tracker, bound, cancelled) ?: return null
        return withNonCancellableContext<ApplyResult> {
            val offList = read as? EntryRead.OffList ?: return@withNonCancellableContext ApplyResult.ChangedSince
            sendNumber(tracker, bound.copy(totalChapters = offList.track.totalChapters), entry.app, entry)
        }
    }

    /** The design's 8.5: the pair's row removed in the app only while the list still lacks the entry. */
    private suspend fun removeTracking(tracker: ListComparingTracker, entry: ComparedEntry, cancelled: StateFlow<Boolean>): ApplyResult? {
        val read = readCancellable(tracker, entry.track, cancelled) ?: return null
        return withNonCancellableContext<ApplyResult> {
            when {
                read is EntryRead.OnList -> ApplyResult.ChangedSince
                removeTrack.removeLocal(entry.track) -> ApplyResult.Done()
                else -> ApplyResult.ChangedSince
            }
        }
    }

    /** The design's 8.6: the waiting update the owner saw is cancelled, if it is still the one waiting. */
    private suspend fun keepRemote(entry: ComparedEntry): ApplyResult = withNonCancellableContext<ApplyResult> {
        val waiting = entry.waitingUpdate
        when {
            waiting == null || boundRow(entry) == null -> ApplyResult.ChangedSince
            store.current(entry.manga.id, entry.track.trackerId) != waiting -> ApplyResult.ChangedSince
            else -> {
                store.remove(entry.manga.id, entry.track.trackerId, waiting.remoteId, waiting.chapterNumber)
                ApplyResult.Done()
            }
        }
    }

    /**
     * One send of [number] on [base], which the tracker stores while the pair names the entry; then
     * the queue's entry at or below the row's highest read goes, and the answer is checked.
     */
    private suspend fun sendNumber(tracker: ListComparingTracker, base: Track, number: Int, entry: ComparedEntry): ApplyResult {
        val answered = try {
            tracker.setRemoteLastChapterRead(base, number)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ApplyResult.Failed(CompareFailures.classify(e, send = true))
        }
        store.remove(entry.manga.id, entry.track.trackerId, entry.track.remoteId, entry.maxRead)
        val kept = answered.lastChapterRead.toInt()
        return if (kept == number) ApplyResult.Done() else ApplyResult.Kept(kept)
    }

    /**
     * [tracker]'s read of [track], or null when [cancelled] turned true while it was on the wire: the
     * call is then cancelled, and it changed nothing.
     */
    private suspend fun readCancellable(tracker: ListComparingTracker, track: Track, cancelled: StateFlow<Boolean>): EntryRead? =
        coroutineScope {
            val read = async { tracker.readEntry(track) }
            val watch = launch {
                cancelled.first { it }
                read.cancel()
            }
            try {
                read.await()
            } catch (e: CancellationException) {
                ensureActive()
                null
            } finally {
                watch.cancel()
            }
        }

    /** The pair's row while it still names the entry the owner was shown, else null. */
    private suspend fun boundRow(entry: ComparedEntry): Track? = trackRepository.getTracksByMangaId(entry.manga.id)
        .firstOrNull { it.trackerId == entry.track.trackerId }
        ?.takeIf { it.remoteId == entry.track.remoteId }

    private suspend fun maxRead(entry: ComparedEntry): Double =
        CompareRules.numbers(chapterRepository.getChapterByMangaId(entry.manga.id)).maxRead

    private suspend fun storeAnswer(answer: Track, entry: ComparedEntry) {
        trackRepository.insertIfBoundTo(answer, entry.track.remoteId)
    }

    /** Whether the service's [answer] still shows the number and status the owner saw. */
    private fun shows(shown: ListEntry, answer: Track): Boolean =
        answer.lastChapterRead.toInt() == shown.chaptersRead && answer.status == shown.status
}

/** A row the owner chose for, with everything the comparison showed ([entry]). */
data class ApplyRow(val entry: ComparedEntry, val choice: CompareChoice)

/** What Apply did with one row. */
sealed interface ApplyResult {

    /** Written; for a mark, [marked] of the [listed] chapters (fewer when some changed since). */
    data class Done(val marked: Int = 0, val listed: Int = 0) : ApplyResult

    /** The service answered the send with another number, [remoteChapters], which is stored. */
    data class Kept(val remoteChapters: Int) : ApplyResult

    /** What the owner was shown no longer holds; nothing was written but the service's answer. */
    data object ChangedSince : ApplyResult

    /** The row failed; a [CompareFailure.Stop] also stopped the run. */
    data class Failed(val failure: CompareFailure) : ApplyResult

    /** The run stopped, or was cancelled, before this row. */
    data object NotReached : ApplyResult
}

/** One Apply run: a result per row, and why it ended early, if it did. */
data class ApplyRun(val results: List<ApplyResult>, val stop: CompareFailure.Stop?, val cancelled: Boolean) {

    /** The rows the run reached, the one that stopped it included. */
    val reached: Int get() = results.count { it != ApplyResult.NotReached }
}

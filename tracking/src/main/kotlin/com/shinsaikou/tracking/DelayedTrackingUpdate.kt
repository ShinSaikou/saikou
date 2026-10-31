package com.shinsaikou.tracking

import com.shinsaikou.tracking.DelayedTrackingStore.DelayedTrackingItem
import com.shinsaikou.tracking.myanimelist.MALTitleNotApproved
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.network.HttpException
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.track.repository.TrackRepository

/**
 * One pass of the retry worker over [DelayedTrackingStore] (phase 3 unit T1), modeled on upstream's
 * `DelayedTrackingUpdateJob` and the push in its `TrackChapter`. `:app`'s worker runs it and maps
 * [run]'s answer to WorkManager's.
 *
 * Each entry is sent as `TrackChapter` sends it: the row is refreshed from the service, its
 * `lastChapterRead` set to the entry's number, and the service's answer stored. The number only
 * ever goes up: an entry at or below the stored row's number is removed unsent, and one at or below
 * the refreshed row's is removed with the refreshed row stored, so a device that read further on
 * the service is never pulled back.
 *
 * An entry names its manga and tracker, and the row is read by that pair on every run, so a store
 * that replaced the row under a new id meanwhile (the worker's own, a bind, a sync pull) leaves the
 * entry pointing at the current row (phase 3 unit TQ). The entry also names the service's entry it
 * was recorded against, and is sent only while the row still names that one: a pair bound to
 * another entry since (a bind to another search result, a sync pull carrying one) never gets a
 * chapter read on the old one (phase 3 unit TQ, verifier finding 1). An entry is dropped, unsent,
 * when the manga has no row for the tracker (unbound, or the manga deleted), when that row names
 * another remote entry, and when no tracker in the graph has the id: none of those is fixed by
 * waiting. So is a push the service refuses for that entry for good ([refusedForGood]): it no longer
 * has the entry (404), or will not list it while its title waits for approval. Upstream kept both
 * and sent them again with every later push. The log says which. Any other failure keeps the entry
 * for a retry.
 *
 * An entry whose tracker is signed out or whose sign-in has expired is kept, unsent, and counts as
 * settled: retrying does not fix it, and the next sign-in asks for a run that sends it
 * ([ResumeQueuedPushes]). Challenge 5 T2 item 3 dropped it, which lost every chapter read between
 * the expiry and the owner's next sign-in. A push that meets the service's refusal itself (the
 * sign-in was fine when the push began, and the refresh was refused) posts the one notice
 * [TrackerSignInNotifier] gives.
 *
 * The service's answer is stored only while the pair's row still names the entry the push went to
 * (phase 3 unit T3): the owner can bind the pair to another search result from the track sheet while
 * a push is on the wire, and an unconditional store would put the old entry back.
 *
 * Each entry is sent under [DelayedTrackingStore.whileSending], the lock the owner's edits from the
 * track sheet take too (phase 4 unit TR, R100 carry-over 3), and is read again from the store once
 * the lock is held: an entry an edit's clear removed since the run read the queue is not sent, and
 * one raised since is sent at its new number. It is read once more after the service's refresh
 * answers, since the reader's [DelayedTrackingStore.add] raises the entry without the lock (the
 * sheet's remove took none either until phase 4 unit TC); a change there leaves the entry unsent by
 * this call.
 *
 * Each run first deletes the keys under the queue's prefix that are not entries (T1's by track row
 * id, and TQ's first values without a remote id), which the store no longer reads.
 */
@Inject
class DelayedTrackingUpdate(
    private val store: DelayedTrackingStore,
    private val trackRepository: TrackRepository,
    private val trackerManager: TrackerManager,
    private val notifier: TrackerSignInNotifier,
) {

    /**
     * Sends or drops every entry in the store. Returns true when none is left to retry; an entry
     * raised while this ran is left for the run the scheduler queued behind this one.
     */
    suspend fun run(): Boolean {
        store.removeOtherKeys().forEach { key ->
            logcat(LogPriority.INFO) { "Removed the queued push under $key: not a queue entry" }
        }
        var settled = true
        for (item in store.getItems()) {
            if (!push(item)) settled = false
        }
        return settled
    }

    /**
     * False when the entry [snapshot] read stays for a retry. The entry is read again under the
     * lock; one cleared since is settled with nothing sent.
     */
    private suspend fun push(snapshot: DelayedTrackingItem): Boolean = store.whileSending {
        val item = store.current(snapshot.mangaId, snapshot.trackerId) ?: return@whileSending true
        send(item)
    }

    /** False when [item] stays for a retry. Runs under [DelayedTrackingStore.whileSending]. */
    private suspend fun send(item: DelayedTrackingItem): Boolean {
        val track = trackRepository.getTracksByMangaId(item.mangaId).firstOrNull { it.trackerId == item.trackerId }
            ?: return drop(item, "the manga has no row for the tracker")
        if (track.remoteId != item.remoteId) {
            return drop(item, "the pair is bound to entry ${track.remoteId} now, not ${item.remoteId}")
        }
        val tracker = trackerManager.get(item.trackerId)
            ?: return drop(item, "no tracker in the graph has id ${item.trackerId}")
        if (!tracker.isLoggedIn) return keep(item, "tracker ${tracker.id} is signed out")
        if (tracker.isAuthExpired) return keep(item, "tracker ${tracker.id} needs a new sign-in")
        if (item.chapterNumber <= track.lastChapterRead) {
            store.remove(item.mangaId, item.trackerId, item.remoteId, item.chapterNumber)
            return true
        }

        return try {
            val remote = tracker.refresh(track)
            if (store.current(item.mangaId, item.trackerId) != item) {
                // The queue changed while the refresh was on the wire (the sheet's remove cleared
                // it, or a read raised it): this entry is not sent, and a raised one is left for
                // the run queued behind this one.
                return true
            }
            val stored = if (remote.lastChapterRead >= item.chapterNumber) {
                remote
            } else {
                tracker.update(remote.copy(lastChapterRead = item.chapterNumber), didReadChapter = true)
            }
            // Stored only while the pair still names the entry pushed to: a rebind that landed while
            // the push was on the wire stays (phase 3 unit T3). Upstream's job stores the answer as is.
            if (!trackRepository.insertIfBoundTo(stored, item.remoteId)) {
                logcat(LogPriority.INFO) {
                    "Not storing the answer for manga ${item.mangaId} on tracker ${tracker.id}: the pair was bound to another entry meanwhile"
                }
            }
            store.remove(item.mangaId, item.trackerId, item.remoteId, item.chapterNumber)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A refresh the service refused marks the sign-in expired on its way out.
            if (tracker.isAuthExpired) {
                notifier.expired(tracker)
                return keep(item, "tracker ${tracker.id} needs a new sign-in")
            }
            refusedForGood(e, item.remoteId)?.let { reason -> return drop(item, reason, e) }
            logcat(LogPriority.WARN, e) { "Could not update manga ${item.mangaId} on tracker ${tracker.id}; kept for a retry" }
            false
        }
    }

    /** Why no retry can send a push that failed with [error] on [remoteId], or null when one may. */
    private fun refusedForGood(error: Exception, remoteId: Long): String? = when {
        error is MALTitleNotApproved -> "the service will not list entry $remoteId while its title waits for approval"
        error is HttpException && error.code == 404 -> "the service no longer has entry $remoteId (404)"
        else -> null
    }

    /** Leaves [item] queued for the next sign-in; true, since a retry before it would not send it. */
    private fun keep(item: DelayedTrackingItem, reason: String): Boolean {
        logcat(LogPriority.INFO) { "Keeping the push of manga ${item.mangaId} on tracker ${item.trackerId}: $reason" }
        return true
    }

    private fun drop(item: DelayedTrackingItem, reason: String, error: Throwable? = null): Boolean {
        logcat(if (error == null) LogPriority.INFO else LogPriority.WARN, error) {
            "Dropping the queued push of manga ${item.mangaId} on tracker ${item.trackerId}: $reason"
        }
        store.remove(item.mangaId, item.trackerId, item.remoteId, item.chapterNumber)
        return true
    }
}

package com.shinsaikou.tracking

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository

/**
 * The track sheet's refresh on open (phase 3 unit T3), upstream's `RefreshTracks`: every row of the
 * manga whose tracker is signed in is read back from the service, all at once, and a tracker that
 * fails never stops the others.
 *
 * Three departures from upstream. A tracker whose sign-in has expired is skipped, since the service
 * would refuse it and the sheet asks for a new sign-in instead. The service's answer is stored only
 * when it differs from the row, so opening the sheet on an unchanged entry writes nothing and logs no
 * change for sync; upstream stores every answer. And it is stored only while the pair still names the
 * entry refreshed ([TrackRepository.insertIfBoundTo]), so a rebind made while the refresh was on
 * the wire is kept.
 *
 * MyAnimeList's refresh adds an entry that is no longer on the owner's list back to it, with the
 * row's status and progress, as upstream's does; that is a write to the service, the third direct one
 * besides the owner's edits and the retry worker. Each tracker's call and its store run under
 * [DelayedTrackingStore.whileSending], so they do not straddle a worker call or an edit (phase 4
 * unit TR). The pair's row is read again once the lock is held, and the refresh is built from that
 * row (phase 4 unit TC, R103 carry-over 1): a refresh that waited for a worker push works from the
 * row the push stored, so a re-add of an entry deleted on the service's site sends the pushed
 * chapter, not the one before it, and does not store the older row over the worker's. A pair
 * unbound, or bound to another entry, while the refresh waited is left alone.
 *
 * When a refreshed pair has a push queued, the retry worker is asked for a run (challenge S1's
 * guard, phase 4 unit TR, R100 carry-over 3), so a push left queued is sent as the sheet opens,
 * usually before the owner's taps reach a status the worker's push would set back to reading. That
 * helps only when no run is waiting: after the work failed its retries, or when the process died
 * between recording a push and scheduling it. With a run waiting, a retry's backoff included, the
 * scheduler enqueues nothing and the guard does nothing, so the race stays open there. A scheduler
 * failure is logged and never fails the refresh.
 */
@Inject
class RefreshTracks(
    private val trackRepository: TrackRepository,
    private val trackerManager: TrackerManager,
    private val store: DelayedTrackingStore,
    private val scheduler: DelayedTrackingScheduler,
) {

    /** Refreshes the rows of [mangaId] and returns one failure for each tracker that failed. */
    suspend fun await(mangaId: Long): List<Pair<Tracker, Exception>> = withIOContext {
        supervisorScope {
            val pairs = trackRepository.getTracksByMangaId(mangaId)
                .mapNotNull { track -> trackerManager.get(track.trackerId)?.let { track to it } }
                .filter { (_, tracker) -> tracker.isLoggedIn && !tracker.isAuthExpired }
            launch { scheduleQueued(mangaId, pairs) }
            pairs.map { (track, tracker) -> async { refresh(tracker, track) } }
                .awaitAll()
                .filterNotNull()
        }
    }

    /**
     * Asks for a run of the retry worker when one of [pairs] has a push queued. A failure is logged,
     * never thrown: the sheet launches the refresh with nothing to catch it.
     */
    private suspend fun scheduleQueued(mangaId: Long, pairs: List<Pair<Track, Tracker>>) {
        try {
            if (pairs.any { (track, tracker) -> store.current(track.mangaId, tracker.id) != null }) {
                scheduler.schedule()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Could not schedule the queued track pushes of manga $mangaId" }
        }
    }

    private suspend fun refresh(tracker: Tracker, track: Track): Pair<Tracker, Exception>? = try {
        store.whileSending {
            val current = trackRepository.getTracksByMangaId(track.mangaId)
                .firstOrNull { it.trackerId == tracker.id }
                ?.takeIf { it.remoteId == track.remoteId }
                ?: return@whileSending
            val refreshed = tracker.refresh(current)
            if (refreshed.copy(id = current.id) != current) {
                trackRepository.insertIfBoundTo(refreshed, current.remoteId)
            }
        }
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logcat(LogPriority.WARN, e) { "Could not refresh manga ${track.mangaId} on tracker ${tracker.id}" }
        tracker to e
    }
}

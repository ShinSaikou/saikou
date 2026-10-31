package com.shinsaikou.tracking

import dev.zacsweers.metro.Inject
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository

/**
 * The owner's edits from the track sheet (phase 3 unit T3): status, chapter, score, dates and the
 * private flag, each sent straight to the service through the tracker's `setRemote*`, which stores
 * the row the service answered, as upstream's sheet sends them. A failure is thrown for the sheet to
 * show; only the progress recorded from reading goes through the retry queue (R95 item 3 as T3
 * amends it).
 *
 * Each edit reads the pair's row when it is confirmed, not the row the sheet showed when it opened,
 * so a refresh or a push stored meanwhile is not sent back to the service. A pair with no row (the
 * row removed meanwhile) is left alone. The answer is stored only while the pair still names the
 * entry the edit was sent to ([BaseTracker] stores through [TrackRepository.insertIfBoundTo]), so a
 * rebind synced in, or a remove, while the edit is on the wire is kept. The edit itself has reached
 * the old entry on the service by then: the rebind was not on this device when the owner confirmed.
 *
 * A chapter set by hand clears the pair's queued push once the service has taken it, whatever the
 * push holds: a higher number queued from reading would otherwise be sent over the owner's lower
 * one.
 *
 * Each edit reads the row, sends it and, for a chapter, clears the queue under
 * [DelayedTrackingStore.whileSending], the lock the retry worker holds for each entry it sends
 * (phase 4 unit TR, R100 carry-over 3). So a worker call already on the wire when the owner confirms
 * finishes and stores its answer first, and the edit then reads that row and sends after it: a
 * chapter set by hand is not overwritten by the worker's older number, and a status, score, date or
 * private edit does not send the chapter from before the worker's push back to the service (the
 * service's update takes the whole entry). An edit can wait for one worker call, on any manga.
 *
 * Two races stay. A push still queued, not yet sent, when the owner sets a status by hand (the
 * worker waiting in its backoff, or not started yet) is sent later, and the service's update sets
 * reading over a hand-set status (dropped, say) whatever the chapter, since it is sent as a chapter
 * read. And an automatic push recorded for the same manga between the owner's chapter confirm and
 * the clear is dropped with the stale entry; the sheet is modal over the manga screen, so no chapter
 * can be read or marked there meanwhile. A clear that removes the entry only while it is still the
 * one read before the send would close it.
 */
@Inject
class EditTrack(
    private val trackRepository: TrackRepository,
    private val store: DelayedTrackingStore,
) {

    suspend fun setStatus(tracker: Tracker, mangaId: Long, status: Long) =
        edit(tracker, mangaId) { tracker.setRemoteStatus(it, status) }

    suspend fun setChapter(tracker: Tracker, mangaId: Long, chapterNumber: Int) =
        edit(tracker, mangaId) {
            tracker.setRemoteLastChapterRead(it, chapterNumber)
            store.clear(mangaId, tracker.id)
        }

    suspend fun setScore(tracker: Tracker, mangaId: Long, score: String) =
        edit(tracker, mangaId) { tracker.setRemoteScore(it, score) }

    suspend fun setStartDate(tracker: Tracker, mangaId: Long, epochMillis: Long) =
        edit(tracker, mangaId) { tracker.setRemoteStartDate(it, epochMillis) }

    suspend fun setFinishDate(tracker: Tracker, mangaId: Long, epochMillis: Long) =
        edit(tracker, mangaId) { tracker.setRemoteFinishDate(it, epochMillis) }

    suspend fun setPrivate(tracker: Tracker, mangaId: Long, private: Boolean) =
        edit(tracker, mangaId) { tracker.setRemotePrivate(it, private) }

    private suspend fun edit(tracker: Tracker, mangaId: Long, send: suspend (Track) -> Unit) {
        withNonCancellableContext {
            withIOContext {
                store.whileSending {
                    val track = trackRepository.getTracksByMangaId(mangaId).firstOrNull { it.trackerId == tracker.id }
                        ?: return@whileSending
                    send(track)
                }
            }
        }
    }
}

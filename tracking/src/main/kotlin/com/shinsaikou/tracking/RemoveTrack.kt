package com.shinsaikou.tracking

import dev.zacsweers.metro.Inject
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository

/**
 * Removing a tracker's row from a manga, the track sheet's remove dialog (phase 3 unit T3), as
 * upstream's: the local row is deleted first, then, when the owner ticked the box, the entry on the
 * service. Here the pair's queued push is cleared between the two, so the retry worker has nothing
 * left to send for the removed row.
 *
 * [track] is the row the owner opened the dialog on, and the local delete and the clear act only
 * while the pair still names its entry (phase 4 unit TR, R100 carry-over 1): a rebind synced in
 * meanwhile, and a push queued for the new entry, are kept, and the local delete is skipped without
 * a word, the sheet then showing the rebound row. The service's delete still goes to [track]'s
 * entry, the one the owner saw.
 *
 * [await] runs under [DelayedTrackingStore.whileSending] (phase 4 unit TC, R103 carry-over 2), so a
 * remove confirmed while an edit's or the retry worker's call is on the wire waits for it, and its
 * delete on the service is ordered against them; the remove can wait for one such call, on any
 * manga, as an edit does. [removeLocal] is the local half alone, for a caller that already holds the
 * lock, which is not reentrant ("Compare with MyAnimeList", whose Apply holds it for each row).
 *
 * The service's delete runs last and its failure is thrown for the sheet to show, where upstream only
 * logs it; the local row stays deleted either way.
 */
@Inject
class RemoveTrack(
    private val trackRepository: TrackRepository,
    private val store: DelayedTrackingStore,
) {

    suspend fun await(tracker: Tracker, track: Track, alsoRemote: Boolean) {
        withNonCancellableContext {
            withIOContext {
                store.whileSending {
                    removeLocal(track)
                    if (alsoRemote && tracker is DeletableTracker) tracker.delete(track)
                }
            }
        }
    }

    /**
     * Deletes the pair's row while it still names [track]'s entry, and then clears the pair's queued
     * push; returns whether it deleted. Takes no lock: the caller holds
     * [DelayedTrackingStore.whileSending].
     */
    suspend fun removeLocal(track: Track): Boolean {
        val deleted = trackRepository.deleteIfBoundTo(track.mangaId, track.trackerId, track.remoteId)
        if (deleted) store.clear(track.mangaId, track.trackerId)
        return deleted
    }
}

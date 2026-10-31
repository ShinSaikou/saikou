package com.shinsaikou.tracking

import com.shinsaikou.domain.track.TrackProgress
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository

/**
 * The progress hook (phase 3 unit T1): the reader's finished chapter and the chapter list's mark
 * read come here, and nothing here touches the network. For each track of the manga whose tracker
 * is in the graph and signed in, and whose `lastChapterRead` is below the chapter (upstream's
 * `TrackChapter` gate, so a re-read pushes nothing), it records the push in [DelayedTrackingStore]
 * under the manga and the tracker, against the row's remote entry, and then asks
 * [DelayedTrackingScheduler] for the worker that sends it. The worker is asked for even when a
 * later track's write throws, so the entries committed before it are sent; a call that committed
 * nothing asks for nothing.
 *
 * A tracker whose sign-in has expired still gets its pushes recorded, so the chapters read until the
 * owner signs in again are sent then ([ResumeQueuedPushes]); until then they ask for no worker,
 * which could not send them. Before, they were not recorded at all.
 *
 * With no tracker signed in it reads nothing and writes nothing, which is every finished chapter
 * until the owner signs in.
 */
@Inject
@ContributesBinding(AppScope::class)
class TrackProgressRecorder(
    private val trackRepository: TrackRepository,
    private val trackerManager: TrackerManager,
    private val store: DelayedTrackingStore,
    private val scheduler: DelayedTrackingScheduler,
) : TrackProgress {

    override suspend fun wouldRecord(mangaId: Long, chapterNumber: Double): Boolean =
        pushable(mangaId, chapterNumber).isNotEmpty()

    override suspend fun record(mangaId: Long, chapterNumber: Double): Int {
        val tracks = pushable(mangaId, chapterNumber)
        if (tracks.isEmpty()) return 0
        var sendable = false
        try {
            tracks.forEach { (track, tracker) ->
                store.add(mangaId, track.trackerId, track.remoteId, chapterNumber)
                if (!tracker.isAuthExpired) sendable = true
            }
        } finally {
            // An entry committed before a later track's write threw is still sent (R95 carry-over 3).
            if (sendable) scheduler.schedule()
        }
        return tracks.size
    }

    private suspend fun pushable(mangaId: Long, chapterNumber: Double): List<Pair<Track, Tracker>> {
        if (trackerManager.trackers.none { it.isLoggedIn }) return emptyList()
        return trackRepository.getTracksByMangaId(mangaId).mapNotNull { track ->
            val tracker = trackerManager.get(track.trackerId) ?: return@mapNotNull null
            (track to tracker).takeIf { tracker.isLoggedIn && chapterNumber > track.lastChapterRead }
        }
    }
}

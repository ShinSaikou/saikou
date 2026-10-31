package com.shinsaikou.tracking

import com.shinsaikou.domain.database.TransactionRunner
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository

/**
 * Binding a manga to a tracker, with the seed step upstream's `AddTracks.bind` performs (phase 3 unit
 * T1). The track sheet (unit T3) calls [bind] once the owner has picked a search result.
 *
 * The tracker binds the entry and the row is stored. Then, when the manga has chapters read here,
 * the service is brought up to them: the last chapter of the unbroken read run from the start is
 * pushed when it is above the service's number, and a start date is set from the manga's first
 * history entry when the service has none: the day it was read on the device ([TrackDates]), where
 * upstream moved that wall time into UTC and so sent the next or the previous day across midnight.
 * Both seed pushes go through the tracker's `setRemote*`, which stores the row it returns; a seed
 * push that fails is logged and the bind stands, as in upstream, where it was a toast.
 *
 * A pair already bound to another entry is rebound (phase 3 unit T3): the row is stored through
 * [TrackRepository.rebind], a delete and an insert in one transaction, so the manga's tracker-set
 * clock moves and the new binding reaches other devices. Upstream inserts in every case; here an insert
 * of a bound pair is progress, which moves no clock (R82), and a rebind stored that way would be
 * undone by the other device's next sync. A push queued for the old entry stays queued and the
 * retry worker drops it unsent.
 *
 * The pair's read that chooses rebind or insert, and that write, are one database transaction
 * (phase 4 unit TR, R100 carry-over 2). A sync file's apply is one transaction too, and a read
 * outside one sees the library as it was before an apply still open: a bind whose read fell inside
 * an apply that rebinds the pair would store a plain insert over the synced rebind, which moves no
 * clock and may lose the next merge. Every transaction opens with `BEGIN IMMEDIATE` (R101 item 3),
 * so the read and the write land wholly before or after an apply. The tracker's bind before them
 * and the seed pushes after them stay outside: the transaction holds the database's writer for one
 * read and one write, never across a network call.
 *
 * Upstream's last step, `SyncChapterProgressWithTrack`, marks local chapters read from the service's
 * number for an enhanced (server) tracker only; no such tracker is built (Q14, the owner's scope),
 * so it is not here.
 */
@Inject
class AddTracks(
    private val trackRepository: TrackRepository,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val getHistory: GetHistory,
    private val transactionRunner: TransactionRunner,
) {

    /**
     * Binds [item], a row for [Track.mangaId] built from a search result, and returns the row as it
     * was last stored. A failure of the bind itself is thrown to the caller.
     */
    suspend fun bind(tracker: Tracker, item: Track): Track = withNonCancellableContext {
        withIOContext {
            val mangaId = item.mangaId
            val allChapters = getChaptersByMangaId.await(mangaId)
            val hasReadChapters = allChapters.any { it.read }
            var track = tracker.bind(item, hasReadChapters)

            transactionRunner.inTransaction {
                val bound = trackRepository.getTracksByMangaId(mangaId).firstOrNull { it.trackerId == tracker.id }
                if (bound != null && bound.remoteId != track.remoteId) {
                    trackRepository.rebind(track)
                } else {
                    trackRepository.insert(track)
                }
            }

            // Update chapter progress if newer chapters marked read locally
            if (hasReadChapters) {
                val latestLocalReadChapterNumber = allChapters
                    .sortedBy { it.chapterNumber }
                    .takeWhile { it.read }
                    .lastOrNull()
                    ?.chapterNumber ?: -1.0

                if (latestLocalReadChapterNumber > track.lastChapterRead) {
                    track = seed(tracker, track) {
                        tracker.setRemoteLastChapterRead(
                            track.copy(lastChapterRead = latestLocalReadChapterNumber),
                            latestLocalReadChapterNumber.toInt(),
                        )
                    }
                }

                if (track.startDate <= 0) {
                    val firstReadAt = getHistory.await(mangaId)
                        .mapNotNull { it.readAt }
                        .minOrNull()

                    if (firstReadAt != null) {
                        val startDate = TrackDates.startOfDay(firstReadAt.time)
                        track = seed(tracker, track) { tracker.setRemoteStartDate(track, startDate) }
                    }
                }
            }

            track
        }
    }

    /**
     * Binds [result], a search result the owner picked for [mangaId], as upstream's track sheet binds a
     * `TrackSearch`: the row starts with no progress, status, score or dates of its own, and is
     * private when the owner bound it privately.
     */
    suspend fun bind(tracker: Tracker, result: TrackSearchResult, mangaId: Long, private: Boolean = false): Track = bind(
        tracker,
        Track(
            id = 0,
            mangaId = mangaId,
            trackerId = tracker.id,
            remoteId = result.remoteId,
            libraryId = null,
            title = result.title,
            lastChapterRead = 0.0,
            totalChapters = result.totalChapters,
            status = 0,
            score = 0.0,
            remoteUrl = result.trackingUrl,
            startDate = 0,
            finishDate = 0,
            private = private,
        ),
    )

    private suspend fun seed(tracker: Tracker, track: Track, push: suspend () -> Track): Track = try {
        push()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logcat(LogPriority.WARN, e) { "Could not seed track progress on tracker ${tracker.id}" }
        track
    }
}

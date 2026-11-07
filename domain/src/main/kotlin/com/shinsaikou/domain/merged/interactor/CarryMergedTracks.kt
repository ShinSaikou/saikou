package com.shinsaikou.domain.merged.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository

/**
 * The main entry holds a title's tracks: the track sheet, the Library's tracker filters and the
 * progress push all read them there. Rows are copied as `MigrateManga` carries them, a plain insert
 * or `rebind`, and the member keeps its own, so an unmerge leaves each entry as it was. The caller
 * holds the transaction.
 */
@Inject
class CarryMergedTracks(
    private val trackRepository: TrackRepository,
) {

    /**
     * A merge: [mainId] takes the tracks of [memberIds] it lacks. A tracker it is bound to stays
     * bound where it is, and for the same remote entry the row further along wins.
     */
    suspend fun ontoMain(mainId: Long, memberIds: List<Long>) {
        val bound = trackRepository.getTracksByMangaId(mainId).associateByTo(HashMap()) { it.trackerId }
        memberIds.filter { it != mainId }.forEach { memberId ->
            trackRepository.getTracksByMangaId(memberId).map { it.copy(mangaId = mainId) }.forEach { track ->
                val pair = bound[track.trackerId]
                val further = pair != null && pair.remoteId == track.remoteId &&
                    track.lastChapterRead > pair.lastChapterRead
                if (pair == null || further) {
                    trackRepository.insert(track)
                    bound[track.trackerId] = track
                }
            }
        }
    }

    /** A new main entry: the group's tracks follow it, over any row of its own for those trackers. */
    suspend fun toNewMain(oldMainId: Long, newMainId: Long) {
        val bound = trackRepository.getTracksByMangaId(newMainId).associateBy { it.trackerId }
        trackRepository.getTracksByMangaId(oldMainId).map { it.copy(mangaId = newMainId) }.forEach { track ->
            carry(track, bound[track.trackerId])
        }
    }

    /** `rebind` moves the tracker-set clock even for the same id, so it runs only on another remote id. */
    private suspend fun carry(track: Track, pair: Track?) {
        if (pair != null && pair.remoteId != track.remoteId) {
            trackRepository.rebind(track)
        } else {
            trackRepository.insert(track)
        }
    }
}

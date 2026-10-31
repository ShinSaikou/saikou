// Vendored from upstream v0.20.4: domain/src/main/java/tachiyomi/domain/track/repository/TrackRepository.kt (modified: yes)
package tachiyomi.domain.track.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.track.model.Track

/**
 * Modified (phase 3 unit T3): [rebind] and [insertIfBoundTo] are added. [insert] of a pair already
 * bound is progress and moves no tracker-set clock (R82), so a bind to another entry goes through
 * [rebind]; a store of a service's answer read before a rebind goes through [insertIfBoundTo].
 * Phase 4 unit TR replaces upstream's unconditional `delete(mangaId, trackerId)` with
 * [deleteIfBoundTo], the track sheet's remove being its one caller (R39).
 */
interface TrackRepository {

    suspend fun getTrackById(id: Long): Track?

    suspend fun getTracksByMangaId(mangaId: Long): List<Track>

    fun getTracksAsFlow(): Flow<List<Track>>

    fun getTracksByMangaIdAsFlow(mangaId: Long): Flow<List<Track>>

    suspend fun insert(track: Track)

    suspend fun insertAll(tracks: List<Track>)

    /**
     * Binds [track]'s manga and tracker to the entry [track] names: deletes the pair's row and
     * inserts [track] in one transaction, so the manga's tracker-set clock moves and the new binding
     * reaches other devices (R82).
     */
    suspend fun rebind(track: Track)

    /**
     * Stores [track] only while its pair's row still names [boundRemoteId], the entry [track] is the
     * service's answer for. Returns false, storing nothing, when the pair is unbound or bound to
     * another entry.
     */
    suspend fun insertIfBoundTo(track: Track, boundRemoteId: Long): Boolean

    /**
     * Deletes the pair's row only while it still names [boundRemoteId], the entry the caller acted
     * on. Returns false, deleting nothing, when the pair is unbound or bound to another entry.
     */
    suspend fun deleteIfBoundTo(mangaId: Long, trackerId: Long, boundRemoteId: Long): Boolean
}

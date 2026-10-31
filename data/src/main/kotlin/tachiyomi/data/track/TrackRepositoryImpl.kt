// Vendored from upstream v0.20.4: data/src/main/java/tachiyomi/data/track/TrackRepositoryImpl.kt (modified: yes)
package tachiyomi.data.track

import com.shinsaikou.data.DatabaseHandler
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository

/**
 * Modified: the constructor takes [DatabaseHandler] instead of `Database`, and [rebind] and
 * [insertIfBoundTo] are added (phase 3 unit T3). Upstream's `delete` is replaced by [deleteIfBoundTo]
 * (phase 4 unit TR).
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class TrackRepositoryImpl(
    private val handler: DatabaseHandler,
) : TrackRepository {

    override suspend fun getTrackById(id: Long): Track? {
        return handler.awaitOneOrNull { manga_syncQueries.getTrackById(id, TrackMapper::mapTrack) }
    }

    override suspend fun getTracksByMangaId(mangaId: Long): List<Track> {
        return handler.awaitList { manga_syncQueries.getTracksByMangaId(mangaId, TrackMapper::mapTrack) }
    }

    override fun getTracksAsFlow(): Flow<List<Track>> {
        return handler.subscribeToList { manga_syncQueries.getTracks(TrackMapper::mapTrack) }
    }

    override fun getTracksByMangaIdAsFlow(mangaId: Long): Flow<List<Track>> {
        return handler.subscribeToList { manga_syncQueries.getTracksByMangaId(mangaId, TrackMapper::mapTrack) }
    }

    override suspend fun insert(track: Track) {
        insertValues(track)
    }

    override suspend fun insertAll(tracks: List<Track>) {
        insertValues(*tracks.toTypedArray())
    }

    override suspend fun rebind(track: Track) {
        handler.await(inTransaction = true) {
            manga_syncQueries.delete(mangaId = track.mangaId, syncId = track.trackerId)
            manga_syncQueries.insert(
                mangaId = track.mangaId,
                syncId = track.trackerId,
                remoteId = track.remoteId,
                libraryId = track.libraryId,
                title = track.title,
                lastChapterRead = track.lastChapterRead,
                totalChapters = track.totalChapters,
                status = track.status,
                score = track.score,
                remoteUrl = track.remoteUrl,
                startDate = track.startDate,
                finishDate = track.finishDate,
                `private` = track.private,
            )
        }
    }

    override suspend fun insertIfBoundTo(track: Track, boundRemoteId: Long): Boolean {
        val inserted = handler.await(inTransaction = true) {
            manga_syncQueries.insertIfBoundTo(
                remoteId = track.remoteId,
                libraryId = track.libraryId,
                title = track.title,
                lastChapterRead = track.lastChapterRead,
                totalChapters = track.totalChapters,
                status = track.status,
                score = track.score,
                remoteUrl = track.remoteUrl,
                startDate = track.startDate,
                finishDate = track.finishDate,
                `private` = track.private,
                mangaId = track.mangaId,
                syncId = track.trackerId,
                boundRemoteId = boundRemoteId,
            )
        }
        return inserted > 0
    }

    override suspend fun deleteIfBoundTo(mangaId: Long, trackerId: Long, boundRemoteId: Long): Boolean {
        val deleted = handler.await(inTransaction = true) {
            manga_syncQueries.deleteIfBoundTo(mangaId = mangaId, syncId = trackerId, boundRemoteId = boundRemoteId)
        }
        return deleted > 0
    }

    private suspend fun insertValues(vararg tracks: Track) {
        handler.await(inTransaction = true) {
            tracks.forEach { mangaTrack ->
                manga_syncQueries.insert(
                    mangaId = mangaTrack.mangaId,
                    syncId = mangaTrack.trackerId,
                    remoteId = mangaTrack.remoteId,
                    libraryId = mangaTrack.libraryId,
                    title = mangaTrack.title,
                    lastChapterRead = mangaTrack.lastChapterRead,
                    totalChapters = mangaTrack.totalChapters,
                    status = mangaTrack.status,
                    score = mangaTrack.score,
                    remoteUrl = mangaTrack.remoteUrl,
                    startDate = mangaTrack.startDate,
                    finishDate = mangaTrack.finishDate,
                    `private` = mangaTrack.private,
                )
            }
        }
    }
}

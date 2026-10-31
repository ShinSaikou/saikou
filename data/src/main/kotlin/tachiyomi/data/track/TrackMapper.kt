// Vendored from upstream v0.20.4: data/src/main/java/tachiyomi/data/track/TrackMapper.kt (modified: no)
package tachiyomi.data.track

import tachiyomi.domain.track.model.Track

object TrackMapper {
    fun mapTrack(
        id: Long,
        mangaId: Long,
        syncId: Long,
        remoteId: Long,
        libraryId: Long?,
        title: String,
        lastChapterRead: Double,
        totalChapters: Long,
        status: Long,
        score: Double,
        remoteUrl: String,
        startDate: Long,
        finishDate: Long,
        private: Boolean,
    ): Track = Track(
        id = id,
        mangaId = mangaId,
        trackerId = syncId,
        remoteId = remoteId,
        libraryId = libraryId,
        title = title,
        lastChapterRead = lastChapterRead,
        totalChapters = totalChapters,
        status = status,
        score = score,
        remoteUrl = remoteUrl,
        startDate = startDate,
        finishDate = finishDate,
        private = private,
    )
}

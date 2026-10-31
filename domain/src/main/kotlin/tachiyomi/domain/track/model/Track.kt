// Vendored from upstream v0.20.4: domain/src/main/java/tachiyomi/domain/track/model/Track.kt (modified: no)
package tachiyomi.domain.track.model

import java.io.Serializable

data class Track(
    val id: Long,
    val mangaId: Long,
    val trackerId: Long,
    val remoteId: Long,
    val libraryId: Long?,
    val title: String,
    val lastChapterRead: Double,
    val totalChapters: Long,
    val status: Long,
    val score: Double,
    val remoteUrl: String,
    val startDate: Long,
    val finishDate: Long,
    val private: Boolean,
) : Serializable

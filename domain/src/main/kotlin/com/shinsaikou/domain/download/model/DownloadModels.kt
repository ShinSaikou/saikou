package com.shinsaikou.domain.download.model

enum class DownloadState(val code: Long) {
    QUEUED(0),
    DOWNLOADING(1),
    PAUSED(2),
    DONE(3),
    ERROR(4),
    ;

    companion object {
        fun fromCode(code: Long): DownloadState = entries.first { it.code == code }
    }
}

data class DownloadQueueEntry(
    val id: Long,
    val chapterId: Long,
    val mangaId: Long,
    val state: DownloadState,
    val queueOrder: Long,
    val totalPages: Long,
    val downloadedPages: Long,
    val errorMessage: String?,
    val dateAdded: Long,
    val dateStarted: Long?,
    val dateCompleted: Long?,
)

/**
 * A completed download. Carries copied identity text and has no foreign keys, so a row outlives
 * its manga and chapter rows.
 */
data class DownloadManifestEntry(
    val id: Long,
    val chapterId: Long,
    val mangaId: Long,
    val sourceId: Long,
    val mangaTitle: String,
    val mangaUrl: String,
    val chapterUrl: String,
    val chapterName: String,
    val scanlator: String?,
    val chapterDirName: String,
    val pageCount: Long,
    val sizeBytes: Long,
    val dateDownloaded: Long,
)

package com.shinsaikou.data.download

import com.shinsaikou.domain.download.model.DownloadQueueEntry
import com.shinsaikou.domain.download.model.DownloadState

/** The `download_queue` columns in declaration order. */
fun mapDownloadQueueEntry(
    id: Long,
    chapterId: Long,
    mangaId: Long,
    state: Long,
    queueOrder: Long,
    totalPages: Long,
    downloadedPages: Long,
    errorMessage: String?,
    dateAdded: Long,
    dateStarted: Long?,
    dateCompleted: Long?,
): DownloadQueueEntry = DownloadQueueEntry(
    id = id,
    chapterId = chapterId,
    mangaId = mangaId,
    state = DownloadState.fromCode(state),
    queueOrder = queueOrder,
    totalPages = totalPages,
    downloadedPages = downloadedPages,
    errorMessage = errorMessage,
    dateAdded = dateAdded,
    dateStarted = dateStarted,
    dateCompleted = dateCompleted,
)

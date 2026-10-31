package com.shinsaikou.domain.download.repository

import com.shinsaikou.domain.download.model.DownloadQueueEntry
import com.shinsaikou.domain.download.model.DownloadState
import kotlinx.coroutines.flow.Flow

/**
 * Phase 1 ships the read side so the library badge and the chapter list compile against the final
 * interface. The write functions are implemented but called by nobody until the download engine
 * lands.
 */
interface DownloadQueueRepository {
    /** ORDER BY queue_order. */
    fun subscribeAll(): Flow<List<DownloadQueueEntry>>

    suspend fun getByChapterId(chapterId: Long): DownloadQueueEntry?

    /** state IN (QUEUED, DOWNLOADING, PAUSED). */
    suspend fun getActive(): List<DownloadQueueEntry>

    suspend fun getByMangaId(mangaId: Long): List<DownloadQueueEntry>

    /**
     * Appends the chapter queued. A row already there that is queued or downloading is left
     * untouched (state, progress and position); any other row is set back to queued in place.
     */
    suspend fun enqueue(chapterId: Long, mangaId: Long, now: Long)

    suspend fun setState(chapterId: Long, state: DownloadState, errorMessage: String?, now: Long)

    suspend fun setProgress(chapterId: Long, downloadedPages: Long, totalPages: Long)

    suspend fun remove(chapterIds: List<Long>)

    suspend fun reorder(orderedChapterIds: List<Long>)
}

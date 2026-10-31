package com.shinsaikou.data.download

import app.cash.sqldelight.async.coroutines.awaitAsOne
import com.shinsaikou.data.DatabaseHandler
import com.shinsaikou.domain.download.model.DownloadQueueEntry
import com.shinsaikou.domain.download.model.DownloadState
import com.shinsaikou.domain.download.repository.DownloadQueueRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DownloadQueueRepositoryImpl(
    private val handler: DatabaseHandler,
) : DownloadQueueRepository {

    override fun subscribeAll(): Flow<List<DownloadQueueEntry>> =
        handler.subscribeToList { download_queueQueries.getAll(::mapDownloadQueueEntry) }

    override suspend fun getByChapterId(chapterId: Long): DownloadQueueEntry? =
        handler.awaitOneOrNull {
            download_queueQueries.getByChapterId(chapterId, ::mapDownloadQueueEntry)
        }

    override suspend fun getActive(): List<DownloadQueueEntry> =
        handler.awaitList { download_queueQueries.getActive(::mapDownloadQueueEntry) }

    override suspend fun getByMangaId(mangaId: Long): List<DownloadQueueEntry> =
        handler.awaitList { download_queueQueries.getByMangaId(mangaId, ::mapDownloadQueueEntry) }

    /** Reading the tail order and appending to it must not interleave with another enqueue. */
    override suspend fun enqueue(chapterId: Long, mangaId: Long, now: Long) {
        handler.await(inTransaction = true) {
            val queueOrder = download_queueQueries.getMaxQueueOrder().awaitAsOne() + 1
            download_queueQueries.insert(
                chapterId = chapterId,
                mangaId = mangaId,
                queueOrder = queueOrder,
                now = now,
            )
        }
    }

    override suspend fun setState(
        chapterId: Long,
        state: DownloadState,
        errorMessage: String?,
        now: Long,
    ) {
        handler.await {
            download_queueQueries.updateState(
                state = state.code,
                errorMessage = errorMessage,
                now = now,
                chapterId = chapterId,
            )
        }
    }

    override suspend fun setProgress(chapterId: Long, downloadedPages: Long, totalPages: Long) {
        handler.await {
            download_queueQueries.updateProgress(
                downloadedPages = downloadedPages,
                totalPages = totalPages,
                chapterId = chapterId,
            )
        }
    }

    override suspend fun remove(chapterIds: List<Long>) {
        handler.await(inTransaction = true) {
            download_queueQueries.deleteByChapterIds(chapterIds)
        }
    }

    /** The list is the new order in full; position in it becomes `queue_order`. */
    override suspend fun reorder(orderedChapterIds: List<Long>) {
        handler.await(inTransaction = true) {
            orderedChapterIds.forEachIndexed { index, chapterId ->
                download_queueQueries.updateOrder(
                    queueOrder = index.toLong(),
                    chapterId = chapterId,
                )
            }
        }
    }
}

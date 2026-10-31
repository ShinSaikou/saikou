// Vendored from upstream v0.20.4: data/src/main/java/tachiyomi/data/history/HistoryRepositoryImpl.kt (modified: yes)
package tachiyomi.data.history

import com.shinsaikou.data.DatabaseHandler
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.repository.HistoryRepository
import java.util.Date

/**
 * Modified: the constructor takes [DatabaseHandler], and both resets delete the rows they zeroed
 * in the same transaction instead of leaving them for the next start (05-data decision E14).
 * `DatabaseInitializer`'s sweep stays as the safety net for a reset that never committed.
 *
 * Only [deleteAllHistory] keeps upstream's swallowing `catch`, because its `Boolean` return exists
 * to report the failure (05-data 4.3); everywhere else the exception reaches the interactor.
 *
 * [mergeHistory] and [deleteHistoryByChapterIds] are Saikou's, a migration's carry of history.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class HistoryRepositoryImpl(
    private val handler: DatabaseHandler,
) : HistoryRepository {

    override fun getHistory(query: String): Flow<List<HistoryWithRelations>> {
        return handler.subscribeToList {
            historyViewQueries.history(query, HistoryMapper::mapHistoryWithRelations)
        }
    }

    override suspend fun getLastHistory(): HistoryWithRelations? {
        return handler.awaitOneOrNull {
            historyViewQueries.getLatestHistory(HistoryMapper::mapHistoryWithRelations)
        }
    }

    override suspend fun getTotalReadDuration(): Long {
        return handler.awaitOne { historyQueries.getReadDuration() }
    }

    override suspend fun getHistoryByMangaId(mangaId: Long): List<History> {
        return handler.awaitList {
            historyQueries.getHistoryByMangaId(mangaId, HistoryMapper::mapHistory)
        }
    }

    override suspend fun resetHistory(historyId: Long) {
        handler.await(inTransaction = true) {
            historyQueries.resetHistoryById(historyId)
            historyQueries.removeResettedHistory()
        }
    }

    override suspend fun resetHistoryByMangaId(mangaId: Long) {
        handler.await(inTransaction = true) {
            historyQueries.resetHistoryByMangaId(mangaId)
            historyQueries.removeResettedHistory()
        }
    }

    override suspend fun deleteAllHistory(): Boolean {
        return try {
            handler.await { historyQueries.removeAllHistory() }
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
            false
        }
    }

    override suspend fun mergeHistory(chapterId: Long, readAt: Date, readDuration: Long) {
        handler.await { historyQueries.migrationMerge(chapterId, readAt, readDuration) }
    }

    override suspend fun deleteHistoryByChapterIds(chapterIds: List<Long>) {
        if (chapterIds.isEmpty()) return
        handler.await(inTransaction = true) {
            // Bound parameters per statement are capped, as for MangaRepositoryImpl's deletes.
            chapterIds.chunked(DELETE_BATCH).forEach { historyQueries.deleteByChapterIds(it) }
        }
    }

    override suspend fun upsertHistory(historyUpdate: HistoryUpdate) {
        handler.await {
            historyQueries.upsert(
                historyUpdate.chapterId,
                historyUpdate.readAt,
                historyUpdate.sessionReadDuration,
            )
        }
    }

    private companion object {
        const val DELETE_BATCH = 1_000
    }
}

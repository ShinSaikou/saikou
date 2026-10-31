// Vendored from upstream v0.20.4: domain/src/main/java/tachiyomi/domain/history/repository/HistoryRepository.kt (modified: yes) adds mergeHistory and deleteHistoryByChapterIds, a migration's carry of history
package tachiyomi.domain.history.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.history.model.HistoryWithRelations
import java.util.Date

interface HistoryRepository {

    fun getHistory(query: String): Flow<List<HistoryWithRelations>>

    suspend fun getLastHistory(): HistoryWithRelations?

    suspend fun getTotalReadDuration(): Long

    suspend fun getHistoryByMangaId(mangaId: Long): List<History>

    suspend fun resetHistory(historyId: Long)

    suspend fun resetHistoryByMangaId(mangaId: Long)

    suspend fun deleteAllHistory(): Boolean

    suspend fun upsertHistory(historyUpdate: HistoryUpdate)

    /**
     * Folds a migrated entry's history into [chapterId]'s: the later of the two read dates, and
     * [readDuration] added to the reading time. A chapter with no history gets a row.
     */
    suspend fun mergeHistory(chapterId: Long, readAt: Date, readDuration: Long)

    /** Deletes the history of [chapterIds], the rows a migration moved. */
    suspend fun deleteHistoryByChapterIds(chapterIds: List<Long>)
}

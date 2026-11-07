// Vendored from upstream v0.20.4: data/src/main/java/tachiyomi/data/chapter/ChapterRepositoryImpl.kt (modified: yes)
package tachiyomi.data.chapter

import app.cash.sqldelight.async.coroutines.awaitAsOne
import com.shinsaikou.data.DatabaseHandler
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import tachiyomi.core.common.util.lang.toLong
import tachiyomi.data.Database
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository

/**
 * Modified: the constructor takes [DatabaseHandler], the mapper moved out to [ChapterMapper],
 * `insertReturningId` became `insert` plus `selectLastInsertedRowId`, `getChaptersByIds`,
 * `getChaptersByNumbers`, `softDelete` and `getDeletedChaptersByMangaId` are added, and an update can clear the volume (phase
 * 4 unit VG) and deleted_at (phase 4 unit CR). Upstream's swallowing `try/catch` bodies are dropped: every function here
 * returns `Unit` or a list, so a caught exception would be an invisible data loss (05-data 4.3).
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ChapterRepositoryImpl(
    private val handler: DatabaseHandler,
) : ChapterRepository {

    override suspend fun addAll(chapters: List<Chapter>): List<Chapter> {
        return handler.await(inTransaction = true) {
            chapters.map { chapter ->
                chaptersQueries.insert(
                    mangaId = chapter.mangaId,
                    url = chapter.url,
                    name = chapter.name,
                    scanlator = chapter.scanlator,
                    read = chapter.read,
                    bookmark = chapter.bookmark,
                    lastPageRead = chapter.lastPageRead,
                    chapterNumber = chapter.chapterNumber,
                    sourceOrder = chapter.sourceOrder,
                    dateFetch = chapter.dateFetch,
                    dateUpload = chapter.dateUpload,
                    version = chapter.version,
                    memo = chapter.memo,
                    volume = chapter.volume,
                    deletedAt = chapter.deletedAt,
                )
                val chapterId = chaptersQueries.selectLastInsertedRowId().awaitAsOne()
                chapter.copy(id = chapterId)
            }
        }
    }

    override suspend fun update(chapterUpdate: ChapterUpdate) {
        handler.await { updateOne(chapterUpdate) }
    }

    override suspend fun updateAll(chapterUpdates: List<ChapterUpdate>) {
        handler.await(inTransaction = true) {
            chapterUpdates.forEach { updateOne(it) }
        }
    }

    override suspend fun removeChaptersWithIds(chapterIds: List<Long>) {
        handler.await(inTransaction = true) { chaptersQueries.removeChaptersWithIds(chapterIds) }
    }

    override suspend fun getChapterByMangaId(mangaId: Long, applyScanlatorFilter: Boolean): List<Chapter> {
        return handler.awaitList {
            chaptersQueries.getChaptersByMangaId(
                mangaId,
                applyScanlatorFilter.toLong(),
                ChapterMapper::mapChapter,
            )
        }
    }

    override suspend fun getScanlatorsByMangaId(mangaId: Long): List<String> {
        return handler.awaitList { chaptersQueries.getScanlatorsByMangaId(mangaId) }
    }

    override fun getScanlatorsByMangaIdAsFlow(mangaId: Long): Flow<List<String>> {
        return handler.subscribeToList { chaptersQueries.getScanlatorsByMangaId(mangaId) }
    }

    override suspend fun getBookmarkedChaptersByMangaId(mangaId: Long): List<Chapter> {
        return handler.awaitList {
            chaptersQueries.getBookmarkedChaptersByMangaId(mangaId, ChapterMapper::mapChapter)
        }
    }

    override suspend fun getChapterById(id: Long): Chapter? {
        return handler.awaitOneOrNull { chaptersQueries.getChapterById(id, ChapterMapper::mapChapter) }
    }

    override suspend fun getChapterByMangaIdAsFlow(mangaId: Long, applyScanlatorFilter: Boolean): Flow<List<Chapter>> {
        return handler.subscribeToList {
            chaptersQueries.getChaptersByMangaId(
                mangaId,
                applyScanlatorFilter.toLong(),
                ChapterMapper::mapChapter,
            )
        }
    }

    override suspend fun getChapterByUrlAndMangaId(url: String, mangaId: Long): Chapter? {
        return handler.awaitOneOrNull {
            chaptersQueries.getChapterByUrlAndMangaId(url, mangaId, ChapterMapper::mapChapter)
        }
    }

    override suspend fun getChaptersByIds(ids: List<Long>): List<Chapter> {
        return handler.awaitList { chaptersQueries.getChaptersByIds(ids, ChapterMapper::mapChapter) }
    }

    override suspend fun getChaptersByNumbers(
        mangaIds: List<Long>,
        numbers: List<Double>,
        applyScanlatorFilter: Boolean,
    ): List<Chapter> = handler.awaitList {
        chaptersQueries.getChaptersByMangaIdsAndNumbers(
            mangaIds = mangaIds,
            numbers = numbers,
            applyScanlatorFilter = if (applyScanlatorFilter) 1L else 0L,
            mapper = ChapterMapper::mapChapter,
        )
    }

    override suspend fun softDelete(chapterIds: List<Long>, now: Long) {
        handler.await(inTransaction = true) {
            chaptersQueries.softDelete(now = now, chapterIds = chapterIds)
        }
    }

    override suspend fun getDeletedChaptersByMangaId(mangaId: Long): List<Chapter> {
        return handler.awaitList {
            chaptersQueries.getDeletedChaptersByMangaId(mangaId, ChapterMapper::mapChapter)
        }
    }

    private suspend fun Database.updateOne(chapterUpdate: ChapterUpdate) {
        chaptersQueries.update(
            mangaId = chapterUpdate.mangaId,
            url = chapterUpdate.url,
            name = chapterUpdate.name,
            scanlator = chapterUpdate.scanlator,
            read = chapterUpdate.read,
            bookmark = chapterUpdate.bookmark,
            lastPageRead = chapterUpdate.lastPageRead,
            chapterNumber = chapterUpdate.chapterNumber,
            sourceOrder = chapterUpdate.sourceOrder,
            dateFetch = chapterUpdate.dateFetch,
            dateUpload = chapterUpdate.dateUpload,
            version = chapterUpdate.version,
            isSyncing = 0,
            // coalesce erases the memo column adapter, so it is encoded here.
            memo = chapterUpdate.memo?.let(MemoColumnAdapter::encode),
            clearVolume = if (chapterUpdate.clearVolume) 1L else 0L,
            volume = chapterUpdate.volume,
            clearDeletedAt = if (chapterUpdate.clearDeletedAt) 1L else 0L,
            deletedAt = chapterUpdate.deletedAt,
            chapterId = chapterUpdate.id,
        )
    }
}

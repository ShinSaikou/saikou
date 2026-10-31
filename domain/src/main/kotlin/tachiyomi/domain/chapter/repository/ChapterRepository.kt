// Vendored from upstream v0.20.4: domain/src/main/java/tachiyomi/domain/chapter/repository/ChapterRepository.kt (modified: yes)
package tachiyomi.domain.chapter.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate

/**
 * Every read of a manga's chapters excludes rows whose deleted_at is not null, except
 * [getDeletedChaptersByMangaId], which returns only those. The reads by id or url return a
 * soft-deleted row too: their callers already hold its identity.
 */
interface ChapterRepository {

    /** Inserts and returns the rows with ids assigned, one transaction. */
    suspend fun addAll(chapters: List<Chapter>): List<Chapter>

    suspend fun update(chapterUpdate: ChapterUpdate)

    /** One transaction. */
    suspend fun updateAll(chapterUpdates: List<ChapterUpdate>)

    /** Hard delete; a sync tombstone is written by trigger. */
    suspend fun removeChaptersWithIds(chapterIds: List<Long>)

    /** applyScanlatorFilter = true drops chapters whose scanlator is in excluded_scanlators. */
    suspend fun getChapterByMangaId(mangaId: Long, applyScanlatorFilter: Boolean = false): List<Chapter>

    suspend fun getScanlatorsByMangaId(mangaId: Long): List<String>

    fun getScanlatorsByMangaIdAsFlow(mangaId: Long): Flow<List<String>>

    suspend fun getBookmarkedChaptersByMangaId(mangaId: Long): List<Chapter>

    suspend fun getChapterById(id: Long): Chapter?

    suspend fun getChapterByMangaIdAsFlow(mangaId: Long, applyScanlatorFilter: Boolean = false): Flow<List<Chapter>>

    suspend fun getChapterByUrlAndMangaId(url: String, mangaId: Long): Chapter?

    // Fresh below this line.

    suspend fun getChaptersByIds(ids: List<Long>): List<Chapter>

    suspend fun softDelete(chapterIds: List<Long>, now: Long)

    /**
     * The manga's soft-deleted rows, which a sync restores when the source lists them again, with
     * [ChapterUpdate.clearDeletedAt] in the chapter's one update.
     */
    suspend fun getDeletedChaptersByMangaId(mangaId: Long): List<Chapter>
}

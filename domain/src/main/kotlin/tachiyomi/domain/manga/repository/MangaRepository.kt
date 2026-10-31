// Vendored from upstream v0.20.4: domain/src/main/java/tachiyomi/domain/manga/repository/MangaRepository.kt (modified: yes)
package tachiyomi.domain.manga.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.MangaWithChapterCount

interface MangaRepository {

    /** Throws NullPointerException ("ResultSet returned null") when absent. */
    suspend fun getMangaById(id: Long): Manga

    /** Its collector throws as [getMangaById] does when the row is absent or is deleted. */
    suspend fun getMangaByIdAsFlow(id: Long): Flow<Manga>

    /** Exact (source, url) match: the backup-restore identity key. */
    suspend fun getMangaByUrlAndSourceId(url: String, sourceId: Long): Manga?

    fun getMangaByUrlAndSourceIdAsFlow(url: String, sourceId: Long): Flow<Manga?>

    /** favorite = 1 AND deleted_at IS NULL. */
    suspend fun getFavorites(): List<Manga>

    /** favorite = 0 rows that have any read chapter or page progress. */
    suspend fun getReadMangaNotInLibrary(): List<Manga>

    /** Library view rows: favourites with chapter aggregates and category ids. */
    suspend fun getLibraryManga(): List<LibraryManga>

    fun getLibraryMangaAsFlow(): Flow<List<LibraryManga>>

    fun getFavoritesBySourceId(sourceId: Long): Flow<List<Manga>>

    suspend fun getDuplicateLibraryManga(id: Long, title: String): List<MangaWithChapterCount>

    /** Sets viewer = 0 on every row; returns false on failure. */
    suspend fun resetViewerFlags(): Boolean

    /**
     * Writes the library's per-entry chapter counts again from the chapters, in one transaction
     * (schema version 3, phase 5 unit SC4); returns false on failure.
     */
    suspend fun rebuildLibrarySummary(): Boolean

    /** Replaces the manga's category set in one transaction. */
    suspend fun setMangaCategories(mangaId: Long, categoryIds: List<Long>)

    suspend fun update(update: MangaUpdate): Boolean

    suspend fun updateAll(mangaUpdates: List<MangaUpdate>): Boolean

    /**
     * Inserts each manga unless a (source, url) row exists; when it exists and the row is not
     * initialized, refreshes title/thumbnail/metadata from the given value; returns the rows as
     * stored (with ids). Runs in one transaction.
     */
    suspend fun insertNetworkManga(manga: List<Manga>): List<Manga>

    // Fresh below this line.

    /**
     * [getMangaByIdAsFlow] that emits null while no row has [id], rather than throwing: an entry
     * intent can outlive its entry, and a screen open on one can see the row deleted under it
     * (phase 4 unit missing-entry). Clear database is what removes a row; a sync only sets
     * `deleted_at`, and the row still reads.
     */
    fun getMangaByIdOrNullAsFlow(id: Long): Flow<Manga?>

    suspend fun getExcludedScanlators(mangaId: Long): Set<String>

    fun getExcludedScanlatorsAsFlow(mangaId: Long): Flow<Set<String>>

    /** Replaces the set in one transaction. */
    suspend fun setExcludedScanlators(mangaId: Long, scanlators: Set<String>)

    /** orderedIds[i] gets manual_position = i + 1, in one transaction. */
    suspend fun setManualPositions(orderedIds: List<Long>)

    /**
     * Writes the owner's edit of the title and description whole, null for unedited (phase 5 unit
     * ED); returns false on failure. Only `SetMangaDetailsOverride` calls it.
     */
    suspend fun setDetailsOverride(mangaId: Long, titleOverride: String?, descriptionOverride: String?): Boolean

    /** Soft delete: deleted_at = now, favorite = 0. Never a SQL DELETE for a manga that was ever favourite. */
    suspend fun softDelete(mangaId: Long, now: Long)

    suspend fun undelete(mangaId: Long)

    /** Hard delete of never-favourited rows for the given sources. */
    suspend fun deleteNonLibraryManga(sourceIds: List<Long>, keepReadManga: Boolean)

    /**
     * Ids of the live non-library rows of the given sources: the rows [getSourceIdsWithNonLibraryMangaAsFlow]
     * counts, as clear database's confirmation freezes them when it opens.
     */
    suspend fun getLiveNonLibraryMangaIds(sourceIds: List<Long>): List<Long>

    /**
     * Hard delete of the given rows that are still live and outside the library, less those with
     * progress when keepReadManga is on. A row among them that joined the library, or was
     * soft-deleted, since the ids were read stays.
     */
    suspend fun deleteNonLibraryMangaByIds(mangaIds: List<Long>, keepReadManga: Boolean)

    /** Rewrites mangas.source (and nothing else) for every row of oldSourceId; used with source_alias. */
    suspend fun rewriteSourceId(oldSourceId: Long, newSourceId: Long): Long

    /** Source ids with the count of favourites in each. */
    fun getSourceIdsWithFavoriteCountAsFlow(): Flow<Map<Long, Long>>

    /** The ids of every live favourite, emitted again whenever the table changes. */
    fun getFavoriteIdsAsFlow(): Flow<Set<Long>>

    /**
     * Source ids with the count of live non-library rows in each: the rows [deleteNonLibraryManga]
     * removes with keepReadManga off. Soft-deleted rows are left out, as the delete leaves them.
     */
    fun getSourceIdsWithNonLibraryMangaAsFlow(): Flow<Map<Long, Long>>
}

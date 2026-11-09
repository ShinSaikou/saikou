// Vendored from upstream v0.20.4: data/src/main/java/tachiyomi/data/manga/MangaRepositoryImpl.kt (modified: yes)
package tachiyomi.data.manga

import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.shinsaikou.data.DatabaseHandler
import com.shinsaikou.domain.manga.model.MangaDetailsOverrides
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import logcat.LogPriority
import tachiyomi.core.common.util.lang.toLong
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.repository.MangaRepository

/**
 * Modified: the constructor takes [DatabaseHandler] rather than `Database`, `getUpcomingManga` is
 * gone (no upcoming screen), and the fresh soft-delete, manual-order, excluded-scanlator and
 * source-rewrite functions of 04-domain's interface are added, with `getMangaByIdOrNullAsFlow`
 * (phase 4 unit missing-entry), `partialUpdate` logs a library add's chapters without progress
 * (phase 4 unit NB), `rebuildLibrarySummary` is the library counts' repair (phase 5 unit SC4),
 * `setDetailsOverride` is the owner's edit of every detail (phase 5 unit ED, R165 item 4), and
 * `rewriteSourceId` rewrites the merged tables too (R163).
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class MangaRepositoryImpl(
    private val handler: DatabaseHandler,
) : MangaRepository {

    override suspend fun getMangaById(id: Long): Manga {
        return handler.awaitOne { mangasQueries.getMangaById(id, MangaMapper::mapManga) }
    }

    override suspend fun getMangaByIdAsFlow(id: Long): Flow<Manga> {
        return handler.subscribeToOne { mangasQueries.getMangaById(id, MangaMapper::mapManga) }
    }

    override fun getMangaByIdOrNullAsFlow(id: Long): Flow<Manga?> {
        return handler.subscribeToOneOrNull { mangasQueries.getMangaById(id, MangaMapper::mapManga) }
    }

    override suspend fun getMangaByUrlAndSourceId(url: String, sourceId: Long): Manga? {
        return handler.awaitOneOrNull {
            mangasQueries.getMangaByUrlAndSource(url, sourceId, MangaMapper::mapManga)
        }
    }

    override fun getMangaByUrlAndSourceIdAsFlow(url: String, sourceId: Long): Flow<Manga?> {
        return handler.subscribeToOneOrNull {
            mangasQueries.getMangaByUrlAndSource(url, sourceId, MangaMapper::mapManga)
        }
    }

    override suspend fun getFavorites(): List<Manga> {
        return handler.awaitList { mangasQueries.getFavorites(MangaMapper::mapManga) }
    }

    override suspend fun getReadMangaNotInLibrary(): List<Manga> {
        return handler.awaitList { mangasQueries.getReadMangaNotInLibrary(MangaMapper::mapManga) }
    }

    override suspend fun getLibraryManga(): List<LibraryManga> {
        return handler.awaitList { libraryViewQueries.library(MangaMapper::mapLibraryManga) }
    }

    override fun getLibraryMangaAsFlow(): Flow<List<LibraryManga>> {
        return handler.subscribeToList { libraryViewQueries.library(MangaMapper::mapLibraryManga) }
    }

    override fun getFavoritesBySourceId(sourceId: Long): Flow<List<Manga>> {
        return handler.subscribeToList {
            mangasQueries.getFavoriteBySourceId(sourceId, MangaMapper::mapManga)
        }
    }

    override suspend fun getDuplicateLibraryManga(id: Long, title: String): List<MangaWithChapterCount> {
        return handler.awaitList {
            mangasQueries.getDuplicateLibraryManga(id, title, MangaMapper::mapMangaWithChapterCount)
        }
    }

    override suspend fun resetViewerFlags(): Boolean {
        return try {
            handler.await { mangasQueries.resetViewerFlags() }
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            false
        }
    }

    override suspend fun rebuildLibrarySummary(): Boolean {
        return try {
            handler.await(inTransaction = true) {
                library_summaryQueries.clearLibrarySummary()
                library_summaryQueries.backfillLibrarySummary()
            }
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            false
        }
    }

    override suspend fun setMangaCategories(mangaId: Long, categoryIds: List<Long>) {
        handler.await(inTransaction = true) {
            mangas_categoriesQueries.deleteMangaCategoryByMangaId(mangaId)
            categoryIds.forEach { categoryId ->
                mangas_categoriesQueries.insert(mangaId, categoryId)
            }
        }
    }

    override suspend fun update(update: MangaUpdate): Boolean {
        return try {
            partialUpdate(update)
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            false
        }
    }

    override suspend fun updateAll(mangaUpdates: List<MangaUpdate>): Boolean {
        return try {
            partialUpdate(*mangaUpdates.toTypedArray())
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            false
        }
    }

    override suspend fun insertNetworkManga(manga: List<Manga>): List<Manga> {
        return handler.await(inTransaction = true) {
            manga.map {
                mangasQueries.insertNetworkManga(
                    source = it.source,
                    url = it.url,
                    artist = it.artist,
                    author = it.author,
                    description = it.description,
                    genre = it.genre,
                    title = it.title,
                    status = it.status,
                    thumbnailUrl = it.thumbnailUrl,
                    favorite = it.favorite,
                    lastUpdate = it.lastUpdate,
                    nextUpdate = it.nextUpdate,
                    initialized = it.initialized,
                    viewer = it.viewerFlags,
                    chapterFlags = it.chapterFlags,
                    coverLastModified = it.coverLastModified,
                    dateAdded = it.dateAdded,
                    updateStrategy = it.updateStrategy,
                    calculateInterval = it.fetchInterval.toLong(),
                    memo = it.memo,
                )
                mangasQueries.refreshNetworkMangaIfUninitialized(
                    source = it.source,
                    url = it.url,
                    artist = it.artist,
                    author = it.author,
                    description = it.description,
                    genre = it.genre,
                    title = it.title,
                    status = it.status,
                    thumbnailUrl = it.thumbnailUrl,
                    lastUpdate = it.lastUpdate,
                    nextUpdate = it.nextUpdate,
                    initialized = it.initialized,
                    coverLastModified = it.coverLastModified,
                    updateStrategy = it.updateStrategy,
                    calculateInterval = it.fetchInterval.toLong(),
                    memo = it.memo,
                )
                // The insert is a guarded INSERT ... SELECT with no RETURNING clause, so the
                // stored row is read back by its (source, url) identity key.
                mangasQueries.getMangaByUrlAndSource(it.url, it.source, MangaMapper::mapManga)
                    .awaitAsOne()
            }
        }
    }

    override suspend fun getExcludedScanlators(mangaId: Long): Set<String> {
        return handler.awaitList {
            excluded_scanlatorsQueries.getExcludedScanlatorsByMangaId(mangaId)
        }.toSet()
    }

    override fun getExcludedScanlatorsAsFlow(mangaId: Long): Flow<Set<String>> {
        return handler.subscribeToList {
            excluded_scanlatorsQueries.getExcludedScanlatorsByMangaId(mangaId)
        }.map { it.toSet() }
    }

    override suspend fun setExcludedScanlators(mangaId: Long, scanlators: Set<String>) {
        handler.await(inTransaction = true) {
            excluded_scanlatorsQueries.removeAll(mangaId)
            scanlators.forEach { scanlator ->
                excluded_scanlatorsQueries.insert(mangaId, scanlator)
            }
        }
    }

    override suspend fun setManualPositions(orderedIds: List<Long>) {
        handler.await(inTransaction = true) {
            orderedIds.forEachIndexed { index, mangaId ->
                // 0 is "unranked", so the first manga is position 1.
                mangasQueries.setManualPosition(position = index + 1L, mangaId = mangaId)
            }
        }
    }

    override suspend fun setDetailsOverride(mangaId: Long, overrides: MangaDetailsOverrides): Boolean {
        return try {
            handler.await {
                mangasQueries.setEditedDetails(
                    titleOverride = overrides.title,
                    descriptionOverride = overrides.description,
                    authorOverride = overrides.author,
                    artistOverride = overrides.artist,
                    statusOverride = overrides.status,
                    genreOverride = overrides.genre,
                    mangaId = mangaId,
                )
            }
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            false
        }
    }

    override suspend fun softDelete(mangaId: Long, now: Long) {
        handler.await { mangasQueries.softDelete(now = now, mangaId = mangaId) }
    }

    override suspend fun undelete(mangaId: Long) {
        handler.await { mangasQueries.undelete(mangaId) }
    }

    override suspend fun deleteNonLibraryManga(sourceIds: List<Long>, keepReadManga: Boolean) {
        handler.await {
            mangasQueries.deleteNonLibraryManga(
                sourceIds = sourceIds,
                keepReadManga = keepReadManga.toLong(),
            )
        }
    }

    override suspend fun getLiveNonLibraryMangaIds(sourceIds: List<Long>): List<Long> {
        return handler.awaitList { mangasQueries.getLiveNonLibraryMangaIds(sourceIds) }
    }

    override suspend fun deleteNonLibraryMangaByIds(mangaIds: List<Long>, keepReadManga: Boolean) {
        handler.await(inTransaction = true) {
            // Bound parameters per statement are capped (32766 in the bundled SQLite).
            mangaIds.chunked(DELETE_BATCH).forEach { batch ->
                mangasQueries.deleteNonLibraryMangaByIds(mangaIds = batch, keepReadManga = keepReadManga.toLong())
            }
        }
    }

    override suspend fun rewriteSourceId(oldSourceId: Long, newSourceId: Long): Long {
        return handler.await(inTransaction = true) {
            merged_groupsQueries.rewriteGroupSource(newSourceId = newSourceId, oldSourceId = oldSourceId)
            merged_groupsQueries.rewriteMemberSource(newSourceId = newSourceId, oldSourceId = oldSourceId)
            mangasQueries.rewriteSourceId(newSourceId = newSourceId, oldSourceId = oldSourceId)
        }
    }

    override fun getSourceIdsWithFavoriteCountAsFlow(): Flow<Map<Long, Long>> {
        return handler.subscribeToList {
            mangasQueries.getSourceIdWithFavoriteCount { source, count -> source to count }
        }.map { it.toMap() }
    }

    override fun getFavoriteIdsAsFlow(): Flow<Set<Long>> {
        return handler.subscribeToList { mangasQueries.getFavoriteIds() }.map { it.toSet() }
    }

    override fun getSourceIdsWithNonLibraryMangaAsFlow(): Flow<Map<Long, Long>> {
        return handler.subscribeToList {
            mangasQueries.getSourceIdsWithLiveNonLibraryManga { source, count -> source to count }
        }.map { it.toMap() }
    }

    /**
     * Every local write of `favorite` comes through here, so here a library add logs the entry's
     * whole chapter list (phase 4 unit NB): `log_manga_progress_on_favorite` logs the chapters with
     * progress, and `logChaptersWithoutProgressOnFavorite` the rest, so another device receives every
     * chapter with the entry. The favourite is read just before each update, not once for the call,
     * because a migration's pair takes one entry out and puts another in. A missing row logs nothing.
     */
    private suspend fun partialUpdate(vararg mangaUpdates: MangaUpdate) {
        handler.await(inTransaction = true) {
            mangaUpdates.forEach { value ->
                val libraryAdd = value.favorite == true &&
                    mangasQueries.getFavoriteById(value.id).awaitAsOneOrNull() == false
                mangasQueries.update(
                    source = value.source,
                    url = value.url,
                    artist = value.artist,
                    author = value.author,
                    description = value.description,
                    // coalesce erases the column adapters, so the three adapted columns are
                    // encoded here instead (carry-over 5 from the :data schema unit).
                    genre = value.genre?.let(StringListColumnAdapter::encode),
                    title = value.title,
                    status = value.status,
                    thumbnailUrl = value.thumbnailUrl,
                    favorite = value.favorite,
                    lastUpdate = value.lastUpdate,
                    nextUpdate = value.nextUpdate,
                    initialized = value.initialized,
                    viewer = value.viewerFlags,
                    chapterFlags = value.chapterFlags,
                    coverLastModified = value.coverLastModified,
                    dateAdded = value.dateAdded,
                    updateStrategy = value.updateStrategy?.ordinal?.toLong(),
                    calculateInterval = value.fetchInterval?.toLong(),
                    version = value.version,
                    isSyncing = 0,
                    notes = value.notes,
                    memo = value.memo?.let(MemoColumnAdapter::encode),
                    manualPosition = value.manualPosition,
                    deletedAt = value.deletedAt,
                    mangaId = value.id,
                )
                if (libraryAdd) sync_change_logQueries.logChaptersWithoutProgressOnFavorite(value.id)
            }
        }
    }

    private companion object {
        const val DELETE_BATCH = 1_000
    }
}

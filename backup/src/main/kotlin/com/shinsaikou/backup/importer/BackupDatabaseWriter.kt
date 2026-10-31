package com.shinsaikou.backup.importer

import app.cash.sqldelight.async.coroutines.awaitAsOne
import com.shinsaikou.data.DatabaseHandler
import dev.zacsweers.metro.Inject
import tachiyomi.data.Database
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.model.History
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.model.Track
import java.util.Date

/**
 * The only class outside `:data` that calls a generated query. It exists so the importer's merge
 * rules can be read on their own and so every query name this module depends on sits in one file.
 *
 * Each function is one `handler.await { }`. Called from inside the chunk transaction it joins that
 * transaction rather than opening its own, which is what makes a chunk atomic.
 *
 * The `update` queries are `coalesce`-based, so every column is bound with the merged value and
 * nothing is left to chance. `manual_position`, `deleted_at` and `volume` are bound to the row's
 * own value, so an import never touches a column the backup format has no field for.
 */
@Inject
class BackupDatabaseWriter(private val handler: DatabaseHandler) {

    suspend fun findManga(url: String, sourceId: Long): Manga? = handler.awaitOneOrNull {
        mangasQueries.getMangaByUrlAndSource(url, sourceId, ::mapManga)
    }

    suspend fun insertManga(manga: Manga): Long = handler.await {
        mangasQueries.insert(
            source = manga.source,
            url = manga.url,
            artist = manga.artist,
            author = manga.author,
            description = manga.description,
            genre = manga.genre,
            title = manga.title,
            status = manga.status,
            thumbnailUrl = manga.thumbnailUrl,
            favorite = manga.favorite,
            lastUpdate = 0L,
            nextUpdate = 0L,
            initialized = manga.initialized,
            // No masking: bits above the reading-mode and orientation groups are preserved.
            viewer = manga.viewerFlags,
            chapterFlags = manga.chapterFlags,
            coverLastModified = 0L,
            dateAdded = manga.dateAdded,
            updateStrategy = manga.updateStrategy,
            calculateInterval = 0L,
            lastModifiedAt = manga.lastModifiedAt,
            version = manga.version,
            notes = manga.notes,
            memo = manga.memo,
            manualPosition = 0L,
            deletedAt = null,
        )
        mangasQueries.selectLastInsertedRowId().awaitAsOne()
    }

    suspend fun updateManga(manga: Manga, isSyncing: Boolean) = handler.await {
        mangasQueries.update(
            source = manga.source,
            url = manga.url,
            artist = manga.artist,
            author = manga.author,
            description = manga.description,
            // coalesce erases the column adapters, so the adapted columns are encoded here.
            genre = manga.genre?.let(StringListColumnAdapter::encode),
            title = manga.title,
            status = manga.status,
            thumbnailUrl = manga.thumbnailUrl,
            favorite = manga.favorite,
            lastUpdate = manga.lastUpdate,
            nextUpdate = manga.nextUpdate,
            initialized = manga.initialized,
            viewer = manga.viewerFlags,
            chapterFlags = manga.chapterFlags,
            coverLastModified = manga.coverLastModified,
            dateAdded = manga.dateAdded,
            updateStrategy = manga.updateStrategy.ordinal.toLong(),
            calculateInterval = null,
            version = manga.version,
            isSyncing = if (isSyncing) 1L else 0L,
            notes = manga.notes,
            memo = MemoColumnAdapter.encode(manga.memo),
            manualPosition = manga.manualPosition,
            deletedAt = manga.deletedAt,
            mangaId = manga.id,
        )
    }

    suspend fun undelete(mangaId: Long) = handler.await {
        mangasQueries.undelete(mangaId)
    }

    /**
     * Writes both overrides whole. Called under the row's latch, so neither the edit's clock nor the
     * version moves, as with every other value a restore writes.
     */
    suspend fun setDetailsOverride(mangaId: Long, titleOverride: String?, descriptionOverride: String?) =
        handler.await {
            mangasQueries.setDetailsOverride(
                titleOverride = titleOverride,
                descriptionOverride = descriptionOverride,
                mangaId = mangaId,
            )
        }

    /**
     * Only `is_syncing` is bound; every other column coalesces to itself. Called right after an
     * insert, so the category and scanlator writes that follow do not bump the row's `version`
     * through their triggers: the restored row must keep the version the file gave it.
     */
    suspend fun markSyncing(mangaId: Long) = handler.await {
        mangasQueries.update(
            source = null, url = null, artist = null, author = null, description = null,
            genre = null, title = null, status = null, thumbnailUrl = null, favorite = null,
            lastUpdate = null, nextUpdate = null, initialized = null, viewer = null,
            chapterFlags = null, coverLastModified = null, dateAdded = null, updateStrategy = null,
            calculateInterval = null, version = null, isSyncing = 1L, notes = null, memo = null,
            manualPosition = null, deletedAt = null, mangaId = mangaId,
        )
    }

    /** Only `is_syncing` is bound; every other column coalesces to itself. */
    suspend fun resetIsSyncing(mangaId: Long) = handler.await {
        mangasQueries.update(
            source = null, url = null, artist = null, author = null, description = null,
            genre = null, title = null, status = null, thumbnailUrl = null, favorite = null,
            lastUpdate = null, nextUpdate = null, initialized = null, viewer = null,
            chapterFlags = null, coverLastModified = null, dateAdded = null, updateStrategy = null,
            calculateInterval = null, version = null, isSyncing = 0L, notes = null, memo = null,
            manualPosition = null, deletedAt = null, mangaId = mangaId,
        )
    }

    suspend fun setMangaCategories(mangaId: Long, categoryIds: List<Long>) = handler.await {
        mangas_categoriesQueries.deleteMangaCategoryByMangaId(mangaId)
        categoryIds.forEach { mangas_categoriesQueries.insert(mangaId, it) }
    }

    suspend fun chaptersByUrl(mangaId: Long): Map<String, Chapter> = handler
        .awaitList {
            chaptersQueries.getChaptersByMangaId(mangaId, applyScanlatorFilter = 0L, mapper = ::mapChapter)
        }
        .associateBy { it.url }

    /**
     * The manga's soft-deleted chapters by url, which a restore matches when no live row has the
     * url (phase 4 unit BF, R120 carry-over 2): a file chapter whose url only a tombstone holds
     * restores that row rather than inserting a second one beside it. Two tombstones of one url,
     * which only that second row could leave behind, give the one deleted last.
     */
    suspend fun deletedChaptersByUrl(mangaId: Long): Map<String, Chapter> = handler
        .awaitList { chaptersQueries.getDeletedChaptersByMangaId(mangaId, ::mapChapter) }
        .sortedWith(compareBy<Chapter>({ it.deletedAt }, { it.id }))
        .associateBy { it.url }

    /**
     * A restore's new chapters, each inserted with its latch up, so the change log gets no row for
     * any of them: the restore's forced full export carries them (phase 5 unit SC2).
     * [lowerChaptersLatch] lowers the latch once the entry's writes are done. They go in
     * [CHAPTERS_PER_STATEMENT] to a statement (`insertSyncingTen`), the rest one at a time
     * (`insertSyncing`), in list order, so each row gets the id a row-at-a-time insert gave it.
     */
    suspend fun insertChapters(chapters: List<Chapter>) = handler.await {
        val whole = chapters.size - chapters.size % CHAPTERS_PER_STATEMENT
        chapters.subList(0, whole).chunked(CHAPTERS_PER_STATEMENT).forEach { insertTen(it) }
        chapters.subList(whole, chapters.size).forEach { chapter ->
            chaptersQueries.insertSyncing(
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
        }
    }

    /** `insertSyncingTen` over [c], whose ten chapters bind its tuples in order, in `insertSyncing`'s columns. */
    private suspend fun Database.insertTen(c: List<Chapter>) {
        check(c.size == CHAPTERS_PER_STATEMENT) { "${c.size} chapters for a statement of $CHAPTERS_PER_STATEMENT" }
        chaptersQueries.insertSyncingTen(
            c[0].mangaId, c[0].url, c[0].name, c[0].scanlator, c[0].read, c[0].bookmark, c[0].lastPageRead,
            c[0].chapterNumber, c[0].sourceOrder, c[0].dateFetch, c[0].dateUpload, c[0].version, c[0].memo, c[0].volume,
            c[0].deletedAt,
            c[1].mangaId, c[1].url, c[1].name, c[1].scanlator, c[1].read, c[1].bookmark, c[1].lastPageRead,
            c[1].chapterNumber, c[1].sourceOrder, c[1].dateFetch, c[1].dateUpload, c[1].version, c[1].memo, c[1].volume,
            c[1].deletedAt,
            c[2].mangaId, c[2].url, c[2].name, c[2].scanlator, c[2].read, c[2].bookmark, c[2].lastPageRead,
            c[2].chapterNumber, c[2].sourceOrder, c[2].dateFetch, c[2].dateUpload, c[2].version, c[2].memo, c[2].volume,
            c[2].deletedAt,
            c[3].mangaId, c[3].url, c[3].name, c[3].scanlator, c[3].read, c[3].bookmark, c[3].lastPageRead,
            c[3].chapterNumber, c[3].sourceOrder, c[3].dateFetch, c[3].dateUpload, c[3].version, c[3].memo, c[3].volume,
            c[3].deletedAt,
            c[4].mangaId, c[4].url, c[4].name, c[4].scanlator, c[4].read, c[4].bookmark, c[4].lastPageRead,
            c[4].chapterNumber, c[4].sourceOrder, c[4].dateFetch, c[4].dateUpload, c[4].version, c[4].memo, c[4].volume,
            c[4].deletedAt,
            c[5].mangaId, c[5].url, c[5].name, c[5].scanlator, c[5].read, c[5].bookmark, c[5].lastPageRead,
            c[5].chapterNumber, c[5].sourceOrder, c[5].dateFetch, c[5].dateUpload, c[5].version, c[5].memo, c[5].volume,
            c[5].deletedAt,
            c[6].mangaId, c[6].url, c[6].name, c[6].scanlator, c[6].read, c[6].bookmark, c[6].lastPageRead,
            c[6].chapterNumber, c[6].sourceOrder, c[6].dateFetch, c[6].dateUpload, c[6].version, c[6].memo, c[6].volume,
            c[6].deletedAt,
            c[7].mangaId, c[7].url, c[7].name, c[7].scanlator, c[7].read, c[7].bookmark, c[7].lastPageRead,
            c[7].chapterNumber, c[7].sourceOrder, c[7].dateFetch, c[7].dateUpload, c[7].version, c[7].memo, c[7].volume,
            c[7].deletedAt,
            c[8].mangaId, c[8].url, c[8].name, c[8].scanlator, c[8].read, c[8].bookmark, c[8].lastPageRead,
            c[8].chapterNumber, c[8].sourceOrder, c[8].dateFetch, c[8].dateUpload, c[8].version, c[8].memo, c[8].volume,
            c[8].deletedAt,
            c[9].mangaId, c[9].url, c[9].name, c[9].scanlator, c[9].read, c[9].bookmark, c[9].lastPageRead,
            c[9].chapterNumber, c[9].sourceOrder, c[9].dateFetch, c[9].dateUpload, c[9].version, c[9].memo, c[9].volume,
            c[9].deletedAt,
        )
    }

    /**
     * [clearDeletedAt] restores each row in its one update: `deleted_at` is written null in the same
     * statement, R120's `clearDeletedAt` parameter, and the chapter's own `deletedAt` is ignored.
     *
     * With [isSyncing] true the latch is up, no trigger moves the version, and the chapter's own
     * `version` is written. With it false the `update_chapter_version` trigger moves the version once
     * for the edit, so the chapter's `version` is not written (phase 4 unit BF): written as well, the
     * trigger's step stacked on it, and a restore stored the file's version plus one, a clock that
     * no edit on this device earned and that the next sync's source-value comparison would trust.
     */
    suspend fun updateChapters(
        chapters: List<Chapter>,
        isSyncing: Boolean,
        clearDeletedAt: Boolean = false,
    ) = handler.await {
        chapters.forEach { chapter ->
            chaptersQueries.update(
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
                version = if (isSyncing) chapter.version else null,
                isSyncing = if (isSyncing) 1L else 0L,
                memo = MemoColumnAdapter.encode(chapter.memo),
                clearVolume = 0L,
                volume = chapter.volume,
                clearDeletedAt = if (clearDeletedAt) 1L else 0L,
                deletedAt = chapter.deletedAt,
                chapterId = chapter.id,
            )
        }
    }

    /** One statement for every chapter of [mangaId] still under the latch; see `resetIsSyncingByMangaId`. */
    suspend fun lowerChaptersLatch(mangaId: Long) = handler.await {
        chaptersQueries.resetIsSyncingByMangaId(mangaId)
    }

    suspend fun resetChaptersIsSyncing(chapterIds: List<Long>) = handler.await {
        chapterIds.forEach { id ->
            chaptersQueries.update(
                mangaId = null, url = null, name = null, scanlator = null, read = null,
                bookmark = null, lastPageRead = null, chapterNumber = null, sourceOrder = null,
                dateFetch = null, dateUpload = null, version = null, isSyncing = 0L, memo = null,
                clearVolume = 0L, volume = null, clearDeletedAt = 0L, deletedAt = null, chapterId = id,
            )
        }
    }

    suspend fun tracksByTrackerId(mangaId: Long): Map<Long, Track> = handler
        .awaitList { manga_syncQueries.getTracksByMangaId(mangaId, ::mapTrack) }
        .associateBy { it.trackerId }

    suspend fun insertTracks(tracks: List<Track>) = handler.await {
        tracks.forEach { track ->
            manga_syncQueries.insert(
                mangaId = track.mangaId,
                syncId = track.trackerId,
                remoteId = track.remoteId,
                libraryId = track.libraryId,
                title = track.title,
                lastChapterRead = track.lastChapterRead,
                totalChapters = track.totalChapters,
                status = track.status,
                score = track.score,
                remoteUrl = track.remoteUrl,
                startDate = track.startDate,
                finishDate = track.finishDate,
                private = track.private,
            )
        }
    }

    suspend fun updateTrack(track: Track) = handler.await {
        manga_syncQueries.update(
            mangaId = track.mangaId,
            syncId = track.trackerId,
            mediaId = track.remoteId,
            libraryId = track.libraryId,
            title = track.title,
            lastChapterRead = track.lastChapterRead,
            totalChapter = track.totalChapters,
            status = track.status,
            score = track.score,
            trackingUrl = track.remoteUrl,
            startDate = track.startDate,
            finishDate = track.finishDate,
            private = track.private,
            id = track.id,
        )
    }

    suspend fun historyByChapterId(mangaId: Long): Map<Long, History> = handler
        .awaitList { historyQueries.getHistoryByMangaId(mangaId, ::mapHistory) }
        .associateBy { it.chapterId }

    /** The query adds [readDuration] to the stored value, so the caller passes a delta. */
    suspend fun upsertHistory(chapterId: Long, readAt: Long, readDuration: Long) = handler.await {
        historyQueries.upsert(chapterId, Date(readAt), readDuration)
    }

    /** `UNIQUE ... ON CONFLICT IGNORE` makes a re-import a no-op. */
    suspend fun addExcludedScanlators(mangaId: Long, scanlators: Set<String>) = handler.await {
        scanlators.forEach { excluded_scanlatorsQueries.insert(mangaId, it) }
    }

    private companion object {
        /** `insertSyncingTen`'s tuples. */
        const val CHAPTERS_PER_STATEMENT = 10
    }
}

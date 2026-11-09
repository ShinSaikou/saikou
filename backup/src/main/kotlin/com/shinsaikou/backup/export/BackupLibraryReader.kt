package com.shinsaikou.backup.export

import com.shinsaikou.data.DatabaseHandler
import com.shinsaikou.domain.chapter.model.BookmarkNote
import com.shinsaikou.domain.chapter.model.ScanlatorPriority
import dev.zacsweers.metro.Inject
import tachiyomi.data.chapter.ChapterMapper
import tachiyomi.data.history.HistoryMapper
import tachiyomi.data.manga.MangaMapper
import tachiyomi.data.track.TrackMapper
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.model.History
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.model.Track

/**
 * The export's reads of the library, a page of entries at a time (phase 5 unit SC2). The repository
 * reads the export used before ran six queries per entry, 240,000 for a 40,000-entry library; each
 * function here is one query for a whole page, its rows grouped by entry in Kotlin. Rows map through
 * the `:data` repository mappers, so an entry's rows are the ones those reads returned, in the same
 * order (each statement's comment says how), and [BackupExporter] builds the same entry from them.
 */
@Inject
class BackupLibraryReader(private val handler: DatabaseHandler) {

    /**
     * Up to [limit] entries with an id above [afterId], by id: the favourites and, with [withRead],
     * the entries outside the library with a read or begun chapter.
     */
    suspend fun libraryPage(afterId: Long, withRead: Boolean, limit: Int): List<Manga> = handler.awaitList {
        mangasQueries.backupGetLibraryPage(
            afterId = afterId,
            withRead = if (withRead) 1L else 0L,
            limit = limit.toLong(),
            mapper = MangaMapper::mapManga,
        )
    }

    /** The live chapters of each entry. */
    suspend fun chapters(mangaIds: List<Long>): Map<Long, List<Chapter>> = handler
        .awaitList { chaptersQueries.backupGetChaptersByMangaIds(mangaIds, ChapterMapper::mapChapter) }
        .groupBy { it.mangaId }

    /** The ids of each entry's live categories, lowest sort value first and the id breaking a tie. */
    suspend fun categoryIds(mangaIds: List<Long>): Map<Long, List<Long>> = handler
        .awaitList {
            categoriesQueries.backupGetCategoryIdsByMangaIds(mangaIds) { mangaId, categoryId -> mangaId to categoryId }
        }
        .groupBy({ it.first }, { it.second })

    suspend fun tracks(mangaIds: List<Long>): Map<Long, List<Track>> = handler
        .awaitList { manga_syncQueries.backupGetTracksByMangaIds(mangaIds, TrackMapper::mapTrack) }
        .groupBy { it.mangaId }

    /** Each entry's history with a read time on a live chapter, with that chapter's url. */
    suspend fun history(mangaIds: List<Long>): Map<Long, List<Pair<String, History>>> = handler
        .awaitList {
            historyQueries.backupGetHistoryByMangaIds(mangaIds) { mangaId, url, id, chapterId, readAt, readDuration ->
                Triple(mangaId, url, HistoryMapper.mapHistory(id, chapterId, readAt, readDuration))
            }
        }
        .groupBy({ it.first }, { it.second to it.third })

    suspend fun excludedScanlators(mangaIds: List<Long>): Map<Long, List<String>> = handler
        .awaitList {
            excluded_scanlatorsQueries.backupGetExcludedScanlatorsByMangaIds(mangaIds) { mangaId, scanlator ->
                mangaId to scanlator
            }
        }
        .groupBy({ it.first }, { it.second })

    /** Each entry's scanlator priority, the lowest first and the scanlator breaking a tie (R165 item 3). */
    suspend fun scanlatorPriorities(mangaIds: List<Long>): Map<Long, List<ScanlatorPriority>> = handler
        .awaitList { scanlator_priorityQueries.backupGetByMangaIds(mangaIds, ::ScanlatorPriority) }
        .groupBy { it.mangaId }
        .mapValues { (_, ranks) -> ranks.sortedWith(compareBy({ it.priority }, { it.scanlator })) }

    /** The page notes on each live chapter of the entries, by chapter id, by page (R165 item 3). */
    suspend fun pageNotes(mangaIds: List<Long>): Map<Long, List<BookmarkNote>> = handler
        .awaitList {
            bookmark_notesQueries.backupGetByMangaIds(mangaIds) { chapterId, pageIndex, note, dateAdded ->
                BookmarkNote(id = 0L, chapterId = chapterId, pageIndex = pageIndex, note = note, dateAdded = dateAdded)
            }
        }
        .groupBy { it.chapterId }
}

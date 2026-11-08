package com.shinsaikou.sync

import app.cash.sqldelight.async.coroutines.awaitAsOne
import com.shinsaikou.di.AppGraph
import com.shinsaikou.tracking.TrackerIds
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.model.Track
import java.util.Date

/**
 * The large library unit G's device check syncs (plan unit G: "a sync of a large fixture library"),
 * generated on the device through the app's own repositories, on `BenchFixtureSeeder`'s and
 * `LibraryCoverSeeder`'s pattern. Nothing in it belongs to anyone.
 *
 * [ENTRIES] favourites of [CHAPTERS] chapters each, under a source id no provider serves, so no row
 * ever starts a fetch. The first [READ] chapters of each entry are read, every [BOOKMARK_EVERY]th
 * chapter is bookmarked, the last read chapter has a history row, and each entry is in one of
 * [CATEGORIES] categories. Every insert goes through the repositories with the triggers on, so the
 * change log holds what a real library's does, and the first sync exports it all.
 *
 * Idempotent: `insertNetworkManga` returns an existing row for a source and url it holds, and an
 * entry that already has chapters gets none added, so a second call inserts nothing.
 *
 * Unit I's `sync_3000_no_oom` step seeds the same library at 3,000 entries (`SyncPairTest`'s
 * `sync-3000`), through the `entries` argument of [seed].
 *
 * Phase 5 unit SC0 (`ScaleRowsTest`) seeds it at 40,000 entries of 50 chapters, so [seed] also
 * takes the chapter and category counts, the batch size, a track on every [TRACK_EVERY]th entry,
 * and [Latch.RAISED]. The read share ([READ] of [CHAPTERS]), the bookmark cadence and the one
 * history row per entry scale with the chapter count as they are at the defaults. Every default is
 * what the earlier callers seed, so their libraries and change logs are unchanged.
 */
object SyncLibrarySeeder {

    const val ENTRIES = 2_000
    const val CHAPTERS = 20
    const val READ = 10
    const val BOOKMARK_EVERY = 7
    const val CATEGORIES = 8

    /** No provider serves this id, so every row under it belongs to this seeder alone. */
    const val SOURCE_ID = 9_000_000_000_000_000_004L

    const val TITLE_PREFIX = "Saikou sync check entry "
    const val CATEGORY_PREFIX = "Sync check "

    private const val URL_PREFIX = "/saikou-sync-check/entry/"

    /** Fixed, so two devices seeded apart hold the same rows. */
    private const val DATE = 1_758_585_600_000L

    private const val READ_DURATION = 60_000L

    private const val BATCH = 100

    /** Tracks, when asked for, sit on every this many entries: entries 0, 50, 100 and so on. */
    const val TRACK_EVERY = 50

    /** The fixture remote id of entry 0's track; entry n's is this plus n. Nothing ever looks it up. */
    private const val TRACK_REMOTE_BASE = 9_000_000_000L

    /** A track's address, on a reserved name that can never resolve (RFC 2606). */
    private const val TRACK_URL_PREFIX = "https://tracker.invalid/manga/"

    /**
     * How the chapters, history, category memberships and tracks are written.
     *
     * [OPEN] goes through the repositories with the triggers on: every chapter of a favourite logs
     * one `sync_change_log` row (`chapters.sq`'s `insert_chapter_change`), which is what a library
     * read and edited on the device holds. [RAISED] writes them as a restore or a sync apply does,
     * under the `is_syncing` latch: each batch raises the entries' latch (`mangas.syncSetIsSyncing`),
     * inserts the chapters with their own latch up (`chapters.syncInsert`, the apply's insert) and
     * the history, memberships and tracks through their plain inserts, then lowers both latches
     * (`resetIsSyncing`), all in one transaction. No trigger logs a row and no clock moves; the
     * entries' own insert still logs as it does on [OPEN]. A latch held by an apply elsewhere would be
     * lowered with it, which on a seeded fixture library there never is.
     */
    enum class Latch { OPEN, RAISED }

    /** What a seed left in place: entry ids, and how many chapters this call inserted. */
    data class Library(val mangaIds: List<Long>, val chaptersInserted: Int)

    fun title(index: Int): String = TITLE_PREFIX + index.toString().padStart(4, '0')

    /** Entry [index]'s url under [SOURCE_ID]. */
    fun url(index: Int): String = "$URL_PREFIX$index"

    /**
     * Seeds [entries] favourites of [chapters] chapters in [categories] categories, [batch] entries
     * per transaction, with a track on every [TRACK_EVERY]th entry when [tracks] is set, written as
     * [latch] says.
     */
    suspend fun seed(
        graph: AppGraph,
        entries: Int = ENTRIES,
        chapters: Int = CHAPTERS,
        categories: Int = CATEGORIES,
        batch: Int = BATCH,
        tracks: Boolean = false,
        latch: Latch = Latch.OPEN,
    ): Library {
        val categoryRows = categories(graph, categories)
        val rows = graph.mangaRepository.insertNetworkManga(
            (0 until entries).map { index ->
                Manga.create().copy(
                    source = SOURCE_ID,
                    url = url(index),
                    title = title(index),
                    favorite = true,
                    initialized = true,
                    dateAdded = DATE,
                )
            },
        )
        check(rows.size == entries) { "the seed stored ${rows.size} of $entries entries" }

        var inserted = 0
        rows.withIndex().chunked(batch).forEach { group ->
            val missing = group.filter { (_, manga) ->
                graph.chapterRepository.getChapterByMangaId(manga.id).isEmpty()
            }
            if (missing.isEmpty()) return@forEach
            inserted += when (latch) {
                Latch.OPEN -> seedOpen(graph, missing, chapters, categoryRows, tracks)
                Latch.RAISED -> seedLatched(graph, missing, chapters, categoryRows, tracks)
            }
        }
        return Library(mangaIds = rows.map { it.id }, chaptersInserted = inserted)
    }

    /** One batch through the repositories, the triggers on. Returns the chapters inserted. */
    private suspend fun seedOpen(
        graph: AppGraph,
        missing: List<IndexedValue<Manga>>,
        chapters: Int,
        categories: List<Category>,
        tracks: Boolean,
    ): Int {
        val stored = graph.chapterRepository.addAll(
            missing.flatMap { (_, manga) -> (0 until chapters).map { number -> chapter(manga, number, chapters) } },
        )
        stored.filter { it.read }.groupBy { it.mangaId }.values.forEach { read ->
            val last = read.maxBy { it.chapterNumber }
            graph.historyRepository.upsertHistory(
                HistoryUpdate(chapterId = last.id, readAt = readAt(last), sessionReadDuration = READ_DURATION),
            )
        }
        missing.forEach { (index, manga) ->
            graph.mangaRepository.setMangaCategories(manga.id, listOf(categories[index % categories.size].id))
        }
        if (tracks) {
            missing.filter { (index, _) -> index % TRACK_EVERY == 0 }
                .map { (index, manga) -> track(index, manga) }
                .takeIf { it.isNotEmpty() }
                ?.let { graph.trackRepository.insertAll(it) }
        }
        return stored.size
    }

    /** One batch under the latch, in one transaction, as [Latch.RAISED] describes. */
    private suspend fun seedLatched(
        graph: AppGraph,
        missing: List<IndexedValue<Manga>>,
        chapters: Int,
        categories: List<Category>,
        tracks: Boolean,
    ): Int {
        var inserted = 0
        graph.database.transaction {
            missing.forEach { (_, manga) -> graph.database.mangasQueries.syncSetIsSyncing(1L, manga.id) }
            missing.forEach { (index, manga) ->
                var lastReadId = -1L
                var lastRead: Chapter? = null
                (0 until chapters).forEach { number ->
                    val chapter = chapter(manga, number, chapters)
                    graph.database.chaptersQueries.syncInsert(
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
                        deletedAt = null,
                        restoredAt = null,
                        bookmarkNotesModifiedAt = null,
                        readModifiedAt = 0,
                        bookmarkModifiedAt = 0,
                    )
                    inserted++
                    if (chapter.read) {
                        lastReadId = graph.database.chaptersQueries.selectLastInsertedRowId().awaitAsOne()
                        lastRead = chapter
                    }
                }
                lastRead?.let { graph.database.historyQueries.upsert(lastReadId, readAt(it), READ_DURATION) }
                graph.database.mangas_categoriesQueries.insert(manga.id, categories[index % categories.size].id)
                if (tracks && index % TRACK_EVERY == 0) {
                    val track = track(index, manga)
                    graph.database.manga_syncQueries.insert(
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
            graph.database.chaptersQueries.resetIsSyncing()
            graph.database.mangasQueries.resetIsSyncing()
        }
        return inserted
    }

    /** Chapter [number] of [chapters] of [manga]: the first [READ] in [CHAPTERS] read, every [BOOKMARK_EVERY]th bookmarked. */
    private fun chapter(manga: Manga, number: Int, chapters: Int): Chapter = Chapter.create().copy(
        mangaId = manga.id,
        read = number < chapters * READ / CHAPTERS,
        bookmark = number % BOOKMARK_EVERY == 0,
        sourceOrder = (chapters - 1 - number).toLong(),
        url = "$URL_PREFIX${manga.url.substringAfterLast('/')}/c$number",
        name = "Chapter ${number + 1}",
        chapterNumber = (number + 1).toDouble(),
        dateUpload = DATE + number * 86_400_000L,
        dateFetch = DATE,
    )

    private fun readAt(chapter: Chapter): Date = Date(DATE + chapter.chapterNumber.toLong() * 60_000L)

    /** A MyAnimeList track with a fixture remote id, as a bound entry's row holds one. */
    private fun track(index: Int, manga: Manga): Track = Track(
        id = 0L,
        mangaId = manga.id,
        trackerId = TrackerIds.MYANIMELIST,
        remoteId = TRACK_REMOTE_BASE + index,
        libraryId = null,
        title = manga.title,
        lastChapterRead = 0.0,
        totalChapters = 0L,
        status = 1L,
        score = 0.0,
        remoteUrl = "$TRACK_URL_PREFIX${TRACK_REMOTE_BASE + index}",
        startDate = 0L,
        finishDate = 0L,
        private = false,
    )

    /**
     * Marks the next [count] unread chapters read, oldest entries first, so the change log gains that
     * many rows after the first export: the segments a pull then resumes through.
     */
    suspend fun markMoreRead(graph: AppGraph, count: Int): Int {
        val updates = mutableListOf<ChapterUpdate>()
        for (manga in graph.mangaRepository.getFavorites().filter { it.source == SOURCE_ID }.sortedBy { it.url }) {
            if (updates.size >= count) break
            graph.chapterRepository.getChapterByMangaId(manga.id)
                .filter { !it.read }
                .sortedBy { it.chapterNumber }
                .take(count - updates.size)
                .forEach { updates += ChapterUpdate(id = it.id, read = true) }
        }
        updates.chunked(1_000).forEach { graph.chapterRepository.updateAll(it) }
        return updates.size
    }

    private suspend fun categories(graph: AppGraph, count: Int): List<Category> {
        val names = (1..count).map { "$CATEGORY_PREFIX$it" }
        val existing = graph.categoryRepository.getAll().map { it.name }.toSet()
        names.withIndex().filter { it.value !in existing }.forEach { (index, name) ->
            graph.categoryRepository.insert(Category(id = 0, name = name, order = index.toLong(), flags = 0))
        }
        val byName = graph.categoryRepository.getAll().associateBy { it.name }
        return names.map { checkNotNull(byName[it]) { "category $it was not stored" } }
    }
}

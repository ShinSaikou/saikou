package com.shinsaikou.sync

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.shinsaikou.di.AppGraph
import com.shinsaikou.sync.format.SyncFormat
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.manga.model.Manga

/**
 * The library the two-device suite syncs (phase 3 unit I, Q11), generated on the device through the
 * app's own repositories with the triggers on, on [SyncLibrarySeeder]'s pattern. Nothing in it
 * belongs to anyone, and no row is ever fetched: no provider serves [SOURCE_ID].
 *
 * [ENTRIES] favourites of [CHAPTERS] chapters each, the first [READ] of each read, in [CATEGORIES]
 * by turns. The scenarios edit named rows of it: [RACE_CHAPTER], [RENAME_CHAPTER], [DELETE_CHAPTER]
 * of [SCENARIO_ENTRY], and the categories [OLD_NAME] and [GONE].
 *
 * [digest] is [SyncDigest]'s hash over every row a full export of the device's library carries, so
 * two devices that converged print the same hash.
 *
 * `killed_mid_apply` grows it on one device: [addBulkChapters] gives every entry [BULK_CHAPTERS]
 * more, so its full export is several parts of at most [SyncFormat.MAX_RAW_ROWS] lines, and
 * [markBulkRead] marks [BULK_READ] of them read, one segment of several thousand rows.
 */
object SyncPairLibrary {

    const val ENTRIES = 30
    const val CHAPTERS = 12
    const val READ = 3

    /** No provider serves this id, so every row under it belongs to the suite alone. */
    const val SOURCE_ID = 9_000_000_000_000_000_005L

    const val READING = "Pair reading"
    const val OLD_NAME = "Pair old name"
    const val NEW_NAME = "Pair new name"
    const val GONE = "Pair gone"
    val CATEGORIES = listOf(READING, OLD_NAME, GONE)

    /** The entry every scenario edits, and its chapters by url. */
    const val SCENARIO_ENTRY = 0
    val RACE_CHAPTER = chapterUrl(SCENARIO_ENTRY, 5)
    val RENAME_CHAPTER = chapterUrl(SCENARIO_ENTRY, 6)
    val DELETE_CHAPTER = chapterUrl(SCENARIO_ENTRY, 7)
    const val RENAMED = "Chapter 7: renamed by its source"

    /**
     * Chapters [addBulkChapters] adds to each entry: [ENTRIES] entries of 500 chapters are about
     * 15,000 export lines, four parts of at most [SyncFormat.MAX_RAW_ROWS].
     */
    const val BULK_CHAPTERS = 488

    /**
     * Chapters [markBulkRead] marks read: one change log row each, so one segment, which holds at
     * most [SyncFormat.MAX_RAW_ROWS] log rows, of several thousand rows.
     */
    const val BULK_READ = 4_500

    /** Fixed, so the rows the seed writes are the same on every run. */
    private const val DATE = 1_758_585_600_000L

    private const val URL_PREFIX = "/saikou-sync-pair/entry/"

    fun entryUrl(index: Int): String = "$URL_PREFIX$index"

    fun chapterUrl(entry: Int, number: Int): String = "${entryUrl(entry)}/c$number"

    /** Seeds the library. Refuses a device that already holds any of it: a scenario starts cleared. */
    suspend fun seed(graph: AppGraph) {
        check(graph.mangaRepository.getFavorites().none { it.source == SOURCE_ID }) { "the pair library is already here" }
        CATEGORIES.forEachIndexed { index, name ->
            graph.categoryRepository.insert(Category(id = 0, name = name, order = index.toLong(), flags = 0))
        }
        val byName = graph.categoryRepository.getAll().associateBy { it.name }
        val rows = graph.mangaRepository.insertNetworkManga(
            (0 until ENTRIES).map { index ->
                Manga.create().copy(
                    source = SOURCE_ID,
                    url = entryUrl(index),
                    title = "Saikou sync pair entry $index",
                    favorite = true,
                    initialized = true,
                    dateAdded = DATE,
                )
            },
        )
        check(rows.size == ENTRIES) { "the seed stored ${rows.size} of $ENTRIES entries" }
        val entryIndex = rows.associate { it.id to it.url.substringAfterLast('/').toInt() }
        graph.chapterRepository.addAll(
            rows.flatMap { manga ->
                val entry = entryIndex.getValue(manga.id)
                (0 until CHAPTERS).map { number ->
                    Chapter.create().copy(
                        mangaId = manga.id,
                        url = chapterUrl(entry, number),
                        name = "Chapter ${number + 1}",
                        read = number < READ,
                        sourceOrder = (CHAPTERS - 1 - number).toLong(),
                        chapterNumber = (number + 1).toDouble(),
                        dateUpload = DATE + number * 86_400_000L,
                        dateFetch = DATE,
                    )
                }
            },
        )
        rows.forEach { manga ->
            val category = CATEGORIES[entryIndex.getValue(manga.id) % CATEGORIES.size]
            graph.mangaRepository.setMangaCategories(manga.id, listOf(checkNotNull(byName[category]) { "no $category" }.id))
        }
    }

    /**
     * Adds [BULK_CHAPTERS] unread chapters to every entry, as a library update that found them would,
     * after the seeded ones. Returns how many it added; refuses a library that has them already.
     */
    suspend fun addBulkChapters(graph: AppGraph): Int {
        val entries = graph.mangaRepository.getFavorites().filter { it.source == SOURCE_ID }
        check(entries.size == ENTRIES) { "the pair library holds ${entries.size} of $ENTRIES entries" }
        var added = 0
        for (manga in entries) {
            val entry = manga.url.substringAfterLast('/').toInt()
            check(graph.chapterRepository.getChapterByMangaId(manga.id).size == CHAPTERS) { "${manga.url} has its bulk chapters already" }
            added += graph.chapterRepository.addAll(
                (CHAPTERS until CHAPTERS + BULK_CHAPTERS).map { number ->
                    Chapter.create().copy(
                        mangaId = manga.id,
                        url = chapterUrl(entry, number),
                        name = "Chapter ${number + 1}",
                        sourceOrder = (CHAPTERS + BULK_CHAPTERS - 1 - number).toLong(),
                        chapterNumber = (number + 1).toDouble(),
                        dateUpload = DATE + number * 86_400_000L,
                        dateFetch = DATE,
                    )
                },
            ).size
        }
        return added
    }

    /** Marks [BULK_READ] unread chapters read, entries in url order and each entry's in number order. */
    suspend fun markBulkRead(graph: AppGraph): Int {
        val updates = mutableListOf<ChapterUpdate>()
        for (manga in graph.mangaRepository.getFavorites().filter { it.source == SOURCE_ID }.sortedBy { it.url }) {
            if (updates.size >= BULK_READ) break
            graph.chapterRepository.getChapterByMangaId(manga.id)
                .filter { !it.read }
                .sortedBy { it.chapterNumber }
                .take(BULK_READ - updates.size)
                .forEach { updates += ChapterUpdate(id = it.id, read = true) }
        }
        check(updates.size == BULK_READ) { "only ${updates.size} unread chapters to mark, where the segment needs $BULK_READ" }
        updates.chunked(1_000).forEach { graph.chapterRepository.updateAll(it) }
        return updates.size
    }

    /** The scenario entry's local id. */
    suspend fun scenarioEntryId(graph: AppGraph): Long =
        checkNotNull(graph.mangaRepository.getFavorites().firstOrNull { it.source == SOURCE_ID && it.url == entryUrl(SCENARIO_ENTRY) }) {
            "the pair library is not on this device"
        }.id

    /** The live chapter of the scenario entry at [url], or null when it is gone or deleted. */
    suspend fun liveChapter(graph: AppGraph, url: String): Chapter? =
        graph.chapterRepository.getChapterByUrlAndMangaId(url, scenarioEntryId(graph))?.takeIf { row ->
            graph.chapterRepository.getChapterByMangaId(row.mangaId).any { it.id == row.id }
        }

    /**
     * What [digest] read: [SyncDigest]'s hash over [lines] wire rows, the rows still latched by an
     * apply, and this library's entries, chapters and categories.
     */
    data class Digest(val sha256: String, val lines: Int, val latched: Int, val entries: Int, val chapters: Int, val categories: Int)

    /**
     * [SyncDigest] over the app's own store: every row a full export of this device's library would
     * carry, by natural key, as the push maps it (fix round 1: the digest had read a fixed list of
     * columns). Beside it, [Digest.latched] counts the rows whose `is_syncing` is still raised, and
     * the counts are this library's entries of [SOURCE_ID], live or removed, their chapters,
     * soft-deleted ones included, and every category but the system one.
     */
    suspend fun digest(graph: AppGraph): Digest {
        val db = graph.database
        var latched = 0
        val mangas = db.mangasQueries.getAllManga().awaitAsList().filter { it.source == SOURCE_ID }
        var chapters = 0
        for (manga in mangas) {
            if (manga.is_syncing != 0L) latched++
            for (chapter in db.chaptersQueries.syncGetByMangaId(manga._id).awaitAsList()) {
                if (chapter.is_syncing != 0L) latched++
                chapters++
            }
        }
        val categories = db.categoriesQueries.syncGetAll().awaitAsList()
        for (category in categories) {
            if (db.categoriesQueries.getCategory(category._id).awaitAsOneOrNull()?.is_syncing == 1L) latched++
        }
        val digest = SyncDigest.of(graph.syncStore)
        return Digest(
            sha256 = digest.sha256,
            lines = digest.rows,
            latched = latched,
            entries = mangas.size,
            chapters = chapters,
            categories = categories.size,
        )
    }
}

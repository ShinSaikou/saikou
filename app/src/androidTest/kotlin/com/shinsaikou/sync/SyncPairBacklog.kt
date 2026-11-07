package com.shinsaikou.sync

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import com.shinsaikou.data.AndroidDatabaseHandler
import com.shinsaikou.data.DatabaseTransactionRunner
import com.shinsaikou.di.AppGraph
import com.shinsaikou.domain.chapter.ChapterReplacements
import com.shinsaikou.domain.chapter.interactor.GetExcludedScanlators
import com.shinsaikou.domain.chapter.interactor.SyncChaptersWithSource
import com.shinsaikou.domain.merged.interactor.GetMergedGroups
import com.shinsaikou.domain.merged.interactor.ReconcileMergedRead
import com.shinsaikou.libraryupdate.FlatLibraryUpdate
import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceManager
import com.shinsaikou.source.model.ChapterInfo
import com.shinsaikou.source.model.ImageResult
import com.shinsaikou.source.model.MangaInfo
import com.shinsaikou.source.model.PageInfo
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import kotlin.time.TimeSource

/**
 * The `new_entry_backlog` scenario's entry (phase 4 unit NB, challenge c5 change 9): one entry of
 * [CHAPTERS] chapters at a fixture source served from this test APK, which lists them on device A
 * and one more on device B. Nothing in it belongs to anyone, and no request leaves the device.
 *
 * [add] is A's library add as the manga screen makes it: the entry stored, its chapter list fetched
 * while it is not a favourite through the screen's own [AppGraph.refreshMangaFromSource], then
 * `UpdateManga.awaitUpdateFavorite`. [update] is B's manual library update: [FlatLibraryUpdate]
 * built as the graph builds it, with the graph's [AppGraph.autoDownload] and its download queue,
 * except that its source registry adds this fixture source to the graph's own, which no product
 * path can do (R39). "Download new chapters" is turned on first.
 */
object SyncPairBacklog {

    const val CHAPTERS = 100

    /** No provider serves this id: [update] adds a source for it to its own registry only. */
    const val SOURCE_ID = 9_000_000_000_000_000_008L

    const val ENTRY_URL = "/saikou-sync-pair/backlog"

    /** Fixed, so the rows are the same on every run. */
    private const val DATE = 1_758_585_600_000L
    private const val DAY = 86_400_000L

    fun chapterUrl(number: Int): String = "$ENTRY_URL/c$number"

    /** [count] chapters, newest first, as a source lists them. */
    private fun listing(count: Int): List<ChapterInfo> = (count downTo 1).map {
        ChapterInfo(url = chapterUrl(it), name = "Chapter $it", chapterNumber = it.toFloat(), dateUpload = DATE + it * DAY)
    }

    /** The fixture source, listing [count] chapters for the entry. */
    private class ListingSource(private val count: Int) : Source {
        override val id = SOURCE_ID
        override val name = "Saikou sync pair fixture"
        override val lang = "en"
        override suspend fun getMangaDetails(manga: MangaInfo) = manga.copy(initialized = true)
        override suspend fun getChapterList(manga: MangaInfo): List<ChapterInfo> {
            check(manga.url == ENTRY_URL) { "the fixture source lists no entry at ${manga.url}" }
            return listing(count)
        }
        override suspend fun getPageList(chapter: ChapterInfo): List<PageInfo> = error("the fixture source serves no pages")
        override suspend fun getImage(page: PageInfo): ImageResult = error("the fixture source serves no pages")
    }

    /** The graph's registry with [source] added; every other id is the graph's answer. */
    private class WithSource(private val real: SourceManager, private val source: Source) : SourceManager by real {
        override fun get(id: Long): Source? = if (id == source.id) source else real.get(id)
        override fun getOrStub(id: Long): Source = if (id == source.id) source else real.getOrStub(id)
    }

    /**
     * A's add. Returns the entry's chapters and the change-log rows the add wrote for them, counted
     * and by distinct chapter, from the log's tail. Its keys are none of those `SyncPairTest`'s digest
     * adds after it, where `chapters` is the pair library's count.
     */
    suspend fun add(graph: AppGraph): Map<String, Any?> {
        val manga = graph.mangaRepository.insertNetworkManga(
            listOf(Manga.create().copy(source = SOURCE_ID, url = ENTRY_URL, title = "Saikou sync pair backlog", initialized = true)),
        ).single()
        check(!manga.favorite) { "the backlog entry is already in the library" }
        val fetched = graph.refreshMangaFromSource(ListingSource(CHAPTERS), manga, fetchChapters = true, manualFetch = true)
        check(fetched.newChapters.size == CHAPTERS) { "the fetch stored ${fetched.newChapters.size} of $CHAPTERS chapters" }
        val logBefore = graph.database.sync_change_logQueries.getLatestSeq().awaitAsOne()
        check(graph.updateManga.awaitUpdateFavorite(manga.id, favorite = true)) { "the add wrote no row" }
        val chapterIds = graph.chapterRepository.getChapterByMangaId(manga.id).map { it.id }.toSet()
        val logged = graph.database.sync_change_logQueries.getAfter(logBefore, Long.MAX_VALUE).awaitAsList()
            .filter { it.entity_type == "chapter" && it.entity_id in chapterIds }
        return linkedMapOf(
            "entryChapters" to chapterIds.size,
            "logged" to logged.size,
            "loggedChapters" to logged.map { it.entity_id }.toSet().size,
        )
    }

    /**
     * B's manual library update against the source listing one chapter more. Returns the entry's
     * chapters, Updates rows and download queue rows before and after it, the chapter it queued,
     * and the run's failures (the pair library's entries, whose source is not installed).
     */
    suspend fun update(graph: AppGraph): Map<String, Any?> {
        val manga = checkNotNull(graph.mangaRepository.getFavorites().singleOrNull { it.source == SOURCE_ID && it.url == ENTRY_URL }) {
            "the backlog entry is not in this library"
        }
        val chaptersBefore = graph.chapterRepository.getChapterByMangaId(manga.id).size
        val updatesBefore = updates(graph, manga.id)
        val queuedBefore = graph.downloadQueueRepository.getByMangaId(manga.id).size
        graph.downloadPreferences.downloadNewChapters.set(true)

        val getChaptersByMangaId = GetChaptersByMangaId(graph.chapterRepository)
        val outcome = FlatLibraryUpdate(
            getLibraryManga = GetLibraryManga(graph.mangaRepository),
            getManga = GetManga(graph.mangaRepository),
            getChaptersByMangaId = getChaptersByMangaId,
            sourceManager = WithSource(graph.sourceManager, ListingSource(CHAPTERS + 1)),
            syncChaptersWithSource = SyncChaptersWithSource(
                chapterRepository = graph.chapterRepository,
                shouldUpdateDbChapter = ShouldUpdateDbChapter(),
                updateManga = graph.updateManga,
                getChaptersByMangaId = getChaptersByMangaId,
                // The graph's handler is this one over the same database: it holds nothing else.
                transactionRunner = DatabaseTransactionRunner(AndroidDatabaseHandler(graph.database)),
                downloadManifestRepository = graph.downloadManifestRepository,
                libraryPreferences = graph.libraryPreferences,
                getExcludedScanlators = GetExcludedScanlators(graph.mangaRepository),
                historyRepository = graph.historyRepository,
                chapterReplacements = ChapterReplacements(
                    graph.chapterRepository,
                    DatabaseTransactionRunner(AndroidDatabaseHandler(graph.database)),
                ),
            ),
            updateManga = graph.updateManga,
            fetchInterval = FetchInterval(getChaptersByMangaId),
            libraryPreferences = graph.libraryPreferences,
            autoDownload = graph.autoDownload,
            getMergedGroups = GetMergedGroups(graph.mergedGroupRepository, graph.libraryPreferences),
            reconcileMergedRead = ReconcileMergedRead(graph.mergedGroupRepository, graph.libraryPreferences),
            dispatchers = graph.dispatchers,
            timeSource = TimeSource.Monotonic,
        ).run(categoryId = null, automatic = false) {}

        // A queued chapter stays in the queue: its download fails, as no installed source serves it,
        // and a failed row is kept in its error state.
        val queued = graph.downloadQueueRepository.getByMangaId(manga.id)
        val urls = graph.chapterRepository.getChapterByMangaId(manga.id).associate { it.id to it.url }
        return linkedMapOf(
            "chaptersBefore" to chaptersBefore,
            "updatesBefore" to updatesBefore,
            "queuedBefore" to queuedBefore,
            "inserted" to outcome.updated[manga.id]?.size,
            "queued" to queued.size,
            "queuedChapters" to queued.joinToString(",") { urls[it.chapterId] ?: "gone" },
            "chaptersAfter" to urls.size,
            "updatesAfter" to updates(graph, manga.id),
            "failures" to outcome.failures.size,
            "failuresOutsidePair" to outcome.failures.count { it.manga.source != SyncPairLibrary.SOURCE_ID },
        )
    }

    /**
     * The entry's rows the Updates tab lists (`updatesView`, which keeps chapters fetched after the
     * add), read by the tab's own query with no filter set.
     */
    private suspend fun updates(graph: AppGraph, mangaId: Long): Int =
        graph.database.updatesViewQueries.getRecentUpdatesWithFilters(
            after = Long.MIN_VALUE,
            unread = null,
            started = null,
            bookmarked = null,
            hideExcludedScanlators = 0,
            includeAll = 1,
            includedCategories = emptyList(),
            includeUncategorized = 0,
            excludedCategories = emptyList(),
            excludeUncategorized = 0,
            limit = Long.MAX_VALUE,
        ).awaitAsList()
            .count { it.mangaId == mangaId }
}

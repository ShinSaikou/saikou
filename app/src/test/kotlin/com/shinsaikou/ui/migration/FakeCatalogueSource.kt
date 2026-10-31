package com.shinsaikou.ui.migration

import com.shinsaikou.source.CatalogueSource
import com.shinsaikou.source.model.ChapterInfo
import com.shinsaikou.source.model.Filter
import com.shinsaikou.source.model.FilterList
import com.shinsaikou.source.model.ImageResult
import com.shinsaikou.source.model.MangaInfo
import com.shinsaikou.source.model.MangaPage
import com.shinsaikou.source.model.MangaUpdate
import com.shinsaikou.source.model.PageInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * A source for the batch migration's tests (phase 4 unit MB): it answers from tables, records every
 * call with its arguments, and can hold its replies on a [CompletableDeferred] so a test counts the
 * calls in flight at once. Nothing here reaches a network.
 *
 * [getFilterList] answers a non-empty tree, so a search that sent it can be told from one that sent
 * the contract's empty list.
 */
class FakeCatalogueSource(
    override val id: Long,
    override val name: String = "Source $id",
    override val lang: String = "en",
) : CatalogueSource {

    data class SearchCall(val page: Int, val query: String, val filters: FilterList)

    /** Results per query; a query not in the table finds nothing. */
    val searchResults = mutableMapOf<String, List<MangaInfo>>()

    /** A query in this table throws its exception in place of a reply. */
    val searchFailures = mutableMapOf<String, Throwable>()

    /** Every source answer waits for this while it is set. */
    var hold: CompletableDeferred<Unit>? = null

    /** Makes the wait on [hold] ignore cancellation, as an extension blocked inside its HTTP call does. */
    var holdIgnoresCancellation = false

    val searchCalls = mutableListOf<SearchCall>()
    val detailCalls = mutableListOf<String>()
    val chapterCalls = mutableListOf<String>()
    var inFlight = 0
        private set
    var maxInFlight = 0
        private set

    override val supportsLatest: Boolean = false

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangaPage {
        searchCalls += SearchCall(page, query, filters)
        return answer {
            searchFailures[query]?.let { throw it }
            MangaPage(searchResults[query].orEmpty(), hasNextPage = false)
        }
    }

    override suspend fun getMangaDetails(manga: MangaInfo): MangaInfo {
        detailCalls += manga.url
        return answer { manga.copy(initialized = true) }
    }

    override suspend fun getChapterList(manga: MangaInfo): List<ChapterInfo> {
        chapterCalls += manga.url
        return answer { emptyList() }
    }

    override suspend fun getMangaUpdate(
        manga: MangaInfo,
        chapters: List<ChapterInfo>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): MangaUpdate =
        error("the migration never asks for a combined update")

    override suspend fun getPopularManga(page: Int): MangaPage = error("not used by the migration")

    override suspend fun getLatestUpdates(page: Int): MangaPage = error("not used by the migration")

    override fun getFilterList(): FilterList = FilterList(Filter.Header("a source's own filters"))

    override suspend fun getPageList(chapter: ChapterInfo): List<PageInfo> = error("not used by the migration")

    override suspend fun getImage(page: PageInfo): ImageResult = error("not used by the migration")

    private suspend fun <T> answer(block: () -> T): T {
        inFlight++
        maxInFlight = maxOf(maxInFlight, inFlight)
        try {
            hold?.let { if (holdIgnoresCancellation) withContext(NonCancellable) { it.await() } else it.await() }
            return block()
        } finally {
            inFlight--
        }
    }

    companion object {
        fun entry(title: String, url: String = "/manga/${title.lowercase().replace(' ', '-')}", cover: String? = null) =
            MangaInfo(url = url, title = title, thumbnailUrl = cover)
    }
}

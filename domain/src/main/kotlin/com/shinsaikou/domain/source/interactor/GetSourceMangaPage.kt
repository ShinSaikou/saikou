package com.shinsaikou.domain.source.interactor

import com.shinsaikou.domain.source.toDomainManga
import com.shinsaikou.source.CatalogueSource
import com.shinsaikou.source.SourceManager
import com.shinsaikou.source.SourceNotInstalledException
import com.shinsaikou.source.model.FilterList
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga

sealed interface BrowseMode {
    data object Popular : BrowseMode
    data object Latest : BrowseMode
    data class Search(val query: String, val filters: FilterList) : BrowseMode
}

data class BrowsePage(val mangas: List<Manga>, val hasNextPage: Boolean, val page: Int)

/**
 * One page of a browse listing. The browse view model keeps [BrowsePage.page], appends
 * [BrowsePage.mangas] and stops when [BrowsePage.hasNextPage] is false; there is no paging library.
 */
@Inject
class GetSourceMangaPage(
    private val sourceManager: SourceManager,
    private val networkToLocalManga: NetworkToLocalManga,
) {

    /**
     * Fetches page [page] (1-based) from the catalogue source with [sourceId] and passes the result
     * through [NetworkToLocalManga], so the returned rows carry ids and library membership.
     *
     * @throws SourceNotInstalledException when no provider serves [sourceId]
     */
    suspend fun await(sourceId: Long, mode: BrowseMode, page: Int): BrowsePage = withContext(Dispatchers.IO) {
        val source = sourceManager.get(sourceId) as? CatalogueSource ?: throw SourceNotInstalledException(sourceId)
        val mangaPage = when (mode) {
            BrowseMode.Popular -> source.getPopularManga(page)
            BrowseMode.Latest -> source.getLatestUpdates(page)
            is BrowseMode.Search -> source.getSearchManga(page, mode.query, mode.filters)
        }
        val mangas = networkToLocalManga(mangaPage.mangas.map { it.toDomainManga(sourceId) })
        BrowsePage(mangas = mangas, hasNextPage = mangaPage.hasNextPage, page = page)
    }
}

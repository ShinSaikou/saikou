package com.shinsaikou.source

import com.shinsaikou.source.model.FilterList
import com.shinsaikou.source.model.MangaPage

/**
 * A source that can be browsed: popular, latest and search listings plus a filter tree.
 * Every extension source and the Local source are CatalogueSources.
 */
interface CatalogueSource : Source {
    /** Whether [getLatestUpdates] is implemented. The browse UI hides the Latest tab when false. */
    val supportsLatest: Boolean

    /** Page [page] (1-based) of the popular listing. */
    suspend fun getPopularManga(page: Int): MangaPage

    /**
     * Page [page] (1-based) of a search. [query] may be blank when only [filters] are set.
     * [filters] must be structurally the list returned by [getFilterList] with states edited,
     * or an empty [FilterList] meaning "source defaults".
     */
    suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangaPage

    /** Page [page] (1-based) of the latest-updates listing. Only valid when [supportsLatest] is true. */
    suspend fun getLatestUpdates(page: Int): MangaPage

    /**
     * A fresh filter tree with default states. Not suspend, may be called on the main thread;
     * extension adapters guarantee this by caching. Callers reset filters by calling it again.
     */
    fun getFilterList(): FilterList
}

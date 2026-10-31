// Vendored from upstream v0.20.4: app/src/main/java/tachiyomi/feature/migration/list/search/SmartSourceSearchEngine.kt (modified: yes)
package tachiyomi.feature.migration.list.search

import com.shinsaikou.domain.source.toDomainManga
import com.shinsaikou.source.CatalogueSource
import com.shinsaikou.source.model.FilterList
import com.shinsaikou.source.model.MangaInfo
import kotlinx.coroutines.CoroutineDispatcher
import tachiyomi.domain.manga.model.Manga

/**
 * Saikou: the source contract's [CatalogueSource] and [MangaInfo] for upstream's `Source` and `SManga`,
 * and the search sends [FilterList.EMPTY], the contract's "source defaults", where upstream sends
 * `source.getFilterList()`: in Saikou that call replaces the adapter's shared filter cache, which an
 * open listing of the same source reads, and Global Search sends the empty list too. A search
 * writes nothing.
 */
class SmartSourceSearchEngine(
    extraSearchParams: String?,
    dispatcher: CoroutineDispatcher,
) : BaseSmartSearchEngine<MangaInfo>(extraSearchParams, dispatcher = dispatcher) {

    override fun getTitle(result: MangaInfo) = result.title

    suspend fun regularSearch(source: CatalogueSource, title: String): Manga? {
        return regularSearch(makeSearchAction(source), title).let {
            it?.toDomainManga(source.id)
        }
    }

    suspend fun deepSearch(source: CatalogueSource, title: String): Manga? {
        return deepSearch(makeSearchAction(source), title).let {
            it?.toDomainManga(source.id)
        }
    }

    private fun makeSearchAction(source: CatalogueSource): SearchAction<MangaInfo> = { query ->
        source.getSearchManga(1, query, FilterList.EMPTY).mangas
    }
}

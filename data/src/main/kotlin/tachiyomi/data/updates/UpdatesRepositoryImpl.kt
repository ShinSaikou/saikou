// Vendored from upstream v0.20.4: data/src/main/java/tachiyomi/data/updates/UpdatesRepositoryImpl.kt (modified: yes)
package tachiyomi.data.updates

import com.shinsaikou.data.DatabaseHandler
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import tachiyomi.core.common.util.lang.toLong
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.domain.updates.repository.UpdatesRepository

/**
 * Modified: the constructor takes [DatabaseHandler], and the category filter binds the explicit
 * `includeAll`, `includeUncategorized` and `excludeUncategorized` flags of 05-data 3.17 instead of
 * upstream's `includedEmpty` / `excludedEmpty` pair and its `0 IN :list` membership tests. The mapper
 * takes the view's `displayTitle` (schema version 3, phase 5 unit SC4) into the model's own (unit ED).
 * Upstream's `awaitWithRead` and `subscribeWithRead` are deleted with the `getUpdatesByReadStatus`
 * query they read (schema version 4, the chapter index diet): only upstream's updates widget used them,
 * and Saikou has none.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class UpdatesRepositoryImpl(
    private val handler: DatabaseHandler,
) : UpdatesRepository {

    override fun subscribeAll(
        after: Long,
        limit: Long,
        unread: Boolean?,
        started: Boolean?,
        bookmarked: Boolean?,
        hideExcludedScanlators: Boolean,
        includedCategories: List<Long>,
        excludedCategories: List<Long>,
    ): Flow<List<UpdatesWithRelations>> {
        // Category 0 is the system "uncategorised" category and no manga carries a row for it,
        // so it is answered by the NOT IN subquery rather than by membership.
        val includeAll = includedCategories.isEmpty()
        val includeUncategorized = Category.UNCATEGORIZED_ID in includedCategories
        val excludeUncategorized = Category.UNCATEGORIZED_ID in excludedCategories
        return handler.subscribeToList {
            updatesViewQueries.getRecentUpdatesWithFilters(
                after = after,
                unread = unread,
                started = started?.toLong(),
                bookmarked = bookmarked,
                hideExcludedScanlators = hideExcludedScanlators.toLong(),
                includeAll = includeAll.toLong(),
                includedCategories = includedCategories.filterNot { it == Category.UNCATEGORIZED_ID },
                includeUncategorized = includeUncategorized.toLong(),
                excludedCategories = excludedCategories.filterNot { it == Category.UNCATEGORIZED_ID },
                excludeUncategorized = excludeUncategorized.toLong(),
                limit = limit,
                mapper = ::mapUpdatesWithRelations,
            )
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun mapUpdatesWithRelations(
        mangaId: Long,
        mangaTitle: String,
        displayTitle: String,
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        chapterUrl: String,
        read: Boolean,
        bookmark: Boolean,
        lastPageRead: Long,
        sourceId: Long,
        favorite: Boolean,
        thumbnailUrl: String?,
        coverLastModified: Long,
        dateUpload: Long,
        dateFetch: Long,
        excludedScanlator: String?,
    ): UpdatesWithRelations = UpdatesWithRelations(
        mangaId = mangaId,
        mangaTitle = mangaTitle,
        displayTitle = displayTitle,
        chapterId = chapterId,
        chapterName = chapterName,
        scanlator = scanlator,
        chapterUrl = chapterUrl,
        read = read,
        bookmark = bookmark,
        lastPageRead = lastPageRead,
        sourceId = sourceId,
        dateFetch = dateFetch,
        coverData = MangaCover(
            mangaId = mangaId,
            sourceId = sourceId,
            isMangaFavorite = favorite,
            url = thumbnailUrl,
            lastModified = coverLastModified,
        ),
    )
}

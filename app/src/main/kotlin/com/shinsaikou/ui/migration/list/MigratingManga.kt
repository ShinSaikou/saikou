// Vendored from upstream v0.20.4: app/src/main/java/tachiyomi/feature/migration/list/models/MigratingManga.kt (modified: yes)
package com.shinsaikou.ui.migration.list

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import tachiyomi.domain.manga.model.Manga
import kotlin.coroutines.CoroutineContext

/**
 * Saikou: the package, and the scope's dispatcher passed in (the graph's default dispatcher) where
 * upstream names `Dispatchers.Default`. The scope has its own [SupervisorJob], so it is not a child of
 * the view model's scope: the view model cancels every entry's scope itself when it is cleared.
 */
class MigratingManga(
    val manga: Manga,
    val chapterCount: Int,
    val latestChapter: Double?,
    val source: String,
    parentContext: CoroutineContext,
    dispatcher: CoroutineDispatcher,
) {
    val migrationScope = CoroutineScope(parentContext + SupervisorJob() + dispatcher)

    val searchResult = MutableStateFlow<SearchResult>(SearchResult.Searching)

    sealed interface SearchResult {
        data object Searching : SearchResult
        data object NotFound : SearchResult
        data class Success(
            val manga: Manga,
            val chapterCount: Int,
            val latestChapter: Double?,
            val source: String,
        ) : SearchResult
    }
}

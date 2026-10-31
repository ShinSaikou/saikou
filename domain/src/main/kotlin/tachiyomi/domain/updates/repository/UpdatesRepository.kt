// Vendored from upstream v0.20.4: domain/src/main/java/tachiyomi/domain/updates/repository/UpdatesRepository.kt (modified: yes)
package tachiyomi.domain.updates.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.updates.model.UpdatesWithRelations

/**
 * Modified: upstream's `awaitWithRead` and `subscribeWithRead` are deleted. Only upstream's updates widget
 * called them, Saikou has no widget, and the indexes their query used are gone (schema version 4, the
 * chapter index diet). A widget port brings them back from upstream.
 */
interface UpdatesRepository {

    fun subscribeAll(
        after: Long,
        limit: Long,
        unread: Boolean?,
        started: Boolean?,
        bookmarked: Boolean?,
        hideExcludedScanlators: Boolean,
        includedCategories: List<Long>,
        excludedCategories: List<Long>,
    ): Flow<List<UpdatesWithRelations>>
}

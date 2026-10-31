// Vendored from upstream v0.20.4: domain/src/main/java/tachiyomi/domain/updates/interactor/GetUpdates.kt (modified: yes)
package tachiyomi.domain.updates.interactor

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.domain.updates.repository.UpdatesRepository
import kotlin.time.Instant

/**
 * Modified: upstream's `await(read, after)` and `subscribe(read, after)` are deleted with the repository
 * methods behind them. Only upstream's updates widget called them, and Saikou has no widget (schema
 * version 4, the chapter index diet).
 */
class GetUpdates(
    private val repository: UpdatesRepository,
) {

    fun subscribe(
        instant: Instant,
        unread: Boolean?,
        started: Boolean?,
        bookmarked: Boolean?,
        hideExcludedScanlators: Boolean,
        includedCategories: List<Long>,
        excludedCategories: List<Long>,
    ): Flow<List<UpdatesWithRelations>> {
        return repository.subscribeAll(
            instant.toEpochMilliseconds(),
            limit = 500,
            unread = unread,
            started = started,
            bookmarked = bookmarked,
            hideExcludedScanlators = hideExcludedScanlators,
            includedCategories = includedCategories,
            excludedCategories = excludedCategories,
        )
    }
}

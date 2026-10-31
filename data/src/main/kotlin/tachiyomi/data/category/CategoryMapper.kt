// Vendored from upstream v0.20.4: data/src/main/java/tachiyomi/data/category/CategoryRepositoryImpl.kt (modified: yes)
package tachiyomi.data.category

import tachiyomi.domain.category.model.Category

/**
 * Extracted from upstream's private `CategoryRepositoryImpl.mapCategory` (05-data 4.1). Modified:
 * the tuple gains `parentId`, `version`, `isSyncing` and `deletedAt`, then schema version 2's
 * clocks and sync key (phase 3 unit C). The domain model carries the sync key since phase 5 unit CT,
 * for the category tree's tie-break; it still carries neither clock.
 */
object CategoryMapper {
    @Suppress("UNUSED_PARAMETER")
    fun mapCategory(
        id: Long,
        name: String,
        order: Long,
        flags: Long,
        parentId: Long?,
        version: Long,
        isSyncing: Long,
        deletedAt: Long?,
        modifiedAt: Long?,
        restoredAt: Long?,
        syncKey: String?,
    ): Category = Category(
        id = id,
        name = name,
        order = order,
        flags = flags,
        parentId = parentId,
        version = version,
        deletedAt = deletedAt,
        syncKey = syncKey,
    )
}

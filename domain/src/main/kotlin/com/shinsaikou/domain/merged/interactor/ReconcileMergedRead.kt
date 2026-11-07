package com.shinsaikou.domain.merged.interactor

import com.shinsaikou.domain.merged.repository.MergedGroupRepository
import com.shinsaikou.domain.preferences.LibraryPreferences
import dev.zacsweers.metro.Inject

/**
 * Raises every live copy of a number any copy of the group has read: read state is written to every
 * copy rather than computed at display, so everything that reads it works unchanged. It only raises,
 * so it is safe to run again, and a path that wrote `read` without `SetReadStatus` heals here. Stands
 * aside while the Experimental switch is off.
 *
 * The write is one set-based statement (`merged_groups.sq`), never a pass per copy, and no trigger:
 * a per-row trigger is quadratic on a bulk mark.
 */
@Inject
class ReconcileMergedRead(
    private val repository: MergedGroupRepository,
    private val libraryPreferences: LibraryPreferences,
) {
    /** The live group of [mainId], the main entry or any other member. */
    suspend fun await(mainId: Long) {
        if (!libraryPreferences.mergedEntries.get()) return
        val group = repository.getLiveGroup(mainId) ?: return
        repository.reconcileRead(group.id)
    }

    /**
     * What a sync pull touched: the live groups among [groupIds], and those holding one of [mangaIds].
     * The live groups are read once, so a pull costs the groups it touched and not the entries.
     */
    suspend fun await(groupIds: Set<Long>, mangaIds: Set<Long>) {
        if (!libraryPreferences.mergedEntries.get()) return
        repository.getLiveGroups()
            .filter { group -> group.id in groupIds || group.members.any { it.mangaId in mangaIds } }
            .forEach { repository.reconcileRead(it.id) }
    }
}

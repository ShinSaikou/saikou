package com.shinsaikou.domain.merged.interactor

import com.shinsaikou.domain.merged.model.MergedGroup
import com.shinsaikou.domain.merged.repository.MergedGroupRepository
import com.shinsaikou.domain.preferences.LibraryPreferences
import dev.zacsweers.metro.Inject

/**
 * The group lookup every caller goes through, so the Experimental switch has one place to bite:
 * while it is off this returns nothing, and every member shows as its own entry. The rows stay.
 */
@Inject
class GetMergedGroup(
    private val repository: MergedGroupRepository,
    private val libraryPreferences: LibraryPreferences,
) {
    /** The live group of [mangaId], the main entry or any other member. */
    suspend fun await(mangaId: Long): MergedGroup? {
        if (!libraryPreferences.mergedEntries.get()) return null
        return repository.getLiveGroup(mangaId)
    }
}

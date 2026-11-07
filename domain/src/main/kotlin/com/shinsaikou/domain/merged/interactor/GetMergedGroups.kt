package com.shinsaikou.domain.merged.interactor

import com.shinsaikou.domain.merged.model.MergedGroup
import com.shinsaikou.domain.merged.repository.MergedGroupRepository
import com.shinsaikou.domain.preferences.LibraryPreferences
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart

/** Every live group as it changes; empty while the Experimental switch is off, as [GetMergedGroup]. */
@Inject
class GetMergedGroups(
    private val repository: MergedGroupRepository,
    private val libraryPreferences: LibraryPreferences,
) {
    fun subscribe(): Flow<List<MergedGroup>> {
        val gate = libraryPreferences.mergedEntries
        return combine(gate.changes().onStart { emit(gate.get()) }, repository.subscribeLiveGroups()) { on, groups ->
            if (on) groups else emptyList()
        }
    }
}

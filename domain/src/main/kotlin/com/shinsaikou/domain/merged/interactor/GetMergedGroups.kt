package com.shinsaikou.domain.merged.interactor

import com.shinsaikou.domain.merged.model.MergedGroup
import com.shinsaikou.domain.merged.model.MergedTitle
import com.shinsaikou.domain.merged.repository.MergedGroupRepository
import com.shinsaikou.domain.preferences.LibraryPreferences
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * Every live group, empty while the Experimental switch is off, as [GetMergedGroup]. The switch
 * decides before anything is read: off, these subscribe to no query at all, so the Library and the
 * entry screen pay a preference read for a feature nobody turned on (R138).
 */
@Inject
class GetMergedGroups(
    private val repository: MergedGroupRepository,
    private val libraryPreferences: LibraryPreferences,
) {
    suspend fun await(): List<MergedGroup> {
        if (!libraryPreferences.mergedEntries.get()) return emptyList()
        return repository.getLiveGroups()
    }

    /**
     * Every live group as it changes. The query reads `mangas`, so most ticks hold the same groups,
     * and they stop here.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun subscribe(): Flow<List<MergedGroup>> {
        val gate = libraryPreferences.mergedEntries
        return gate.changes().onStart { emit(gate.get()) }
            .distinctUntilChanged()
            .flatMapLatest { on -> if (on) repository.subscribeLiveGroups() else flowOf(emptyList()) }
            .distinctUntilChanged()
    }

    /** The live group of [mangaId], the main entry or any other member, as it changes. */
    fun subscribe(mangaId: Long): Flow<MergedGroup?> = subscribe()
        .map { groups -> groups.firstOrNull { group -> group.members.any { it.mangaId == mangaId } } }
        .distinctUntilChanged()

    /** What the Library folds. With no group there is nothing to count, so no chapter is read. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun subscribeTitles(): Flow<List<MergedTitle>> = subscribe().flatMapLatest { groups ->
        if (groups.isEmpty()) {
            flowOf(emptyList())
        } else {
            repository.subscribeGroupCounts()
                .distinctUntilChanged()
                .map { counts -> groups.map { MergedTitle(it, counts[it.id]) } }
        }
    }
}

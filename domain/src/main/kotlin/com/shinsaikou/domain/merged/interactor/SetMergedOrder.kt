package com.shinsaikou.domain.merged.interactor

import com.shinsaikou.domain.merged.repository.MergedGroupRepository
import dev.zacsweers.metro.Inject

/** Sets which source wins a chapter number. Independent of which member is main. */
@Inject
class SetMergedOrder(
    private val repository: MergedGroupRepository,
    private val getMergedGroup: GetMergedGroup,
) {
    /**
     * [mangaIds] are the group's members, the winning source first. An id outside the group is left
     * out: only [MergeEntries] adds a member.
     */
    suspend fun await(mangaIds: List<Long>) {
        val group = mangaIds.firstOrNull()?.let { getMergedGroup.await(it) } ?: return
        val members = group.members.mapTo(HashSet()) { it.mangaId }
        repository.setOrder(group.id, mangaIds.filter { it in members })
    }
}

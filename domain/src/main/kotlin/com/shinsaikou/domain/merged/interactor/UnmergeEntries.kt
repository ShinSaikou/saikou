package com.shinsaikou.domain.merged.interactor

import com.shinsaikou.domain.merged.repository.MergedGroupRepository
import dev.zacsweers.metro.Inject

/** Takes the group apart: each entry is back as it was, except that read marks written stay. */
@Inject
class UnmergeEntries(
    private val repository: MergedGroupRepository,
    private val getMergedGroup: GetMergedGroup,
) {
    suspend fun await(mangaId: Long) {
        val group = getMergedGroup.await(mangaId) ?: return
        repository.unmerge(group.id)
    }
}

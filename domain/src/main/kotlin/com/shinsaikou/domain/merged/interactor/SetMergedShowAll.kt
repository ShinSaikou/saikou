package com.shinsaikou.domain.merged.interactor

import com.shinsaikou.domain.merged.repository.MergedGroupRepository
import dev.zacsweers.metro.Inject

/** Show every copy, the way out when a number's winning copy is broken. Per merged entry. */
@Inject
class SetMergedShowAll(
    private val repository: MergedGroupRepository,
    private val getMergedGroup: GetMergedGroup,
) {
    suspend fun await(mangaId: Long, showAll: Boolean) {
        val group = getMergedGroup.await(mangaId) ?: return
        repository.setShowAll(group.id, showAll)
    }
}

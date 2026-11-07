package com.shinsaikou.domain.merged.interactor

import com.shinsaikou.domain.merged.repository.MergedGroupRepository
import dev.zacsweers.metro.Inject

/** Whether the library update refreshes one member, so a slow fallback source can sit out. */
@Inject
class SetMergedCheckUpdates(
    private val repository: MergedGroupRepository,
    private val getMergedGroup: GetMergedGroup,
) {
    suspend fun await(mangaId: Long, checkUpdates: Boolean) {
        getMergedGroup.await(mangaId) ?: return
        repository.setCheckUpdates(mangaId, checkUpdates)
    }
}

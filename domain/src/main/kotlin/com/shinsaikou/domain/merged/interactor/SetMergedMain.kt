package com.shinsaikou.domain.merged.interactor

import com.shinsaikou.domain.merged.repository.MergedGroupRepository
import dev.zacsweers.metro.Inject

/** Makes one member the entry that stands for the group. The chapter order stays as it is. */
@Inject
class SetMergedMain(
    private val repository: MergedGroupRepository,
    private val getMergedGroup: GetMergedGroup,
) {
    suspend fun await(mangaId: Long) {
        val group = getMergedGroup.await(mangaId) ?: return
        if (group.mainMangaId != mangaId) repository.setMain(group.id, mangaId)
    }
}

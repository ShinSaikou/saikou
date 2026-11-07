package com.shinsaikou.domain.merged.interactor

import com.shinsaikou.domain.database.TransactionRunner
import com.shinsaikou.domain.merged.repository.MergedGroupRepository
import dev.zacsweers.metro.Inject

/**
 * Makes one member the entry that stands for the group. The chapter order stays as it is, and the
 * group's tracks go with the main entry.
 */
@Inject
class SetMergedMain(
    private val repository: MergedGroupRepository,
    private val getMergedGroup: GetMergedGroup,
    private val carryMergedTracks: CarryMergedTracks,
    private val transactionRunner: TransactionRunner,
) {
    suspend fun await(mangaId: Long) {
        val group = getMergedGroup.await(mangaId) ?: return
        if (group.mainMangaId == mangaId) return
        transactionRunner.inTransaction {
            repository.setMain(group.id, mangaId)
            carryMergedTracks.toNewMain(oldMainId = group.mainMangaId, newMainId = mangaId)
        }
    }
}

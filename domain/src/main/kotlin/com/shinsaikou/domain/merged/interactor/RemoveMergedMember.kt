package com.shinsaikou.domain.merged.interactor

import com.shinsaikou.domain.database.TransactionRunner
import com.shinsaikou.domain.merged.repository.MergedGroupRepository
import dev.zacsweers.metro.Inject

/**
 * Takes one source out of its group. The last two members leave together, as an unmerge, because
 * one entry is no group. Removing the main entry hands the main to the first member left.
 */
@Inject
class RemoveMergedMember(
    private val repository: MergedGroupRepository,
    private val getMergedGroup: GetMergedGroup,
    private val carryMergedTracks: CarryMergedTracks,
    private val transactionRunner: TransactionRunner,
) {
    suspend fun await(mangaId: Long) {
        val group = getMergedGroup.await(mangaId) ?: return
        val left = group.members.filter { it.mangaId != mangaId }
        transactionRunner.inTransaction {
            if (left.size < 2) {
                repository.unmerge(group.id)
                return@inTransaction
            }
            if (group.mainMangaId == mangaId) {
                repository.setMain(group.id, left.first().mangaId)
                carryMergedTracks.toNewMain(oldMainId = mangaId, newMainId = left.first().mangaId)
            }
            repository.removeMember(mangaId)
        }
    }
}

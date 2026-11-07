package com.shinsaikou.domain.merged.interactor

import com.shinsaikou.domain.database.TransactionRunner
import com.shinsaikou.domain.merged.repository.MergedGroupRepository
import com.shinsaikou.domain.preferences.LibraryPreferences
import dev.zacsweers.metro.Inject

/**
 * Merges entries into one group under a main entry. An entry already in a group brings that whole
 * group, in its place in the list, and a group left behind is unmerged. Each member stays a favourite
 * of its own, which the caller has made it. While the Experimental switch is off there is no merge
 * action, and this writes nothing.
 */
@Inject
class MergeEntries(
    private val repository: MergedGroupRepository,
    private val getMergedGroup: GetMergedGroup,
    private val reconcileMergedRead: ReconcileMergedRead,
    private val carryMergedTracks: CarryMergedTracks,
    private val libraryPreferences: LibraryPreferences,
    private val transactionRunner: TransactionRunner,
) {
    /** [mangaIds] is the chapter order, the first winning; [mainId] is one of them. */
    suspend fun await(mainId: Long, mangaIds: List<Long>) {
        if (!libraryPreferences.mergedEntries.get()) return
        require(mainId in mangaIds) { "The main entry $mainId is not among $mangaIds" }

        val groups = mangaIds.distinct().associateWith { getMergedGroup.await(it) }
        val members = mangaIds.flatMap { id -> groups[id]?.members?.map { it.mangaId } ?: listOf(id) }.distinct()
        if (members.size < 2) return

        transactionRunner.inTransaction {
            repository.merge(mainId, members)
            groups.values.filterNotNull()
                .filter { it.mainMangaId != mainId }
                .distinctBy { it.id }
                .forEach { repository.unmerge(it.id) }
            carryMergedTracks.ontoMain(mainId, members)
        }
        reconcileMergedRead.await(mainId)
    }
}

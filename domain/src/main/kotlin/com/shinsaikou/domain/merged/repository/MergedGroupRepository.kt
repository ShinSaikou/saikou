package com.shinsaikou.domain.merged.repository

import com.shinsaikou.domain.merged.model.MergedGroup
import com.shinsaikou.domain.merged.model.MergedGroupCounts
import kotlinx.coroutines.flow.Flow

/**
 * The merged entries as stored, whatever the Experimental switch says: the rows stay and still sync
 * while it is off, and the interactors are what stand aside.
 *
 * The writes take the members as manga ids, which must be stored. Each is one transaction, and
 * logs through the tables' triggers.
 */
interface MergedGroupRepository {

    /** The live group that holds [mangaId], the main entry or any other member, or null. */
    suspend fun getLiveGroup(mangaId: Long): MergedGroup?

    suspend fun getLiveGroups(): List<MergedGroup>

    fun subscribeLiveGroups(): Flow<List<MergedGroup>>

    /** Every group's counts by group id. A chapter of the Local source counts as downloaded. */
    fun subscribeGroupCounts(): Flow<Map<Long, MergedGroupCounts>>

    /**
     * Makes [mangaIds] the first members of [mainId]'s group, in that order, creating the group or
     * reusing its tombstone. A member held by another group moves here with its `check_updates`.
     * A row of the group that [mangaIds] does not name stays, after them.
     */
    suspend fun merge(mainId: Long, mangaIds: List<Long>)

    /** Deletes every member of the group, inert ones included. The group's row stays as the tombstone. */
    suspend fun unmerge(groupId: Long)

    /** Takes one member out of whatever group holds it. */
    suspend fun removeMember(mangaId: Long)

    /** [mangaIds] first, in that order; the group's other rows follow, in the order they had. */
    suspend fun setOrder(groupId: Long, mangaIds: List<Long>)

    /**
     * Moves the group's members to [mangaId]'s group, whose row may be a tombstone, and leaves the
     * old row empty. Both rows take one clock (`design/plans/merged-entries.md` section 6).
     */
    suspend fun setMain(groupId: Long, mangaId: Long)

    /**
     * A migration: [newMangaId] takes [oldMangaId]'s place in whatever group holds it, its position,
     * its `check_updates` and the main entry's role included, so the group outlives the old entry.
     * An old entry in no group writes nothing. Logged, so the other devices follow.
     */
    suspend fun replaceMember(oldMangaId: Long, newMangaId: Long)

    suspend fun setShowAll(groupId: Long, showAll: Boolean)

    suspend fun setCheckUpdates(mangaId: Long, checkUpdates: Boolean)

    /** One statement: every live copy of a number any copy has read is raised to read. */
    suspend fun reconcileRead(groupId: Long)
}

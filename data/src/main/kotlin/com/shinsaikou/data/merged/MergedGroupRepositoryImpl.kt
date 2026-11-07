package com.shinsaikou.data.merged

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.shinsaikou.data.DatabaseHandler
import com.shinsaikou.domain.merged.model.MergedGroup
import com.shinsaikou.domain.merged.model.MergedGroupCounts
import com.shinsaikou.domain.merged.repository.MergedGroupRepository
import com.shinsaikou.source.SourceIds
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tachiyomi.data.Database

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class MergedGroupRepositoryImpl(
    private val handler: DatabaseHandler,
) : MergedGroupRepository {

    override suspend fun getLiveGroup(mangaId: Long): MergedGroup? =
        liveGroups(handler.awaitList { merged_groupsQueries.getLiveMembersByMangaId(mangaId, ::mapLiveMember) })
            .singleOrNull()

    override suspend fun getLiveGroups(): List<MergedGroup> =
        liveGroups(handler.awaitList { merged_groupsQueries.getLiveMembers(::mapLiveMember) })

    override fun subscribeLiveGroups(): Flow<List<MergedGroup>> =
        handler.subscribeToList { merged_groupsQueries.getLiveMembers(::mapLiveMember) }.map(::liveGroups)

    override fun subscribeGroupCounts(): Flow<Map<Long, MergedGroupCounts>> =
        handler.subscribeToList { merged_groupsQueries.getGroupCounts(SourceIds.LOCAL, ::mapGroupCounts) }
            .map { rows -> rows.associateBy { it.groupId } }

    override suspend fun merge(mainId: Long, mangaIds: List<Long>) {
        handler.await(inTransaction = true) {
            val main = mangasQueries.getMangaById(mainId).awaitAsOne()
            merged_groupsQueries.insertGroup(mainSource = main.source, mainUrl = main.url, showAll = false)
            placeMembers(merged_groupsQueries.getGroupByMain(main.source, main.url).awaitAsOne()._id, mangaIds)
        }
    }

    /** Show every copy goes with the members: the row is a tombstone now, and a new merge starts plain. */
    override suspend fun unmerge(groupId: Long) {
        handler.await(inTransaction = true) {
            merged_groupsQueries.deleteMembersByGroupId(groupId)
            merged_groupsQueries.setShowAll(showAll = false, groupId = groupId)
        }
    }

    override suspend fun removeMember(mangaId: Long) {
        handler.await(inTransaction = true) {
            val manga = mangasQueries.getMangaById(mangaId).awaitAsOne()
            merged_groupsQueries.deleteMember(manga.source, manga.url)
        }
    }

    override suspend fun setOrder(groupId: Long, mangaIds: List<Long>) {
        handler.await(inTransaction = true) { placeMembers(groupId, mangaIds) }
    }

    override suspend fun setMain(groupId: Long, mangaId: Long) {
        handler.await(inTransaction = true) { moveGroup(groupId, mangaId) }
    }

    override suspend fun replaceMember(oldMangaId: Long, newMangaId: Long) {
        handler.await(inTransaction = true) {
            val old = mangasQueries.getMangaById(oldMangaId).awaitAsOne()
            val new = mangasQueries.getMangaById(newMangaId).awaitAsOne()
            val member = merged_groupsQueries.getMember(old.source, old.url).awaitAsOneOrNull() ?: return@await
            val group = merged_groupsQueries.getGroupById(member.group_id).awaitAsOne()
            val held = merged_groupsQueries.getMember(new.source, new.url).awaitAsOneOrNull()

            merged_groupsQueries.deleteMember(old.source, old.url)
            if (held?.group_id != group._id) {
                if (held != null) leaveGroup(new.source, new.url, held.group_id)
                merged_groupsQueries.insertMember(
                    memberSource = new.source,
                    memberUrl = new.url,
                    groupId = group._id,
                    position = member.position,
                    checkUpdates = member.check_updates,
                )
            }
            if (group.main_source == old.source && group.main_url == old.url) moveGroup(group._id, newMangaId)
        }
    }

    override suspend fun setShowAll(groupId: Long, showAll: Boolean) {
        handler.await { merged_groupsQueries.setShowAll(showAll = showAll, groupId = groupId) }
    }

    override suspend fun setCheckUpdates(mangaId: Long, checkUpdates: Boolean) {
        handler.await(inTransaction = true) {
            val manga = mangasQueries.getMangaById(mangaId).awaitAsOne()
            merged_groupsQueries.setCheckUpdates(checkUpdates, manga.source, manga.url)
        }
    }

    override suspend fun reconcileRead(groupId: Long) {
        handler.await { merged_groupsQueries.reconcileRead(groupId) }
    }

    /**
     * Takes a member out of the group it is in before it joins another. A group that would lose its
     * main entry, or be left with one member, is unmerged, so the two groups never run together.
     */
    private suspend fun Database.leaveGroup(source: Long, url: String, groupId: Long) {
        val group = merged_groupsQueries.getGroupById(groupId).awaitAsOne()
        val isMain = group.main_source == source && group.main_url == url
        val left = merged_groupsQueries.getMembersByGroupId(groupId).awaitAsList().size - 1
        if (isMain || left < 2) {
            merged_groupsQueries.deleteMembersByGroupId(groupId)
            merged_groupsQueries.setShowAll(showAll = false, groupId = groupId)
        } else {
            merged_groupsQueries.deleteMember(source, url)
        }
    }

    /** The members of [groupId] under [mangaId]'s row, the old row left empty, at one clock. */
    private suspend fun Database.moveGroup(groupId: Long, mangaId: Long) {
        val old = merged_groupsQueries.getGroupById(groupId).awaitAsOne()
        val main = mangasQueries.getMangaById(mangaId).awaitAsOne()
        if (main.source == old.main_source && main.url == old.main_url) return
        merged_groupsQueries.insertGroup(main.source, main.url, old.show_all)
        val new = merged_groupsQueries.getGroupByMain(main.source, main.url).awaitAsOne()._id

        val members = merged_groupsQueries.getMembersByGroupId(groupId).awaitAsList()
        merged_groupsQueries.deleteMembersByGroupId(groupId)
        members.forEach { member ->
            merged_groupsQueries.insertMember(
                memberSource = member.member_source,
                memberUrl = member.member_url,
                groupId = new,
                position = member.position,
                checkUpdates = member.check_updates,
            )
        }
        merged_groupsQueries.setShowAll(showAll = old.show_all, groupId = new)
        merged_groupsQueries.setShowAll(showAll = false, groupId = groupId)

        // At one clock the line with members beats the empty line, whichever a device reads first.
        val clock = maxOf(
            merged_groupsQueries.getGroupById(groupId).awaitAsOne().modified_at,
            merged_groupsQueries.getGroupById(new).awaitAsOne().modified_at,
        )
        merged_groupsQueries.setModifiedAt(modifiedAt = clock, groupId = groupId)
        merged_groupsQueries.setModifiedAt(modifiedAt = clock, groupId = new)
    }

    /**
     * Numbers [mangaIds] from 0, then the group's other rows, which are the members a sync line named
     * that have not arrived here: they must stay in the set this device writes back. Only rows
     * that move are written, so a row in its place logs nothing.
     */
    private suspend fun Database.placeMembers(groupId: Long, mangaIds: List<Long>) {
        val named = mangaIds.distinct().map { id ->
            mangasQueries.getMangaById(id).awaitAsOne().let { it.source to it.url }
        }
        val rest = merged_groupsQueries.getMembersByGroupId(groupId).awaitAsList()
            .map { it.member_source to it.member_url } - named.toSet()
        (named + rest).forEachIndexed { index, (source, url) ->
            val row = merged_groupsQueries.getMember(source, url).awaitAsOneOrNull()
            when {
                row == null -> merged_groupsQueries.insertMember(source, url, groupId, index.toLong(), true)
                row.group_id != groupId -> {
                    merged_groupsQueries.deleteMember(source, url)
                    merged_groupsQueries.insertMember(source, url, groupId, index.toLong(), row.check_updates)
                }
                row.position != index.toLong() -> merged_groupsQueries.setPosition(index.toLong(), source, url)
            }
        }
    }
}

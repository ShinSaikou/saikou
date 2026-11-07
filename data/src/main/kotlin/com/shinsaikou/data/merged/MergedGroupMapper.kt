package com.shinsaikou.data.merged

import com.shinsaikou.domain.merged.model.MergedGroup
import com.shinsaikou.domain.merged.model.MergedGroupCounts
import com.shinsaikou.domain.merged.model.MergedMember

/** One row of `getLiveMembers`: a member with a live favourite behind it, and its group. */
class LiveMember(
    val groupId: Long,
    val mainSource: Long,
    val mainUrl: String,
    val showAll: Boolean,
    val member: MergedMember,
)

/** The columns of `getLiveMembers` and `getLiveMembersByMangaId` in declaration order. */
fun mapLiveMember(
    groupId: Long,
    mainSource: Long,
    mainUrl: String,
    showAll: Boolean,
    memberSource: Long,
    memberUrl: String,
    position: Long,
    checkUpdates: Boolean,
    mangaId: Long,
): LiveMember = LiveMember(
    groupId = groupId,
    mainSource = mainSource,
    mainUrl = mainUrl,
    showAll = showAll,
    member = MergedMember(
        mangaId = mangaId,
        source = memberSource,
        url = memberUrl,
        position = position,
        checkUpdates = checkUpdates,
    ),
)

/** The columns of `getGroupCounts` in declaration order; SQLDelight reads a `sum` as a nullable double. */
fun mapGroupCounts(groupId: Long, total: Long, read: Double?, bookmarked: Double?, downloaded: Double?) =
    MergedGroupCounts(
        groupId = groupId,
        total = total,
        read = read?.toLong() ?: 0L,
        bookmarked = bookmarked?.toLong() ?: 0L,
        downloaded = downloaded?.toLong() ?: 0L,
    )

/**
 * The live groups among [rows]: those whose main entry is a live favourite with a member row of its
 * own and that hold at least two live members. Liveness is decided here alone, so a dead group
 * hides nothing wherever groups are read. A main entry without a row of its own is a group that
 * lost it to a newer claim, and showing it would put that entry in two groups.
 */
fun liveGroups(rows: List<LiveMember>): List<MergedGroup> = rows.groupBy { it.groupId }.values.mapNotNull { members ->
    val first = members.first()
    val main = members.find { it.member.source == first.mainSource && it.member.url == first.mainUrl }
    if (main == null || members.size < 2) return@mapNotNull null
    MergedGroup(
        id = first.groupId,
        mainMangaId = main.member.mangaId,
        showAll = first.showAll,
        members = members.map { it.member },
    )
}

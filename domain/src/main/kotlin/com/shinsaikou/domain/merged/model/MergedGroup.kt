package com.shinsaikou.domain.merged.model

/**
 * A live merged entry (R163): one title from several sources, each still a whole favourite with its
 * own row, chapters and downloads. One member, the main entry, stands for the group in the Library.
 *
 * [members] holds the members with a live favourite behind them, in chapter order, the main entry
 * among them. A member that has not arrived on this device yet is inert and is not here. A group
 * that is not live is never one of these: at least two members, and the main entry one of them.
 */
data class MergedGroup(
    val id: Long,
    val mainMangaId: Long,
    val showAll: Boolean,
    val members: List<MergedMember>,
)

/** One member of a [MergedGroup]. [position] orders the sources' chapters, the lowest first. */
data class MergedMember(
    val mangaId: Long,
    val source: Long,
    val url: String,
    val position: Long,
    val checkUpdates: Boolean,
)

/**
 * What the Library counts for one merged title, across its live members: each recognised number
 * once however many copies it has, and each chapter with no recognised number on its own. A number
 * counts as read, bookmarked or downloaded while any copy is.
 */
data class MergedGroupCounts(
    val groupId: Long,
    val total: Long,
    val read: Long,
    val bookmarked: Long,
    val downloaded: Long,
)

/** A live group as the Library folds it; [counts] is null only for a tick behind the group itself. */
data class MergedTitle(val group: MergedGroup, val counts: MergedGroupCounts?)

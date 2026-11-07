package com.shinsaikou.sync.format

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * The rows of format 3 (Q4; format 1 plus the manga line's edit of phase 5 unit ED and the merged
 * group line of R163). Local ids never leave the device (Q5): every row names what it is about by
 * natural key, and a child collection travels whole, keyed by its parent and decided by its own
 * clock (Q6). Clocks are epoch milliseconds; a null clock is a value the owner never set.
 *
 * Property names are the JSON keys. Renaming one changes the wire, which `format1.jsonl`,
 * `format2.jsonl` and `format3.jsonl` in the tests pin; a field or row added later raises
 * [SyncFormat.CURRENT] and gets a written rule for rows of the older formats.
 */

/** A manga across devices: its source id and its url on that source. */
@Serializable
data class MangaKey(val source: Long, val url: String)

/** A chapter across devices: its manga's key and the chapter's url. */
@Serializable
data class ChapterKey(val manga: MangaKey, val url: String)

/** The kind of a row, which is also its `entity` value on the wire and its key in a header's counts. */
@Serializable
enum class RowKind {
    @SerialName("manga")
    MANGA,

    @SerialName("chapter")
    CHAPTER,

    @SerialName("chapter_removed")
    CHAPTER_REMOVED,

    @SerialName("category")
    CATEGORY,

    @SerialName("manga_categories")
    MANGA_CATEGORIES,

    @SerialName("history")
    HISTORY,

    @SerialName("tracks")
    TRACKS,

    @SerialName("excluded_scanlators")
    EXCLUDED_SCANLATORS,

    @SerialName("scanlator_priority")
    SCANLATOR_PRIORITY,

    @SerialName("bookmark_notes")
    BOOKMARK_NOTES,

    @SerialName("merged_group")
    MERGED_GROUP,
}

/** One line of a segment, export part or snapshot after the header. */
@Serializable
sealed class SyncRow {
    abstract val kind: RowKind

    /**
     * The manga this row belongs to, which a file must carry before this row (Q4); null for a category
     * and for a merged group, which belongs to none.
     */
    abstract val parentManga: MangaKey?
}

/**
 * A manga, live or soft-deleted. `version` decides the source values (title to memo); each owner
 * value is decided by the clock beside it, and the tombstone by `deletedAt` against `restoredAt`.
 * Format 2 adds the owner's title and description edit and its clock (phase 5 unit ED, R138 item
 * 2); a format 1 line has none of the three and reads as an entry never edited.
 */
@Serializable
@SerialName("manga")
data class MangaRow(
    val key: MangaKey,
    val favorite: Boolean,
    val dateAdded: Long,
    val favoriteModifiedAt: Long?,
    val deletedAt: Long?,
    val restoredAt: Long?,
    val version: Long,
    val title: String,
    val author: String?,
    val artist: String?,
    val description: String?,
    val genre: List<String>?,
    val status: Long,
    val thumbnailUrl: String?,
    /** The stored `update_strategy` code, not the enum's name, so a renamed constant keeps its value. */
    val updateStrategy: Long,
    val memo: JsonObject,
    val viewer: Long,
    val chapterFlags: Long,
    val displayModifiedAt: Long?,
    val notes: String,
    val notesModifiedAt: Long?,
    val manualPosition: Long,
    val positionModifiedAt: Long?,
    val titleOverride: String? = null,
    val descriptionOverride: String? = null,
    val detailsModifiedAt: Long? = null,
) : SyncRow() {
    override val kind: RowKind get() = RowKind.MANGA
    override val parentManga: MangaKey get() = key
}

/**
 * A chapter, live or soft-deleted. `version` decides the source values (name to volume).
 * [sourceOrder] is not merged by any rule; a device that does not have the chapter yet uses it
 * when it inserts the row, to place it among its own, so the chapter list keeps the source's order
 * until the next update.
 */
@Serializable
@SerialName("chapter")
data class ChapterRow(
    val key: ChapterKey,
    val read: Boolean,
    val bookmark: Boolean,
    val lastPageRead: Long,
    val dateFetch: Long,
    val version: Long,
    val name: String,
    val scanlator: String?,
    val chapterNumber: Double,
    val dateUpload: Long,
    val volume: Double?,
    val sourceOrder: Long,
    val deletedAt: Long?,
    val restoredAt: Long?,
) : SyncRow() {
    override val kind: RowKind get() = RowKind.CHAPTER
    override val parentManga: MangaKey get() = key.manga
}

/**
 * A chapter the pushing device hard-deleted, named by the key C records in the change log because
 * the row is gone. It travels as a tombstone at [removedAt] with no values.
 */
@Serializable
@SerialName("chapter_removed")
data class ChapterRemovedRow(
    val key: ChapterKey,
    val removedAt: Long,
) : SyncRow() {
    override val kind: RowKind get() = RowKind.CHAPTER_REMOVED
    override val parentManga: MangaKey get() = key.manga
}

/**
 * A category, keyed by its `sync_key` (Q7), live or soft-deleted. `modifiedAt` decides the name,
 * flags, order and parent, and [parent] is the parent category's key.
 */
@Serializable
@SerialName("category")
data class CategoryRow(
    val key: String,
    val name: String,
    val order: Long,
    val flags: Long,
    val parent: String?,
    val modifiedAt: Long?,
    val deletedAt: Long?,
    val restoredAt: Long?,
) : SyncRow() {
    override val kind: RowKind get() = RowKind.CATEGORY
    override val parentManga: MangaKey? get() = null
}

/** A manga's whole category set as category keys. The system category never travels (Q7). */
@Serializable
@SerialName("manga_categories")
data class MangaCategoriesRow(
    val manga: MangaKey,
    val categories: Set<String>,
    val categoriesModifiedAt: Long?,
) : SyncRow() {
    override val kind: RowKind get() = RowKind.MANGA_CATEGORIES
    override val parentManga: MangaKey get() = manga
}

/** One chapter's history. No clock: each value merges by max (Q6). */
@Serializable
@SerialName("history")
data class HistoryRow(
    val chapter: ChapterKey,
    val lastRead: Long?,
    val timeRead: Long,
) : SyncRow() {
    override val kind: RowKind get() = RowKind.HISTORY
    override val parentManga: MangaKey get() = chapter.manga
}

/** A manga's whole tracker set; `trackersModifiedAt` decides which trackers are bound. */
@Serializable
@SerialName("tracks")
data class TracksRow(
    val manga: MangaKey,
    val tracks: List<TrackEntry>,
    val trackersModifiedAt: Long?,
) : SyncRow() {
    override val kind: RowKind get() = RowKind.TRACKS
    override val parentManga: MangaKey get() = manga
}

/** One tracker bound to a manga, keyed within its set by [trackerId]. */
@Serializable
data class TrackEntry(
    val trackerId: Long,
    val remoteId: Long,
    val libraryId: Long?,
    val title: String,
    val lastChapterRead: Double,
    val totalChapters: Long,
    val status: Long,
    val score: Double,
    val remoteUrl: String,
    val startDate: Long,
    val finishDate: Long,
    val private: Boolean,
)

/** A manga's whole set of excluded scanlators. */
@Serializable
@SerialName("excluded_scanlators")
data class ExcludedScanlatorsRow(
    val manga: MangaKey,
    val scanlators: Set<String>,
    val exclusionsModifiedAt: Long?,
) : SyncRow() {
    override val kind: RowKind get() = RowKind.EXCLUDED_SCANLATORS
    override val parentManga: MangaKey get() = manga
}

/** A manga's whole scanlator priority list. */
@Serializable
@SerialName("scanlator_priority")
data class ScanlatorPriorityRow(
    val manga: MangaKey,
    val priorities: List<PriorityEntry>,
    val priorityModifiedAt: Long?,
) : SyncRow() {
    override val kind: RowKind get() = RowKind.SCANLATOR_PRIORITY
    override val parentManga: MangaKey get() = manga
}

@Serializable
data class PriorityEntry(val scanlator: String, val priority: Long)

/** A chapter's whole set of bookmark notes; the clock is the chapter's `bookmark_notes_modified_at`. */
@Serializable
@SerialName("bookmark_notes")
data class BookmarkNotesRow(
    val chapter: ChapterKey,
    val notes: List<NoteEntry>,
    val bookmarkNotesModifiedAt: Long?,
) : SyncRow() {
    override val kind: RowKind get() = RowKind.BOOKMARK_NOTES
    override val parentManga: MangaKey get() = chapter.manga
}

/** One page's note, keyed within its set by [pageIndex]. */
@Serializable
data class NoteEntry(val pageIndex: Long, val note: String, val dateAdded: Long)

/**
 * A merged entry's whole set of members, keyed by the main entry (R163). No member, the main one
 * included, has to be stored on the device that reads it: the line is stored as it stands and a
 * member that has not arrived is inert until it does, so it is never skipped, which is why the
 * tables name entries by key (`design/plans/merged-entries.md` section 3). An unmerge is an empty
 * line with a newer clock. The clock decides per main entry, and between two groups that claim one
 * member, as [com.shinsaikou.sync.MergeEngine.mergeMergedGroup] says.
 */
@Serializable
@SerialName("merged_group")
data class MergedGroupRow(
    val main: MangaKey,
    val members: List<MergedMemberEntry>,
    val showAll: Boolean,
    val modifiedAt: Long,
) : SyncRow() {
    override val kind: RowKind get() = RowKind.MERGED_GROUP
    override val parentManga: MangaKey? get() = null
}

/** One member of a merged group, in chapter order by [position], the main entry among them. */
@Serializable
data class MergedMemberEntry(val manga: MangaKey, val position: Long, val checkUpdates: Boolean)

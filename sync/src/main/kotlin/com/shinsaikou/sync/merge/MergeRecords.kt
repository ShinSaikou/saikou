package com.shinsaikou.sync.merge

import com.shinsaikou.source.model.UpdateStrategy
import com.shinsaikou.sync.format.ChapterKey
import com.shinsaikou.sync.format.MangaKey
import kotlinx.serialization.json.JsonObject

// The values MergeEngine compares, one type per synced row (plans/phase3-start.md Q5 to Q7). Local
// ids never appear: a row is named by its natural key, a category by its sync key, and a child
// collection travels whole inside its parent, keyed the way its table's UNIQUE constraint keys it,
// so two devices that read the same rows in a different order hold equal values. Every clock is
// epoch milliseconds, and null means the value was never stamped, which loses to any stamp. The
// sync engine maps database rows to these types and back; the columns it leaves out, and why, are
// listed by name in MergeColumnCoverageTest. Manga and chapters are keyed by the wire format's
// [MangaKey] and [ChapterKey], the one key type sync has (R81 carry-over 2).

/** One `mangas` row with the child collections that travel with it. */
data class MangaRecord(
    val key: MangaKey,

    // Favourite and its add date, decided by favoriteModifiedAt.
    val favorite: Boolean,
    val dateAdded: Long,
    val favoriteModifiedAt: Long?,

    // The tombstone and its revival, as a chapter's: undelete and a favourite flip from 0 to 1 stamp
    // restoredAt.
    val deletedAt: Long?,
    val restoredAt: Long?,

    // Values a source owns, decided by version.
    val version: Long,
    val title: String,
    val author: String?,
    val artist: String?,
    val description: String?,
    val genre: List<String>?,
    val status: Long,
    val thumbnailUrl: String?,
    val updateStrategy: UpdateStrategy,
    val memo: JsonObject,

    // Values the owner sets, each decided by its own clock.
    val viewerFlags: Long,
    val chapterFlags: Long,
    val displayModifiedAt: Long?,
    val notes: String,
    val notesModifiedAt: Long?,
    val manualPosition: Long,
    val positionModifiedAt: Long?,
    /**
     * The owner's edits of the title, description, author, artist, status and genres, each null while
     * unedited, under one clock (R138 item 2, R165 item 4).
     */
    val titleOverride: String?,
    val descriptionOverride: String?,
    val authorOverride: String?,
    val artistOverride: String?,
    val statusOverride: Long?,
    val genreOverride: List<String>?,
    val detailsModifiedAt: Long?,

    /** The sync keys of the categories the manga is in; the system category never travels. */
    val categories: Set<String>,
    val categoriesModifiedAt: Long?,
    val excludedScanlators: Set<String>,
    val exclusionsModifiedAt: Long?,
    /** Scanlator to priority, the lowest ranking first. */
    val scanlatorPriority: Map<String, Long>,
    val priorityModifiedAt: Long?,
    /** Tracker id to the binding's fields; the set of keys is the set of bound trackers. */
    val tracks: Map<Long, TrackRecord>,
    val trackersModifiedAt: Long?,
)

/** One `manga_sync` row's fields; the tracker id is the key it sits under in [MangaRecord.tracks]. */
data class TrackRecord(
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

/** One `chapters` row with its bookmark notes. */
data class ChapterRecord(
    val key: ChapterKey,

    // Read and bookmark, each decided by its own clock (R165). Unlike the other clocks these are
    // never null: 0 is a value never stamped, from before schema version 6 or format 4.
    val read: Boolean,
    val readModifiedAt: Long,
    val bookmark: Boolean,
    val bookmarkModifiedAt: Long,

    // Progress, decided by no clock.
    val lastPageRead: Long,
    val dateFetch: Long,

    // Values a source owns, decided by version.
    val version: Long,
    val name: String,
    val scanlator: String?,
    val chapterNumber: Double,
    val dateUpload: Long,
    val volume: Double?,

    // Tombstone and the revival that beats an older one.
    val deletedAt: Long?,
    val restoredAt: Long?,

    /** Page index to the note on that page. */
    val bookmarkNotes: Map<Long, BookmarkNoteRecord>,
    val bookmarkNotesModifiedAt: Long?,
)

/** One `bookmark_notes` row's fields; the page index is the key it sits under. */
data class BookmarkNoteRecord(val note: String, val dateAdded: Long)

/** One `categories` row, named by its sync key (Q7), its parent by the parent's sync key. */
data class CategoryRecord(
    val syncKey: String,
    val name: String,
    val order: Long,
    val flags: Long,
    val parentKey: String?,
    val modifiedAt: Long?,
    val deletedAt: Long?,
    val restoredAt: Long?,
)

/**
 * One `merged_groups` row with its members, by position then key, so two devices that hold the same
 * rows hold equal values. Empty [members] are an unmerge's tombstone. Unlike the other clocks,
 * [modifiedAt] is never null: a group has one from the write that made it.
 */
data class MergedGroupRecord(
    val main: MangaKey,
    val showAll: Boolean,
    val modifiedAt: Long,
    val members: List<MergedMemberRecord>,
)

/** One `merged_members` row. */
data class MergedMemberRecord(val key: MangaKey, val position: Long, val checkUpdates: Boolean)

/** One `history` row: when the chapter was last read, and for how long. */
data class HistoryRecord(val chapter: ChapterKey, val readAt: Long?, val readDuration: Long)

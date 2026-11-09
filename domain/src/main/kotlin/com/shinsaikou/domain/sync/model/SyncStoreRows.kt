package com.shinsaikou.domain.sync.model

import com.shinsaikou.source.model.UpdateStrategy
import kotlinx.serialization.json.JsonObject

/*
 * The synced rows as the sync engine reads and writes them through [com.shinsaikou.domain.sync.repository.SyncStore]
 * (phase 3 unit E2): each carries its local id, its natural key (Q5) and every value and clock a Q6
 * rule merges, and nothing else. The domain models carry none of the phase 3 clocks (R85 carry-over
 * 7), so sync has its own. Clocks are epoch milliseconds, null where the owner never set the value.
 */

/**
 * One `mangas` row, without its child collections, which are read and written one at a time. On an
 * insert [id] is ignored.
 */
data class StoredManga(
    val id: Long,
    val source: Long,
    val url: String,
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
    val updateStrategy: UpdateStrategy,
    val memo: JsonObject,
    val viewerFlags: Long,
    val chapterFlags: Long,
    val displayModifiedAt: Long?,
    val notes: String,
    val notesModifiedAt: Long?,
    val manualPosition: Long,
    val positionModifiedAt: Long?,
    val titleOverride: String?,
    val descriptionOverride: String?,
    val authorOverride: String?,
    val artistOverride: String?,
    val statusOverride: Long?,
    val genreOverride: List<String>?,
    val detailsModifiedAt: Long?,
    val categoriesModifiedAt: Long?,
    val exclusionsModifiedAt: Long?,
    val priorityModifiedAt: Long?,
    val trackersModifiedAt: Long?,
)

/** One `chapters` row, without its bookmark notes. On an insert [id] is ignored. */
data class StoredChapter(
    val id: Long,
    val mangaId: Long,
    val url: String,
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
    val bookmarkNotesModifiedAt: Long?,
    /** The read and bookmark clocks (R165), 0 while never stamped. */
    val readModifiedAt: Long,
    val bookmarkModifiedAt: Long,
)

/**
 * One keyed `categories` row. The system category has no sync key and is never one of these (Q7).
 * The parent is named by its own sync key. On an insert [id] is ignored.
 */
data class StoredCategory(
    val id: Long,
    val syncKey: String,
    val name: String,
    val order: Long,
    val flags: Long,
    val parentKey: String?,
    val modifiedAt: Long?,
    val deletedAt: Long?,
    val restoredAt: Long?,
)

/** One `history` row, keyed by its chapter. */
data class StoredHistory(val chapterId: Long, val lastRead: Long?, val timeRead: Long)

/** One `manga_sync` row: the tracker [trackerId] bound to a manga. */
data class StoredTrack(
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

/** One `bookmark_notes` row of a chapter. */
data class StoredNote(val pageIndex: Long, val note: String, val dateAdded: Long)

/**
 * One `merged_groups` row with its member rows. Everything is named by source and url, because a
 * member's own row may not be here yet. Empty [members] are an unmerge's tombstone. On an insert [id]
 * is ignored.
 */
data class StoredMergedGroup(
    val id: Long,
    val mainSource: Long,
    val mainUrl: String,
    val showAll: Boolean,
    val modifiedAt: Long,
    val members: List<StoredMergedMember>,
)

data class StoredMergedMember(val source: Long, val url: String, val position: Long, val checkUpdates: Boolean)

/**
 * What a push reads, by local id, as the change log names the rows (R85 item 1): the manga rows, each
 * manga collection by its manga, the chapter rows, each chapter's notes, the history rows by chapter,
 * the category rows, the manga that each hard-deleted chapter belonged to, and the merged groups.
 */
data class SyncPushRequest(
    val mangaIds: Set<Long> = emptySet(),
    val categoryMembershipMangaIds: Set<Long> = emptySet(),
    val exclusionMangaIds: Set<Long> = emptySet(),
    val priorityMangaIds: Set<Long> = emptySet(),
    val trackMangaIds: Set<Long> = emptySet(),
    val chapterIds: Set<Long> = emptySet(),
    val noteChapterIds: Set<Long> = emptySet(),
    val historyChapterIds: Set<Long> = emptySet(),
    val removedChapters: Set<SyncDeletedKey.Chapter> = emptySet(),
    val mergedGroupIds: Set<Long> = emptySet(),
)

/**
 * The rows a [SyncPushRequest] named that still exist, read in one transaction. [mangas] also holds
 * every manga a returned chapter, note set, history row or collection belongs to, and the manga of
 * each removed chapter that is still stored; [chapters] also holds the chapter of every returned
 * note set and history row. [categories] is every keyed category, deleted ones included, since a
 * category set or a parent may name any of them. Collections are keyed by their parent's id, and
 * [mergedGroups] by the group's.
 */
data class SyncPushRows(
    val mangas: Map<Long, StoredManga>,
    val categoryKeys: Map<Long, Set<String>>,
    val excludedScanlators: Map<Long, Set<String>>,
    val scanlatorPriority: Map<Long, Map<String, Long>>,
    val tracks: Map<Long, List<StoredTrack>>,
    val chapters: Map<Long, StoredChapter>,
    val notes: Map<Long, List<StoredNote>>,
    val history: Map<Long, StoredHistory>,
    val categories: Map<Long, StoredCategory>,
    val mergedGroups: Map<Long, StoredMergedGroup> = emptyMap(),
)

/**
 * The library a sync run found and left: live library entries, live categories (the system one
 * excluded), and live chapters of live library entries that are read and that are bookmarked.
 */
data class SyncLibraryCounts(
    val libraryEntries: Long,
    val categories: Long,
    val readChapters: Long,
    val bookmarkedChapters: Long,
)

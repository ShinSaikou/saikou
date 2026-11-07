package com.shinsaikou.domain.sync.repository

import com.shinsaikou.domain.sync.model.StoredCategory
import com.shinsaikou.domain.sync.model.StoredChapter
import com.shinsaikou.domain.sync.model.StoredHistory
import com.shinsaikou.domain.sync.model.StoredManga
import com.shinsaikou.domain.sync.model.StoredMergedGroup
import com.shinsaikou.domain.sync.model.StoredNote
import com.shinsaikou.domain.sync.model.StoredTrack
import com.shinsaikou.domain.sync.model.SyncLibraryCounts
import com.shinsaikou.domain.sync.model.SyncPushRequest
import com.shinsaikou.domain.sync.model.SyncPushRows

/**
 * The sync engine's reach into the database (phase 3 unit E2). It lives here because `:sync` may not
 * depend on `:data` nor `:data` on `:sync`: `:data` implements it and `:app` binds it. The change
 * log itself is read and pruned through [SyncChangeLogRepository].
 */
interface SyncStore {

    /** The rows [request] names, as they are now, read in one transaction. */
    suspend fun readForPush(request: SyncPushRequest): SyncPushRows

    /**
     * The manga id of each of [chapterIds] still stored, by chapter id, read in one transaction: the
     * entries a push batch's chapter, history and bookmark-note rows belong to (phase 4 unit NB).
     */
    suspend fun chapterMangaIds(chapterIds: Collection<Long>): Map<Long, Long>

    /**
     * Up to [limit] ids, ascending and above [afterId], of the manga a full export or a snapshot
     * carries: every favourite, every soft-deleted manga, and every manga that has been in the
     * library, whose removal is state the other devices need.
     */
    suspend fun libraryMangaIds(afterId: Long, limit: Long): List<Long>

    /**
     * [mangaIds] whole, read in one transaction: each manga with its four collections, and every
     * chapter it has, soft-deleted ones included, with its history and notes. [SyncPushRows.categories]
     * is every keyed category, as for a push.
     */
    suspend fun readLibrary(mangaIds: Collection<Long>): SyncPushRows

    /**
     * Every merged group, tombstones included, read in one transaction: a group is named by keys, so
     * it belongs to no entry a page of [readLibrary] reads, and a full export or a snapshot carries
     * it once.
     */
    suspend fun mergedGroups(): List<StoredMergedGroup>

    /**
     * Fixes the seq a full export ends at, in one transaction: the highest seq the change log has
     * handed out (pruned rows included), or one past [pushedSeq] when the log has handed out nothing
     * since. The log's counter is raised to it, so no later row takes a seq at or below it, and the
     * log is pruned up to it: the export carries every row those log rows named.
     */
    suspend fun beginExport(pushedSeq: Long): Long

    /** What the library holds now, for a run's before and after counts. */
    suspend fun libraryCounts(): SyncLibraryCounts

    /**
     * Runs [block] as one database transaction: one segment's apply. Every category the start-up
     * backfill has not keyed yet is keyed first (R85 item 5). Each write in [SyncApplyScope] raises
     * `is_syncing` on the row whose triggers read it, so an apply never logs, bumps a version or
     * moves a clock; every latch raised is lowered again before the transaction commits, so a write
     * another coroutine makes to the same row waits for the commit and is logged as usual.
     */
    suspend fun <T> apply(block: suspend SyncApplyScope.() -> T): T
}

/**
 * Reads by natural key and writes of one apply. Reads see the apply's own writes. A write takes the
 * values as given, tombstone and restore clocks included (R82 item 4), and moves no clock itself.
 */
interface SyncApplyScope {

    suspend fun manga(source: Long, url: String): StoredManga?

    suspend fun chapter(mangaId: Long, url: String): StoredChapter?

    suspend fun category(syncKey: String): StoredCategory?

    /** The keys of the live categories the manga is in; the system category is never one (Q7). */
    suspend fun categoryKeys(mangaId: Long): Set<String>

    suspend fun excludedScanlators(mangaId: Long): Set<String>

    suspend fun scanlatorPriority(mangaId: Long): Map<String, Long>

    suspend fun tracks(mangaId: Long): List<StoredTrack>

    suspend fun notes(chapterId: Long): List<StoredNote>

    suspend fun history(chapterId: Long): StoredHistory?

    /** The group whose main entry is that key, with its members, tombstones included. */
    suspend fun mergedGroup(mainSource: Long, mainUrl: String): StoredMergedGroup?

    /** The group that holds the member with that key, with all its members. */
    suspend fun mergedGroupOfMember(source: Long, url: String): StoredMergedGroup?

    /** Inserts [manga] and returns its id. */
    suspend fun insertManga(manga: StoredManga): Long

    /**
     * Writes every value and clock of [manga] over the row with its id. The favourite clock is
     * written in a statement of its own after the favourite, whose unguarded trigger stamps the
     * write's time (R85 carry-over 5).
     */
    suspend fun updateManga(manga: StoredManga)

    /** Inserts [chapter] and returns its id. */
    suspend fun insertChapter(chapter: StoredChapter): Long

    /** Writes every value and clock of [chapter] over the row with its id; `source_order` is kept. */
    suspend fun updateChapter(chapter: StoredChapter)

    /** The `source_order` of each live chapter of the manga, by chapter id. */
    suspend fun chapterSourceOrders(mangaId: Long): Map<Long, Long>

    /**
     * Writes each chapter's `source_order`, by chapter id. No rule merges it and no trigger reads it,
     * so the write logs nothing and moves no version, as a library update's renumbering does not.
     */
    suspend fun setSourceOrders(orders: Map<Long, Long>)

    /**
     * Inserts [category] and returns its id. Its parent is resolved by key to a live category once
     * the apply's block has run, so a parent the same apply writes later is found (phase 5 unit CT).
     */
    suspend fun insertCategory(category: StoredCategory): Long

    /**
     * Writes every value and clock of [category] over the row with its id, the parent as
     * [insertCategory] resolves it. When the write deletes a live category, the categories under it
     * are reparented to none once the block has run, as the local delete reparents them.
     */
    suspend fun updateCategory(category: StoredCategory)

    /** Drops every membership of the category, as deleting a category does locally. */
    suspend fun dropMemberships(categoryId: Long)

    /**
     * Makes the manga's categories the live ones [keys] names, leaving a key with no live category
     * out, and stores [modifiedAt] as the set's clock.
     */
    suspend fun setCategoryKeys(mangaId: Long, keys: Set<String>, modifiedAt: Long?)

    suspend fun setExcludedScanlators(mangaId: Long, scanlators: Set<String>, modifiedAt: Long?)

    suspend fun setScanlatorPriority(mangaId: Long, priorities: Map<String, Long>, modifiedAt: Long?)

    /** Binds exactly [tracks], each as given, and stores [modifiedAt] as the tracker set's clock. */
    suspend fun setTracks(mangaId: Long, tracks: List<StoredTrack>, modifiedAt: Long?)

    suspend fun setNotes(chapterId: Long, notes: List<StoredNote>, modifiedAt: Long?)

    suspend fun setHistory(history: StoredHistory)

    /** Inserts [group] and its members, whose keys no other group holds, and returns its id. */
    suspend fun insertMergedGroup(group: StoredMergedGroup): Long

    /** Writes every value and member of [group] over the row with its id. */
    suspend fun updateMergedGroup(group: StoredMergedGroup)

    /** Takes the member with that key out of whatever group holds it; the group's clock stays. */
    suspend fun removeMergedMember(source: Long, url: String)
}

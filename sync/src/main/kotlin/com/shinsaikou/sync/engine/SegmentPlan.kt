package com.shinsaikou.sync.engine

import com.shinsaikou.domain.sync.model.StoredCategory
import com.shinsaikou.domain.sync.model.SyncChange
import com.shinsaikou.domain.sync.model.SyncChangeType
import com.shinsaikou.domain.sync.model.SyncDeletedKey
import com.shinsaikou.domain.sync.model.SyncEntity
import com.shinsaikou.domain.sync.model.SyncPushRequest
import com.shinsaikou.domain.sync.model.SyncPushRows
import com.shinsaikou.sync.format.ChapterKey
import com.shinsaikou.sync.format.ChapterRemovedRow
import com.shinsaikou.sync.format.MangaKey
import com.shinsaikou.sync.format.RowKind
import com.shinsaikou.sync.format.SyncRow

/**
 * One push batch's fold (phase 3 unit E2): the log rows of the batch, one per (entity, id), the
 * highest `seq` of each (R85 item 1). [request] names the rows the segment is read from.
 *
 * A `delete` with a recorded key is a hard delete. A chapter's travels as a `chapter_removed`
 * line; no row kind carries a hard-deleted manga, category or history row, so those are skipped,
 * as a `delete` with no recorded key is when its row is gone (Q5). A `delete` with no key and a row
 * still stored is a soft delete, read like an upsert. A history `delete` travels as nothing: a
 * cleared history does not propagate (Q6).
 */
internal class SegmentFold(batch: List<SyncChange>) {

    val changes: List<SyncChange> = batch.groupBy { it.entity to it.entityId }
        .map { (_, rows) -> rows.maxBy { it.seq } }
        .sortedBy { it.seq }

    private fun ids(entity: SyncEntity): Set<Long> =
        changes.filter { it.entity == entity && it.deletedKey == null }.map { it.entityId }.toSet()

    val categoryIds: Set<Long> = ids(SyncEntity.CATEGORY)

    val removedChapters: Set<SyncDeletedKey.Chapter> = changes
        .filter { it.entity == SyncEntity.CHAPTER && it.changeType == SyncChangeType.DELETE }
        .mapNotNull { it.deletedKey as? SyncDeletedKey.Chapter }
        .toSet()

    val request = SyncPushRequest(
        mangaIds = ids(SyncEntity.MANGA),
        categoryMembershipMangaIds = ids(SyncEntity.MANGA_CATEGORY),
        exclusionMangaIds = ids(SyncEntity.EXCLUDED_SCANLATOR),
        priorityMangaIds = ids(SyncEntity.SCANLATOR_PRIORITY),
        trackMangaIds = ids(SyncEntity.TRACK),
        chapterIds = ids(SyncEntity.CHAPTER),
        noteChapterIds = ids(SyncEntity.BOOKMARK_NOTE),
        historyChapterIds = changes
            .filter { it.entity == SyncEntity.HISTORY && it.changeType == SyncChangeType.UPSERT }
            .map { it.entityId }
            .toSet(),
        removedChapters = removedChapters,
        mergedGroupIds = ids(SyncEntity.MERGED_GROUP),
    )
}

/**
 * The lines of one segment, planned whole before its header is written, since the header declares
 * every count (R81 item 5). The file is self-contained (Q4): every category line comes first, each
 * after its parent's, then each manga's line followed by the lines that belong to it. A chapter's
 * line also precedes its history and notes, which a receiver cannot store without the chapter
 * (R81 carry-over 5). The merged group lines come last; they want no parent. A row the fold names
 * that is gone by now is left out.
 */
internal fun planSegment(fold: SegmentFold, stored: SyncPushRows): List<SyncRow> {
    val request = fold.request
    val categoriesByKey = stored.categories.values.associateBy { it.syncKey }
    val mangaIds = sortedSetOf<Long>()
    mangaIds += request.mangaIds + request.categoryMembershipMangaIds + request.exclusionMangaIds +
        request.priorityMangaIds + request.trackMangaIds
    val chapterIds = (request.chapterIds + request.noteChapterIds + request.historyChapterIds)
        .filter { it in stored.chapters }
        .toSortedSet()
    chapterIds.forEach { mangaIds += stored.chapters.getValue(it).mangaId }

    // A removal whose manga is gone cannot be written (R81 carry-over 5), and a chapter stored again
    // under the same key since is written as the row it is.
    val storedChapterKeys = chapterIds.map { stored.chapters.getValue(it) }.mapNotNull { chapter ->
        stored.mangas[chapter.mangaId]?.let { ChapterKey(it.key, chapter.url) }
    }.toSet()
    val mangaIdsByKey = stored.mangas.values.associate { it.key to it.id }
    val removals = fold.removedChapters
        .map { ChapterKey(MangaKey(it.source, it.mangaUrl), it.url) }
        .filter { it.manga in mangaIdsByKey && it !in storedChapterKeys }
    removals.forEach { mangaIds += mangaIdsByKey.getValue(it.manga) }
    val removedAt = fold.changes.mapNotNull { change ->
        (change.deletedKey as? SyncDeletedKey.Chapter)?.let {
            ChapterKey(MangaKey(it.source, it.mangaUrl), it.url) to change.changedAt
        }
    }.toMap()

    val categoryKeys = linkedSetOf<String>()
    fold.categoryIds.mapNotNullTo(categoryKeys) { stored.categories[it]?.syncKey }
    request.categoryMembershipMangaIds.forEach { id -> stored.categoryKeys[id]?.let(categoryKeys::addAll) }

    val rows = mutableListOf<SyncRow>()
    categoriesParentFirst(categoryKeys, categoriesByKey).forEach { rows += it.toRecord().toRow() }

    for (mangaId in mangaIds) {
        val manga = stored.mangas[mangaId] ?: continue
        val record = manga.toRecord(
            categories = stored.categoryKeys[mangaId].orEmpty(),
            excludedScanlators = stored.excludedScanlators[mangaId].orEmpty(),
            scanlatorPriority = stored.scanlatorPriority[mangaId].orEmpty(),
            tracks = stored.tracks[mangaId].orEmpty(),
        )
        rows += record.toRow()
        if (mangaId in stored.categoryKeys) rows += record.categoriesRow()
        if (mangaId in stored.excludedScanlators) rows += record.excludedScanlatorsRow()
        if (mangaId in stored.scanlatorPriority) rows += record.scanlatorPriorityRow()
        if (mangaId in stored.tracks) rows += record.tracksRow()

        for (chapterId in chapterIds) {
            val chapter = stored.chapters.getValue(chapterId)
            if (chapter.mangaId != mangaId) continue
            val chapterRecord = chapter.toRecord(record.key, stored.notes[chapterId].orEmpty())
            rows += chapterRecord.toRow(chapter.sourceOrder)
            stored.history[chapterId]?.let { rows += it.toRecord(chapterRecord.key).toRow() }
            if (chapterId in stored.notes) rows += chapterRecord.notesRow()
        }
        removals.filter { it.manga == record.key }.forEach { key ->
            rows += ChapterRemovedRow(key = key, removedAt = removedAt.getValue(key))
        }
    }
    stored.mergedGroups.values.sortedBy { it.id }.forEach { rows += it.toRecord().toRow() }
    return rows
}

/**
 * One manga of a full export or a snapshot, whole: its line, each collection that holds something
 * or has a clock, then every chapter, soft-deleted ones included, each followed by its history and
 * notes. The categories its set names are written before it by the caller. Null when the manga is
 * gone since its id was listed.
 */
internal fun planLibraryManga(mangaId: Long, stored: SyncPushRows): List<SyncRow>? {
    val manga = stored.mangas[mangaId] ?: return null
    val record = manga.toRecord(
        categories = stored.categoryKeys[mangaId].orEmpty(),
        excludedScanlators = stored.excludedScanlators[mangaId].orEmpty(),
        scanlatorPriority = stored.scanlatorPriority[mangaId].orEmpty(),
        tracks = stored.tracks[mangaId].orEmpty(),
    )
    val rows = mutableListOf<SyncRow>(record.toRow())
    if (record.categories.isNotEmpty() || record.categoriesModifiedAt != null) rows += record.categoriesRow()
    if (record.excludedScanlators.isNotEmpty() || record.exclusionsModifiedAt != null) {
        rows += record.excludedScanlatorsRow()
    }
    if (record.scanlatorPriority.isNotEmpty() || record.priorityModifiedAt != null) {
        rows += record.scanlatorPriorityRow()
    }
    if (record.tracks.isNotEmpty() || record.trackersModifiedAt != null) rows += record.tracksRow()
    stored.chapters.values.filter { it.mangaId == mangaId }.sortedBy { it.id }.forEach { chapter ->
        val notes = stored.notes[chapter.id].orEmpty()
        val chapterRecord = chapter.toRecord(record.key, notes)
        rows += chapterRecord.toRow(chapter.sourceOrder)
        stored.history[chapter.id]?.let { rows += it.toRecord(chapterRecord.key).toRow() }
        if (notes.isNotEmpty() || chapter.bookmarkNotesModifiedAt != null) rows += chapterRecord.notesRow()
    }
    return rows
}

/**
 * The category lines a file of whole manga opens with: every category [keys] names and each
 * ancestor, each after its parent.
 */
internal fun planCategories(keys: Set<String>, categories: Collection<StoredCategory>): List<SyncRow> =
    categoriesParentFirst(keys, categories.associateBy { it.syncKey }).map { it.toRecord().toRow() }

/** How many lines of each kind [rows] holds, for the header. */
internal fun countRows(rows: List<SyncRow>): Map<RowKind, Int> = rows.groupingBy { it.kind }.eachCount()

/**
 * [keys] and every ancestor of theirs, each after its parent. A parent this device does not hold
 * is left out, and a cycle of parents, which no screen can make, is broken where it closes.
 */
private fun categoriesParentFirst(keys: Set<String>, byKey: Map<String, StoredCategory>): List<StoredCategory> {
    val ordered = linkedMapOf<String, StoredCategory>()
    val visiting = mutableSetOf<String>()
    fun visit(key: String) {
        if (key in ordered || !visiting.add(key)) return
        val category = byKey[key] ?: return
        category.parentKey?.let(::visit)
        ordered[key] = category
    }
    keys.forEach(::visit)
    return ordered.values.toList()
}

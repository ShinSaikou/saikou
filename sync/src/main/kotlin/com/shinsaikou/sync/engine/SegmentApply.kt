package com.shinsaikou.sync.engine

import com.shinsaikou.domain.source.repository.SourceAliasRepository
import com.shinsaikou.domain.sync.model.StoredChapter
import com.shinsaikou.domain.sync.model.StoredManga
import com.shinsaikou.domain.sync.model.SyncEntity
import com.shinsaikou.domain.sync.repository.SyncApplyScope
import com.shinsaikou.domain.sync.repository.SyncStore
import com.shinsaikou.sync.MergeEngine
import com.shinsaikou.sync.SyncConflict
import com.shinsaikou.sync.format.BookmarkNotesRow
import com.shinsaikou.sync.format.CategoryRow
import com.shinsaikou.sync.format.ChapterKey
import com.shinsaikou.sync.format.ChapterRemovedRow
import com.shinsaikou.sync.format.ChapterRow
import com.shinsaikou.sync.format.ExcludedScanlatorsRow
import com.shinsaikou.sync.format.HistoryRow
import com.shinsaikou.sync.format.MangaCategoriesRow
import com.shinsaikou.sync.format.MangaKey
import com.shinsaikou.sync.format.MangaRow
import com.shinsaikou.sync.format.RowKind
import com.shinsaikou.sync.format.ScanlatorPriorityRow
import com.shinsaikou.sync.format.SyncRow
import com.shinsaikou.sync.format.TracksRow
import com.shinsaikou.sync.maxClock
import com.shinsaikou.sync.merge.ChapterRecord

/**
 * Applies one file's rows, or one chunk of an export part or a snapshot, to this device's database
 * as one transaction (phase 3 unit E2), in the file's order, which puts every parent first. Each row
 * is matched by natural key, a source id resolved through the device's aliases first as a restore
 * resolves it; merged through [MergeEngine]; and written only when the merge changed the stored
 * row, clocks included.
 *
 * A row whose parent this device does not hold (a hand-made file, R81 carry-over 5) is skipped and
 * counted, since this device then lacks a row its cursor claims (Q3); a `chapter_removed` for a
 * chapter it never had changes nothing and is not a skip. A merge that took a value from the remote
 * side of a row this device changed since it last pulled ([LocalChanges]) is reported as a
 * conflict.
 *
 * The result also names the live chapters inserted and the entries that arrived in the library,
 * inserted or turned favourite, so the pull can hand new chapters to auto-download once the
 * transaction has committed (phase 3 unit AD).
 *
 * A chapter inserted into an entry this device already held takes the sender's source order, which
 * counts from the top of the sender's list while the entry's other rows keep this device's own
 * count from its last update; on the default by-source sort the two sets would interleave. So the
 * apply ends by renumbering each such entry, as [Scope.renumberSourceOrders] says.
 */
internal class SegmentApply(
    private val store: SyncStore,
    private val sourceAliases: SourceAliasRepository,
    private val merge: MergeEngine,
) {

    suspend fun apply(rows: List<SyncRow>, local: LocalChanges = LocalChanges.NONE): ApplyResult {
        // Resolved before the transaction opens: the walk is its own reads, one per hop.
        val sources = rows.mapNotNull { it.parentManga?.source }.toSet()
            .associateWith { sourceAliases.resolve(it) }
        return store.apply {
            val scope = Scope(this, local) { key -> key.copy(source = sources[key.source] ?: key.source) }
            rows.forEach { scope.apply(it) }
            scope.renumberSourceOrders()
            ApplyResult(
                written = scope.written,
                skipped = scope.skipped,
                conflicts = scope.conflicts,
                insertedChapters = scope.insertedChapters,
                arrivedManga = scope.arrivedManga,
            )
        }
    }

    private inner class Scope(
        private val db: SyncApplyScope,
        private val local: LocalChanges,
        private val resolve: (MangaKey) -> MangaKey,
    ) {
        var written = 0
        var skipped = 0
        val conflicts = mutableListOf<SyncConflict>()
        val insertedChapters = mutableListOf<InsertedChapter>()
        val arrivedManga = mutableSetOf<MangaKey>()

        /** The entries this apply inserted, whose chapters all carry the sender's order. */
        private val insertedMangaIds = mutableSetOf<Long>()

        /** The live chapters inserted into an entry held before, by entry id: chapter id to the wire's order. */
        private val pulledOrders = mutableMapOf<Long, MutableMap<Long, Long>>()

        suspend fun apply(row: SyncRow) {
            val wrote = when (row) {
                is CategoryRow -> category(row)
                is MangaRow -> manga(row)
                is MangaCategoriesRow -> categories(row)
                is ExcludedScanlatorsRow -> excludedScanlators(row)
                is ScanlatorPriorityRow -> scanlatorPriority(row)
                is TracksRow -> tracks(row)
                is ChapterRow -> chapter(row)
                is ChapterRemovedRow -> chapterRemoved(row)
                is HistoryRow -> history(row)
                is BookmarkNotesRow -> notes(row)
            }
            if (wrote) written++
        }

        /**
         * Notes a conflict when [fields] took a remote value on a row this device changed since it
         * last pulled: the log names the row by [entity] and [localId].
         */
        private fun report(
            kind: RowKind,
            entity: SyncEntity,
            localId: Long,
            fields: Set<String>,
            title: String,
            chapter: String? = null,
        ) {
            if (fields.isNotEmpty() && local.changed(entity, localId)) {
                conflicts += SyncConflict(kind = kind, title = title, chapter = chapter, fields = fields)
            }
        }

        private suspend fun category(row: CategoryRow): Boolean {
            val remote = row.toRecord()
            val local = db.category(row.key)
            if (local == null) {
                db.insertCategory(remote.toStored(id = 0))
                return true
            }
            val merged = merge.mergeCategory(local.toRecord(), remote)
            if (!merged.changed) return false
            db.updateCategory(merged.value.toStored(local.id))
            // As deleting a category drops its memberships locally (Q6).
            if (local.deletedAt == null && merged.value.deletedAt != null) db.dropMemberships(local.id)
            report(RowKind.CATEGORY, SyncEntity.CATEGORY, local.id, merged.fieldsFromRemote, local.name)
            return true
        }

        private suspend fun manga(row: MangaRow): Boolean {
            val key = resolve(row.key)
            val local = db.manga(key.source, key.url)
            if (local == null) {
                insertedMangaIds += db.insertManga(row.toRecord(key).toStored(id = 0))
                arrivedManga += key
                return true
            }
            // The collections merge from their own lines; here both sides carry the local ones.
            val localRecord = local.toRecord()
            val remote = row.toRecord(key).copy(
                categoriesModifiedAt = localRecord.categoriesModifiedAt,
                exclusionsModifiedAt = localRecord.exclusionsModifiedAt,
                priorityModifiedAt = localRecord.priorityModifiedAt,
                trackersModifiedAt = localRecord.trackersModifiedAt,
            )
            val merged = merge.mergeManga(localRecord, remote)
            if (!merged.changed) return false
            db.updateManga(merged.value.toStored(local.id))
            if (!local.favorite && merged.value.favorite) arrivedManga += key
            report(RowKind.MANGA, SyncEntity.MANGA, local.id, merged.fieldsFromRemote, local.displayTitle)
            return true
        }

        private suspend fun categories(row: MangaCategoriesRow): Boolean {
            val manga = mangaOf(row.manga) ?: return skip()
            val local = db.categoryKeys(manga.id)
            // A membership to a category this device holds deleted, or not at all, is dropped
            // before the merge, as the write would drop it after (Q6).
            val remote = row.categories.filterTo(mutableSetOf()) { key ->
                db.category(key)?.let { it.deletedAt == null } == true
            }
            val merged = merge.mergeMangaCategories(local, remote, manga.categoriesModifiedAt, row.categoriesModifiedAt)
            val clock = maxClock(manga.categoriesModifiedAt, row.categoriesModifiedAt)
            if (!merged.changed && clock == manga.categoriesModifiedAt) return false
            db.setCategoryKeys(manga.id, merged.value, clock)
            report(
                RowKind.MANGA_CATEGORIES, SyncEntity.MANGA_CATEGORY, manga.id, merged.fieldsFromRemote,
                manga.displayTitle,
            )
            return true
        }

        private suspend fun excludedScanlators(row: ExcludedScanlatorsRow): Boolean {
            val manga = mangaOf(row.manga) ?: return skip()
            val merged = merge.mergeExcludedScanlators(
                db.excludedScanlators(manga.id), row.scanlators, manga.exclusionsModifiedAt, row.exclusionsModifiedAt,
            )
            val clock = maxClock(manga.exclusionsModifiedAt, row.exclusionsModifiedAt)
            if (!merged.changed && clock == manga.exclusionsModifiedAt) return false
            db.setExcludedScanlators(manga.id, merged.value, clock)
            report(
                RowKind.EXCLUDED_SCANLATORS, SyncEntity.EXCLUDED_SCANLATOR, manga.id, merged.fieldsFromRemote,
                manga.displayTitle,
            )
            return true
        }

        private suspend fun scanlatorPriority(row: ScanlatorPriorityRow): Boolean {
            val manga = mangaOf(row.manga) ?: return skip()
            val merged = merge.mergeScanlatorPriority(
                db.scanlatorPriority(manga.id), row.toRanking(), manga.priorityModifiedAt, row.priorityModifiedAt,
            )
            val clock = maxClock(manga.priorityModifiedAt, row.priorityModifiedAt)
            if (!merged.changed && clock == manga.priorityModifiedAt) return false
            db.setScanlatorPriority(manga.id, merged.value, clock)
            report(
                RowKind.SCANLATOR_PRIORITY, SyncEntity.SCANLATOR_PRIORITY, manga.id, merged.fieldsFromRemote,
                manga.displayTitle,
            )
            return true
        }

        private suspend fun tracks(row: TracksRow): Boolean {
            val manga = mangaOf(row.manga) ?: return skip()
            val merged = merge.mergeTracks(
                db.tracks(manga.id).toTrackRecords(), row.toRecords(), manga.trackersModifiedAt, row.trackersModifiedAt,
            )
            val clock = maxClock(manga.trackersModifiedAt, row.trackersModifiedAt)
            if (!merged.changed && clock == manga.trackersModifiedAt) return false
            db.setTracks(manga.id, merged.value.toStoredTracks(), clock)
            report(RowKind.TRACKS, SyncEntity.TRACK, manga.id, merged.fieldsFromRemote, manga.displayTitle)
            return true
        }

        private suspend fun chapter(row: ChapterRow): Boolean {
            val manga = mangaOf(row.key.manga) ?: return skip()
            val key = ChapterKey(manga.key, row.key.url)
            val local = db.chapter(manga.id, key.url)
            if (local == null) {
                // A chapter this device dropped and has not pushed yet is its tombstone at the time
                // the log recorded, as the chapter_removed line it will push is on the other side;
                // inserting it live would keep it here and delete it there.
                val remote = row.toRecord(key)
                val inserted = this.local.removedAt(key)?.let { removedAt ->
                    merge.mergeChapter(remote.copy(deletedAt = removedAt, restoredAt = null), remote).value
                } ?: remote
                val id = db.insertChapter(inserted.toStored(id = 0, mangaId = manga.id, sourceOrder = row.sourceOrder))
                if (inserted.deletedAt == null) {
                    insertedChapters += InsertedChapter(manga.key, id)
                    if (manga.id !in insertedMangaIds) {
                        pulledOrders.getOrPut(manga.id) { mutableMapOf() }[id] = row.sourceOrder
                    }
                }
                return true
            }
            // The notes merge from their own line; here both sides carry the local ones.
            val localRecord = local.toRecord(manga.key)
            val remote = row.toRecord(key).copy(bookmarkNotesModifiedAt = localRecord.bookmarkNotesModifiedAt)
            return writeChapter(RowKind.CHAPTER, manga, local, merge.mergeChapter(localRecord, remote))
        }

        /** A tombstone at [ChapterRemovedRow.removedAt] against this device's own restore (R81 item 4). */
        private suspend fun chapterRemoved(row: ChapterRemovedRow): Boolean {
            val manga = mangaOf(row.key.manga) ?: return false
            val local = db.chapter(manga.id, row.key.url) ?: return false
            val localRecord = local.toRecord(manga.key)
            val remote = localRecord.copy(deletedAt = row.removedAt, restoredAt = null)
            return writeChapter(RowKind.CHAPTER_REMOVED, manga, local, merge.mergeChapter(localRecord, remote))
        }

        private suspend fun writeChapter(
            kind: RowKind,
            manga: StoredManga,
            local: StoredChapter,
            merged: MergeEngine.Merged<ChapterRecord>,
        ): Boolean {
            if (!merged.changed) return false
            db.updateChapter(merged.value.toStored(local.id, local.mangaId, local.sourceOrder))
            report(kind, SyncEntity.CHAPTER, local.id, merged.fieldsFromRemote, manga.displayTitle, local.name)
            return true
        }

        private suspend fun history(row: HistoryRow): Boolean {
            val (manga, chapter) = chapterOf(row.chapter) ?: return skip()
            val key = ChapterKey(manga.key, chapter.url)
            val local = db.history(chapter.id)
            val merged = merge.mergeHistory(local?.toRecord(key), row.toRecord(key))
            if (!merged.changed) return false
            db.setHistory(merged.value.toStored(chapter.id))
            // A chapter with no history here has nothing of this device's to lose.
            if (local != null) {
                report(
                    RowKind.HISTORY, SyncEntity.HISTORY, chapter.id, merged.fieldsFromRemote, manga.displayTitle,
                    chapter.name,
                )
            }
            return true
        }

        private suspend fun notes(row: BookmarkNotesRow): Boolean {
            val (manga, chapter) = chapterOf(row.chapter) ?: return skip()
            val merged = merge.mergeBookmarkNotes(
                db.notes(chapter.id).toNoteRecords(), row.toRecords(), chapter.bookmarkNotesModifiedAt, row.bookmarkNotesModifiedAt,
            )
            val clock = maxClock(chapter.bookmarkNotesModifiedAt, row.bookmarkNotesModifiedAt)
            if (!merged.changed && clock == chapter.bookmarkNotesModifiedAt) return false
            db.setNotes(chapter.id, merged.value.toStoredNotes(), clock)
            report(
                RowKind.BOOKMARK_NOTES, SyncEntity.BOOKMARK_NOTE, chapter.id, merged.fieldsFromRemote,
                manga.displayTitle, chapter.name,
            )
            return true
        }

        /**
         * Numbers the live chapters of each entry that took new ones 0 upwards again, when two of
         * them now share a number: the sender counted from another top than this device did, as
         * after a library update on the sender. While every number is still its own, the sender's
         * numbers sort the new rows among the old as well as any would, and nothing is written.
         *
         * The rows this device held keep their order; each new one goes where the sender's number
         * puts it, after as many chapters as came before it on the sender (the top of the list,
         * for new chapters), the lowest number first. A local renumbering, as a library update's
         * is: only rows whose place moved are written, and nothing is logged.
         */
        suspend fun renumberSourceOrders() {
            pulledOrders.forEach { (mangaId, pulled) ->
                val stored = db.chapterSourceOrders(mangaId)
                if (stored.values.toSet().size == stored.size) return@forEach
                val ordered = stored.keys.filterNot { it in pulled }
                    .sortedWith(compareBy({ stored.getValue(it) }, { it }))
                    .toMutableList()
                pulled.entries.sortedWith(compareBy({ it.value }, { it.key })).forEach { (id, order) ->
                    // Removed again by a later line of the same apply.
                    if (id !in stored) return@forEach
                    ordered.add(order.coerceIn(0L, ordered.size.toLong()).toInt(), id)
                }
                val moved = ordered.withIndex()
                    .filter { (place, id) -> stored[id] != place.toLong() }
                    .associate { (place, id) -> id to place.toLong() }
                if (moved.isNotEmpty()) db.setSourceOrders(moved)
            }
        }

        private fun skip(): Boolean {
            skipped++
            return false
        }

        private suspend fun mangaOf(key: MangaKey): StoredManga? = resolve(key).let { db.manga(it.source, it.url) }

        private suspend fun chapterOf(key: ChapterKey): Pair<StoredManga, StoredChapter>? {
            val manga = mangaOf(key.manga) ?: return null
            val chapter = db.chapter(manga.id, key.url) ?: return null
            return manga to chapter
        }
    }
}

/**
 * What one apply did: [written] rows changed, [skipped] rows could not be stored for want of a
 * parent, and [conflicts] name the rows where a remote value replaced one this device had changed.
 * [insertedChapters] are the live chapter rows it inserted, and [arrivedManga] the entries it
 * inserted or turned favourite, by the key this device holds them under.
 */
internal data class ApplyResult(
    val written: Int,
    val skipped: Int,
    val conflicts: List<SyncConflict>,
    val insertedChapters: List<InsertedChapter> = emptyList(),
    val arrivedManga: Set<MangaKey> = emptySet(),
) {
    operator fun plus(other: ApplyResult) = ApplyResult(
        written + other.written,
        skipped + other.skipped,
        conflicts + other.conflicts,
        insertedChapters + other.insertedChapters,
        arrivedManga + other.arrivedManga,
    )

    /** The same result with no chapter counted as new: a snapshot's, or a first pull's export. */
    fun withoutInsertedChapters(): ApplyResult = copy(insertedChapters = emptyList())

    companion object {
        val NONE = ApplyResult(written = 0, skipped = 0, conflicts = emptyList())
    }
}

/** A chapter row an apply inserted: its entry's key here, and its new local id. */
internal data class InsertedChapter(val manga: MangaKey, val chapterId: Long)

/**
 * The rows this device changed since its last pull, as the change log names them after
 * `lastPushedSeq` (every older row was pushed and pruned): by entity, the local id each log row
 * carries, which for a child collection is its parent's; and the chapters it hard-deleted and has
 * not pushed yet, by key, at the time their log rows recorded.
 */
internal class LocalChanges(
    private val ids: Map<SyncEntity, Set<Long>>,
    private val removedChapters: Map<ChapterKey, Long> = emptyMap(),
) {

    fun changed(entity: SyncEntity, localId: Long): Boolean = ids[entity]?.contains(localId) == true

    /** When this device hard-deleted the chapter at [key], if it did since its last push. */
    fun removedAt(key: ChapterKey): Long? = removedChapters[key]

    companion object {
        val NONE = LocalChanges(emptyMap())
    }
}

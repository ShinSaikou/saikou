package com.shinsaikou.sync.engine

import com.shinsaikou.domain.sync.model.StoredCategory
import com.shinsaikou.domain.sync.model.SyncChange
import com.shinsaikou.domain.sync.model.SyncEntity
import com.shinsaikou.domain.sync.repository.SyncChangeLogRepository
import com.shinsaikou.domain.sync.repository.SyncStore
import com.shinsaikou.sync.ExportProgress
import com.shinsaikou.sync.SyncBackend
import com.shinsaikou.sync.SyncContent
import com.shinsaikou.sync.SyncPreferences
import com.shinsaikou.sync.format.ExportDone
import com.shinsaikou.sync.format.MangaCategoriesRow
import com.shinsaikou.sync.format.SyncFormat
import com.shinsaikou.sync.format.SyncHeader
import com.shinsaikou.sync.format.SyncLayout
import com.shinsaikou.sync.format.SyncRow
import com.shinsaikou.sync.format.SyncScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.uuid.Uuid

/**
 * One run's push (phase 3 unit E2). A device whose last full export was not to this backend makes
 * one first (Q3): its first push, its first after a restore, its first after a backend change.
 * Then the log after `lastPushedSeq`, one segment per batch of at most [SyncFormat.MAX_RAW_ROWS]
 * rows, a full batch ending before an entry its last row would split (phase 4 unit NB), until the log
 * holds nothing newer. Every file is read back before anything moves. Each step's time goes to
 * [timings].
 */
internal class SyncPush(
    private val backend: SyncBackend,
    private val deviceId: String,
    private val store: SyncStore,
    private val changeLog: SyncChangeLogRepository,
    private val preferences: SyncPreferences,
    private val timings: SyncTimings,
) {
    var segmentsPushed: Int = 0
        private set
    var exported: Boolean = false
        private set

    suspend fun run() {
        if (preferences.exportedBackend.get() != backend.id) {
            timings.time(SyncTimings.Step.EXPORT) { export() }
            exported = true
        }
        timings.time(SyncTimings.Step.SEGMENTS) { pushSegments() }
    }

    /**
     * Each segment is planned whole, written, and read back; only a read-back that returns the bytes
     * written moves `lastPushedSeq` and prunes the log up to it.
     *
     * A full batch ends before the rows of the entries its boundary would split ([wholeEntries]),
     * because an entry must not arrive on another device in one file and its chapters in a later
     * one: that device's pull of the later file counts those chapters as new, since the entry no
     * longer arrives in that run (R97 item 3), and its library update between the two takes every
     * chapter it lacks for new. Either way it would download the entry's back catalogue (phase 4
     * unit NB). The shorter batch is planned and named as any batch is (R81 item 7).
     */
    private suspend fun pushSegments() {
        while (true) {
            val afterSeq = preferences.lastPushedSeq.get()
            val batch = wholeEntries(changeLog.getChangesAfter(afterSeq, SyncFormat.MAX_RAW_ROWS.toLong()))
            if (batch.isEmpty()) return
            val lastSeq = batch.last().seq

            val fold = SegmentFold(batch)
            val rows = planSegment(fold, store.readForPush(fold.request))
            val header = SyncHeader(
                format = SyncFormat.CURRENT,
                deviceId = deviceId,
                scope = SyncScope.Segment(afterSeq = afterSeq, lastSeq = lastSeq, rawRows = batch.size),
                counts = countRows(rows),
            )
            val content = SyncContent(encodeFile(header, rows))
            backend.createVerified(SyncLayout.segmentPath(deviceId, afterSeq, lastSeq), content, timings)
            preferences.lastPushedSeq.set(lastSeq)
            changeLog.prune(lastSeq)
            segmentsPushed++
        }
    }

    /**
     * [batch], cut before its first row owned by an entry of its boundary. Only a batch of
     * [SyncFormat.MAX_RAW_ROWS] rows has a boundary, and its entries are the owners of the batch's
     * last row and of the one row after it: both, because one library add's rows are not one run of
     * one entry (a migration logs the target's categories, the old entry, the target's chapters with
     * progress, the target, then its other chapters). A manga's own row and the rows of its four
     * collections are owned by their entity id; an unkeyed chapter, history or bookmark-note row by
     * its chapter's manga; a keyed chapter or history delete and a category row by nothing. A cut
     * at the batch's first row, or a batch with no row after it, keeps the batch whole, so every
     * push advances and an entry whose own rows pass the cap is still split, as before.
     */
    private suspend fun wholeEntries(batch: List<SyncChange>): List<SyncChange> {
        if (batch.size < SyncFormat.MAX_RAW_ROWS) return batch
        val next = changeLog.getChangesAfter(batch.last().seq, 1).firstOrNull() ?: return batch
        val chapterOwners = store.chapterMangaIds(
            (batch + next).filter { it.ownedByChapter }.mapTo(mutableSetOf()) { it.entityId },
        )

        fun owner(change: SyncChange): Long? = when {
            change.ownedByChapter -> chapterOwners[change.entityId]
            change.entity in OWNED_BY_ENTITY_ID -> change.entityId
            else -> null
        }

        val boundary = setOfNotNull(owner(batch.last()), owner(next))
        val cut = batch.indexOfFirst { change -> owner(change)?.let { it in boundary } == true }
        return if (cut > 0) batch.subList(0, cut) else batch
    }

    private val SyncChange.ownedByChapter: Boolean
        get() = entity in OWNED_BY_CHAPTER && deletedKey == null

    /**
     * The whole library as `full-<exportId>/part-<n>`, then `done.json` (Q3). Starting one fixes its
     * `lastSeq`, prunes the log up to it and moves `lastPushedSeq` there, since the export carries
     * every row those log rows named. Each part holds whole manga in id order, about [PART_LINES]
     * lines, and the first also every keyed category. A restore or another backend drops the
     * progress and starts a new export.
     *
     * Part 1 is written first and alone: it makes the export's directory, so no two uploads race to
     * create it, and it holds every keyed category. After it, up to [PARTS_IN_FLIGHT] parts upload at
     * once while the next is planned and encoded (phase 5 unit SC3). The saved progress is always
     * contiguous: the highest part N such that parts 1 to N have all been written and verified, with
     * N's last manga, since uploads are awaited in part order and each one moves the progress only
     * once every part before it has. A pusher that restarts resumes the same export id at the manga
     * after N and writes any part above N again at the same path, which no reader trusts while
     * `done.json` is not there (R89). `done.json` is written only after every part's verified write
     * has returned.
     */
    private suspend fun export() {
        var progress = preferences.exportProgress.get()?.takeIf { it.backendId == backend.id }
            ?: ExportProgress(
                backendId = backend.id,
                exportId = Uuid.random().toString(),
                lastSeq = store.beginExport(preferences.lastPushedSeq.get()),
                parts = 0,
                lastMangaId = 0,
            ).also(preferences.exportProgress::set)
        preferences.lastPushedSeq.set(progress.lastSeq)

        if (progress.parts == 0) {
            // The first part is written even for an empty library, so planning it never ends the export.
            val first = checkNotNull(encodedPart(progress)) { "An export's first part is always planned" }
            backend.createVerified(first.path, SyncContent(first.bytes), timings)
            progress = saved(progress, first)
        }
        progress = coroutineScope {
            var contiguous = progress
            var planned = progress
            val uploads = ArrayDeque<Upload>()
            while (true) {
                val part = encodedPart(planned) ?: break
                if (uploads.size == PARTS_IN_FLIGHT) contiguous = landed(uploads.removeFirst(), contiguous)
                uploads += Upload(part, async { backend.createVerified(part.path, SyncContent(part.bytes), timings) })
                planned = planned.copy(parts = part.number, lastMangaId = part.lastMangaId)
            }
            while (uploads.isNotEmpty()) contiguous = landed(uploads.removeFirst(), contiguous)
            contiguous
        }

        val done = ExportDone(format = SyncFormat.CURRENT, parts = progress.parts, lastSeq = progress.lastSeq)
        timings.time(SyncTimings.Step.EXPORT_DONE) {
            val content = SyncContent(SyncLayout.encodeDone(done))
            backend.createVerified(SyncLayout.exportDonePath(deviceId, progress.exportId), content, timings)
        }
        preferences.exportedBackend.set(backend.id)
        preferences.exportProgress.delete()
    }

    private class Part(val rows: List<SyncRow>, val lastMangaId: Long)

    /** Part [number] of an export, planned and encoded, to be written at [path]. */
    private class EncodedPart(val number: Int, val path: String, val bytes: ByteArray, val lastMangaId: Long)

    /** [part]'s verified write, under way. */
    private class Upload(val part: EncodedPart, val write: Deferred<Unit>)

    /** The part after [planned], planned and encoded; null once every manga is in a part. */
    private suspend fun encodedPart(planned: ExportProgress): EncodedPart? {
        val part = timings.time(SyncTimings.Step.EXPORT_PLAN) { planPart(planned) } ?: return null
        val number = planned.parts + 1
        val header = SyncHeader(
            format = SyncFormat.CURRENT,
            deviceId = deviceId,
            scope = SyncScope.ExportPart(exportId = planned.exportId, part = number),
            counts = countRows(part.rows),
        )
        val bytes = timings.time(SyncTimings.Step.EXPORT_ENCODE) { encodeFile(header, part.rows) }
        val path = SyncLayout.exportPartPath(deviceId, planned.exportId, number)
        return EncodedPart(number, path, bytes, part.lastMangaId)
    }

    /**
     * Waits for [upload], the oldest under way, whose part is the one after [contiguous]'s, and
     * saves the progress through it.
     */
    private suspend fun landed(upload: Upload, contiguous: ExportProgress): ExportProgress {
        upload.write.await()
        return saved(contiguous, upload.part)
    }

    /** [progress] moved through [part], the part after its last, and saved. */
    private fun saved(progress: ExportProgress, part: EncodedPart): ExportProgress {
        check(part.number == progress.parts + 1) { "Part ${part.number} would follow part ${progress.parts}" }
        return progress.copy(parts = part.number, lastMangaId = part.lastMangaId).also {
            preferences.exportProgress.set(it)
            timings.exportParts = it.parts
        }
    }

    /**
     * The next part: the manga after [ExportProgress.lastMangaId] until adding one more would pass
     * [PART_LINES] (a part holds at least one), after the categories their sets name. Null once
     * every manga is written; the first part is written even for an empty library, since an export
     * has at least one part (R81 item 8). The first part also ends with every merged group, tombstones
     * included: a group belongs to no entry, and it wants none of them read first.
     */
    private suspend fun planPart(progress: ExportProgress): Part? {
        val blocks = mutableListOf<SyncRow>()
        val categories = mutableMapOf<String, StoredCategory>()
        var lastMangaId = progress.lastMangaId
        paging@ while (true) {
            val ids = store.libraryMangaIds(lastMangaId, EXPORT_PAGE)
            val stored = store.readLibrary(ids)
            stored.categories.values.associateByTo(categories) { it.syncKey }
            if (ids.isEmpty()) break
            for (id in ids) {
                val block = planLibraryManga(id, stored).orEmpty()
                if (blocks.isNotEmpty() && blocks.size + block.size > PART_LINES) break@paging
                blocks += block
                lastMangaId = id
            }
        }
        if (blocks.isEmpty() && progress.parts > 0) return null

        val keys = if (progress.parts == 0) {
            categories.keys
        } else {
            blocks.filterIsInstance<MangaCategoriesRow>().flatMapTo(mutableSetOf()) { it.categories }
        }
        val groups = if (progress.parts == 0) store.mergedGroups().map { it.toRecord().toRow() } else emptyList()
        return Part(planCategories(keys, categories.values) + blocks + groups, lastMangaId)
    }

    private companion object {
        /** Entities whose log row names the manga itself: its row and its four collections. */
        val OWNED_BY_ENTITY_ID = setOf(
            SyncEntity.MANGA,
            SyncEntity.MANGA_CATEGORY,
            SyncEntity.EXCLUDED_SCANLATOR,
            SyncEntity.SCANLATOR_PRIORITY,
            SyncEntity.TRACK,
        )

        /** Entities whose log row names a chapter: the chapter, its history and its bookmark notes. */
        val OWNED_BY_CHAPTER = setOf(SyncEntity.CHAPTER, SyncEntity.HISTORY, SyncEntity.BOOKMARK_NOTE)

        /** Lines one export part aims at; the same bound as a segment's raw rows. */
        const val PART_LINES = SyncFormat.MAX_RAW_ROWS

        /**
         * Export parts uploaded at once after the first (phase 5 unit SC3). Three keeps one of
         * OkHttp's five requests per host free for the push's own listing and reads, and stays
         * inside Drive's per-user request rate; each upload holds one encoded part, a few tens of
         * kB.
         */
        const val PARTS_IN_FLIGHT = 3

        /** Manga read per transaction while planning a part. */
        const val EXPORT_PAGE = 20L
    }
}

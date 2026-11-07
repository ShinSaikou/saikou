package com.shinsaikou.sync.engine

import com.shinsaikou.domain.sync.model.StoredCategory
import com.shinsaikou.domain.sync.repository.SyncStore
import com.shinsaikou.sync.SyncBackend
import com.shinsaikou.sync.SyncContent
import com.shinsaikou.sync.SyncPreferences
import com.shinsaikou.sync.format.ExportFiles
import com.shinsaikou.sync.format.RemoteLayout
import com.shinsaikou.sync.format.SyncChain
import com.shinsaikou.sync.format.SyncFormat
import com.shinsaikou.sync.format.SyncHeader
import com.shinsaikou.sync.format.SyncLayout
import com.shinsaikou.sync.format.SyncScope
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Q3's deletion and compaction, at the end of a run whose pull applied everything it listed and
 * whose push finished (phase 3 unit E2). The caller runs neither for a device that skipped a row
 * or stopped at a gap: its database would lack what its cursors claim (Q3).
 *
 * Time is the server's own: the newest modification time in a listing taken just before, which can
 * only run behind the server's clock, so every age it measures errs toward keeping a file.
 *
 * Deletion comes first. A device deletes only its own files: its unfinished exports but the one in
 * progress, at once, since no reader applies an export without `done.json`; and, once the newest
 * snapshot of the two newest generations is [DELETE_AFTER_MS] old, its segments and finished
 * exports that every snapshot of those generations covers, and its snapshots older than those two
 * generations.
 *
 * Compaction then writes a snapshot of the next generation when the files past the newest
 * snapshot's coverage number [SNAPSHOT_AFTER_FILES], or when any exist and that snapshot (or, with
 * none, the oldest of them) is [SNAPSHOT_EVERY_MS] old. The interval is twice the deletion age, so
 * every device has a week in each cycle to delete what the last snapshot covers before the next
 * one resets the clock.
 */
internal class SyncCompaction(
    private val backend: SyncBackend,
    private val deviceId: String,
    private val store: SyncStore,
    private val preferences: SyncPreferences,
) {
    var filesDeleted: Int = 0
        private set
    var compacted: Boolean = false
        private set

    suspend fun run() {
        val listing = backend.list(SyncLayout.ROOT_PREFIX)
        if (listing.isEmpty()) return
        val now = listing.maxOf { it.modifiedAt }
        val modifiedAt = listing.associate { it.path to it.modifiedAt }
        val layout = SyncChain.group(listing.map { it.path })
        deleteOwn(layout, modifiedAt, now)
        compactIfDue(layout, modifiedAt, now)
    }

    private suspend fun deleteOwn(layout: RemoteLayout, modifiedAt: Map<String, Long>, now: Long) {
        val own = layout.devices[deviceId]
        val inProgress = preferences.exportProgress.get()?.exportId
        own?.exports?.values?.filter { it.donePath == null && it.exportId != inProgress }?.forEach { deleteExport(it) }

        val generations = layout.snapshots.map { it.generation }.distinct().sortedDescending().take(2)
        if (generations.isEmpty()) return
        val recent = layout.snapshots.filter { it.generation in generations }
        if (now - recent.maxOf { modifiedAt.getValue(it.path) } < DELETE_AFTER_MS) return
        val covered = recent.minOf { readCovers(backend, it)[deviceId] ?: SyncChain.NOTHING_APPLIED }

        own?.segments?.filter { it.lastSeq <= covered }?.forEach { delete(it.path) }
        own?.exports?.values?.forEach { export ->
            val done = export.donePath?.let { readDone(it) } ?: return@forEach
            if (done.lastSeq <= covered) deleteExport(export)
        }
        layout.snapshots.filter { it.deviceId == deviceId && it.generation < generations.last() }
            .forEach { delete(it.path) }
    }

    private suspend fun compactIfDue(layout: RemoteLayout, modifiedAt: Map<String, Long>, now: Long) {
        val selected = SyncChain.selectSnapshot(layout.snapshots)
        val covers = selected?.let { readCovers(backend, it) }.orEmpty()
        val uncovered = layout.devices.values.flatMap { device ->
            val covered = covers[device.deviceId] ?: SyncChain.NOTHING_APPLIED
            device.segments.filter { it.lastSeq > covered }.map { it.path } +
                device.exports.values.mapNotNull { export ->
                    val path = export.donePath ?: return@mapNotNull null
                    path.takeIf { readDone(it)?.let { done -> done.lastSeq > covered } == true }
                }
        }
        if (uncovered.isEmpty()) return
        val since = selected?.let { modifiedAt.getValue(it.path) } ?: uncovered.minOf { modifiedAt.getValue(it) }
        if (uncovered.size < SNAPSHOT_AFTER_FILES && now - since < SNAPSHOT_EVERY_MS) return
        writeSnapshot(generation = (layout.snapshots.maxOfOrNull { it.generation } ?: 0) + 1)
    }

    /**
     * The whole library, spooled to disk and encoded once its counts are known, covering every
     * device this one has applied files of through its cursor, and itself through `lastPushedSeq`.
     * A snapshot that does not read back is deleted before the run fails, so no reader picks it.
     */
    private suspend fun writeSnapshot(generation: Long) {
        val covers = preferences.pullCursors.get().filter { (device, seq) -> device != deviceId && seq >= 0 } +
            (deviceId to preferences.lastPushedSeq.get())
        RowSpool().use { spool ->
            val categories = mutableMapOf<String, StoredCategory>()
            store.readLibrary(emptyList()).categories.values.associateByTo(categories) { it.syncKey }
            var after = 0L
            while (true) {
                val ids = store.libraryMangaIds(after, SNAPSHOT_PAGE)
                if (ids.isEmpty()) break
                val stored = store.readLibrary(ids)
                stored.categories.values.associateByTo(categories) { it.syncKey }
                ids.forEach { id -> planLibraryManga(id, stored)?.forEach(spool::add) }
                after = ids.last()
            }
            store.mergedGroups().forEach { spool.add(it.toRecord().toRow()) }
            val file = spool.finish(planCategories(categories.keys, categories.values)) { counts ->
                SyncHeader(
                    format = SyncFormat.CURRENT,
                    deviceId = deviceId,
                    scope = SyncScope.Snapshot(generation = generation, covers = covers),
                    counts = counts,
                )
            }
            val path = SyncLayout.snapshotPath(generation, deviceId)
            try {
                backend.createVerified(path, SyncContent(file))
            } catch (e: IOException) {
                runCatching { backend.delete(path) }
                throw e
            }
        }
        compacted = true
    }

    private suspend fun readDone(path: String) = try {
        backend.read(path) { SyncLayout.decodeDone(it.readBytes()) }
    } catch (_: FileNotFoundException) {
        null
    }

    /** `done.json` first, so no reader starts an export whose parts are going. */
    private suspend fun deleteExport(export: ExportFiles) {
        export.donePath?.let { delete(it) }
        export.parts.values.forEach { delete(it) }
    }

    private suspend fun delete(path: String) {
        backend.delete(path)
        filesDeleted++
    }

    companion object {
        const val DAY_MS: Long = 24L * 60 * 60 * 1000

        /** Q3: a device deletes what the two newest generations cover once they are this old. */
        const val DELETE_AFTER_MS: Long = 7 * DAY_MS

        const val SNAPSHOT_EVERY_MS: Long = 14 * DAY_MS

        const val SNAPSHOT_AFTER_FILES: Int = 50

        /** Manga read per transaction while writing a snapshot. */
        const val SNAPSHOT_PAGE: Long = 50L
    }
}

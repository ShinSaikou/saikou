package com.shinsaikou.sync.engine

import com.shinsaikou.sync.PulledExportParts
import com.shinsaikou.sync.SyncBackend
import com.shinsaikou.sync.SyncPreferences
import com.shinsaikou.sync.format.CategoryRow
import com.shinsaikou.sync.format.ChainPlan
import com.shinsaikou.sync.format.ChainStep
import com.shinsaikou.sync.format.DeviceFiles
import com.shinsaikou.sync.format.ExportDone
import com.shinsaikou.sync.format.MalformedSyncFileException
import com.shinsaikou.sync.format.RemoteName
import com.shinsaikou.sync.format.SegmentReader
import com.shinsaikou.sync.format.SyncChain
import com.shinsaikou.sync.format.SyncFormat
import com.shinsaikou.sync.format.SyncHeader
import com.shinsaikou.sync.format.SyncLayout
import com.shinsaikou.sync.format.SyncRow
import com.shinsaikou.sync.format.SyncScope
import java.io.FileNotFoundException
import kotlin.time.TimeSource

/**
 * One run's pull (phase 3 unit E2): every other device's files by Q3's chain rule from this
 * device's cursor for it, and the newest snapshot first when a chain cannot reach what that
 * snapshot covers, because a file it needs is gone or was never listed.
 *
 * A segment is read whole and applied as one transaction. An export's parts and a snapshot are
 * streamed and applied [CHUNK] rows per transaction, parents first as the file orders them, since
 * they hold a whole library. A device's cursor moves only once the last row of its export is
 * committed, but the export resumes per part (R89 carry-over 1): once a part's last chunk commits,
 * [SyncPreferences.pulledExportParts] records the export id and the parts done, and a pull that
 * meets the same export id again skips those parts and starts at the next. The entry is dropped
 * when the export's cursor moves, by the export or by a snapshot that covers its device; an entry
 * naming another export id of that device is ignored, so a
 * new export starts at part 1. A stop between a part's commit and its mark applies that one part
 * again, and a stop inside a part applies the part's committed chunks again, both of which the
 * merge's idempotence makes safe. A snapshot is one file and has no mark: a pull stopped inside it
 * reads it again whole.
 *
 * [newChapterIds] are the chapters this pull inserted that auto-download may take (phase 3 unit
 * AD): not those from a snapshot or from an export of a device this device had pulled nothing from
 * when the pull began, which hold a whole library rather than what is new, and none of an entry that
 * arrived in the library during this pull, whose every chapter would otherwise be downloaded.
 *
 * The time spent decoding rows and in transactions goes to [timings].
 */
internal class SyncPull(
    private val backend: SyncBackend,
    private val deviceId: String,
    private val preferences: SyncPreferences,
    private val apply: SegmentApply,
    private val local: LocalChanges,
    private val timings: SyncTimings,
) {
    var result: ApplyResult = ApplyResult.NONE
        private set
    var segmentsApplied: Int = 0
        private set
    var exportsApplied: Int = 0
        private set
    var snapshotApplied: Boolean = false
        private set
    var stoppedAtGap: Boolean = false
        private set

    /** Every chapter row committed so far that counts as new, in the order it was inserted. */
    val newChapterIds: List<Long>
        get() = result.insertedChapters.filter { it.manga !in result.arrivedManga }.map { it.chapterId }

    suspend fun run() {
        val layout = SyncChain.group(backend.list(SyncLayout.ROOT_PREFIX).map { it.path })
        val others = layout.devices.values.filter { it.deviceId != deviceId }.sortedBy { it.deviceId }
        val firstPulls = others.map { it.deviceId }.filterTo(mutableSetOf()) { cursor(it) == SyncChain.NOTHING_APPLIED }
        val dones = others.associate { it.deviceId to readDones(it) }
        fun plans(): Map<String, ChainPlan> = others.associate { device ->
            device.deviceId to SyncChain.plan(device, dones.getValue(device.deviceId), cursor(device.deviceId))
        }
        var plans = plans()

        SyncChain.selectSnapshot(layout.snapshots)?.let { snapshot ->
            val covers = readCovers(backend, snapshot).filterKeys { it != deviceId }
            val behind = covers.any { (device, seq) -> (plans[device]?.cursor ?: cursor(device)) < seq }
            if (behind) {
                applySnapshot(snapshot)
                preferences.pullCursors.set(
                    preferences.pullCursors.get() + covers.mapValues { (device, seq) -> maxOf(cursor(device), seq) },
                )
                // Each covered device's cursor moved, so a mark of its export being resumed goes too.
                preferences.pulledExportParts.set(preferences.pulledExportParts.get() - covers.keys)
                plans = plans()
            }
        }

        for (device in others) {
            val plan = plans.getValue(device.deviceId)
            for (step in plan.steps) {
                when (step) {
                    is ChainStep.Segment -> {
                        val rows = timings.time(SyncTimings.Step.PULL_DECODE) { readSegment(step.segment) }
                        result += timings.time(SyncTimings.Step.PULL_APPLY) { apply.apply(rows, local) }
                        segmentsApplied++
                    }
                    is ChainStep.Export -> {
                        val done = partsDone(device.deviceId, step.exportId)
                        step.parts.forEachIndexed { index, path ->
                            val part = index + 1
                            if (part <= done) return@forEachIndexed
                            val applied = applyStreamed(path) { header ->
                                header.deviceId == device.deviceId &&
                                    header.scope == SyncScope.ExportPart(step.exportId, part)
                            }
                            result += if (device.deviceId in firstPulls) applied.withoutInsertedChapters() else applied
                            preferences.pulledExportParts.set(
                                preferences.pulledExportParts.get() + (device.deviceId to PulledExportParts(step.exportId, part)),
                            )
                        }
                        exportsApplied++
                    }
                }
                preferences.pullCursors.set(preferences.pullCursors.get() + (device.deviceId to step.lastSeq))
                if (step is ChainStep.Export) {
                    preferences.pulledExportParts.set(preferences.pulledExportParts.get() - device.deviceId)
                }
            }
            stoppedAtGap = stoppedAtGap || plan.stoppedAtGap
        }
    }

    private fun cursor(device: String): Long = preferences.pullCursors.get()[device] ?: SyncChain.NOTHING_APPLIED

    /** How many parts of [exportId] from [device] are committed here: 0 unless the mark names that export. */
    private fun partsDone(device: String, exportId: String): Int =
        preferences.pulledExportParts.get()[device]?.takeIf { it.exportId == exportId }?.parts ?: 0

    /**
     * Every listed export's `done.json`, by export id. One listed a moment ago and gone now was
     * deleted by its device; the export is left out as if it had never been finished.
     */
    private suspend fun readDones(device: DeviceFiles): Map<String, ExportDone> =
        device.exports.values.mapNotNull { export ->
            val path = export.donePath ?: return@mapNotNull null
            try {
                export.exportId to backend.read(path) { SyncLayout.decodeDone(it.readBytes()) }
            } catch (_: FileNotFoundException) {
                null
            }
        }.toMap()

    private suspend fun applySnapshot(snapshot: RemoteName.Snapshot) {
        result += applyStreamed(snapshot.path) { header ->
            val scope = header.scope
            header.deviceId == snapshot.deviceId && scope is SyncScope.Snapshot && scope.generation == snapshot.generation
        }.withoutInsertedChapters()
        snapshotApplied = true
    }

    /**
     * The whole segment, decoded before its transaction opens, so no transaction waits on the
     * network; a segment folds at most [SyncFormat.MAX_RAW_ROWS] log rows.
     */
    private suspend fun readSegment(segment: RemoteName.Segment): List<SyncRow> =
        backend.read(segment.path) { stream ->
            SegmentReader(stream).use { reader ->
                val scope = reader.header.scope
                if (reader.header.deviceId != segment.deviceId || scope !is SyncScope.Segment ||
                    scope.afterSeq != segment.afterSeq || scope.lastSeq != segment.lastSeq
                ) {
                    throw MalformedSyncFileException("The header of ${segment.path} names another file")
                }
                generateSequence { reader.next() }.toList()
            }
        }

    /**
     * [path] applied [CHUNK] rows per transaction, once [matches] accepts its header. The category
     * lines a file opens with are never split between two transactions, however many there are: the
     * apply gives each category its parent in one last pass over the lines it holds, and a cycle of
     * parents puts a line before its parent's (phase 5 unit CT).
     */
    private suspend fun applyStreamed(path: String, matches: (SyncHeader) -> Boolean): ApplyResult =
        backend.read(path) { stream ->
            // The decode's time is the file's less its transactions', so no row pays for a clock read.
            val started = TimeSource.Monotonic.markNow()
            var applying = 0L
            suspend fun applyChunk(chunk: List<SyncRow>): ApplyResult {
                val mark = TimeSource.Monotonic.markNow()
                try {
                    return apply.apply(chunk, local)
                } finally {
                    applying += mark.elapsedNow().inWholeNanoseconds
                }
            }
            try {
                SegmentReader(stream).use { reader ->
                    if (!matches(reader.header)) {
                        throw MalformedSyncFileException("The header of $path names another file")
                    }
                    var applied = ApplyResult.NONE
                    val chunk = ArrayList<SyncRow>(CHUNK)
                    while (true) {
                        val row = reader.next() ?: break
                        if (chunk.size >= CHUNK && !(row is CategoryRow && chunk.last() is CategoryRow)) {
                            applied += applyChunk(chunk)
                            chunk.clear()
                        }
                        chunk += row
                    }
                    if (chunk.isNotEmpty()) applied += applyChunk(chunk)
                    applied
                }
            } finally {
                timings.add(SyncTimings.Step.PULL_APPLY, applying)
                timings.add(SyncTimings.Step.PULL_DECODE, started.elapsedNow().inWholeNanoseconds - applying)
            }
        }

    companion object {
        /** Rows of an export part or a snapshot per transaction. */
        const val CHUNK: Int = 2_000
    }
}

/**
 * What [snapshot] covers: each device's id and the seq through which its rows are in it. Only the
 * header is read.
 */
internal suspend fun readCovers(backend: SyncBackend, snapshot: RemoteName.Snapshot): Map<String, Long> =
    backend.read(snapshot.path) { stream ->
        SegmentReader(stream).use { reader ->
            val scope = reader.header.scope
            if (reader.header.deviceId != snapshot.deviceId || scope !is SyncScope.Snapshot ||
                scope.generation != snapshot.generation
            ) {
                throw MalformedSyncFileException("The header of ${snapshot.path} names another file")
            }
            scope.covers
        }
    }

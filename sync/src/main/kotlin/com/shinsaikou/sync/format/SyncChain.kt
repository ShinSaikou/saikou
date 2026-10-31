package com.shinsaikou.sync.format

/**
 * Q3's reading rules as pure functions over a listing: which of another device's files to apply, in
 * which order, from a cursor, and which snapshot a reader picks.
 *
 * A cursor is the seq through which a device's rows have been applied. A reader that has applied
 * nothing of a device holds [NOTHING_APPLIED], below every segment's `afterSeq`, so its first step
 * is always a full export (or a snapshot): a device's first push is one (Q10), and the export
 * carries rows no log row names. An export covers its device's whole state through its `lastSeq`,
 * so it applies from any cursor below that.
 */
object SyncChain {

    /** The cursor of a device none of whose rows has been applied yet. */
    const val NOTHING_APPLIED: Long = -1L

    /** Groups [paths] by what they are. A path the layout never writes is left out. */
    fun group(paths: Iterable<String>): RemoteLayout {
        val segments = mutableMapOf<String, MutableList<RemoteName.Segment>>()
        val parts = mutableMapOf<Pair<String, String>, MutableMap<Int, String>>()
        val dones = mutableMapOf<Pair<String, String>, String>()
        val snapshots = mutableListOf<RemoteName.Snapshot>()
        for (path in paths) {
            when (val name = SyncLayout.parse(path)) {
                is RemoteName.Segment -> segments.getOrPut(name.deviceId) { mutableListOf() } += name
                is RemoteName.ExportPart ->
                    parts.getOrPut(name.deviceId to name.exportId) { mutableMapOf() }[name.part] = name.path
                is RemoteName.Done -> dones[name.deviceId to name.exportId] = name.path
                is RemoteName.Snapshot -> snapshots += name
                null -> Unit
            }
        }
        val exportKeys = parts.keys + dones.keys
        val deviceIds = segments.keys + exportKeys.map { it.first }
        val devices = deviceIds.associateWith { deviceId ->
            DeviceFiles(
                deviceId = deviceId,
                segments = segments[deviceId].orEmpty(),
                exports = exportKeys.filter { it.first == deviceId }.associate { key ->
                    key.second to ExportFiles(
                        exportId = key.second,
                        parts = parts[key].orEmpty(),
                        donePath = dones[key],
                    )
                },
            )
        }
        return RemoteLayout(devices, snapshots)
    }

    /**
     * The steps that take a reader of [device] from [cursor] as far as the listing allows. [dones]
     * holds the `done.json` of each export the caller read, by export id; an export without one, or
     * missing a part it names, is not applied.
     *
     * At each step the candidates are the segments with `afterSeq` at or below the cursor and
     * `lastSeq` above it, and the finished exports with `lastSeq` above it. The one with the lowest
     * `lastSeq` is applied (a segment before an export that ends at the same seq), and the cursor
     * moves to its `lastSeq`. It stops when no candidate is left; if a file the listing shows still
     * ends above the cursor, the reader stopped at a gap and re-lists next sync (Q3), never moving
     * past the missing range.
     */
    fun plan(device: DeviceFiles, dones: Map<String, ExportDone>, cursor: Long): ChainPlan {
        require(cursor >= NOTHING_APPLIED) { "Not a cursor: $cursor" }
        val candidates = device.segments.map { Candidate(it.afterSeq, it.lastSeq, ChainStep.Segment(it)) }
            .toMutableList()
        var unfinishedEnd = NOTHING_APPLIED
        for (export in device.exports.values) {
            val done = dones[export.exportId] ?: continue
            val partPaths = (1..done.parts).map { export.parts[it] }
            if (partPaths.any { it == null }) {
                unfinishedEnd = maxOf(unfinishedEnd, done.lastSeq)
                continue
            }
            val step = ChainStep.Export(export.exportId, partPaths.filterNotNull(), done.lastSeq)
            candidates += Candidate(NOTHING_APPLIED, done.lastSeq, step)
        }

        val order = compareBy<Candidate>({ it.lastSeq }, { it.step is ChainStep.Export }, { it.afterSeq })
        val steps = mutableListOf<ChainStep>()
        var at = cursor
        while (true) {
            val next = candidates.filter { it.afterSeq <= at && it.lastSeq > at }.minWithOrNull(order) ?: break
            steps += next.step
            at = next.lastSeq
        }
        val gap = unfinishedEnd > at || candidates.any { it.lastSeq > at }
        return ChainPlan(steps, at, gap)
    }

    /** The snapshot a reader uses: the newest generation, then the lowest device id (Q3). */
    fun selectSnapshot(snapshots: Iterable<RemoteName.Snapshot>): RemoteName.Snapshot? =
        snapshots.minWithOrNull(compareByDescending<RemoteName.Snapshot> { it.generation }.thenBy { it.deviceId })

    private class Candidate(val afterSeq: Long, val lastSeq: Long, val step: ChainStep)
}

/** A listing grouped: each device's files by device id, and every snapshot. */
data class RemoteLayout(val devices: Map<String, DeviceFiles>, val snapshots: List<RemoteName.Snapshot>)

data class DeviceFiles(
    val deviceId: String,
    val segments: List<RemoteName.Segment>,
    val exports: Map<String, ExportFiles>,
)

/** One export's listed parts, by part number, and its `done.json` if listed. */
data class ExportFiles(val exportId: String, val parts: Map<Int, String>, val donePath: String?)

/**
 * What [SyncChain.plan] found. [cursor] is where the reader stands after every step, each of which
 * moves it on commit. [stoppedAtGap] says the listing shows rows past [cursor] that no step reaches.
 */
data class ChainPlan(val steps: List<ChainStep>, val cursor: Long, val stoppedAtGap: Boolean)

sealed interface ChainStep {
    /** The cursor once this step's rows are committed. */
    val lastSeq: Long

    data class Segment(val segment: RemoteName.Segment) : ChainStep {
        override val lastSeq: Long get() = segment.lastSeq
    }

    /** A finished full export: every part, in order. */
    data class Export(val exportId: String, val parts: List<String>, override val lastSeq: Long) : ChainStep
}

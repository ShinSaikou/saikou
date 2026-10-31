package com.shinsaikou.sync.format

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject

/**
 * The names of Q3's remote layout, relative to a backend's root:
 *
 * - `saikou-sync/v1/devices/<deviceId>/<afterSeq>-<lastSeq>.jsonl.gz`, one segment;
 * - `saikou-sync/v1/devices/<deviceId>/full-<exportId>/part-<n>.jsonl.gz`, one part of a full
 *   export, and `done.json` beside the parts, written last;
 * - `saikou-sync/v1/snapshot-<generation>-<deviceId>.jsonl.gz`, one snapshot.
 *
 * Device and export ids are letters, digits and `-` (a UUID's text); seqs, parts and generations are
 * decimal with no leading zero, so one file has one name.
 */
object SyncLayout {

    const val ROOT: String = "saikou-sync/v1"

    /** The prefix every file of the layout shares, for one listing of devices and snapshots. */
    const val ROOT_PREFIX: String = "$ROOT/"

    /** The prefix every device's files share, for one listing of them all. */
    const val DEVICES: String = "$ROOT/devices/"

    private const val ROWS_SUFFIX = ".jsonl.gz"
    private const val DONE_FILE = "done.json"
    private const val NUMBER = "(0|[1-9][0-9]*)"
    private const val ID = "([A-Za-z0-9-]+)"

    private val segmentName = Regex("$NUMBER-$NUMBER\\.jsonl\\.gz")
    private val exportPartName = Regex("full-$ID/part-$NUMBER\\.jsonl\\.gz")
    private val exportDoneName = Regex("full-$ID/done\\.json")
    private val snapshotName = Regex("snapshot-$NUMBER-$ID\\.jsonl\\.gz")
    private val idPattern = Regex(ID)

    fun devicePrefix(deviceId: String): String {
        requireId(deviceId, "device id")
        return "$DEVICES$deviceId/"
    }

    fun segmentPath(deviceId: String, afterSeq: Long, lastSeq: Long): String {
        require(afterSeq >= 0 && lastSeq > afterSeq) { "Not a segment range: ($afterSeq, $lastSeq]" }
        return "${devicePrefix(deviceId)}$afterSeq-$lastSeq$ROWS_SUFFIX"
    }

    fun exportPartPath(deviceId: String, exportId: String, part: Int): String {
        require(part >= 1) { "Export parts count from 1, not $part" }
        return "${exportPrefix(deviceId, exportId)}part-$part$ROWS_SUFFIX"
    }

    fun exportDonePath(deviceId: String, exportId: String): String =
        "${exportPrefix(deviceId, exportId)}$DONE_FILE"

    fun snapshotPath(generation: Long, deviceId: String): String {
        require(generation >= 1) { "Snapshot generations count from 1, not $generation" }
        requireId(deviceId, "device id")
        return "$ROOT/snapshot-$generation-$deviceId$ROWS_SUFFIX"
    }

    /** What [path] is in the layout, or null for a file the layout never writes. */
    fun parse(path: String): RemoteName? {
        if (path.startsWith(DEVICES)) {
            val rest = path.removePrefix(DEVICES)
            val slash = rest.indexOf('/')
            if (slash <= 0) return null
            val deviceId = rest.substring(0, slash)
            if (!idPattern.matches(deviceId)) return null
            val name = rest.substring(slash + 1)
            segmentName.matchEntire(name)?.let { match ->
                val afterSeq = match.groupValues[1].toLongOrNull() ?: return null
                val lastSeq = match.groupValues[2].toLongOrNull() ?: return null
                if (lastSeq <= afterSeq) return null
                return RemoteName.Segment(path, deviceId, afterSeq, lastSeq)
            }
            exportPartName.matchEntire(name)?.let { match ->
                val part = match.groupValues[2].toIntOrNull()?.takeIf { it >= 1 } ?: return null
                return RemoteName.ExportPart(path, deviceId, match.groupValues[1], part)
            }
            exportDoneName.matchEntire(name)?.let { match ->
                return RemoteName.Done(path, deviceId, match.groupValues[1])
            }
            return null
        }
        if (path.startsWith("$ROOT/")) {
            snapshotName.matchEntire(path.removePrefix("$ROOT/"))?.let { match ->
                val generation = match.groupValues[1].toLongOrNull()?.takeIf { it >= 1 } ?: return null
                return RemoteName.Snapshot(path, generation, match.groupValues[2])
            }
        }
        return null
    }

    /** `done.json`'s bytes for [done]. */
    fun encodeDone(done: ExportDone): ByteArray {
        require(done.format == SyncFormat.CURRENT) { "This build writes format ${SyncFormat.CURRENT}" }
        return SyncFormat.json.encodeToString(ExportDone.serializer(), done).encodeToByteArray()
    }

    /**
     * Reads a `done.json`. A newer format throws [NewerFormatException] as a row file's header does;
     * anything else unreadable, including a part count below 1 or a negative `lastSeq`, throws
     * [MalformedSyncFileException].
     */
    fun decodeDone(bytes: ByteArray): ExportDone {
        try {
            val element = SyncFormat.json.parseToJsonElement(bytes.decodeToString()).jsonObject
            SyncFormat.checkFormat(element, "done.json")
            return SyncFormat.json.decodeFromJsonElement(ExportDone.serializer(), element)
        } catch (e: IllegalArgumentException) {
            throw MalformedSyncFileException("Cannot read done.json", e)
        }
    }

    internal fun requireId(id: String, what: String) {
        require(idPattern.matches(id)) { "A $what is letters, digits and '-', not '$id'" }
    }

    private fun exportPrefix(deviceId: String, exportId: String): String {
        requireId(exportId, "export id")
        return "${devicePrefix(deviceId)}full-$exportId/"
    }
}

/**
 * The file a full export writes last: it has [parts] parts, and reflects its device through [lastSeq].
 * An export has at least one part and a seq of 0 or more, whether built here or decoded, so
 * [SyncChain.plan] never takes an export with no parts for a finished one.
 */
@Serializable
data class ExportDone(val format: Int, val parts: Int, val lastSeq: Long) {
    init {
        require(parts >= 1 && lastSeq >= 0) { "Not a finished export: parts $parts, lastSeq $lastSeq" }
    }
}

/** A path the layout names, parsed. */
sealed interface RemoteName {
    val path: String

    data class Segment(
        override val path: String,
        val deviceId: String,
        val afterSeq: Long,
        val lastSeq: Long,
    ) : RemoteName

    data class ExportPart(
        override val path: String,
        val deviceId: String,
        val exportId: String,
        val part: Int,
    ) : RemoteName

    /** An export's `done.json`. */
    data class Done(override val path: String, val deviceId: String, val exportId: String) : RemoteName

    data class Snapshot(override val path: String, val generation: Long, val deviceId: String) : RemoteName
}

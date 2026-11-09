package com.shinsaikou.sync.format

import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.domain.error.KeyedFailure
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** The version of the wire format this build writes and the newest it reads (Q4). */
object SyncFormat {

    /**
     * Raised whenever a row or a field is added; every older value keeps a written rule. Format 2
     * (phase 5 unit ED) adds the manga line's `titleOverride`, `descriptionOverride` and
     * `detailsModifiedAt`; a format 1 line, which has none of them, reads as an entry never edited.
     * Format 3 (R163) adds the `merged_group` line. A file of format 1 or 2 has none and says nothing
     * about merges, so it leaves every group as it is. A build older than format 3 stops at the header
     * of a format 3 file, and it runs push and compaction only after a pull that returned, so it never
     * rewrites a snapshot without these lines. Format 4 (R165) adds the chapter line's
     * `readModifiedAt` and `bookmarkModifiedAt`, so an unread and a removed bookmark travel. A line
     * of format 1 to 3 has neither and reads them as 0, which merges as before: equal clocks take the
     * OR. A build older than format 4 stops at the header of a format 4 file in the same way, so it
     * never writes a snapshot that drops these clocks. Format 4 also adds the manga line's
     * `authorOverride`, `artistOverride`, `statusOverride` and `genreOverride` (R165 item 4), under
     * `detailsModifiedAt`; a line without them reads as those details never edited.
     */
    const val CURRENT: Int = 4

    /** The most raw change log rows one segment folds: `SyncChangeLogRepositoryImpl.MAX_LIMIT` (Q3). */
    const val MAX_RAW_ROWS: Int = 5_000

    /**
     * The one JSON configuration of the wire. Unknown keys are ignored (they only come from a
     * writer newer than a reader, which the header's `format` already refused); nulls and defaults
     * are written, so every line shows every field.
     */
    internal val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "entity"
    }

    /**
     * Throws [NewerFormatException] when [element] names a format newer than [CURRENT], and
     * [MalformedSyncFileException] when it names none. Read before anything else in the file is.
     */
    internal fun checkFormat(element: JsonObject, what: String) {
        val format = runCatching { element["format"]?.jsonPrimitive?.intOrNull }.getOrNull()
            ?: throw MalformedSyncFileException("$what has no format")
        if (format > CURRENT) throw NewerFormatException(format)
        if (format < 1) throw MalformedSyncFileException("$what has format $format")
    }
}

/**
 * A file written by a newer Saikou. The reader stops the sync without moving any cursor, and settings
 * say "Update Saikou on this device to keep syncing" (Q4); nothing after the header was read.
 */
class NewerFormatException(val found: Int) :
    Exception("Format $found is newer than this build's ${SyncFormat.CURRENT}"), KeyedFailure {
    override val errorKey: ErrorKey get() = ErrorKey.SYNC_NEWER_FORMAT
}

/** A file that is not a well-formed file of a format this build reads: a cut stream or a bad line. */
class MalformedSyncFileException(message: String, cause: Throwable? = null) : Exception(message, cause), KeyedFailure {
    override val errorKey: ErrorKey get() = ErrorKey.SYNC_MALFORMED_FILE
}

/**
 * The first line of every row file. [counts] declares how many rows of each kind follow; the writer
 * refuses to finish and the reader refuses to end on any other number, so a cut file never reads as
 * a short one.
 */
@Serializable
data class SyncHeader(
    val format: Int,
    val deviceId: String,
    val scope: SyncScope,
    val counts: Map<RowKind, Int>,
)

/** What a row file holds, by the file it is (Q3). */
@Serializable
@JsonClassDiscriminator("kind")
sealed class SyncScope {

    /**
     * The fold of change log rows `(afterSeq, lastSeq]`: [rawRows] of them, at most
     * [SyncFormat.MAX_RAW_ROWS].
     */
    @Serializable
    @SerialName("segment")
    data class Segment(val afterSeq: Long, val lastSeq: Long, val rawRows: Int) : SyncScope()

    /** Part [part] of full export [exportId]; parts count from 1, and `done.json` names the count. */
    @Serializable
    @SerialName("export")
    data class ExportPart(val exportId: String, val part: Int) : SyncScope()

    /**
     * A whole library at [generation]. [covers] maps each device id to the seq through which that
     * device's segments are reflected in it.
     */
    @Serializable
    @SerialName("snapshot")
    data class Snapshot(val generation: Long, val covers: Map<String, Long>) : SyncScope()
}

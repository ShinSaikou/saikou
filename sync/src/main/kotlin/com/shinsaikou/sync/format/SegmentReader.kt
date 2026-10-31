package com.shinsaikou.sync.format

import kotlinx.serialization.json.jsonObject
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStream
import java.io.InputStreamReader
import java.util.EnumMap
import java.util.zip.GZIPInputStream

/**
 * Reads one row file that [SegmentWriter] wrote, one line at a time. The header is read on
 * construction: a newer `format` throws [NewerFormatException] before any row is looked at, and a
 * header that is not one throws [MalformedSyncFileException]. [next] decodes one row per call and
 * returns null at the end, after checking the rows read against the header's counts.
 *
 * A failure of [input] itself (a dropped connection, a cut gzip stream) is thrown as the
 * [java.io.IOException] it is, so the caller can tell it from a file that is wrong. Closing closes
 * [input].
 */
class SegmentReader(input: InputStream) : Closeable {

    private val reader = BufferedReader(InputStreamReader(GZIPInputStream(input), Charsets.UTF_8))
    private val read = EnumMap<RowKind, Int>(RowKind::class.java)
    private var ended = false

    val header: SyncHeader = try {
        readHeader()
    } catch (e: Throwable) {
        reader.close()
        throw e
    }

    /** The next row, or null once every row the header declares has been read. */
    fun next(): SyncRow? {
        if (ended) return null
        val line = reader.readLine()
        if (line == null) {
            ended = true
            val short = RowKind.entries.filter { (read[it] ?: 0) != (header.counts[it] ?: 0) }
            if (short.isNotEmpty()) {
                throw MalformedSyncFileException(
                    "The file ends with rows missing: " +
                        short.joinToString { "$it (declared ${header.counts[it] ?: 0}, read ${read[it] ?: 0})" },
                )
            }
            return null
        }
        val row = decode("a row") { SyncFormat.json.decodeFromString(SyncRow.serializer(), line) }
        val count = (read[row.kind] ?: 0) + 1
        if (count > (header.counts[row.kind] ?: 0)) {
            throw MalformedSyncFileException("The file holds more ${row.kind} rows than its header declares")
        }
        read[row.kind] = count
        return row
    }

    override fun close() {
        reader.close()
    }

    private fun readHeader(): SyncHeader {
        val line = reader.readLine() ?: throw MalformedSyncFileException("The file is empty")
        val element = decode("the header") { SyncFormat.json.parseToJsonElement(line).jsonObject }
        SyncFormat.checkFormat(element, "The header")
        return decode("the header") { SyncFormat.json.decodeFromJsonElement(SyncHeader.serializer(), element) }
    }

    private inline fun <T> decode(what: String, block: () -> T): T = try {
        block()
    } catch (e: IllegalArgumentException) {
        // A SerializationException is one, and so is a JSON value of the wrong shape.
        throw MalformedSyncFileException("Cannot read $what", e)
    }
}

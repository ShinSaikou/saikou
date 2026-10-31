package com.shinsaikou.sync.engine

import com.shinsaikou.sync.SyncBackend
import com.shinsaikou.sync.SyncContent
import com.shinsaikou.sync.format.RowKind
import com.shinsaikou.sync.format.SegmentWriter
import com.shinsaikou.sync.format.SyncFormat
import com.shinsaikou.sync.format.SyncHeader
import com.shinsaikou.sync.format.SyncRow
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.EnumMap

/*
 * Writing the engine's files (phase 3 unit E2). A segment or an export part is bounded, so it is
 * planned and encoded in memory; a snapshot holds the whole library, so its rows are spooled to a
 * temporary file first and encoded from there once their counts, which the header declares
 * (R81 item 5), are known. Every file is read back after it is written and compared before anything
 * that depends on it moves: by the backend's own create where it checks its writes, else here, byte
 * for byte.
 */

/** [rows] under [header], encoded whole. */
internal fun encodeFile(header: SyncHeader, rows: List<SyncRow>): ByteArray =
    ByteArrayOutputStream().also { output ->
        SegmentWriter(output, header).use { writer -> rows.forEach(writer::write) }
    }.toByteArray()

/**
 * Writes [content] at [path] and reads it back, throwing an [IOException] when the store returns
 * anything but the bytes written: a store that reports a write it did not make is a failed sync,
 * never a silent success. A backend whose [SyncBackend.create] reads back and checks the file itself
 * ([SyncBackend.createReadsBack]) is not read a second time. [timings], when given, takes the time
 * of each.
 */
internal suspend fun SyncBackend.createVerified(path: String, content: SyncContent, timings: SyncTimings? = null) {
    timed(timings, SyncTimings.Step.CREATE) { create(path, content) }
    if (createReadsBack) return
    val same = timed(timings, SyncTimings.Step.READ_BACK) {
        read(path) { remote -> content.open().use { written -> sameBytes(written, remote) } }
    }
    if (!same) throw IOException("$path did not read back as the ${content.length} bytes written")
}

private inline fun <T> timed(timings: SyncTimings?, step: SyncTimings.Step, block: () -> T): T =
    if (timings == null) block() else timings.time(step, block)

private fun sameBytes(a: InputStream, b: InputStream): Boolean {
    val bufferA = ByteArray(BUFFER)
    val bufferB = ByteArray(BUFFER)
    while (true) {
        val readA = fill(a, bufferA)
        val readB = fill(b, bufferB)
        if (readA != readB) return false
        if (readA == 0) return true
        for (i in 0 until readA) if (bufferA[i] != bufferB[i]) return false
    }
}

/** Reads until [buffer] is full or the stream ends; `InputStream.readNBytes` needs API 33. */
private fun fill(stream: InputStream, buffer: ByteArray): Int {
    var filled = 0
    while (filled < buffer.size) {
        val read = stream.read(buffer, filled, buffer.size - filled)
        if (read < 0) break
        filled += read
    }
    return filled
}

private const val BUFFER = 64 * 1024

/**
 * A row file too large for memory: rows go to a temporary file one line at a time, counted as they
 * go, and [finish] encodes the real file once the counts are known, with a few [finish] lines ahead
 * of them (a snapshot's categories, which every category set needs first). Closing deletes both
 * temporary files.
 */
internal class RowSpool : Closeable {

    private val body: File = File.createTempFile("saikou-sync-", ".jsonl")
    private val out: BufferedWriter = body.bufferedWriter()
    private val counts = EnumMap<RowKind, Int>(RowKind::class.java)
    private var encoded: File? = null

    fun add(row: SyncRow) {
        out.write(SyncFormat.json.encodeToString(SyncRow.serializer(), row))
        out.newLine()
        counts[row.kind] = (counts[row.kind] ?: 0) + 1
    }

    /** The encoded file: [head], then every row added, under the header [header] builds from the counts. */
    fun finish(head: List<SyncRow>, header: (Map<RowKind, Int>) -> SyncHeader): File {
        out.close()
        val all = EnumMap(counts)
        head.forEach { all[it.kind] = (all[it.kind] ?: 0) + 1 }
        val file = File.createTempFile("saikou-sync-", ".jsonl.gz").also { encoded = it }
        SegmentWriter(file.outputStream().buffered(), header(all)).use { writer ->
            head.forEach(writer::write)
            body.bufferedReader().useLines { lines ->
                lines.forEach { writer.write(SyncFormat.json.decodeFromString(SyncRow.serializer(), it)) }
            }
        }
        return file
    }

    override fun close() {
        out.close()
        body.delete()
        encoded?.delete()
    }
}

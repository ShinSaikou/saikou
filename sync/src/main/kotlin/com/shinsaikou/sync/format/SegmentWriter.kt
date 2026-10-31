package com.shinsaikou.sync.format

import java.io.BufferedWriter
import java.io.Closeable
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.EnumMap
import java.util.zip.GZIPOutputStream

/**
 * Writes one row file of Q3's layout (a segment, an export part or a snapshot) as gzip JSON Lines:
 * [header] first, then one row per [write], each encoded and written on its own so a library of any
 * size passes through one line at a time. Closing finishes the gzip stream and closes [output].
 *
 * The file is self-contained (Q4): a row that belongs to a manga is refused unless that manga's
 * [MangaRow] was written earlier in this file, and a [MangaCategoriesRow] is refused unless every
 * category it names had its [CategoryRow] written earlier. [close] refuses a file whose rows do not
 * match the header's counts. Each refusal is an [IllegalArgumentException] or
 * [IllegalStateException], because each is a defect in the caller, never in the data.
 */
class SegmentWriter(output: OutputStream, private val header: SyncHeader) : Closeable {

    private val writer = BufferedWriter(OutputStreamWriter(GZIPOutputStream(output), Charsets.UTF_8))
    private val written = EnumMap<RowKind, Int>(RowKind::class.java)
    private val mangaWritten = HashSet<MangaKey>()
    private val categoriesWritten = HashSet<String>()
    private var closed = false

    init {
        try {
            requireValid(header)
            writeLine(SyncFormat.json.encodeToString(SyncHeader.serializer(), header))
        } catch (e: Throwable) {
            writer.close()
            throw e
        }
    }

    fun write(row: SyncRow) {
        check(!closed) { "The file is closed" }
        val manga = row.parentManga
        require(row is MangaRow || manga == null || manga in mangaWritten) {
            "A ${row.kind} row names a manga this file has not written yet: $manga"
        }
        if (row is MangaCategoriesRow) {
            val missing = row.categories.filterNot { it in categoriesWritten }
            require(missing.isEmpty()) {
                "A category set names categories this file has not written yet: $missing"
            }
        }
        val count = (written[row.kind] ?: 0) + 1
        val declared = header.counts[row.kind] ?: 0
        require(count <= declared) { "The header declares $declared ${row.kind} rows, and this is one more" }

        writeLine(SyncFormat.json.encodeToString(SyncRow.serializer(), row))
        written[row.kind] = count
        when (row) {
            is MangaRow -> mangaWritten += row.key
            is CategoryRow -> categoriesWritten += row.key
            else -> Unit
        }
    }

    /** Finishes the file. Throws when the rows written do not match the header's counts. */
    override fun close() {
        if (closed) return
        closed = true
        writer.close()
        val short = RowKind.entries.filter { (written[it] ?: 0) != (header.counts[it] ?: 0) }
        check(short.isEmpty()) {
            "The header's counts do not match the rows written for " +
                short.joinToString { "$it (declared ${header.counts[it] ?: 0}, wrote ${written[it] ?: 0})" }
        }
    }

    private fun writeLine(line: String) {
        writer.write(line)
        writer.write('\n'.code)
    }

    private companion object {
        fun requireValid(header: SyncHeader) {
            require(header.format == SyncFormat.CURRENT) {
                "This build writes format ${SyncFormat.CURRENT}, not ${header.format}"
            }
            SyncLayout.requireId(header.deviceId, "device id")
            require(header.counts.values.all { it >= 0 }) { "Negative count in ${header.counts}" }
            when (val scope = header.scope) {
                is SyncScope.Segment -> {
                    require(scope.afterSeq >= 0 && scope.lastSeq > scope.afterSeq) {
                        "A segment's range must be (afterSeq, lastSeq] with lastSeq above afterSeq: $scope"
                    }
                    require(scope.rawRows in 1..SyncFormat.MAX_RAW_ROWS) {
                        "A segment folds 1 to ${SyncFormat.MAX_RAW_ROWS} raw rows, not ${scope.rawRows}"
                    }
                    require(scope.rawRows <= scope.lastSeq - scope.afterSeq) {
                        "A segment cannot fold more raw rows than its range holds: $scope"
                    }
                }
                is SyncScope.ExportPart -> {
                    SyncLayout.requireId(scope.exportId, "export id")
                    require(scope.part >= 1) { "Export parts count from 1, not ${scope.part}" }
                }
                is SyncScope.Snapshot -> {
                    require(scope.generation >= 1) { "Snapshot generations count from 1, not ${scope.generation}" }
                    scope.covers.forEach { (device, seq) ->
                        SyncLayout.requireId(device, "device id")
                        require(seq >= 0) { "A snapshot covers seq $seq for $device" }
                    }
                }
            }
        }
    }
}

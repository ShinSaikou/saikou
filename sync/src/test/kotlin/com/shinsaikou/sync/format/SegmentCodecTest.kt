package com.shinsaikou.sync.format

import com.shinsaikou.sync.format.SampleRows.chapter
import com.shinsaikou.sync.format.SampleRows.decode
import com.shinsaikou.sync.format.SampleRows.encode
import com.shinsaikou.sync.format.SampleRows.manga
import com.shinsaikou.sync.format.SampleRows.segmentHeader
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.random.Random

class SegmentCodecTest {

    // region round trips

    @Test
    fun `every row kind survives a round trip with every field set`() {
        SampleRows.full.map { it.kind }.toSet() shouldBe RowKind.entries.toSet()
        val header = segmentHeader(SampleRows.full)

        val (readHeader, rows) = decode(encode(header, SampleRows.full))

        readHeader shouldBe header
        rows shouldContainExactly SampleRows.full
        rows.filterIsInstance<MangaRow>().single().restoredAt shouldBe 1_600_000_000_018
    }

    @Test
    fun `tombstones cleared, null clocks and empty collections survive a round trip as null and empty`() {
        val (_, rows) = decode(encode(segmentHeader(SampleRows.nulls), SampleRows.nulls))

        rows shouldContainExactly SampleRows.nulls
        rows.filterIsInstance<MangaRow>().single().favoriteModifiedAt shouldBe null
        rows.filterIsInstance<MangaRow>().single().restoredAt shouldBe null
    }

    @Test
    fun `an export part and a snapshot header survive a round trip`() {
        val rows = SampleRows.full
        val export = SyncHeader(
            SyncFormat.CURRENT,
            SampleRows.DEVICE,
            SyncScope.ExportPart("e-1", 3),
            SampleRows.counts(rows),
        )
        val snapshot = SyncHeader(
            SyncFormat.CURRENT,
            SampleRows.DEVICE,
            SyncScope.Snapshot(generation = 4, covers = mapOf(SampleRows.DEVICE to 900L, "b-2" to 0L)),
            SampleRows.counts(rows),
        )

        decode(encode(export, rows)).first shouldBe export
        decode(encode(snapshot, rows)).first shouldBe snapshot
    }

    /**
     * The fixture is format 4 as a text editor shows it once gunzipped. Encoding the sample rows must
     * give it byte for byte, so a renamed property or row is a failing test rather than a new wire,
     * and a build still writing an older format fails here: format 4 carries the chapter line's read
     * and bookmark clocks and the manga line's author, artist, status and genre edits (R165), which an
     * older reader must refuse (R81 item 10) rather than read and drop.
     */
    @Test
    fun `format 4 encodes exactly as its fixture and the fixture decodes to the sample rows`() {
        val fixture = fixtureText()
        val header = segmentHeader(SampleRows.full)

        gunzip(encode(header, SampleRows.full)) shouldBe fixture
        fixture.lineSequence().first() shouldContain "\"format\":4,"
        fixture.lines().single { "\"entity\":\"chapter\"" in it } shouldContain
            "\"readModifiedAt\":1600000000019,\"bookmarkModifiedAt\":1600000000020}"
        fixture.lines().single { "\"entity\":\"manga\"" in it } shouldContain
            "\"authorOverride\":\"Edited author\",\"artistOverride\":\"Edited artist\",\"statusOverride\":5," +
            "\"genreOverride\":[\"Comedy\",\"Mystery\"]}"

        val (readHeader, rows) = decode(gzip(fixture))
        readHeader shouldBe header
        rows shouldContainExactly SampleRows.full
    }

    /**
     * A format 4 manga line without the author, artist, status and genre edits (R165 item 4), as the
     * format 4 build before them wrote it, reads those four as never edited; its title and
     * description edits and their clock stay as written.
     */
    @Test
    fun `a format 4 manga line without the four new edits reads them as none`() {
        val fixture = fixtureText().lines().joinToString("\n") { line ->
            if ("\"entity\":\"manga\"" !in line) return@joinToString line
            val element = SyncFormat.json.parseToJsonElement(line).jsonObject
            JsonObject(element - detailEditKeys).toString()
        }
        fixture shouldNotContain "authorOverride"

        val (_, rows) = decode(gzip(fixture))

        rows shouldContainExactly SampleRows.full.map(::withoutDetailEdits)
        val read = rows.filterIsInstance<MangaRow>().single()
        read.titleOverride shouldBe "Edited title"
        read.detailsModifiedAt shouldBe 1_600_000_000_006
    }

    /**
     * Format 3's fixture, kept as it was written (Q4): its chapter line has no read or bookmark clock
     * and reads both as 0, a clock never stamped, which merges as before; its manga line has none of
     * format 4's author, artist, status and genre edits and reads them as never edited.
     */
    @Test
    fun `format 3's fixture still decodes, its chapter line with both clocks 0`() {
        val (readHeader, rows) = decode(gzip(fixtureText("format3.jsonl")))

        readHeader shouldBe segmentHeader(SampleRows.full).copy(format = 3)
        rows shouldContainExactly SampleRows.full.map(::withoutChapterClocks).map(::withoutDetailEdits)
        rows.filterIsInstance<ChapterRow>().single().readModifiedAt shouldBe 0L
        rows.filterIsInstance<ChapterRow>().single().bookmarkModifiedAt shouldBe 0L
    }

    /** Format 2's fixture, kept as it was written (Q4): it has no merged group line and says nothing of merges. */
    @Test
    fun `format 2's fixture still decodes, with no merged group line`() {
        val (readHeader, rows) = decode(gzip(fixtureText("format2.jsonl")))

        readHeader shouldBe segmentHeader(olderRows).copy(format = 2)
        rows shouldContainExactly olderRows
    }

    /**
     * Format 1's fixture, kept as it was written (Q4): a file a device on the format 1 build wrote
     * still reads, and its manga line, which has no edit fields, reads as an entry never edited.
     */
    @Test
    fun `format 1's fixture still decodes, its manga line an entry never edited`() {
        val (readHeader, rows) = decode(gzip(fixtureText("format1.jsonl")))

        readHeader shouldBe segmentHeader(olderRows).copy(format = 1)
        rows shouldContainExactly olderRows.map {
            if (it is MangaRow) it.copy(titleOverride = null, descriptionOverride = null, detailsModifiedAt = null) else it
        }
    }

    @Test
    fun `unknown keys in the header, in every row and in nested values are ignored`() {
        val extended = fixtureText().lines().filter { it.isNotEmpty() }.joinToString("\n", postfix = "\n") { line ->
            addUnknownKeys(SyncFormat.json.parseToJsonElement(line)).toString()
        }
        extended shouldContain "\"addedLater\""

        val (header, rows) = decode(gzip(extended))

        header shouldBe segmentHeader(SampleRows.full)
        rows shouldContainExactly SampleRows.full
    }

    // endregion

    // region refusals on read

    @Test
    fun `a newer format is refused from the header without reading any row`() {
        val newer = buildJsonObject {
            put("format", JsonPrimitive(SyncFormat.CURRENT + 1))
            put("somethingNew", JsonPrimitive(true))
        }
        // Rows no format-1 reader could decode, many of them, and hard to compress.
        val random = Random(7)
        val text = buildString {
            appendLine(newer.toString())
            repeat(20_000) {
                appendLine("{\"entity\":\"from_the_future\",\"x\":\"${random.nextLong()}${random.nextLong()}\"}")
            }
        }
        val bytes = gzip(text)
        val counted = CountingInputStream(ByteArrayInputStream(bytes))

        val refusal = shouldThrow<NewerFormatException> { SegmentReader(counted) }

        refusal.found shouldBe SyncFormat.CURRENT + 1
        bytes.size.toLong() shouldBeGreaterThan 400_000L
        counted.count shouldBeLessThan 64 * 1024L
        counted.closed shouldBe true
    }

    @Test
    fun `a header with no format, an empty file and an undecodable row are malformed`() {
        shouldThrow<MalformedSyncFileException> { decode(gzip("{\"deviceId\":\"a\"}\n")) }
        shouldThrow<MalformedSyncFileException> { decode(gzip("")) }
        shouldThrow<MalformedSyncFileException> { decode(gzip("not json\n")) }

        val header = SyncFormat.json.encodeToString(SyncHeader.serializer(), segmentHeader(SampleRows.full))
        shouldThrow<MalformedSyncFileException> { decode(gzip("$header\n{\"entity\":\"manga\"}\n")) }
        shouldThrow<MalformedSyncFileException> { decode(gzip("$header\n{\"entity\":\"from_the_future\"}\n")) }
    }

    @Test
    fun `a file that ends early or holds more rows than its header declares is malformed`() {
        val header = segmentHeader(SampleRows.full)
        val lines = gunzip(encode(header, SampleRows.full)).lines().filter { it.isNotEmpty() }

        val short = shouldThrow<MalformedSyncFileException> { decode(gzip(lines.dropLast(1).joinToString("\n"))) }
        short.message shouldContain "MERGED_GROUP"

        val extra = lines + lines.last()
        shouldThrow<MalformedSyncFileException> { decode(gzip(extra.joinToString("\n"))) }
    }

    @Test
    fun `a cut gzip stream is thrown as the IO failure it is, not as a malformed file`() {
        val bytes = encode(segmentHeader(SampleRows.full), SampleRows.full)

        val failure = shouldThrow<Exception> { decode(bytes.copyOf(bytes.size - 12)) }

        failure.shouldBeInstanceOf<EOFException>()
    }

    // endregion

    // region the writer

    @Test
    fun `a row that names a manga the file has not written is refused, for every kind that has one`() {
        val children = SampleRows.full.filter { it !is MangaRow && it.parentManga != null }
        children.map { it.kind }.toSet() shouldBe
            RowKind.entries.toSet() - RowKind.MANGA - RowKind.CATEGORY - RowKind.MERGED_GROUP

        for (child in children) {
            val header = segmentHeader(listOf(child))
            val refusal = shouldThrow<IllegalArgumentException> {
                SegmentWriter(ByteArrayOutputStream(), header).use { it.write(child) }
            }
            refusal.message shouldContain "has not written yet"
        }
    }

    @Test
    fun `a row goes through once its manga is written earlier in the file, not when the manga comes after it`() {
        val mangaRow = SampleRows.full.filterIsInstance<MangaRow>().single()
        val other = mangaRow.copy(key = MangaKey(manga.source, "/series/43"))
        val history = HistoryRow(chapter, lastRead = 1, timeRead = 2)
        val rows = listOf(other, mangaRow, history)

        decode(encode(segmentHeader(rows), rows)).second shouldContainExactly rows

        val later = listOf(other, history, mangaRow)
        shouldThrow<IllegalArgumentException> { encode(segmentHeader(later), later) }
    }

    @Test
    fun `a category set is refused until every category it names is written`() {
        val mangaRow = SampleRows.full.filterIsInstance<MangaRow>().single()
        val categories = SampleRows.full.filterIsInstance<CategoryRow>()
        val set = SampleRows.full.filterIsInstance<MangaCategoriesRow>().single()

        val missingOne = listOf(categories.first(), mangaRow, set)
        val refusal = shouldThrow<IllegalArgumentException> { encode(segmentHeader(missingOne), missingOne) }
        refusal.message shouldContain categories.last().key

        val complete = categories + mangaRow + set
        decode(encode(segmentHeader(complete), complete)).second shouldContainExactly complete
    }

    @Test
    fun `the writer refuses counts that do not match the rows, and a header it cannot write`() {
        val rows = SampleRows.full
        val declaresMore = segmentHeader(rows).let { it.copy(counts = it.counts + (RowKind.HISTORY to 2)) }
        shouldThrow<IllegalStateException> { encode(declaresMore, rows) }.message shouldContain "HISTORY"
        val declaresFewer = segmentHeader(rows).let { it.copy(counts = it.counts - RowKind.HISTORY) }
        shouldThrow<IllegalArgumentException> { encode(declaresFewer, rows) }

        fun refused(header: SyncHeader) = shouldThrow<IllegalArgumentException> { encode(header, emptyList()) }
        val empty = SyncHeader(SyncFormat.CURRENT, SampleRows.DEVICE, SyncScope.Segment(0, 1, 1), emptyMap())
        encode(empty, emptyList())
        refused(empty.copy(format = SyncFormat.CURRENT + 1))
        refused(empty.copy(deviceId = "a/b"))
        refused(empty.copy(scope = SyncScope.Segment(5, 5, 1)))
        refused(empty.copy(scope = SyncScope.Segment(0, 10, 11)))
        refused(empty.copy(scope = SyncScope.Segment(0, 10, 0)))
        refused(empty.copy(scope = SyncScope.ExportPart("e", 0)))
        refused(empty.copy(scope = SyncScope.Snapshot(0, emptyMap())))
    }

    @Test
    fun `a segment folds at most 5,000 raw rows`() {
        SyncFormat.MAX_RAW_ROWS shouldBe 5_000
        val atCap = SyncHeader(SyncFormat.CURRENT, SampleRows.DEVICE, SyncScope.Segment(0, 5_000, 5_000), emptyMap())
        decode(encode(atCap, emptyList())).first shouldBe atCap

        val overCap = atCap.copy(scope = SyncScope.Segment(0, 5_001, 5_001))
        shouldThrow<IllegalArgumentException> { encode(overCap, emptyList()) }.message shouldContain "5000"
    }

    // endregion

    /**
     * The sample rows a format before 3 can carry, as such a file reads: no chapter clocks and no
     * author, artist, status or genre edits.
     */
    private val olderRows = SampleRows.full.filterNot { it is MergedGroupRow }.map(::withoutChapterClocks)
        .map(::withoutDetailEdits)

    /** The manga line's keys for the author, artist, status and genre edits (R165 item 4). */
    private val detailEditKeys = setOf("authorOverride", "artistOverride", "statusOverride", "genreOverride")

    /** [row] as a manga line without format 4's author, artist, status and genre edits reads. */
    private fun withoutDetailEdits(row: SyncRow): SyncRow =
        if (row is MangaRow) {
            row.copy(authorOverride = null, artistOverride = null, statusOverride = null, genreOverride = null)
        } else {
            row
        }

    /** [row] as a line of a format before 4 reads: a chapter's read and bookmark clocks at 0. */
    private fun withoutChapterClocks(row: SyncRow): SyncRow =
        if (row is ChapterRow) row.copy(readModifiedAt = 0, bookmarkModifiedAt = 0) else row

    private fun fixtureText(name: String = "format4.jsonl"): String =
        requireNotNull(javaClass.getResourceAsStream("/$name")) { "$name is missing" }
            .use { it.readBytes().decodeToString() }

    /**
     * Adds a key no format-1 class declares to every object that is a class, at any depth. The
     * objects that are maps (the header's counts and covers, a memo) are data, not classes, so an
     * extra entry there would be a different value rather than an unknown key.
     */
    private fun addUnknownKeys(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.mapValues { (key, value) -> if (key in maps) value else addUnknownKeys(value) } +
                ("addedLater" to buildJsonObject { put("nested", JsonPrimitive(1)) }),
        )
        is JsonArray -> JsonArray(element.map(::addUnknownKeys))
        else -> element
    }

    private val maps = setOf("counts", "covers", "memo")

    private fun gzip(text: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { it.write(text.encodeToByteArray()) }
        return bytes.toByteArray()
    }

    private fun gunzip(bytes: ByteArray): String =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes().decodeToString() }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var count = 0L
        var closed = false

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) count += it }

        override fun close() {
            closed = true
            super.close()
        }
    }
}

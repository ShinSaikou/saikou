package com.shinsaikou.sync.format

import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.OutputStream
import java.util.zip.GZIPInputStream

/**
 * PLAN's "encoded as a stream so 3,000-entry libraries do not OOM", checked on the heap rather than
 * trusted. A 3,000-entry library with a hundred chapters each is written as one snapshot to a file
 * from rows made one at a time, then read back one row at a time. Every [SAMPLE_EVERY] rows, on both
 * sides, the test forces a full collection and reads how much of the heap is still live. The library
 * decoded or encoded whole is several times [BUDGET]; the codec streaming it holds a few buffers and
 * the writer's set of manga keys, so live memory stays near where it started.
 */
class StreamedLibraryTest {

    @Test
    fun `a 3,000 entry library streams through the writer and the reader without being held whole`(@TempDir dir: File) {
        val file = File(dir, "snapshot.jsonl.gz")
        val header = SyncHeader(
            format = SyncFormat.CURRENT,
            deviceId = SampleRows.DEVICE,
            scope = SyncScope.Snapshot(generation = 1, covers = mapOf(SampleRows.DEVICE to 1L)),
            counts = libraryCounts(),
        )
        val baseline = liveBytes()

        var writePeak = 0L
        var written = 0L
        var writeChecksum = 0L
        SegmentWriter(file.outputStream(), header).use { writer ->
            for (row in library()) {
                writer.write(row)
                writeChecksum = writeChecksum * 31 + row.hashCode()
                if (++written % SAMPLE_EVERY == 0L) writePeak = maxOf(writePeak, liveBytes() - baseline)
            }
        }

        var readPeak = 0L
        var read = 0L
        var readChecksum = 0L
        SegmentReader(file.inputStream()).use { reader ->
            reader.header shouldBe header
            while (true) {
                val row = reader.next() ?: break
                readChecksum = readChecksum * 31 + row.hashCode()
                if (++read % SAMPLE_EVERY == 0L) readPeak = maxOf(readPeak, liveBytes() - baseline)
            }
        }

        read shouldBe written
        readChecksum shouldBe writeChecksum
        written shouldBe libraryCounts().values.sum().toLong()
        // The rows as JSON text are what a whole library in memory would at least cost.
        val jsonBytes = GZIPInputStream(file.inputStream()).use { it.copyTo(NullOutputStream) }
        jsonBytes shouldBeGreaterThan 4 * BUDGET
        println(
            "streamed $written rows, $jsonBytes bytes of JSON, ${file.length()} gzipped; live growth peaked at " +
                "${writePeak / KIB} KiB writing and ${readPeak / KIB} KiB reading",
        )
        writePeak shouldBeLessThan BUDGET
        readPeak shouldBeLessThan BUDGET
    }

    /** Ten categories, then per entry its manga, category set, chapters, history, tracks and collections. */
    private fun library(): Sequence<SyncRow> = sequence {
        repeat(CATEGORIES) { c ->
            yield(CategoryRow("cat$c", "Category $c", c.toLong(), 0, null, 1_700_000_000_000 + c, null, null))
        }
        repeat(ENTRIES) { m ->
            val manga = MangaKey(source = 1_000L + m % 20, url = "/series/$m")
            yield(
                MangaRow(
                    key = manga,
                    favorite = true,
                    dateAdded = 1_600_000_000_000 + m,
                    favoriteModifiedAt = 1_600_000_000_000 + m,
                    deletedAt = null,
                    restoredAt = null,
                    version = m.toLong(),
                    title = "Series number $m with a title of a usual length",
                    author = "Author ${m % 97}",
                    artist = "Artist ${m % 89}",
                    description = "Entry $m. " + "A description of a usual length, repeated. ".repeat(30),
                    genre = listOf("Action", "Drama", "Genre ${m % 13}"),
                    status = 1,
                    thumbnailUrl = "https://example.invalid/covers/$m.jpg",
                    updateStrategy = 0,
                    memo = buildJsonObject { put("entry", JsonPrimitive(m)) },
                    viewer = 1,
                    chapterFlags = 0,
                    displayModifiedAt = null,
                    notes = "",
                    notesModifiedAt = null,
                    manualPosition = m.toLong(),
                    positionModifiedAt = 1_600_000_000_000 + m,
                ),
            )
            yield(MangaCategoriesRow(manga, setOf("cat${m % CATEGORIES}"), 1_600_000_000_000 + m))
            repeat(CHAPTERS) { c ->
                yield(
                    ChapterRow(
                        key = ChapterKey(manga, "/series/$m/chapter/$c"),
                        read = c < CHAPTERS / 2,
                        bookmark = c % 10 == 0,
                        lastPageRead = c.toLong(),
                        dateFetch = 1_600_000_000_000 + c,
                        version = 1,
                        name = "Chapter $c: a chapter name of a usual length",
                        scanlator = "Group ${c % 3}",
                        chapterNumber = c.toDouble(),
                        dateUpload = 1_500_000_000_000 + c,
                        volume = (c / 10).toDouble(),
                        sourceOrder = (CHAPTERS - c).toLong(),
                        deletedAt = null,
                        restoredAt = null,
                    ),
                )
            }
            repeat(HISTORY) { c ->
                yield(HistoryRow(ChapterKey(manga, "/series/$m/chapter/$c"), 1_650_000_000_000 + c, 60_000L * c))
            }
            yield(TracksRow(manga, emptyList(), null))
            yield(ExcludedScanlatorsRow(manga, setOf("Group 2"), 1_600_000_000_001))
            yield(ScanlatorPriorityRow(manga, listOf(PriorityEntry("Group 0", 0), PriorityEntry("Group 1", 1)), null))
            yield(BookmarkNotesRow(ChapterKey(manga, "/series/$m/chapter/0"), listOf(NoteEntry(1, "Note", 1)), 1))
        }
    }

    private fun libraryCounts(): Map<RowKind, Int> = mapOf(
        RowKind.CATEGORY to CATEGORIES,
        RowKind.MANGA to ENTRIES,
        RowKind.MANGA_CATEGORIES to ENTRIES,
        RowKind.CHAPTER to ENTRIES * CHAPTERS,
        RowKind.HISTORY to ENTRIES * HISTORY,
        RowKind.TRACKS to ENTRIES,
        RowKind.EXCLUDED_SCANLATORS to ENTRIES,
        RowKind.SCANLATOR_PRIORITY to ENTRIES,
        RowKind.BOOKMARK_NOTES to ENTRIES,
    )

    /** The heap still reachable after full collections: what the code holds, not what it dropped. */
    private fun liveBytes(): Long {
        val runtime = Runtime.getRuntime()
        repeat(3) { System.gc() }
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private object NullOutputStream : OutputStream() {
        override fun write(b: Int) = Unit

        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }

    private companion object {
        const val ENTRIES = 3_000
        const val CHAPTERS = 100
        const val HISTORY = 30
        const val CATEGORIES = 10
        const val SAMPLE_EVERY = 25_000L
        const val KIB = 1024L
        const val MIB = 1024L * KIB
        const val BUDGET = 24 * MIB
    }
}

package com.shinsaikou.sync

import com.shinsaikou.sync.format.ChainStep
import com.shinsaikou.sync.format.ChapterKey
import com.shinsaikou.sync.format.ExportDone
import com.shinsaikou.sync.format.HistoryRow
import com.shinsaikou.sync.format.MangaKey
import com.shinsaikou.sync.format.MangaRow
import com.shinsaikou.sync.format.SegmentReader
import com.shinsaikou.sync.format.SegmentWriter
import com.shinsaikou.sync.format.SyncChain
import com.shinsaikou.sync.format.SyncFormat
import com.shinsaikou.sync.format.SyncHeader
import com.shinsaikou.sync.format.SyncLayout
import com.shinsaikou.sync.format.SyncRow
import com.shinsaikou.sync.format.SyncScope
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import kotlin.random.Random

class InMemorySyncBackendTest {

    private val device = "a1"

    /** Returns Unit explicitly: ending in [shouldThrow] would return its exception, and JUnit skips a test that returns a value. */
    @Test
    fun `files are created, listed by prefix with size and server time, read as streams and deleted`(): Unit = runBlocking {
        var now = 1_000L
        val backend = InMemorySyncBackend(clock = { now })

        backend.create("saikou-sync/v1/devices/a1/0-1.jsonl.gz", SyncContent(byteArrayOf(1, 2, 3)))
        now = 2_000L
        backend.create("saikou-sync/v1/snapshot-1-a1.jsonl.gz", SyncContent(byteArrayOf(4)))

        backend.list("saikou-sync/v1/devices/") shouldContainExactly
            listOf(RemoteFile("saikou-sync/v1/devices/a1/0-1.jsonl.gz", 3, 1_000))
        backend.list("saikou-sync/v1/").map { it.modifiedAt } shouldContainExactly listOf(1_000L, 2_000L)
        backend.read("saikou-sync/v1/devices/a1/0-1.jsonl.gz") { it.readBytes().toList() } shouldBe listOf<Byte>(1, 2, 3)

        backend.delete("saikou-sync/v1/devices/a1/0-1.jsonl.gz")
        backend.delete("saikou-sync/v1/devices/a1/0-1.jsonl.gz")
        backend.list("saikou-sync/v1/devices/") shouldBe emptyList()
        shouldThrow<FileNotFoundException> { backend.read("saikou-sync/v1/devices/a1/0-1.jsonl.gz") { it.read() } }
    }

    @Test
    fun `a hidden file is left out of listings but can still be read, and a listing can be reordered`() = runBlocking {
        val backend = InMemorySyncBackend()
        val paths = (1..4).map { "p/$it" }
        paths.forEach { backend.create(it, SyncContent(byteArrayOf(it.last().code.toByte()))) }

        backend.hide("p/2")
        backend.listingOrder = { it.reversed() }

        backend.list("p/").map { it.path } shouldContainExactly listOf("p/4", "p/3", "p/1")
        backend.read("p/2") { it.read() } shouldBe '2'.code
        backend.unhide("p/2")
        backend.list("p/").map { it.path } shouldContainExactly listOf("p/4", "p/3", "p/2", "p/1")
        backend.bytes("p/9").shouldBeNull()
    }

    /**
     * The layout end to end over a backend: a device's export and segments are written, a reader
     * lists and follows the chain, a hidden segment stops it at the gap without skipping past, and
     * the next listing, with the file back, carries it on from where it stopped.
     */
    @Test
    fun `a reader follows another device's chain, stops at a hidden segment and resumes when it is listed`() = runBlocking {
        val backend = InMemorySyncBackend()
        backend.listingOrder = { it.shuffled(Random(3)) }
        val mangaRow = sampleManga()

        write(backend, SyncLayout.exportPartPath(device, "e", 1), SyncScope.ExportPart("e", 1), listOf(mangaRow))
        backend.create(
            SyncLayout.exportDonePath(device, "e"),
            SyncContent(SyncLayout.encodeDone(ExportDone(SyncFormat.CURRENT, parts = 1, lastSeq = 5))),
        )
        for ((after, last) in listOf(5L to 9L, 9L to 12L, 12L to 20L)) {
            val history = HistoryRow(ChapterKey(mangaRow.key, "/c/$last"), last, last)
            val path = SyncLayout.segmentPath(device, after, last)
            write(backend, path, SyncScope.Segment(after, last, 1), listOf(mangaRow, history))
        }
        backend.hide(SyncLayout.segmentPath(device, 9, 12))

        val first = pull(backend, SyncChain.NOTHING_APPLIED)
        first.first shouldBe 9
        first.second shouldBe true
        first.third shouldContainExactly listOf("manga", "manga", "history 9")

        backend.unhide(SyncLayout.segmentPath(device, 9, 12))
        val second = pull(backend, first.first)
        second.first shouldBe 20
        second.second shouldBe false
        second.third shouldContainExactly listOf("manga", "history 12", "manga", "history 20")
    }

    /** Lists, plans and reads every step for [device]; returns the cursor, the gap flag and what it read. */
    private suspend fun pull(backend: InMemorySyncBackend, cursor: Long): Triple<Long, Boolean, List<String>> {
        val files = SyncChain.group(backend.list(SyncLayout.DEVICES).map { it.path }).devices.getValue(device)
        val dones = files.exports.values.mapNotNull { export ->
            val path = export.donePath ?: return@mapNotNull null
            export.exportId to backend.read(path) { SyncLayout.decodeDone(it.readBytes()) }
        }.toMap()
        val plan = SyncChain.plan(files, dones, cursor)
        val read = mutableListOf<String>()
        for (step in plan.steps) {
            val paths = when (step) {
                is ChainStep.Segment -> listOf(step.segment.path)
                is ChainStep.Export -> step.parts
            }
            for (path in paths) {
                backend.read(path) { input ->
                    SegmentReader(input).use { reader ->
                        generateSequence { reader.next() }.forEach { row ->
                            read += when (row) {
                                is HistoryRow -> "history ${row.timeRead}"
                                else -> row.kind.name.lowercase()
                            }
                        }
                    }
                }
            }
        }
        return Triple(plan.cursor, plan.stoppedAtGap, read)
    }

    private suspend fun write(backend: SyncBackend, path: String, scope: SyncScope, rows: List<SyncRow>) {
        val header = SyncHeader(SyncFormat.CURRENT, device, scope, rows.groupingBy { it.kind }.eachCount())
        val bytes = ByteArrayOutputStream()
        SegmentWriter(bytes, header).use { writer -> rows.forEach(writer::write) }
        backend.create(path, SyncContent(bytes.toByteArray()))
    }

    private fun sampleManga() = MangaRow(
        key = MangaKey(1, "/m"),
        favorite = true,
        dateAdded = 1,
        favoriteModifiedAt = null,
        deletedAt = null,
        restoredAt = null,
        version = 1,
        title = "M",
        author = null,
        artist = null,
        description = null,
        genre = null,
        status = 0,
        thumbnailUrl = null,
        updateStrategy = 0,
        memo = buildJsonObject { },
        viewer = 0,
        chapterFlags = 0,
        displayModifiedAt = null,
        notes = "",
        notesModifiedAt = null,
        manualPosition = 0,
        positionModifiedAt = null,
    )
}

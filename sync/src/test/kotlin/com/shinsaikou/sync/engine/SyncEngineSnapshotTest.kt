package com.shinsaikou.sync.engine

import com.shinsaikou.sync.InMemorySyncBackend
import com.shinsaikou.sync.PulledExportParts
import com.shinsaikou.sync.SyncContent
import com.shinsaikou.sync.engine.SyncCompaction.Companion.DAY_MS
import com.shinsaikou.sync.format.MangaRow
import com.shinsaikou.sync.format.RemoteName
import com.shinsaikou.sync.format.SyncLayout
import com.shinsaikou.sync.format.SyncScope
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * Snapshots, compaction and deletion (phase 3 unit E2, Q3): a device that applied everything it
 * lists writes the next generation when the last is two weeks old; a reader whose chain cannot reach
 * what the newest snapshot covers applies it first; a device that skipped a row or stopped at a gap
 * never compacts; and a device deletes only its own files, once the two newest generations cover
 * them and the newest is a week old by the server's clock.
 */
class SyncEngineSnapshotTest {

    private var now = 0L
    private val backend = InMemorySyncBackend(clock = { now })
    private val a = SyncTestDevice(backend)
    private val b = SyncTestDevice(backend)
    private val c = SyncTestDevice(backend)

    @AfterEach
    fun close() {
        a.close()
        b.close()
        c.close()
    }

    private fun snapshots(): List<RemoteName.Snapshot> = backend.paths().mapNotNull { SyncLayout.parse(it) as? RemoteName.Snapshot }

    private fun segments(device: SyncTestDevice): List<RemoteName.Segment> =
        backend.paths().mapNotNull { SyncLayout.parse(it) as? RemoteName.Segment }.filter { it.deviceId == device.deviceId }

    private fun covers(snapshot: RemoteName.Snapshot): Map<String, Long> =
        (backend.rowFile(snapshot.path).first.scope as SyncScope.Snapshot).covers

    /** A edits and syncs, [count] times, one segment each. */
    private fun SyncTestDevice.pushSegments(count: Int, from: Long = 100) {
        repeat(count) { i ->
            addFavourite(from + i)
            sync().shouldNotBeNull().segmentsPushed shouldBe 1
        }
    }

    @Test
    fun `a device compacts once the files past the last snapshot are two weeks old, covering what it applied`() {
        a.buildLibrary()
        a.sync()
        b.addFavourite(50)
        b.sync()
        a.sync()
        snapshots() shouldBe emptyList()

        now = 14 * DAY_MS - 1
        a.pushSegments(1)
        snapshots() shouldBe emptyList()
        now = 14 * DAY_MS
        a.addFavourite(60)
        val result = a.sync().shouldNotBeNull()

        result.compacted shouldBe true
        val snapshot = snapshots().single()
        snapshot.generation shouldBe 1
        snapshot.deviceId shouldBe a.deviceId
        covers(snapshot) shouldBe mapOf(
            a.deviceId to a.preferences.lastPushedSeq.get(),
            b.deviceId to a.preferences.pullCursors.get().getValue(b.deviceId),
        )
        backend.rowFile(snapshot.path).second.filterIsInstance<MangaRow>().map { it.key.url } shouldContain "/series/50"

        // The next run finds it fresh and writes none.
        a.addFavourite(61)
        a.sync().shouldNotBeNull().compacted shouldBe false
    }

    @Test
    fun `a reader whose listing hides a segment it needs applies the newest snapshot, then what follows`() {
        a.buildLibrary()
        a.sync()
        a.pushSegments(3)
        now = 14 * DAY_MS
        a.pushSegments(1, from = 200)
        snapshots() shouldBe listOf(RemoteName.Snapshot(SyncLayout.snapshotPath(1, a.deviceId), 1, a.deviceId))
        now = 15 * DAY_MS
        a.pushSegments(1, from = 300)
        val hidden = segments(a).sortedBy { it.lastSeq }[1]
        backend.hide(hidden.path)

        val result = c.sync().shouldNotBeNull()

        result.snapshotApplied shouldBe true
        result.stoppedAtGap shouldBe false
        // The snapshot put the cursor past the hidden segment, and the last segment applied after it.
        result.segmentsApplied shouldBe 1
        c.preferences.pullCursors.get().getValue(a.deviceId) shouldBe a.preferences.lastPushedSeq.get()
        c.library() shouldBe a.library()
    }

    @Test
    fun `a snapshot that moves a device's cursor drops the mark of that device's export being resumed`() {
        a.buildLibrary()
        a.sync()
        val export = backend.paths().mapNotNull { SyncLayout.parse(it) as? RemoteName.Done }.single { it.deviceId == a.deviceId }
        a.pushSegments(3)
        now = 14 * DAY_MS
        a.pushSegments(1, from = 200)
        snapshots().size shouldBe 1
        now = 15 * DAY_MS
        a.pushSegments(1, from = 300)
        backend.hide(segments(a).sortedBy { it.lastSeq }[1].path)
        // C stopped after part 1 of A's export, and holds a mark for a device the snapshot does not cover.
        c.preferences.pulledExportParts.set(
            mapOf(a.deviceId to PulledExportParts(export.exportId, 1), "x-device" to PulledExportParts("x-export", 2)),
        )

        c.sync().shouldNotBeNull().snapshotApplied shouldBe true

        c.preferences.pullCursors.get().getValue(a.deviceId) shouldBe a.preferences.lastPushedSeq.get()
        c.preferences.pulledExportParts.get() shouldBe mapOf("x-device" to PulledExportParts("x-export", 2))
        c.library() shouldBe a.library()
    }

    @Test
    fun `a reader that can reach everything a snapshot covers never reads it`() {
        a.buildLibrary()
        a.sync()
        now = 14 * DAY_MS
        a.pushSegments(1)
        snapshots().size shouldBe 1

        val result = c.sync().shouldNotBeNull()

        result.snapshotApplied shouldBe false
        result.exportsApplied shouldBe 1
        c.library() shouldBe a.library()
    }

    @Test
    fun `a device that skipped a row it could not store never compacts`() {
        a.addFavourite(1)
        a.sync()
        // A hand-made segment from a device B never read, whose history row names a chapter no one has.
        runBlocking {
            backend.create(
                SyncLayout.segmentPath("x-device", 0, 1),
                SyncContent(
                    rawFile(
                        """{"format":1,"deviceId":"x-device","scope":{"kind":"segment","afterSeq":0,"lastSeq":1,"rawRows":1},""" +
                            """"counts":{"history":1}}""",
                        """{"entity":"history","chapter":{"manga":{"source":4242,"url":"/series/99"},"url":"/c"},""" +
                            """"lastRead":1,"timeRead":1}""",
                    ),
                ),
            )
        }
        b.startReading("x-device", seq = 0)
        now = 14 * DAY_MS

        val skipped = b.sync().shouldNotBeNull()

        skipped.rowsSkipped shouldBe 1
        skipped.stoppedAtGap shouldBe false
        skipped.compacted shouldBe false
        b.preferences.skippedRows.get() shouldBe true
        now = 30 * DAY_MS
        b.addFavourite(2)
        b.sync().shouldNotBeNull().compacted shouldBe false
        snapshots() shouldBe emptyList()
    }

    @Test
    fun `a device that stopped at a gap does not compact until the gap closes`() {
        a.addFavourite(1)
        a.sync()
        a.pushSegments(2)
        val first = segments(a).minBy { it.lastSeq }
        backend.hide(first.path)
        now = 14 * DAY_MS

        val gapped = c.sync().shouldNotBeNull()

        gapped.stoppedAtGap shouldBe true
        gapped.compacted shouldBe false
        snapshots() shouldBe emptyList()
        backend.unhide(first.path)
        c.sync().shouldNotBeNull().compacted shouldBe true
    }

    @Test
    fun `a device deletes only its own files, once every recent snapshot covers them and the newest is a week old`() {
        a.buildLibrary()
        a.sync()
        a.pushSegments(1)
        b.addFavourite(50)
        b.sync()
        // An export of A's that never finished, as a restore that cut one short leaves.
        runBlocking { backend.create(SyncLayout.exportPartPath(a.deviceId, "cut-short", 1), SyncContent(rawFile("{}"))) }
        now = 14 * DAY_MS
        a.pushSegments(1, from = 200)
        val snapshot = snapshots().single()
        val covered = covers(snapshot).getValue(a.deviceId)
        backend.paths().any { "cut-short" in it } shouldBe false
        val aFilesBefore = backend.paths().filter { a.deviceId in it && "snapshot" !in it }

        now = 21 * DAY_MS - 1
        a.pushSegments(1, from = 300)
        a.sync().shouldNotBeNull().filesDeleted shouldBe 0

        now = 21 * DAY_MS
        a.addFavourite(400)
        val result = a.sync().shouldNotBeNull()

        // A's export (a part and done.json) and its segments through the snapshot's cover.
        val deleted = aFilesBefore.filterNot { it in backend.paths() }
        result.filesDeleted shouldBe deleted.size
        deleted.size shouldBe 4
        segments(a).forEach { (it.lastSeq > covered) shouldBe true }
        backend.paths().filter { b.deviceId in it } shouldContainExactly
            listOf(SyncLayout.exportDonePath(b.deviceId, exportIdOf(b)), SyncLayout.exportPartPath(b.deviceId, exportIdOf(b), 1))
        backend.paths() shouldContain snapshot.path

        // A reader that starts now finds A's early files gone and reads them from the snapshot.
        val result2 = c.sync().shouldNotBeNull()
        result2.snapshotApplied shouldBe true
        c.library() shouldBe a.library()
    }

    /**
     * Two snapshots of one generation that disagree on how far they cover A, a day apart: the one
     * the tie-break picks (the lower device id) covers only A's export, since its device's listing
     * lagged behind A's segments and the other snapshot. A keeps every segment the lesser cover
     * leaves out, and waits a week from the newer snapshot, not the older.
     */
    @Test
    fun `a device keeps what any recent snapshot leaves uncovered, and waits a week from the newest of them`() {
        val (low, high) = listOf(b, c).sortedBy { it.deviceId }
        a.buildLibrary()
        a.sync()
        val exported = a.preferences.lastPushedSeq.get()
        val aExport = backend.paths().filter { it.startsWith(SyncLayout.devicePrefix(a.deviceId)) }
        a.pushSegments(3)
        val aSegments = segments(a).map { it.path }

        now = 14 * DAY_MS
        high.addFavourite(50)
        high.sync().shouldNotBeNull().compacted shouldBe true
        val highSnapshot = RemoteName.Snapshot(SyncLayout.snapshotPath(1, high.deviceId), 1, high.deviceId)
        covers(highSnapshot).getValue(a.deviceId) shouldBe a.preferences.lastPushedSeq.get()

        now = 15 * DAY_MS
        val lagging = aSegments + highSnapshot.path
        lagging.forEach(backend::hide)
        low.addFavourite(60)
        low.sync().shouldNotBeNull().compacted shouldBe true
        lagging.forEach(backend::unhide)
        val lowSnapshot = RemoteName.Snapshot(SyncLayout.snapshotPath(1, low.deviceId), 1, low.deviceId)
        covers(lowSnapshot).getValue(a.deviceId) shouldBe exported
        snapshots().toSet() shouldBe setOf(highSnapshot, lowSnapshot)

        // The older snapshot is past a week, the newer is not: nothing goes yet.
        now = 22 * DAY_MS - 1
        a.addFavourite(500)
        a.sync().shouldNotBeNull().filesDeleted shouldBe 0

        now = 22 * DAY_MS
        a.addFavourite(600)
        val before = backend.paths()
        val result = a.sync().shouldNotBeNull()

        // Only the export both cover goes; the segments only the other snapshot covers stay.
        val deleted = before.filterNot { it in backend.paths() }
        deleted.toSet() shouldBe aExport.toSet()
        result.filesDeleted shouldBe deleted.size
        backend.paths().filter { it in aSegments } shouldContainExactly aSegments

        // A device joining now starts from the lesser snapshot and reaches A through those segments.
        SyncTestDevice(backend).use { joined ->
            val join = joined.sync().shouldNotBeNull()
            join.snapshotApplied shouldBe true
            join.stoppedAtGap shouldBe false
            joined.preferences.pullCursors.get().getValue(a.deviceId) shouldBe a.preferences.lastPushedSeq.get()
            joined.library() shouldBe a.library()
        }
    }

    @Test
    fun `a snapshot older than the two newest generations is deleted by its own device a week after`() {
        a.addFavourite(1)
        a.sync()
        for (generation in 1..3) {
            now = generation * 14 * DAY_MS
            a.pushSegments(1, from = generation * 100L)
            snapshots().maxOf { it.generation } shouldBe generation
        }
        backend.paths() shouldContain SyncLayout.snapshotPath(1, a.deviceId)

        now = 3 * 14 * DAY_MS + 7 * DAY_MS
        a.pushSegments(1, from = 900)

        val left = snapshots().map { it.generation }
        left shouldNotContain 1L
        left shouldContainExactly listOf(2L, 3L)
    }

    private fun exportIdOf(device: SyncTestDevice): String =
        backend.paths().firstNotNullOf { (SyncLayout.parse(it) as? RemoteName.Done)?.takeIf { d -> d.deviceId == device.deviceId } }
            .exportId
}

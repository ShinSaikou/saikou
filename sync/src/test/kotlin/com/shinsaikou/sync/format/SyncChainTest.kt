package com.shinsaikou.sync.format

import com.shinsaikou.sync.format.SyncChain.NOTHING_APPLIED
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SyncChainTest {

    private val device = "b-device"

    private fun segment(afterSeq: Long, lastSeq: Long) = SyncLayout.segmentPath(device, afterSeq, lastSeq)

    private fun part(exportId: String, n: Int) = SyncLayout.exportPartPath(device, exportId, n)

    private fun done(exportId: String) = SyncLayout.exportDonePath(device, exportId)

    private fun plan(paths: List<String>, cursor: Long, dones: Map<String, ExportDone> = emptyMap()): ChainPlan {
        val files = SyncChain.group(paths).devices[device] ?: DeviceFiles(device, emptyList(), emptyMap())
        return SyncChain.plan(files, dones, cursor)
    }

    /** The ranges a plan applies, `after-last` for a segment and `full-<id>` for an export. */
    private fun ChainPlan.ranges(): List<String> = steps.map { step ->
        when (step) {
            is ChainStep.Segment -> "${step.segment.afterSeq}-${step.segment.lastSeq}"
            is ChainStep.Export -> "full-${step.exportId}"
        }
    }

    // region segments

    @Test
    fun `a contiguous chain applies in lastSeq order whatever order the listing gives`() {
        val paths = listOf(segment(200, 300), segment(0, 100), segment(100, 200))

        val result = plan(paths, cursor = 0)

        result.ranges() shouldContainExactly listOf("0-100", "100-200", "200-300")
        result.cursor shouldBe 300
        result.stoppedAtGap shouldBe false
    }

    @Test
    fun `a missing range stops the reader before it and says so`() {
        val result = plan(listOf(segment(0, 100), segment(200, 300)), cursor = 0)

        result.ranges() shouldContainExactly listOf("0-100")
        result.cursor shouldBe 100
        result.stoppedAtGap shouldBe true
    }

    @Test
    fun `segments at or below the cursor are skipped and a reader already past every file is caught up`() {
        val paths = listOf(segment(0, 100), segment(100, 200))

        plan(paths, cursor = 150).ranges() shouldContainExactly listOf("100-200")
        val caughtUp = plan(paths, cursor = 200)
        caughtUp.steps.shouldBeEmpty()
        caughtUp.cursor shouldBe 200
        caughtUp.stoppedAtGap shouldBe false
    }

    @Test
    fun `a retried push that overlaps an earlier segment is applied after it, and moves the cursor to its end`() {
        // The pusher died after writing 100-200 and before storing its cursor, then pushed 100-250.
        val result = plan(listOf(segment(0, 100), segment(100, 200), segment(100, 250), segment(250, 300)), cursor = 0)

        result.ranges() shouldContainExactly listOf("0-100", "100-200", "100-250", "250-300")
        result.cursor shouldBe 300
        result.stoppedAtGap shouldBe false
    }

    @Test
    fun `a wide segment that starts at the cursor bridges a narrow one that starts past it`() {
        val result = plan(listOf(segment(50, 80), segment(0, 100)), cursor = 0)

        result.ranges() shouldContainExactly listOf("0-100")
        result.cursor shouldBe 100
        result.stoppedAtGap shouldBe false
    }

    @Test
    fun `a range listed twice is applied once`() {
        val result = plan(listOf(segment(0, 100), segment(0, 100), segment(100, 200), segment(100, 200)), cursor = 0)

        result.ranges() shouldContainExactly listOf("0-100", "100-200")
    }

    @Test
    fun `a segment that starts past the cursor is not applied, even when it ends where the applied one does`() {
        val result = plan(listOf(segment(40, 100), segment(0, 100)), cursor = 0)

        result.ranges() shouldContainExactly listOf("0-100")
    }

    @Test
    fun `two segments both already reachable and ending at one seq apply the one with the lower afterSeq`() {
        // Both afterSeq 40 and afterSeq 0 are at or below the cursor here, unlike the case above, so
        // the comparator's afterSeq key chooses, not the candidate filter. The loser is listed first:
        // on a full tie minWithOrNull keeps the first it sees, so without the key 40-100 would win.
        val result = plan(listOf(segment(40, 100), segment(0, 100)), cursor = 40)

        result.ranges() shouldContainExactly listOf("0-100")
        result.cursor shouldBe 100
        result.stoppedAtGap shouldBe false
    }

    @Test
    fun `a reader that has applied nothing does not start from a segment`() {
        val result = plan(listOf(segment(0, 100)), cursor = NOTHING_APPLIED)

        result.steps.shouldBeEmpty()
        result.cursor shouldBe NOTHING_APPLIED
        result.stoppedAtGap shouldBe true
    }

    // endregion

    // region full exports

    @Test
    fun `a finished export starts a new reader's chain, even at lastSeq 0, and segments follow it`() {
        val paths = listOf(part("e1", 2), part("e1", 1), done("e1"), segment(0, 40))
        val dones = mapOf("e1" to ExportDone(SyncFormat.CURRENT, parts = 2, lastSeq = 0))

        val result = plan(paths, NOTHING_APPLIED, dones)

        result.steps.first() shouldBe ChainStep.Export("e1", listOf(part("e1", 1), part("e1", 2)), 0)
        result.ranges() shouldContainExactly listOf("full-e1", "0-40")
        result.cursor shouldBe 40
        result.stoppedAtGap shouldBe false
    }

    @Test
    fun `an export without done json is ignored, and so is one whose done json the caller did not read`() {
        val paths = listOf(part("e1", 1), segment(10, 20))

        val result = plan(paths, NOTHING_APPLIED)
        result.steps.shouldBeEmpty()
        result.stoppedAtGap shouldBe true

        plan(paths + done("e1"), NOTHING_APPLIED).steps.shouldBeEmpty()
    }

    @Test
    fun `an export missing a part its done json names is not applied and leaves a gap`() {
        val paths = listOf(part("e1", 1), part("e1", 3), done("e1"))
        val dones = mapOf("e1" to ExportDone(SyncFormat.CURRENT, parts = 3, lastSeq = 90))

        val result = plan(paths, NOTHING_APPLIED, dones)

        result.steps.shouldBeEmpty()
        result.stoppedAtGap shouldBe true
    }

    @Test
    fun `an export after a restore is applied by a reader part way along, between the segments around it`() {
        // Pushed 0-100 and 100-150, restored a backup, exported at 180, then pushed 180-220.
        val paths = listOf(segment(0, 100), segment(100, 150), part("r", 1), done("r"), segment(180, 220))
        val dones = mapOf("r" to ExportDone(SyncFormat.CURRENT, parts = 1, lastSeq = 180))

        plan(paths, cursor = 100, dones).ranges() shouldContainExactly listOf("100-150", "full-r", "180-220")
        plan(paths, cursor = 180, dones).ranges() shouldContainExactly listOf("180-220")
    }

    @Test
    fun `an export bridges a range whose segments are gone`() {
        val paths = listOf(part("e", 1), done("e"), segment(300, 350))
        val dones = mapOf("e" to ExportDone(SyncFormat.CURRENT, parts = 1, lastSeq = 300))

        val result = plan(paths, cursor = 120, dones)

        result.ranges() shouldContainExactly listOf("full-e", "300-350")
        result.stoppedAtGap shouldBe false
    }

    @Test
    fun `a segment is preferred to an export that ends at the same seq`() {
        val paths = listOf(segment(0, 100), part("e", 1), done("e"))
        val dones = mapOf("e" to ExportDone(SyncFormat.CURRENT, parts = 1, lastSeq = 100))

        plan(paths, cursor = 0, dones).ranges() shouldContainExactly listOf("0-100")
    }

    // endregion

    // region grouping and snapshots

    @Test
    fun `a listing groups by device, keeps snapshots apart and drops what the layout never writes`() {
        val other = "a-device"
        val layout = SyncChain.group(
            listOf(
                segment(0, 10),
                SyncLayout.segmentPath(other, 5, 6),
                SyncLayout.exportDonePath(other, "x"),
                SyncLayout.snapshotPath(2, other),
                "saikou-sync/v1/devices/$device/notes.txt",
                "elsewhere/0-10.jsonl.gz",
            ),
        )

        layout.devices.keys shouldBe setOf(device, other)
        layout.devices.getValue(device).segments.map { it.lastSeq } shouldContainExactly listOf(10L)
        layout.devices.getValue(other).exports.getValue("x") shouldBe
            ExportFiles("x", emptyMap(), SyncLayout.exportDonePath(other, "x"))
        layout.snapshots.map { it.generation } shouldContainExactly listOf(2L)
    }

    @Test
    fun `the newest generation wins, then the lowest device id`() {
        fun snapshot(generation: Long, deviceId: String) =
            SyncLayout.parse(SyncLayout.snapshotPath(generation, deviceId)) as RemoteName.Snapshot

        val picked = SyncChain.selectSnapshot(
            listOf(snapshot(4, "c"), snapshot(5, "b"), snapshot(3, "a"), snapshot(5, "a1"), snapshot(5, "d")),
        )

        picked shouldBe snapshot(5, "a1")
        SyncChain.selectSnapshot(emptyList()).shouldBeNull()
    }

    // endregion
}

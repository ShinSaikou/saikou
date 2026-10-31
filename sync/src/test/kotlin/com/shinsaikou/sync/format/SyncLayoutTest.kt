package com.shinsaikou.sync.format

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SyncLayoutTest {

    private val device = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"

    @Test
    fun `each name is Q3's and parses back to what made it`() {
        val segment = SyncLayout.segmentPath(device, 0, 5_000)
        val part = SyncLayout.exportPartPath(device, "e-7", 12)
        val done = SyncLayout.exportDonePath(device, "e-7")
        val snapshot = SyncLayout.snapshotPath(3, device)

        segment shouldBe "saikou-sync/v1/devices/$device/0-5000.jsonl.gz"
        part shouldBe "saikou-sync/v1/devices/$device/full-e-7/part-12.jsonl.gz"
        done shouldBe "saikou-sync/v1/devices/$device/full-e-7/done.json"
        snapshot shouldBe "saikou-sync/v1/snapshot-3-$device.jsonl.gz"

        SyncLayout.parse(segment) shouldBe RemoteName.Segment(segment, device, 0, 5_000)
        SyncLayout.parse(part) shouldBe RemoteName.ExportPart(part, device, "e-7", 12)
        SyncLayout.parse(done) shouldBe RemoteName.Done(done, device, "e-7")
        SyncLayout.parse(snapshot) shouldBe RemoteName.Snapshot(snapshot, 3, device)
    }

    @Test
    fun `a path the layout never writes parses to null`() {
        listOf(
            "saikou-sync/v1/devices/$device/5-5.jsonl.gz",
            "saikou-sync/v1/devices/$device/9-5.jsonl.gz",
            "saikou-sync/v1/devices/$device/05-9.jsonl.gz",
            "saikou-sync/v1/devices/$device/5-9.jsonl",
            "saikou-sync/v1/devices/$device/5-9.jsonl.gz.part",
            "saikou-sync/v1/devices/$device/full-e/part-0.jsonl.gz",
            "saikou-sync/v1/devices/$device/full-e/other.json",
            "saikou-sync/v1/devices/$device/nested/1-2.jsonl.gz",
            "saikou-sync/v1/devices/1-2.jsonl.gz",
            "saikou-sync/v1/snapshot-0-$device.jsonl.gz",
            "saikou-sync/v1/snapshot-x-$device.jsonl.gz",
            "saikou-sync/v2/snapshot-1-$device.jsonl.gz",
            "saikou-sync/v1/devices/a_b/1-2.jsonl.gz",
            "other/1-2.jsonl.gz",
        ).forEach { SyncLayout.parse(it).shouldBeNull() }
    }

    @Test
    fun `a name with an id that is not letters, digits and dashes, or a number out of range, is refused`() {
        shouldThrow<IllegalArgumentException> { SyncLayout.segmentPath("a/b", 0, 1) }
        shouldThrow<IllegalArgumentException> { SyncLayout.segmentPath("", 0, 1) }
        shouldThrow<IllegalArgumentException> { SyncLayout.segmentPath(device, 3, 3) }
        shouldThrow<IllegalArgumentException> { SyncLayout.segmentPath(device, -1, 3) }
        shouldThrow<IllegalArgumentException> { SyncLayout.exportPartPath(device, "e.1", 1) }
        shouldThrow<IllegalArgumentException> { SyncLayout.exportPartPath(device, "e", 0) }
        shouldThrow<IllegalArgumentException> { SyncLayout.snapshotPath(0, device) }
    }

    @Test
    fun `done json round trips, refuses a newer format and names anything else malformed`() {
        val done = ExportDone(SyncFormat.CURRENT, parts = 4, lastSeq = 812)
        val bytes = SyncLayout.encodeDone(done)

        bytes.decodeToString() shouldBe """{"format":2,"parts":4,"lastSeq":812}"""
        SyncLayout.decodeDone(bytes) shouldBe done
        SyncLayout.decodeDone("""{"format":2,"parts":4,"lastSeq":812,"later":[1]}""".encodeToByteArray()) shouldBe done

        val newer = shouldThrow<NewerFormatException> {
            SyncLayout.decodeDone("""{"format":3,"shape":"unknown"}""".encodeToByteArray())
        }
        newer.found shouldBe 3
        shouldThrow<MalformedSyncFileException> { SyncLayout.decodeDone("""{"parts":4}""".encodeToByteArray()) }
        shouldThrow<MalformedSyncFileException> { SyncLayout.decodeDone("""{"format":1}""".encodeToByteArray()) }
        shouldThrow<MalformedSyncFileException> { SyncLayout.decodeDone("[]".encodeToByteArray()) }
    }

    /**
     * An export with no parts would be a finished export with nothing to read: `SyncChain.plan` would
     * apply it as an empty step and move the reader's cursor past the device's whole state.
     */
    @Test
    fun `a done json with no parts or a negative lastSeq is malformed, and no ExportDone holds one`() {
        for (json in listOf(
            """{"format":1,"parts":0,"lastSeq":500}""",
            """{"format":1,"parts":-5,"lastSeq":500}""",
            """{"format":1,"parts":2,"lastSeq":-1}""",
        )) {
            shouldThrow<MalformedSyncFileException> { SyncLayout.decodeDone(json.encodeToByteArray()) }
        }
        // A format 1 file, as the build before format 2 wrote it, still reads.
        SyncLayout.decodeDone("""{"format":1,"parts":1,"lastSeq":0}""".encodeToByteArray()) shouldBe
            ExportDone(1, parts = 1, lastSeq = 0)

        shouldThrow<IllegalArgumentException> { ExportDone(SyncFormat.CURRENT, parts = 0, lastSeq = 500) }
        shouldThrow<IllegalArgumentException> { ExportDone(SyncFormat.CURRENT, parts = 1, lastSeq = -1) }
    }
}

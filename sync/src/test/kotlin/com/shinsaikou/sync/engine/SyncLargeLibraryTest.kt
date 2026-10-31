package com.shinsaikou.sync.engine

import com.shinsaikou.domain.sync.model.SyncPushRows
import com.shinsaikou.domain.sync.repository.SyncApplyScope
import com.shinsaikou.domain.sync.repository.SyncStore
import com.shinsaikou.sync.InMemorySyncBackend
import com.shinsaikou.sync.format.RemoteName
import com.shinsaikou.sync.format.SyncLayout
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream

/**
 * PLAN's "3,000-entry libraries do not OOM" for the engine (phase 3 unit E2's suite), measured on
 * the heap rather than trusted. A 3,000-entry library with [CHAPTERS] chapters each goes through
 * every path that handles a whole library: A's full export, B's pull of it, A's snapshot, and a
 * third device that can only start from that snapshot. While each runs, the store samples the live
 * heap (after full collections) as it hands over a page or takes a chunk, less what the in-memory
 * backend itself holds. The library as JSON text alone is over twice [BUDGET], and as decoded rows
 * several times that; the engine, which pages its reads, applies 2,000 rows per transaction and
 * spools a snapshot to disk, stays under it.
 */
class SyncLargeLibraryTest {

    private val backend = InMemorySyncBackend()
    private val probes = mutableListOf<HeapProbe>()

    private fun device(name: String): SyncTestDevice =
        SyncTestDevice(backend, storeWrapper = { HeapProbe(name, it, ::backendBytes).also(probes::add) })

    private val a = device("A")
    private val b = device("B")
    private val c = device("C")

    @AfterEach
    fun close() {
        listOf(a, b, c).forEach { it.close() }
    }

    private fun backendBytes(): Long = backend.paths().sumOf { (backend.bytes(it)?.size ?: 0).toLong() }

    /** Samples the heap on every [EVERY]th page read or chunk applied, and keeps the peak above [baseline]. */
    private class HeapProbe(val name: String, private val real: SyncStore, private val remote: () -> Long) : SyncStore by real {
        var baseline = 0L
        var peak = 0L
        var samples = 0
        private var calls = 0

        private fun sample() {
            if (++calls % EVERY != 0) return
            peak = maxOf(peak, liveBytes() - remote() - baseline)
            samples++
        }

        override suspend fun readLibrary(mangaIds: Collection<Long>): SyncPushRows = real.readLibrary(mangaIds).also { sample() }

        override suspend fun <T> apply(block: suspend SyncApplyScope.() -> T): T {
            sample()
            return real.apply(block)
        }
    }

    @Test
    fun `a 3,000 entry library exports, pulls, compacts and joins from a snapshot within a bounded heap`() {
        a.bulkLibrary(entries = ENTRIES, chapters = CHAPTERS, description = DESCRIPTION)
        val baseline = liveBytes()
        probes.forEach { it.baseline = baseline }

        a.sync().shouldNotBeNull().exported shouldBe true
        b.sync().shouldNotBeNull().exportsApplied shouldBe 1
        // Two weeks on, a new segment moves the server's time and A compacts.
        backend.clock = { 15 * DAY }
        a.exec("UPDATE chapters SET read = 1 WHERE _id = 1003")
        a.sync().shouldNotBeNull().compacted shouldBe true
        // C sees no finished export, so it can start only from the snapshot.
        backend.paths().filter { SyncLayout.parse(it) is RemoteName.Done }.forEach(backend::hide)
        c.sync().shouldNotBeNull().snapshotApplied shouldBe true

        // The whole library as JSON, what holding it at once would at least cost.
        val json = backend.paths().filter { SyncLayout.parse(it) is RemoteName.ExportPart && it.contains(a.deviceId) }
            .sumOf { path -> GZIPInputStream(ByteArrayInputStream(backend.bytes(path)!!)).use { it.copyTo(NullOutputStream) } }
        println(
            "library: ${a.long("SELECT count(*) FROM chapters")} chapters, $json bytes of JSON in A's export; " +
                probes.joinToString { "${it.name} peaked ${it.peak / MIB} MiB over ${it.samples} samples" },
        )
        json shouldBeGreaterThan 2 * BUDGET
        probes.forEach { probe ->
            probe.samples shouldBeGreaterThan 5
            probe.peak shouldBeLessThan BUDGET
        }
        b.sync().shouldNotBeNull().segmentsApplied shouldBe 1
        for (device in listOf(b, c)) device.summary() shouldBe a.summary()
        listOf(a, b, c).forEach { it.latchesRaised() shouldBe 0 }
    }

    /** Counts and sums over every synced table, a comparison that does not load 100,000 rows. */
    private fun SyncTestDevice.summary() = rows(
        """
        SELECT (SELECT count(*) FROM mangas WHERE favorite = 1), (SELECT count(*) FROM chapters),
            (SELECT sum(read) FROM chapters), (SELECT sum(bookmark) FROM chapters),
            (SELECT sum(last_page_read) FROM chapters), (SELECT count(*) FROM history),
            (SELECT sum(time_read) FROM history), (SELECT count(*) FROM mangas_categories),
            (SELECT sum(length(description)) FROM mangas)
        """,
        9,
    )

    private object NullOutputStream : OutputStream() {
        override fun write(b: Int) = Unit

        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }

    private companion object {
        const val ENTRIES = 3_000
        const val CHAPTERS = 40
        const val EVERY = 8
        const val DAY = 24L * 60 * 60 * 1000
        const val MIB = 1024L * 1024
        const val BUDGET = 20 * MIB
        val DESCRIPTION = "A description of a usual length, repeated. ".repeat(30)

        /** The heap still reachable after full collections: what the code holds, not what it dropped. */
        fun liveBytes(): Long {
            val runtime = Runtime.getRuntime()
            repeat(3) { System.gc() }
            return runtime.totalMemory() - runtime.freeMemory()
        }
    }
}

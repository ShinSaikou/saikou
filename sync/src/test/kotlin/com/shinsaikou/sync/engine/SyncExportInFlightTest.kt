package com.shinsaikou.sync.engine

import com.shinsaikou.sync.InMemorySyncBackend
import com.shinsaikou.sync.SyncBackend
import com.shinsaikou.sync.SyncContent
import com.shinsaikou.sync.format.MangaRow
import com.shinsaikou.sync.format.RemoteName
import com.shinsaikou.sync.format.SyncLayout
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * A full export's parts in flight (phase 5 unit SC3): part 1 written alone, then up to
 * `SyncPush.PARTS_IN_FLIGHT` uploads at once while the next part is planned. Whatever order the
 * uploads finish in, every part lands whole and in the order of its content; the saved progress is
 * the highest part N with parts 1 to N all verified at every point; `done.json` is written only after
 * every part's verified write has returned; a cancel stops every upload under way; and a run that
 * stopped with parts above its mark already written resumes to the export a clean run makes.
 *
 * The library is 20 entries of 999 chapters, four entries to a part's 5,000 lines: five parts. The
 * uploads are held in virtual time ([runTest]), each part for its own while, so they finish out of
 * order.
 */
class SyncExportInFlightTest {

    private val backend = InMemorySyncBackend()
    private val device = SyncTestDevice(backend).apply { bulkLibrary(entries = ENTRIES, chapters = CHAPTERS) }

    @AfterEach
    fun close() {
        device.close()
    }

    private fun partOf(path: String): Int? = (SyncLayout.parse(path) as? RemoteName.ExportPart)?.part

    private fun exportIds(on: InMemorySyncBackend, of: SyncTestDevice): List<String> = on.paths()
        .mapNotNull { SyncLayout.parse(it) as? RemoteName.Done }
        .filter { it.deviceId == of.deviceId }
        .map { it.exportId }

    private fun InMemorySyncBackend.part(of: SyncTestDevice, exportId: String, part: Int) =
        rowFile(SyncLayout.exportPartPath(of.deviceId, exportId, part))

    private fun InMemorySyncBackend.done(of: SyncTestDevice, exportId: String) =
        SyncLayout.decodeDone(bytes(SyncLayout.exportDonePath(of.deviceId, exportId))!!)

    /**
     * [delegate], each part's write held [holdMillis] of its number before it is stored, recording in
     * [events] each write's start and return and each read-back's return, and in [violations] every
     * point where the saved progress named a part that was not verified yet, or a part above one
     * that was not.
     */
    private inner class Holding(
        private val delegate: InMemorySyncBackend,
        private val holdMillis: (Int) -> Long,
    ) : SyncBackend by delegate {
        val events = mutableListOf<String>()
        val verified = mutableSetOf<Int>()
        val violations = mutableListOf<String>()
        var verifiedAtDone: Set<Int>? = null
        private var writing = 0
        var mostWriting = 0
            private set

        private fun checkProgress(at: String) {
            val saved = device.preferences.exportProgress.get()?.parts ?: return
            val contiguous = generateSequence(1) { it + 1 }.takeWhile { it in verified }.count()
            if (saved > contiguous) {
                violations += "at $at the progress named part $saved with parts 1 to $contiguous verified"
            }
        }

        override suspend fun create(path: String, content: SyncContent) {
            val part = partOf(path)
            if (part == null) {
                if (path.endsWith("/done.json")) verifiedAtDone = verified.toSet()
                delegate.create(path, content)
                return
            }
            events += "start $part"
            checkProgress("the start of part $part")
            writing++
            mostWriting = maxOf(mostWriting, writing)
            try {
                delay(holdMillis(part))
                delegate.create(path, content)
            } finally {
                writing--
            }
            events += "return $part"
            checkProgress("the return of part $part")
        }

        override suspend fun <T> read(path: String, block: suspend (InputStream) -> T): T {
            val result = delegate.read(path, block)
            partOf(path)?.let { part ->
                verified += part
                events += "verified $part"
                checkProgress("the read-back of part $part")
            }
            return result
        }
    }

    /** Part 3 slowest, then 2, 5, 4: none in the order it started. */
    private val outOfOrder: (Int) -> Long = { part ->
        mapOf(1 to 10L, 2 to 300L, 3 to 400L, 4 to 100L, 5 to 200L).getValue(part)
    }

    @Test
    fun `parts written three at a time land whole and in the order of their content, whatever order they finish in`() =
        runTest {
            val holding = Holding(backend, outOfOrder)

            device.engine.run(holding).shouldNotBeNull().exported shouldBe true

            // Part 1 alone, then three at once while the next is planned.
            holding.events.take(3) shouldContainExactly listOf("start 1", "return 1", "verified 1")
            holding.mostWriting shouldBe 3
            val returned = holding.events.filter { it.startsWith("return ") }.map { it.substringAfter(' ').toInt() }
            returned shouldNotBe returned.sorted()
            // Each part holds the next four entries, in id order.
            val exportId = exportIds(backend, device).single()
            val urls = (1..5).map { part ->
                backend.part(device, exportId, part).second.filterIsInstance<MangaRow>().map { it.key.url }
            }
            urls shouldBe (1..ENTRIES).map { "/series/$it" }.chunked(4)
            backend.done(device, exportId).parts shouldBe 5
            SyncTestDevice(backend).use { joined ->
                joined.sync()
                joined.library() shouldBe device.library()
            }
        }

    @Test
    fun `the saved progress is contiguous at every point, and done json waits for every part's verified write`() =
        runTest {
            val holding = Holding(backend, outOfOrder)

            device.engine.run(holding).shouldNotBeNull()

            holding.violations shouldBe emptyList()
            holding.verifiedAtDone shouldBe (1..5).toSet()
            device.preferences.exportProgress.get().shouldBeNull()
        }

    /** [delegate], every part after the first held until it is cancelled, which [cancelled] records. */
    private class Hanging(private val delegate: InMemorySyncBackend) : SyncBackend by delegate {
        val started = mutableListOf<Int>()
        val cancelled = mutableListOf<Int>()

        override suspend fun create(path: String, content: SyncContent) {
            val part = (SyncLayout.parse(path) as? RemoteName.ExportPart)?.part
            if (part == null || part == 1) return delegate.create(path, content)
            started += part
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                cancelled += part
                throw e
            }
        }
    }

    @Test
    fun `a cancel during three uploads cancels all three and leaves the progress at part 1`() = runTest {
        val hanging = Hanging(backend)

        val run = launch { device.engine.run(hanging) }
        advanceUntilIdle()
        hanging.started shouldContainExactly listOf(2, 3, 4)
        run.cancel()
        run.join()

        hanging.cancelled shouldContainExactly listOf(2, 3, 4)
        device.preferences.exportProgress.get().shouldNotBeNull().parts shouldBe 1
        device.preferences.exportedBackend.get() shouldBe ""
        backend.paths().filter { it.endsWith("/done.json") } shouldBe emptyList()
    }

    /** [delegate] failing part 2's write after its hold, the others written after theirs. */
    private class FailingPart2(private val delegate: InMemorySyncBackend) : SyncBackend by delegate {
        override suspend fun create(path: String, content: SyncContent) {
            when ((SyncLayout.parse(path) as? RemoteName.ExportPart)?.part) {
                2 -> {
                    delay(300)
                    throw IOException("Connection reset writing $path")
                }
                3, 4 -> delay(100)
            }
            delegate.create(path, content)
        }
    }

    @Test
    fun `a run stopped with parts above its mark written resumes to the export a clean run makes`(@TempDir dir: File) =
        runTest {
            // The library before any run, for the clean run to start from.
            val start = File(dir, "start.db")
            device.exec("VACUUM INTO '${start.path}'")

            shouldThrow<IOException> { device.engine.run(FailingPart2(backend)) }

            val progress = device.preferences.exportProgress.get().shouldNotBeNull()
            progress.parts shouldBe 1
            // Parts 3 and 4 finished before part 2 failed: written above the mark, and written again.
            backend.bytes(SyncLayout.exportPartPath(device.deviceId, progress.exportId, 3)).shouldNotBeNull()
            device.engine.run(backend).shouldNotBeNull().exported shouldBe true
            exportIds(backend, device) shouldBe listOf(progress.exportId)

            val cleanBackend = InMemorySyncBackend()
            SyncTestDevice(cleanBackend, url = "jdbc:sqlite:${start.path}").use { clean ->
                clean.sync()
                val cleanId = exportIds(cleanBackend, clean).single()
                for (part in 1..5) {
                    val (header, rows) = backend.part(device, progress.exportId, part)
                    val (cleanHeader, cleanRows) = cleanBackend.part(clean, cleanId, part)
                    rows shouldBe cleanRows
                    header.counts shouldBe cleanHeader.counts
                }
                backend.done(device, progress.exportId) shouldBe cleanBackend.done(clean, cleanId)
                clean.library() shouldBe device.library()
                backend.paths().count { partOf(it) != null } shouldBe 5
            }
        }

    private companion object {
        const val ENTRIES = 20
        const val CHAPTERS = 999
    }
}

package com.shinsaikou.sync.engine

import com.shinsaikou.sync.InMemorySyncBackend
import com.shinsaikou.sync.MapPreferenceStore
import com.shinsaikou.sync.PulledExportParts
import com.shinsaikou.sync.SyncBackend
import com.shinsaikou.sync.SyncPreferences
import com.shinsaikou.sync.format.RemoteName
import com.shinsaikou.sync.format.SyncLayout
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.InputStream

/**
 * A full export pulled part by part (phase 3 unit I, R89 carry-over 1). A holds a library of three
 * export parts, each more than one pull transaction, and B, a new reader, pulls it. B is killed
 * right after each event of its pull in turn: every transaction its store commits and every part
 * mark it writes. The same install, started again, must read no part at or below the mark the
 * killed run left, read every part above it once, and reach the database, cursors and marks an
 * uninterrupted pull reaches. An export with another id starts at part 1, and so does the same
 * export after a restore.
 */
class SyncPullResumeTest {

    @TempDir
    lateinit var dir: File

    private val backend = InMemorySyncBackend()

    /** [real], counting each read by path. */
    private class ReadCounting(private val real: SyncBackend) : SyncBackend by real {
        val reads = mutableMapOf<String, Int>()

        override suspend fun <T> read(path: String, block: suspend (InputStream) -> T): T {
            synchronized(reads) { reads.merge(path, 1, Int::plus) }
            return real.read(path, block)
        }
    }

    /**
     * A preference map that counts each part mark written as an event of [events]. Dropping the
     * mark when the cursor moves writes an empty map, which is not a mark.
     */
    private class MarkCounting(private val events: KillEvents) : HashMap<String, Any>() {
        var marks = 0
            private set

        override fun put(key: String, value: Any): Any? {
            val previous = super.put(key, value)
            if (key == markKey && value != "") {
                marks++
                events.happened()
            }
            return previous
        }
    }

    private var files = 0

    /**
     * B on a file database, as a device whose own first export is taken as made, so its run is the
     * pull alone. Its store's transactions count as events of [events].
     */
    private fun reader(database: File, preferences: MutableMap<String, Any>, events: KillEvents, remote: SyncBackend = backend) =
        SyncTestDevice(
            backend = backend,
            url = "jdbc:sqlite:${database.path}",
            preferenceValues = preferences,
            storeWrapper = { CountingStore(it, events) },
            exported = true,
            remote = remote,
        )

    private fun newDatabase(): File = File(dir, "reader-${files++}.db")

    /** A's export of [entries] favourites, and its parts' paths in order. */
    private fun exportFrom(a: SyncTestDevice, entries: Int): List<String> {
        a.bulkLibrary(entries = entries, chapters = 8)
        a.sync().shouldNotBeNull().exported shouldBe true
        return exportParts(a.deviceId)
    }

    private fun exportParts(deviceId: String): List<String> = backend.paths()
        .mapNotNull { SyncLayout.parse(it) as? RemoteName.ExportPart }
        .filter { it.deviceId == deviceId }
        .sortedBy { it.part }
        .map { it.path }

    private fun marks(preferences: Map<String, Any>): Map<String, PulledExportParts> =
        SyncPreferences(MapPreferenceStore(preferences.toMutableMap())).pulledExportParts.get()

    /** A killed run, then B started again over the same database and preferences, its reads counted. */
    private class Resumed(val marked: Int, val reads: Map<String, Int>)

    private fun killAndResume(killAfter: Int, aId: String, expect: (SyncTestDevice) -> Unit): Resumed {
        val database = newDatabase()
        val killing = KillEvents(killAfter)
        val preferences = MarkCounting(killing)
        reader(database, preferences, killing).use { killed ->
            shouldThrow<Killed> { killed.sync() }
        }
        val marked = marks(preferences)[aId]?.parts ?: 0
        val counting = ReadCounting(backend)
        reader(database, HashMap<String, Any>(preferences), KillEvents(0), counting).use { resumed ->
            resumed.sync().shouldNotBeNull()
            expect(resumed)
        }
        return Resumed(marked, counting.reads.toMap())
    }

    @Test
    fun `a pull killed after any transaction or part mark resumes at the next part and reads no part below it again`() {
        val a = SyncTestDevice(backend)
        // About 12,000 lines: three parts of up to 5,000, each over one 2,000-row transaction.
        val parts = exportFrom(a, entries = 1_000)
        parts.size shouldBe 3

        // The uninterrupted pull, and how many events it has.
        val counted = KillEvents(0)
        val referencePreferences = MarkCounting(counted)
        val reference = reader(newDatabase(), referencePreferences, counted)
        val result = reference.sync().shouldNotBeNull()
        result.exportsApplied shouldBe 1
        referencePreferences.marks shouldBe parts.size
        // More transactions than parts: a stop can land inside a part as well as between two.
        (counted.count - referencePreferences.marks) shouldBeGreaterThan parts.size
        val expected = reference.library()
        val expectedCursors = reference.preferences.pullCursors.get()
        reference.preferences.pulledExportParts.get().shouldBeEmpty()
        reference.library() shouldBe a.library()
        reference.close()
        println("${counted.count} events, ${referencePreferences.marks} of them part marks, killed after each in turn")

        val marksSeen = mutableListOf<Int>()
        for (k in 1..counted.count) withClue("killed after event $k of ${counted.count}") {
            val resumed = killAndResume(k, a.deviceId) { device ->
                device.library() shouldBe expected
                device.latchesRaised() shouldBe 0
                device.preferences.pullCursors.get() shouldBe expectedCursors
                device.preferences.pulledExportParts.get().shouldBeEmpty()
            }
            parts.forEachIndexed { index, path ->
                val part = index + 1
                withClue("part $part after a stop that left the mark at ${resumed.marked}") {
                    resumed.reads[path] shouldBe if (part <= resumed.marked) null else 1
                }
            }
            marksSeen += resumed.marked
        }
        // Stops left every mark from none to the whole export.
        marksSeen.distinct().sorted() shouldContainExactly (0..parts.size).toList()
        a.close()
    }

    @Test
    fun `a mark for another export of the same device is not used, and that export starts at part 1`() {
        val a = SyncTestDevice(backend)
        val first = exportFrom(a, entries = 1_000)
        first.size shouldBe 3
        val firstId = (SyncLayout.parse(first[0]) as RemoteName.ExportPart).exportId

        // B stops right after its mark for part 2 of A's first export.
        val database = newDatabase()
        val killing = MarkCounting(KillEvents(2))
        reader(database, killing, KillEvents(0)).use { killed -> shouldThrow<Killed> { killed.sync() } }
        marks(killing) shouldBe mapOf(a.deviceId to PulledExportParts(firstId, parts = 2))
        val preferences = HashMap<String, Any>(killing)

        // A restores, so its next run is a new full export, and deletes its first one, as its own
        // deletion does once that export is covered.
        a.restorePreferences.restoredSinceSync.set(true)
        a.sync().shouldNotBeNull().exported shouldBe true
        runBlocking {
            first.forEach { backend.delete(it) }
            backend.delete(SyncLayout.exportDonePath(a.deviceId, firstId))
        }
        val second = exportParts(a.deviceId)
        second.size shouldBe 3
        (SyncLayout.parse(second[0]) as RemoteName.ExportPart).exportId shouldNotBe firstId

        val counting = ReadCounting(backend)
        reader(database, preferences, KillEvents(0), counting).use { b ->
            b.sync().shouldNotBeNull().exportsApplied shouldBe 1
            second.forEach { path -> withClue(path) { counting.reads[path] shouldBe 1 } }
            b.preferences.pulledExportParts.get().shouldBeEmpty()
            b.library() shouldBe a.library()
        }
        a.close()
    }

    @Test
    fun `a restore forgets the part marks with the cursors, so the export is read again from part 1`() {
        val a = SyncTestDevice(backend)
        val parts = exportFrom(a, entries = 1_000)

        val database = newDatabase()
        val killing = MarkCounting(KillEvents(2))
        reader(database, killing, KillEvents(0)).use { killed -> shouldThrow<Killed> { killed.sync() } }
        marks(killing)[a.deviceId]?.parts shouldBe 2
        val preferences = HashMap<String, Any>(killing)

        val counting = ReadCounting(backend)
        reader(database, preferences, KillEvents(0), counting).use { b ->
            b.restorePreferences.restoredSinceSync.set(true)
            b.sync().shouldNotBeNull().exportsApplied shouldBe 1
            parts.forEach { path -> withClue(path) { counting.reads[path] shouldBe 1 } }
            b.preferences.pulledExportParts.get().shouldBeEmpty()
            b.library() shouldBe a.library()
        }
        a.close()
    }

    private companion object {
        /** The part marks' preference key, read from the preference rather than spelled here. */
        val markKey: String = SyncPreferences(MapPreferenceStore()).pulledExportParts.key()
    }
}

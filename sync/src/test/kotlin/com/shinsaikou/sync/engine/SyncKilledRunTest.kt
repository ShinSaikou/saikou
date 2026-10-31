package com.shinsaikou.sync.engine

import com.shinsaikou.sync.InMemorySyncBackend
import com.shinsaikou.sync.SyncBackend
import com.shinsaikou.sync.SyncContent
import com.shinsaikou.sync.SyncRunResult
import com.shinsaikou.sync.format.SyncLayout
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * A run killed after any file or transaction, resumed to the same database (phase 3 unit E2's
 * suite, Q10). One start state is taken once, a file database with its preferences and the remote
 * files, and copied for every run. A reference run goes through uninterrupted and counts its
 * events: each transaction the store commits (an apply, an export's start) and each file the
 * backend writes or deletes. Then, for every event in turn, a copy runs until just after that
 * event and dies there, and the same install, started again, runs once more. Its database, its
 * cursors and what it pushed must be what the reference reached, and a device that joins from the
 * remote files afterwards must reach the same rows.
 */
class SyncKilledRunTest {

    @TempDir
    lateinit var dir: File

    /**
     * [real], each write and delete counted by [events]. A write yields first, so the parts an
     * export has in flight all start before the first of them lands, and a kill after one finds
     * the others under way.
     */
    private class CountingBackend(private val real: SyncBackend, private val events: KillEvents) : SyncBackend by real {
        override suspend fun create(path: String, content: SyncContent) {
            yield()
            real.create(path, content)
            events.happened()
        }

        override suspend fun delete(path: String) {
            real.delete(path)
            events.happened()
        }
    }

    /** A saved start: B's database file, B's preferences and the remote files. */
    private class Start(val database: File, val preferences: Map<String, Any>, val backend: InMemorySyncBackend)

    private var copies = 0

    /** B on [database], [preferences] and [backend], its store's transactions counted by [events]. */
    private fun open(events: KillEvents, database: File, preferences: MutableMap<String, Any>, backend: InMemorySyncBackend) =
        SyncTestDevice(
            backend = backend,
            url = "jdbc:sqlite:${database.path}",
            preferenceValues = preferences,
            storeWrapper = { CountingStore(it, events) },
        )

    private fun SyncTestDevice.run(events: KillEvents): SyncRunResult? =
        runBlocking { engine.run(CountingBackend(backend, events)) }

    /**
     * Runs B from [start] once uninterrupted, then once per event killed after it and resumed, and
     * returns what the uninterrupted run did.
     */
    private fun killEverywhere(start: Start, expectEvents: Int): SyncRunResult {
        fun copy(): Triple<File, MutableMap<String, Any>, InMemorySyncBackend> {
            val file = File(dir, "copy-${copies++}.db")
            start.database.copyTo(file)
            return Triple(file, start.preferences.toMutableMap(), start.backend.copy())
        }

        val (refFile, refPreferences, refBackend) = copy()
        val counted = KillEvents(killAfter = 0)
        val reference = open(counted, refFile, refPreferences, refBackend)
        val result = reference.run(counted).shouldNotBeNull()
        val events = counted.count
        println("${start.database.name}: $events events, killed after each in turn")
        events shouldBe expectEvents
        val expected = reference.library()
        val expectedCursors = reference.preferences.pullCursors.get()
        val expectedPushed = reference.preferences.lastPushedSeq.get()
        reference.close()

        for (k in 1..events) withClue("killed after event $k of $events") {
            val (file, preferences, backend) = copy()
            val killing = KillEvents(killAfter = k)
            open(killing, file, preferences, backend).use { killed ->
                shouldThrow<Killed> { killed.run(killing) }
                // The saved progress names only parts written in full, whatever was in flight.
                killed.preferences.exportProgress.get()?.let { progress ->
                    for (part in 1..progress.parts) {
                        backend.bytes(SyncLayout.exportPartPath(killed.deviceId, progress.exportId, part))
                            .shouldNotBeNull()
                    }
                }
            }
            // The same install started again: the database file, the preferences and the remote.
            val resumed = open(KillEvents(0), file, preferences, backend)
            resumed.use {
                it.run(KillEvents(0)).shouldNotBeNull()
                it.library() shouldBe expected
                it.latchesRaised() shouldBe 0
                it.logCount() shouldBe 0
                it.preferences.pullCursors.get() shouldBe expectedCursors
                it.preferences.lastPushedSeq.get() shouldBe expectedPushed
                it.preferences.exportProgress.get() shouldBe null
            }
            SyncTestDevice(backend).use { joined ->
                joined.sync()
                joined.library() shouldBe expected
            }
        }
        return result
    }

    /** Saves B's state as [Start]: its database copied through VACUUM INTO, its preferences, the remote. */
    private fun SyncTestDevice.save(name: String): Start {
        val file = File(dir, "$name.db")
        exec("VACUUM INTO '${file.path}'")
        return Start(file, preferenceValues.toMap(), backend.copy())
    }

    @Test
    fun `a first join killed after any transaction or file resumes to the same database`() {
        val backend = InMemorySyncBackend()
        SyncTestDevice(backend).use { a ->
            // Over one pull chunk and over one export part, so both are several.
            a.bulkLibrary(entries = 500, chapters = 8)
            a.sync()
        }
        val start = SyncTestDevice(backend).use { b ->
            b.addFavourite(9_001)
            b.addChapter(9_001_001, mangaId = 9_001)
            b.save("first-join")
        }

        // Four pull transactions (6,002 lines at 2,000 each), the export's start, two parts and done.json.
        val result = killEverywhere(start, expectEvents = 8)
        result.exportsApplied shouldBe 1
        result.exported shouldBe true
    }

    @Test
    fun `a first push killed after any part, with parts in flight, resumes to the same database`() {
        val start = SyncTestDevice(InMemorySyncBackend()).use { b ->
            // Five parts of 5,000 lines: part 1 alone, then three in flight.
            b.bulkLibrary(entries = 20, chapters = 999)
            b.save("first-push")
        }

        // The export's start, five parts and done.json.
        val result = killEverywhere(start, expectEvents = 7)
        result.exported shouldBe true
    }

    @Test
    fun `a later run killed after any transaction or file, compaction included, resumes to the same database`() {
        val backend = InMemorySyncBackend()
        val a = SyncTestDevice(backend)
        val b = SyncTestDevice(backend)
        a.bulkLibrary(entries = 40, chapters = 4)
        listOf(a, b, a).forEach { it.sync() }
        // Three segments from A, one edit waiting on B, an unfinished export of B's left by an
        // earlier death, and two weeks gone, so B's run pulls, pushes, deletes and compacts.
        repeat(3) { round ->
            a.exec("UPDATE chapters SET last_page_read = ${round + 1} WHERE _id % 1000 = 4")
            a.sync()
        }
        b.exec("UPDATE chapters SET bookmark = 1 WHERE url LIKE '/series/1/%'")
        runBlocking {
            backend.create(SyncLayout.exportPartPath(b.deviceId, "stale", 1), SyncContent(ByteArray(3)))
        }
        backend.clock = { 15 * 24 * 60 * 60 * 1000L }
        val start = b.save("later-run")
        a.close()
        b.close()

        // Three applies, one segment, one delete and one snapshot.
        val result = killEverywhere(start, expectEvents = 6)
        result.segmentsApplied shouldBe 3
        result.segmentsPushed shouldBe 1
        result.filesDeleted shouldBe 1
        result.compacted shouldBe true
    }
}

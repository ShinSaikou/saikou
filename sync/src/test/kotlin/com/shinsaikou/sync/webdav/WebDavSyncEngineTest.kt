package com.shinsaikou.sync.webdav

import com.shinsaikou.sync.InMemorySyncBackend
import com.shinsaikou.sync.engine.SyncTestDevice
import com.shinsaikou.sync.engine.buildLibrary
import com.shinsaikou.sync.engine.bulkLibrary
import com.shinsaikou.sync.engine.latchesRaised
import com.shinsaikou.sync.engine.library
import com.shinsaikou.sync.format.RemoteName
import com.shinsaikou.sync.format.SyncLayout
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The engine over the real backend at least once (plan unit F): two devices, each a real database
 * and its own [WebDavSyncBackend] on one account of the sync fixture, sync until they hold the same
 * library, as E2's convergence suite does over the in-memory backend. A server that stops storing
 * writes then fails a run by name with no cursor moved, and the next honest run carries the edit.
 * Each file a push writes is read back once, by the backend's own check (phase 5 unit SC3).
 */
class WebDavSyncEngineTest {

    @TempDir
    lateinit var dir: File

    private val opened = mutableListOf<AutoCloseable>()

    @AfterEach
    fun close() {
        opened.asReversed().forEach(AutoCloseable::close)
    }

    private fun <T : AutoCloseable> T.closing(): T = also { opened += it }

    @Test
    fun `two devices converge through the WebDAV backend and a lying write stops the run`() {
        val fixture = SyncFixtureProcess(dir).closing()
        val client = syncHttpClient(File(dir, "cache"))
        // Each device's in-memory backend goes unused: the engine runs over [SyncTestDevice.remote].
        val a = SyncTestDevice(InMemorySyncBackend(), remote = fixture.backend(client)).closing()
        val b = SyncTestDevice(InMemorySyncBackend(), remote = fixture.backend(client)).closing()

        a.buildLibrary()
        a.sync()
        b.sync()
        a.sync()
        b.library() shouldBe a.library()

        // A reads chapter 11 to the end; B renames a category and bookmarks chapter 11.
        a.exec("UPDATE chapters SET read = 1, last_page_read = 20 WHERE _id = 11")
        b.exec("UPDATE categories SET name = 'Now reading' WHERE name = 'Reading'")
        b.exec("UPDATE chapters SET bookmark = 1 WHERE url = '/series/1/chapter/11'")
        a.sync()
        b.sync()
        a.sync()

        a.library() shouldBe b.library()
        a.latchesRaised() shouldBe 0
        b.latchesRaised() shouldBe 0
        a.rows("SELECT read, bookmark, last_page_read FROM chapters WHERE _id = 11", 3) shouldBe listOf(listOf("1", "1", "20"))
        a.string("SELECT name FROM categories WHERE _id = 2") shouldBe "Now reading"

        // Both devices' exports and segments are on the server, under Q3's layout.
        val paths = runBlocking { fixture.backend(client).list(SyncLayout.ROOT_PREFIX) }.map { it.path }
        for (device in listOf(a, b)) {
            paths.filter { it.startsWith(SyncLayout.devicePrefix(device.deviceId)) }.let { own ->
                own.any { it.endsWith("/done.json") } shouldBe true
                own.any { SyncLayout.parse(it) is RemoteName.Segment } shouldBe true
            }
        }

        // The server stops storing writes: A's push fails by name and its cursor stays.
        a.exec("UPDATE mangas SET notes = 'changed while the server lied' WHERE _id = 1")
        val pushed = a.preferences.lastPushedSeq.get()
        fixture.modes("""{"lie": true}""")
        shouldThrow<WebDavWriteNotStoredException> { a.sync() }
        a.preferences.lastPushedSeq.get() shouldBe pushed

        fixture.modes("""{"lie": false}""")
        a.sync()
        a.preferences.lastPushedSeq.get() shouldNotBe pushed
        b.sync()
        b.string("SELECT notes FROM mangas WHERE _id = 1") shouldBe "changed while the server lied"
        b.library() shouldBe a.library()
    }

    @Test
    fun `each file a push writes is read back once, by the backend's own check`() {
        val fixture = SyncFixtureProcess(dir).closing()
        val client = syncHttpClient(File(dir, "cache"))
        val a = SyncTestDevice(InMemorySyncBackend(), remote = fixture.backend(client)).closing()
        // Six entries of 999 chapters are over one part's 5,000 lines: two parts and done.json, then a segment.
        a.bulkLibrary(entries = 6, chapters = 999)
        a.sync().shouldNotBeNull().exported shouldBe true
        a.exec("UPDATE chapters SET read = 1 WHERE _id = 1011")
        a.sync().shouldNotBeNull().segmentsPushed shouldBe 1

        val requests = fixture.requestsAfterStop()
        val written = requests.filter { it.first == "PUT" }.map { it.second }.distinct()
        written shouldHaveSize 4
        val reads = requests.filter { it.first == "GET" }.groupingBy { it.second }.eachCount()
        // Once each, by the backend's check; done.json also once per run by compaction, which reads
        // it to learn what the export covers. Nothing else is read: the pulls list and find no other device.
        reads shouldBe written.associateWith { if (it.endsWith("/done.json")) 1 + 2 else 1 }
    }
}

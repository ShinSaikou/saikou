package com.shinsaikou.sync.drive

import com.shinsaikou.sync.InMemorySyncBackend
import com.shinsaikou.sync.RemoteFile
import com.shinsaikou.sync.SoftwareSecretCipher
import com.shinsaikou.sync.SyncBackend
import com.shinsaikou.sync.SyncContent
import com.shinsaikou.sync.engine.SyncCompaction
import com.shinsaikou.sync.engine.SyncTestDevice
import com.shinsaikou.sync.engine.buildLibrary
import com.shinsaikou.sync.engine.bulkLibrary
import com.shinsaikou.sync.engine.latchesRaised
import com.shinsaikou.sync.engine.library
import com.shinsaikou.sync.format.RemoteName
import com.shinsaikou.sync.format.SyncLayout
import com.shinsaikou.sync.mapSecretStore
import com.shinsaikou.sync.webdav.SyncFixtureProcess
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
import java.util.concurrent.ConcurrentHashMap

/**
 * E2's engine end to end over the Drive backend (plan unit H, item 8), on
 * [com.shinsaikou.sync.webdav.WebDavSyncEngineTest]'s pattern: two devices, each a real database and its
 * own [DriveAccount] signed in to the one account of unit D's fixture, sync until they hold the same
 * library. A full export spans two parts; a write the fixture reports and does not store fails the
 * run by name with no cursor moved; a listing that hides a segment leaves a gap the reader does not
 * pass; Q3's compaction and deletion run on the time Drive's listing reports; and each file a push
 * writes is read back once, by the backend's own check (phase 5 unit SC3).
 *
 * The fixture's `modifiedTime` is its own clock, which a test cannot move, and compaction waits
 * weeks of server time. [DriveClock] stands in for those weeks: every file written after the test
 * moves [serverOffsetMs] on is listed that much later than Drive said, as a server whose clock had
 * moved on would list it. Everything else is the fixture's.
 */
class DriveSyncEngineTest {

    @TempDir
    lateinit var dir: File

    private val opened = mutableListOf<AutoCloseable>()

    /** How far the stand-in server clock has moved on, and what it read when each path was written. */
    private var serverOffsetMs = 0L
    private val writtenAt = ConcurrentHashMap<String, Long>()

    @AfterEach
    fun close() {
        opened.asReversed().forEach(AutoCloseable::close)
    }

    private fun <T : AutoCloseable> T.closing(): T = also { opened += it }

    /** [delegate] listed with each file's `modifiedTime` moved on by the offset it was written at. */
    private inner class DriveClock(private val delegate: SyncBackend) : SyncBackend by delegate {
        override suspend fun create(path: String, content: SyncContent) {
            delegate.create(path, content)
            writtenAt[path] = serverOffsetMs
        }

        override suspend fun list(prefix: String): List<RemoteFile> =
            delegate.list(prefix).map { it.copy(modifiedAt = it.modifiedAt + (writtenAt[it.path] ?: 0L)) }
    }

    private lateinit var fixture: DriveFixture

    /** A device signed in to the fixture's account with its own secrets, as a second phone would be. */
    private fun device(): SyncTestDevice {
        val account = fixture.account(mapSecretStore(mutableMapOf(), SoftwareSecretCipher()))
        runBlocking { fixture.signIn(account) }
        val backend = runBlocking { account.backend() }.shouldNotBeNull()
        // Each device's in-memory backend goes unused: the engine runs over [SyncTestDevice.remote].
        return SyncTestDevice(InMemorySyncBackend(), remote = DriveClock(backend)).closing()
    }

    private fun startFixture() {
        fixture = DriveFixture(SyncFixtureProcess(dir).closing(), dir)
    }

    private fun SyncTestDevice.paths(): List<String> = runBlocking { remote.list(SyncLayout.ROOT_PREFIX) }.map { it.path }

    @Test
    fun `two devices converge through the Drive backend over a two-part export, and a lying write stops the run`() {
        startFixture()
        val a = device()
        val b = device()
        a.id() shouldBe b.id()

        // Six entries of 999 chapters each are over one part's 5,000 lines.
        a.bulkLibrary(entries = 6, chapters = 999)
        a.sync().shouldNotBeNull().exported shouldBe true
        val parts = a.paths().mapNotNull { SyncLayout.parse(it) as? RemoteName.ExportPart }.filter { it.deviceId == a.deviceId }
        parts.map { it.part }.sorted() shouldBe listOf(1, 2)
        b.sync().shouldNotBeNull().exportsApplied shouldBe 1
        a.sync()
        b.library() shouldBe a.library()

        // A reads chapter 11 of entry 1 to the end; B renames a category and bookmarks the same chapter.
        a.exec("UPDATE chapters SET read = 1, last_page_read = 20 WHERE _id = 1011")
        b.exec("UPDATE categories SET name = 'Now reading' WHERE name = 'Reading'")
        b.exec("UPDATE chapters SET bookmark = 1 WHERE url = '/series/1/chapter/11'")
        a.sync()
        b.sync()
        a.sync()

        a.library() shouldBe b.library()
        a.latchesRaised() shouldBe 0
        b.latchesRaised() shouldBe 0
        a.rows("SELECT read, bookmark, last_page_read FROM chapters WHERE _id = 1011", 3) shouldBe listOf(listOf("1", "1", "20"))
        a.string("SELECT name FROM categories WHERE _id = 1") shouldBe "Now reading"
        for (device in listOf(a, b)) {
            a.paths().filter { it.startsWith(SyncLayout.devicePrefix(device.deviceId)) }.let { own ->
                own.any { it.endsWith("/done.json") } shouldBe true
                own.any { SyncLayout.parse(it) is RemoteName.Segment } shouldBe true
            }
        }

        // The fixture stops storing writes: A's push fails by name and its cursor stays.
        a.exec("UPDATE mangas SET notes = 'changed while Drive lied' WHERE _id = 1")
        val pushed = a.preferences.lastPushedSeq.get()
        fixture.modes("""{"lie": true}""")
        shouldThrow<DriveWriteNotStoredException> { a.sync() }
        a.preferences.lastPushedSeq.get() shouldBe pushed

        fixture.modes("""{"lie": false}""")
        a.sync()
        a.preferences.lastPushedSeq.get() shouldNotBe pushed
        b.sync()
        b.string("SELECT notes FROM mangas WHERE _id = 1") shouldBe "changed while Drive lied"
        b.library() shouldBe a.library()
    }

    @Test
    fun `each file a push writes is read back once, by the backend's own check`() {
        startFixture()
        val a = device()
        a.bulkLibrary(entries = 6, chapters = 999)
        a.sync().shouldNotBeNull().exported shouldBe true
        a.exec("UPDATE chapters SET read = 1 WHERE _id = 1011")
        a.sync().shouldNotBeNull().segmentsPushed shouldBe 1

        val requests = fixture.process.requestsAfterStop()
        // Two parts, done.json and a segment, each one multipart upload under a new id.
        requests.count { it.first == "POST" && it.second == UPLOAD_PATH } shouldBe 4
        // Each read is files.get of one id: each of the four files once, by the backend's check, and
        // done.json twice more, once per run by compaction, which reads it to learn what the export
        // covers. With no other device's files, nothing else is read.
        val reads = requests.filter { it.first == "GET" && it.second.startsWith("$FILES_PATH/") }
            .groupingBy { it.second }.eachCount()
        reads.values.sorted() shouldBe listOf(1, 1, 1, 1 + 2)
    }

    @Test
    fun `a hidden segment stops the reader at the gap, and compaction and deletion run on Drive's time`() {
        startFixture()
        val a = device()
        val b = device()
        a.buildLibrary()
        listOf(a, b, a).forEach { it.sync() }

        // Three segments; the listing leaves out the second, as a lagging files.list would.
        a.exec("UPDATE chapters SET last_page_read = 1 WHERE _id = 11")
        a.sync()
        a.exec("UPDATE mangas SET notes = 'second' WHERE _id = 1")
        a.sync()
        a.exec("UPDATE chapters SET read = 1 WHERE _id = 11")
        a.sync()
        val (first, second, third) = a.paths().mapNotNull { SyncLayout.parse(it) as? RemoteName.Segment }
            .filter { it.deviceId == a.deviceId }.sortedBy { it.lastSeq }.takeLast(3)
        fixture.modes("""{"hide": "${second.path}"}""")

        val stopped = b.sync().shouldNotBeNull()
        stopped.stoppedAtGap shouldBe true
        stopped.segmentsApplied shouldBe 1
        stopped.compacted shouldBe false
        b.preferences.pullCursors.get()[a.deviceId] shouldBe first.lastSeq
        b.string("SELECT notes FROM mangas WHERE url = '/series/1'") shouldBe "owner notes"

        fixture.modes("""{"hide": null}""")
        val caughtUp = b.sync().shouldNotBeNull()
        caughtUp.stoppedAtGap shouldBe false
        caughtUp.segmentsApplied shouldBe 2
        b.preferences.pullCursors.get()[a.deviceId] shouldBe third.lastSeq
        b.library() shouldBe a.library()

        // Two weeks of Drive's time on, A's next file dates the listing and A compacts.
        serverOffsetMs = 15 * SyncCompaction.DAY_MS
        a.exec("UPDATE chapters SET bookmark = 1 WHERE _id = 11")
        a.sync().shouldNotBeNull().compacted shouldBe true
        a.paths().mapNotNull { SyncLayout.parse(it) as? RemoteName.Snapshot }.map { it.generation to it.deviceId } shouldBe
            listOf(1L to a.deviceId)
        b.sync()

        // A week after the snapshot, A deletes what it covers of its own files, and nothing of B's.
        serverOffsetMs = 23 * SyncCompaction.DAY_MS
        a.exec("UPDATE mangas SET notes = 'after the snapshot' WHERE _id = 1")
        val before = a.paths()
        a.sync().shouldNotBeNull().filesDeleted shouldNotBe 0
        val deleted = before - a.paths().toSet()
        deleted.isNotEmpty() shouldBe true
        deleted.forEach { it.startsWith(SyncLayout.devicePrefix(a.deviceId)) shouldBe true }
        val own = a.paths().mapNotNull { SyncLayout.parse(it) }.filter { it.deviceOf() == a.deviceId }
        own.filterIsInstance<RemoteName.Snapshot>() shouldHaveSize 1
        own.filterIsInstance<RemoteName.Done>() shouldHaveSize 0
        own.filterIsInstance<RemoteName.Segment>() shouldHaveSize 1

        // A device joining now starts from A's snapshot, then A's newest segment.
        val joined = device()
        val join = joined.sync().shouldNotBeNull()
        join.snapshotApplied shouldBe true
        b.sync()
        joined.library() shouldBe a.library()
        b.library() shouldBe a.library()
        listOf(a, b, joined).forEach { it.latchesRaised() shouldBe 0 }
    }

    private fun SyncTestDevice.id(): String = remote.id

    private fun RemoteName.deviceOf(): String? = when (this) {
        is RemoteName.Segment -> deviceId
        is RemoteName.ExportPart -> deviceId
        is RemoteName.Done -> deviceId
        is RemoteName.Snapshot -> deviceId
    }

    private companion object {
        const val FILES_PATH = "/drive/v3/files"
        const val UPLOAD_PATH = "/upload/drive/v3/files"
    }
}

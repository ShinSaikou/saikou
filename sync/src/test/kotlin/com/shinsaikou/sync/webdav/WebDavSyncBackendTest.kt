package com.shinsaikou.sync.webdav

import com.shinsaikou.sync.RemoteFile
import com.shinsaikou.sync.SyncAuthorization
import com.shinsaikou.sync.SyncContent
import com.shinsaikou.sync.SyncHttpClient
import com.shinsaikou.sync.SyncSignedOutException
import com.shinsaikou.sync.format.SyncLayout
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.Request
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileNotFoundException
import java.time.ZonedDateTime
import java.util.concurrent.Executors
import java.time.format.DateTimeFormatter
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random

/**
 * The WebDAV backend over real HTTP against unit D's fixture, through the real sync client: one
 * fixture process for the class, reset before each test.
 *
 * Each check here is one plan unit F names: nested and percent-encoded listings with the server's
 * own times, a write into collections that do not exist yet, the read-back that catches the
 * fixture's lying mode, the missing-file rules, and a 401 as signed out.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebDavSyncBackendTest {

    /** The class's directory: the fixture's log and state, and the client's cache. */
    private lateinit var dir: File

    private lateinit var fixture: SyncFixtureProcess
    private lateinit var client: SyncHttpClient
    private lateinit var backend: WebDavSyncBackend

    @BeforeAll
    fun start(@TempDir dir: File) {
        this.dir = dir
        fixture = SyncFixtureProcess(dir)
        client = syncHttpClient(File(dir, "cache"))
        backend = fixture.backend(client)
    }

    @AfterAll
    fun stop() {
        fixture.close()
    }

    @BeforeEach
    fun reset() {
        fixture.reset()
    }

    private fun bytes(size: Int, seed: Int = size): ByteArray = Random(seed).nextBytes(size)

    private fun create(path: String, content: ByteArray) = runBlocking { backend.create(path, SyncContent(content)) }

    private fun read(path: String): ByteArray = runBlocking { backend.read(path) { it.readBytes() } }

    private fun list(prefix: String): List<RemoteFile> = runBlocking { backend.list(prefix) }

    @Test
    fun `probe answers reachable and signed in, and a wrong password reachable and signed out`() {
        val status = runBlocking { backend.probe() }
        status.reachable shouldBe true
        status.authenticated shouldBe true

        val refused = runBlocking { fixture.backend(client, password = "not-the-password").probe() }
        refused.reachable shouldBe true
        refused.authenticated shouldBe false
    }

    @Test
    fun `a write into collections that do not exist makes each of them and reads back`() {
        val path = SyncLayout.segmentPath("device-1", 0, 5)
        val content = bytes(1_000)

        create(path, content)

        read(path) shouldBe content
        // saikou-sync, v1, devices, device-1 and the file.
        fixture.state() shouldContain "\"davResources\": 5"
    }

    @Test
    fun `a listing walks nested collections, decodes percent-encoded names and leaves out other paths`() {
        val segment = SyncLayout.segmentPath("device-1", 0, 5)
        val part = SyncLayout.exportPartPath("device-2", "export-1", 1)
        val done = SyncLayout.exportDonePath("device-2", "export-1")
        val snapshot = SyncLayout.snapshotPath(1, "device-1")
        val encoded = "saikou-sync/v1/devices/a b%c/ü #1?.bin"
        val outside = "elsewhere/saikou-sync/v1/file.bin"
        val sizes = mapOf(segment to 10, part to 2_000, done to 30, snapshot to 40_000, encoded to 7, outside to 3)
        sizes.forEach { (path, size) -> create(path, bytes(size)) }

        list(SyncLayout.ROOT_PREFIX).map { it.path to it.size } shouldContainExactlyInAnyOrder
            (sizes - outside).map { (path, size) -> path to size.toLong() }
        list(SyncLayout.devicePrefix("device-2")).map { it.path } shouldContainExactlyInAnyOrder listOf(part, done)
        list("saikou-sync/v1/snapshot-").map { it.path } shouldBe listOf(snapshot)
        list("").map { it.path } shouldContainExactlyInAnyOrder sizes.keys
        read(encoded) shouldBe bytes(7)
    }

    @Test
    fun `a listing of a prefix nothing was written under is empty`() {
        list(SyncLayout.ROOT_PREFIX).shouldBeEmpty()
        create(SyncLayout.segmentPath("device-1", 0, 5), bytes(10))
        list(SyncLayout.devicePrefix("device-9")).shouldBeEmpty()
    }

    @Test
    fun `a listed file's modifiedAt is the server's own time for it, to the second the server gives`() {
        val path = SyncLayout.segmentPath("device-1", 0, 5)
        create(path, bytes(10))

        val listed = list(SyncLayout.ROOT_PREFIX).single()

        val lastModified = client.client.newCall(
            Request.Builder().url(fixture.davUrl + path)
                .tag(SyncAuthorization::class, SyncAuthorization(Credentials.basic(SyncFixtureProcess.USER, SyncFixtureProcess.PASSWORD)))
                .build(),
        ).execute().use { it.header("Last-Modified")!! }
        val serverTime = ZonedDateTime.parse(lastModified, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        listed.modifiedAt shouldBe serverTime
        listed.modifiedAt % 1000 shouldBe 0
        listed.modifiedAt shouldBeGreaterThan 0
    }

    @Test
    fun `a write over an existing file replaces it, and a large file streams through`() {
        val path = SyncLayout.snapshotPath(1, "device-1")
        create(path, bytes(100))
        val large = File(dir, "large.bin").apply { writeBytes(bytes(3 * 1024 * 1024 + 17)) }

        runBlocking { backend.create(path, SyncContent(large)) }

        read(path) shouldBe large.readBytes()
        list(SyncLayout.ROOT_PREFIX).single().size shouldBe large.length()
    }

    @Test
    fun `a server that reports a write it did not store fails the write by name`() {
        val existing = SyncLayout.segmentPath("device-1", 0, 5)
        create(existing, bytes(10, seed = 1))
        fixture.modes("""{"lie": true}""")

        val fresh = SyncLayout.segmentPath("device-1", 5, 9)
        shouldThrow<WebDavWriteNotStoredException> { create(fresh, bytes(10, seed = 2)) }.path shouldBe fresh
        // A replaced file that kept its old bytes is caught by the comparison, not by its absence.
        shouldThrow<WebDavWriteNotStoredException> { create(existing, bytes(10, seed = 3)) }.message shouldContain "holds"

        fixture.modes("""{"lie": false}""")
        read(existing) shouldBe bytes(10, seed = 1)
        shouldThrow<FileNotFoundException> { read(fresh) }
    }

    @Test
    fun `read hands the stream to its block in the caller's context`() {
        val path = SyncLayout.segmentPath("device-1", 0, 5)
        create(path, bytes(10))
        val caller = Executors.newSingleThreadExecutor { Thread(it, "sync-caller") }.asCoroutineDispatcher()
        try {
            val (thread, dispatcher) = runBlocking(caller) {
                backend.read(path) {
                    it.readBytes()
                    // Coroutine debug mode appends " @coroutine#n" to a thread's name.
                    Thread.currentThread().name.substringBefore(" @") to currentCoroutineContext()[ContinuationInterceptor]
                }
            }
            thread shouldBe "sync-caller"
            dispatcher shouldBe caller
        } finally {
            caller.close()
        }
    }

    @Test
    fun `reading a missing file is FileNotFoundException, and deleting one succeeds`() {
        val path = SyncLayout.segmentPath("device-1", 0, 5)
        shouldThrow<FileNotFoundException> { read(path) }
        runBlocking { backend.delete(path) }

        create(path, bytes(10))
        runBlocking { backend.delete(path) }
        shouldThrow<FileNotFoundException> { read(path) }
        list(SyncLayout.ROOT_PREFIX).shouldBeEmpty()
        runBlocking { backend.delete(path) }
    }

    @Test
    fun `a 401 on any call during a run is signed out`() {
        val path = SyncLayout.segmentPath("device-1", 0, 5)
        create(path, bytes(10))
        val refused = fixture.backend(client, password = "not-the-password")

        shouldThrow<SyncSignedOutException> { runBlocking { refused.list(SyncLayout.ROOT_PREFIX) } }
        shouldThrow<SyncSignedOutException> { runBlocking { refused.create(path, SyncContent(bytes(5))) } }
        shouldThrow<SyncSignedOutException> { runBlocking { refused.read(path) { it.readBytes() } } }
        shouldThrow<SyncSignedOutException> { runBlocking { refused.delete(path) } }
        read(path) shouldBe bytes(10)
    }
}

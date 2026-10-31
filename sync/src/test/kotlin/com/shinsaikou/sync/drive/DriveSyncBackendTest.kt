package com.shinsaikou.sync.drive

import com.shinsaikou.sync.SoftwareSecretCipher
import com.shinsaikou.sync.SyncContent
import com.shinsaikou.sync.format.SyncLayout
import com.shinsaikou.sync.mapSecretStore
import com.shinsaikou.sync.webdav.SyncFixtureProcess
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The Drive backend against unit D's fixture (plan unit H, item 8): the listing's pages, prefix and
 * duplicates, `modifiedAt` as the server's time, both upload kinds at the 5 MiB line, a resumable
 * upload resumed from a partial 308, after a 401 on a chunk, after a dropped chunk and a 503, and
 * after a lost session, the read-back catching the fixture's `lie`, and reads and deletes by name.
 *
 * The fixture produces every answer here but two: it neither drops a Drive request nor answers one
 * with 503, and it forgets an upload session only on a reset, which also revokes the grant. For
 * those, an application interceptor in the client under the backend drops the answer to a chunk the
 * fixture stored, answers one with 503 before it reaches the fixture, or sends one to a session id
 * the fixture never issued, so the fixture's real 404 answers it. Everything else is the fixture's.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DriveSyncBackendTest {

    private lateinit var dir: File
    private lateinit var process: SyncFixtureProcess
    private lateinit var fixture: DriveFixture

    private val values = mutableMapOf<String, Any>()
    private val secrets = mapSecretStore(values, SoftwareSecretCipher())
    private lateinit var account: DriveAccount

    @BeforeAll
    fun start(@TempDir dir: File) {
        this.dir = dir
        process = SyncFixtureProcess(dir)
        fixture = DriveFixture(process, dir)
    }

    @AfterAll
    fun stop() {
        process.close()
    }

    @BeforeEach
    fun signIn() {
        fixture.reset()
        values.clear()
        synchronized(fixture.waits) { fixture.waits.clear() }
        account = fixture.account(secrets)
        runBlocking { fixture.signIn(account) }
    }

    private suspend fun backend(
        pageSize: Int = DriveSyncBackend.PAGE_SIZE,
        multipartLimit: Long = DriveSyncBackend.MULTIPART_LIMIT,
        chunkSize: Int = DriveSyncBackend.CHUNK_SIZE,
        client: OkHttpClient = fixture.http.client,
    ): DriveSyncBackend = account.backend(pageSize, multipartLimit, chunkSize, client)!!

    private fun segment(device: String, n: Long) = SyncLayout.segmentPath(device, n * 10, n * 10 + 9)

    @Test
    fun `a listing follows every page, with a small page size and with the fixture's page limit`() {
        runBlocking {
            val backend = backend(pageSize = 3)
            val paths = (1L..7L).map { segment("device-a", it) }
            paths.forEach { backend.create(it, SyncContent(bytes(100, it.hashCode()))) }

            // Each create's cleanup made one exact name query, a page of one file.
            val before = fixture.log("drive.files.list") { it.size >= paths.size }.size
            before shouldBe paths.size
            backend.list(SyncLayout.ROOT_PREFIX).map { it.path } shouldContainExactly paths
            // Seven files at three a page.
            fixture.log("drive.files.list") { it.size >= before + 3 }.size shouldBe before + 3

            fixture.modes("""{"drivePageLimit": 2}""")
            backend.list(SyncLayout.ROOT_PREFIX).map { it.path } shouldContainExactly paths
            fixture.log("drive.files.list") { it.size >= before + 7 }.size shouldBe before + 7
        }
    }

    @Test
    fun `the prefix is matched with startsWith on the whole name, slashes and dashes included, never inside it`() {
        runBlocking {
            val backend = backend()
            val names = listOf(
                segment("device-a", 1),
                segment("device-a", 2),
                segment("device-ab", 1),
                "saikou-sync/v1x/devices/device-a/0-9.jsonl.gz",
                "device-a",
                // Both prefixes inside a name, not at its start.
                "old/" + segment("device-a", 3),
            )
            names.forEach { backend.create(it, SyncContent(bytes(10))) }

            backend.list(SyncLayout.devicePrefix("device-a")).map { it.path } shouldContainExactly names.take(2)
            backend.list(SyncLayout.ROOT_PREFIX).map { it.path } shouldContainExactly names.take(3)
            backend.list("").map { it.path }.toSet() shouldBe names.toSet()
        }
    }

    @Test
    fun `a listing reports each file's size and the fixture's modifiedTime as modifiedAt`() {
        runBlocking {
            val backend = backend()
            backend.create(segment("device-a", 1), SyncContent(bytes(1234)))
            backend.create(segment("device-a", 2), SyncContent(bytes(10)))

            val listed = backend.list(SyncLayout.ROOT_PREFIX).associateBy { it.path }
            val raw = rawFiles()
            raw.size shouldBe 2
            for (file in raw) {
                val name = file.string("name")!!
                listed.getValue(name).modifiedAt shouldBe Instant.parse(file.string("modifiedTime")!!).toEpochMilliseconds()
                listed.getValue(name).size shouldBe file.string("size")!!.toLong()
            }
        }
    }

    @Test
    fun `two files with one name list and read as the newest, and a rewrite leaves one`() {
        runBlocking {
            val backend = backend()
            val path = segment("device-a", 1)
            backend.create(path, SyncContent(bytes(50, seed = 1)))
            // The fixture stamps createdTime from the host's clock to the millisecond: once that clock
            // is past the first file's, the second is created later.
            val first = rawFiles().single().created()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (Clock.System.now() <= first && System.nanoTime() < deadline) Thread.sleep(1)
            // What a retried multipart upload leaves when its first answer was lost.
            rawCreate(path, bytes(60, seed = 2))

            rawFiles().filter { it.string("name") == path }.map { it.created() }.sorted().let { (older, newer) ->
                older shouldBe first
                (newer > first) shouldBe true
            }
            backend.list(SyncLayout.ROOT_PREFIX).single().size shouldBe 60
            backend.read(path) { it.readBytes() } shouldBe bytes(60, seed = 2)
            // A backend that has not listed finds the newest by an exact name query.
            backend().read(path) { it.readBytes() } shouldBe bytes(60, seed = 2)

            backend.create(path, SyncContent(bytes(70, seed = 3)))
            rawFiles().filter { it.string("name") == path }.map { it.string("size") } shouldBe listOf("70")
            backend.read(path) { it.readBytes() } shouldBe bytes(70, seed = 3)
        }
    }

    @Test
    fun `a read whose listed id is gone finds the file by name, and a missing file is FileNotFoundException`() {
        runBlocking {
            val reader = backend()
            val writer = backend()
            val path = segment("device-a", 1)
            writer.create(path, SyncContent(bytes(40, seed = 1)))
            reader.list(SyncLayout.ROOT_PREFIX).single().path shouldBe path
            // The writer rewrites the range: a new id, and the listed one deleted.
            writer.create(path, SyncContent(bytes(41, seed = 2)))

            reader.read(path) { it.readBytes() } shouldBe bytes(41, seed = 2)
            shouldThrow<FileNotFoundException> { reader.read(segment("device-a", 9)) { it.readBytes() } }
        }
    }

    @Test
    fun `a delete removes every file of the name, and deleting a missing one is no error`() {
        runBlocking {
            val backend = backend()
            val path = segment("device-a", 1)
            backend.create(path, SyncContent(bytes(10)))
            rawCreate(path, bytes(11))
            backend.create(segment("device-a", 2), SyncContent(bytes(12)))

            backend.delete(path)
            rawFiles().map { it.string("name") } shouldBe listOf(segment("device-a", 2))
            backend.delete(path)
            shouldThrow<FileNotFoundException> { backend.read(path) { it.readBytes() } }
            backend.list(SyncLayout.ROOT_PREFIX).map { it.path } shouldBe listOf(segment("device-a", 2))
        }
    }

    @Test
    fun `a name with a quote and a backslash is escaped in every name query`() {
        runBlocking {
            val path = "saikou-sync/v1/it's \\ here"
            val backend = backend()
            backend.create(path, SyncContent(bytes(10, seed = 1)))
            // The rewrite's cleanup finds the first file by name.
            backend.create(path, SyncContent(bytes(11, seed = 2)))
            rawFiles().filter { it.string("name") == path }.map { it.string("size") } shouldBe listOf("11")
            // A backend that has not listed reads by name.
            backend().read(path) { it.readBytes() } shouldBe bytes(11, seed = 2)
            backend().delete(path)
            rawFiles() shouldBe emptyList()
        }
    }

    @Test
    fun `exactly 5 MiB is one multipart upload, and one byte more is resumable in chunks`() {
        runBlocking {
            val backend = backend()
            val limit = bytes(5 * 1024 * 1024)
            limit.size shouldBe 5_242_880
            backend.create(segment("device-a", 1), SyncContent(limit))
            fixture.log("drive.upload.chunk") shouldHaveSize 0
            fixture.log("drive.files.create") { it.size >= 1 }.size shouldBe 1

            val over = bytes(5 * 1024 * 1024 + 1, seed = 9)
            backend.create(segment("device-a", 2), SyncContent(over))
            val chunks = fixture.log("drive.upload.chunk") { it.size >= 2 }
            // A 4 MiB chunk, then the last byte-and-a-mebibyte.
            chunks.map { it.range() } shouldBe listOf(listOf(0L, 4_194_303L, 5_242_881L), listOf(4_194_304L, 5_242_880L, 5_242_881L))
            chunks.map { it.status() } shouldBe listOf(308, 200)
            backend.read(segment("device-a", 2)) { it.readBytes() } shouldBe over
        }
    }

    @Test
    fun `a chunk the session keeps only part of is resumed from the Range the 308 names`() {
        runBlocking {
            // Not a multiple of 256 KiB, so the fixture keeps less than each chunk sends.
            val backend = backend(chunkSize = 1100 * 1024)
            val content = bytes(5 * 1024 * 1024 + 1, seed = 4)
            backend.create(segment("device-a", 1), SyncContent(content))

            val starts = fixture.log("drive.upload.chunk") { lines -> lines.any { it.status() == 200 } }.map { it.range()!![0] }
            // Each chunk after the first starts where the fixture's 256 KiB-aligned Range ended.
            starts shouldBe listOf(0L, 1_048_576L, 2_097_152L, 3_145_728L, 4_194_304L)
            backend.read(segment("device-a", 1)) { it.readBytes() } shouldBe content
        }
    }

    @Test
    fun `a 401 on a chunk refreshes once, asks the session its status and resumes`() {
        runBlocking {
            val chunk = 1024 * 1024
            val backend = backend(multipartLimit = chunk.toLong(), chunkSize = chunk)
            val content = bytes(3 * chunk + 5, seed = 5)
            // The fixture refuses the next Drive call once, as its token had expired: the second chunk.
            val hooked = hookedContent(content, chunk.toLong()) { fixture.modes("""{"early401": 1}""") }
            backend.create(segment("device-a", 1), hooked)

            val lines = fixture.log("drive.upload.chunk", "oauth.token") { lines -> lines.any { it.status() == 200 && it.string("kind") == "drive.upload.chunk" } }
                .dropWhile { it.string("kind") == "oauth.token" && it.string("grant") == "authorization_code" }
            lines.map { listOf(it.string("kind"), it.status(), it.string("injected"), it.range()?.get(0)) } shouldBe listOf(
                listOf("drive.upload.chunk", 308, null, 0L),
                listOf("drive.upload.chunk", 401, "early401", null),
                listOf("oauth.token", 200, null, null),
                // The status query: no range of bytes, and the fixture's Range says the first chunk.
                listOf("drive.upload.chunk", 308, null, null),
                listOf("drive.upload.chunk", 308, null, chunk.toLong()),
                listOf("drive.upload.chunk", 308, null, 2L * chunk),
                listOf("drive.upload.chunk", 200, null, 3L * chunk),
            )
            backend.read(segment("device-a", 1)) { it.readBytes() } shouldBe content
        }
    }

    @Test
    fun `a dropped chunk and a 503 each ask the session its status and resume within the budget`() {
        runBlocking {
            val chunk = 1024 * 1024
            val faults = ChunkFaults(mapOf(1 to Fault.DROP_ANSWER, 2 to Fault.ANSWER_503))
            val backend = backend(multipartLimit = chunk.toLong(), chunkSize = chunk, client = faults.client(fixture))
            val content = bytes(3 * chunk + 5, seed = 6)
            backend.create(segment("device-a", 1), SyncContent(content))

            val lines = fixture.log("drive.upload.chunk") { lines -> lines.any { it.status() == 200 } }
            lines.map { listOf(it.status(), it.range()?.get(0)) } shouldBe listOf(
                listOf(308, 0L),
                // The fixture stored the second chunk; its answer never arrived.
                listOf(308, chunk.toLong()),
                listOf(308, null),
                // The third chunk's 503 never reached the fixture; the status query found it missing.
                listOf(308, null),
                listOf(308, 2L * chunk),
                listOf(200, 3L * chunk),
            )
            // One wait after each failure: the status in between confirmed progress, so the budget
            // started again.
            fixture.waits shouldBe listOf(1.seconds, 1.seconds)
            backend.read(segment("device-a", 1)) { it.readBytes() } shouldBe content
        }
    }

    @Test
    fun `failures without progress spend the budget, and the last one propagates`() {
        runBlocking {
            val chunk = 1024 * 1024
            val faults = ChunkFaults((0..20).associateWith { Fault.ANSWER_503 })
            val backend = backend(multipartLimit = chunk.toLong(), chunkSize = chunk, client = faults.client(fixture))
            shouldThrow<DriveUnavailableException> { backend.create(segment("device-a", 1), SyncContent(bytes(2 * chunk))) }
                .status shouldBe 503
            fixture.waits shouldBe listOf(1.seconds, 2.seconds, 4.seconds)
        }
    }

    @Test
    fun `a session Drive no longer knows starts one new session, and a second loss fails the write`() {
        runBlocking {
            val chunk = 1024 * 1024
            val content = bytes(3 * chunk + 5, seed = 7)
            val once = ChunkFaults(mapOf(1 to Fault.LOSE_SESSION))
            backend(multipartLimit = chunk.toLong(), chunkSize = chunk, client = once.client(fixture))
                .create(segment("device-a", 1), SyncContent(content))

            fixture.log("drive.upload.chunk") { lines -> lines.any { it.status() == 200 } }.map { it.status() } shouldBe
                listOf(308, 404, 308, 308, 308, 200)
            fixture.log("drive.files.create") { it.size >= 2 }.size shouldBe 2
            backend().read(segment("device-a", 1)) { it.readBytes() } shouldBe content

            val always = ChunkFaults((1..20).associateWith { Fault.LOSE_SESSION })
            val failure = shouldThrow<DriveHttpException> {
                backend(multipartLimit = chunk.toLong(), chunkSize = chunk, client = always.client(fixture))
                    .create(segment("device-a", 2), SyncContent(content))
            }
            failure.status shouldBe 404
            failure.reason shouldBe "notFound"
        }
    }

    @Test
    fun `a write the fixture does not store is caught by the read-back, multipart or resumable`() {
        runBlocking {
            val backend = backend(multipartLimit = 1024 * 1024, chunkSize = 1024 * 1024)
            fixture.modes("""{"lie": true}""")
            shouldThrow<DriveWriteNotStoredException> { backend.create(segment("device-a", 1), SyncContent(bytes(100))) }
            shouldThrow<DriveWriteNotStoredException> { backend.create(segment("device-a", 2), SyncContent(bytes(2 * 1024 * 1024 + 3))) }
            fixture.modes("""{"lie": false}""")
            backend.list(SyncLayout.ROOT_PREFIX) shouldBe emptyList()
        }
    }

    @Test
    fun `a probe reads the account and a refused grant probes as not signed in`() {
        runBlocking {
            val backend = backend()
            backend.probe().authenticated shouldBe true
            fixture.modes("""{"revoked": true}""")
            backend.probe().let {
                it.reachable shouldBe true
                it.authenticated shouldBe false
            }
        }
    }

    /** The Drive files the fixture holds, listed with a token of their own. */
    private suspend fun rawFiles(): List<JsonObject> {
        val url = "${fixture.base}/drive/v3/files?spaces=appDataFolder&fields=files(id,name,size,modifiedTime,createdTime)"
        return raw(Request.Builder().url(url).get()).let { body ->
            Json.parseToJsonElement(body).jsonObject["files"]!!.jsonArray.map { it.jsonObject }
        }
    }

    /** A file created behind the backend's back, as a lost answer's retry would leave one. */
    private suspend fun rawCreate(name: String, content: ByteArray) {
        val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
            .addPart("""{"name": "$name", "parents": ["appDataFolder"]}""".toRequestBody("application/json".toMediaType()))
            .addPart(content.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        raw(Request.Builder().url("${fixture.base}/upload/drive/v3/files?uploadType=multipart").post(body))
    }

    private suspend fun raw(request: Request.Builder): String {
        val token = fixture.oauth().refresh(secrets.get(DriveAccount.REFRESH_TOKEN)!!).accessToken.value
        return plain.newCall(request.header("Authorization", "Bearer $token").build()).execute().use { response ->
            check(response.isSuccessful) { "The fixture answered ${response.code}" }
            response.body.string()
        }
    }

    private fun JsonObject.created(): Instant = Instant.parse(string("createdTime")!!)

    private fun JsonObject.status(): Int? = this["status"]?.jsonPrimitive?.content?.toIntOrNull()

    /** A chunk line's `[start, end, total]`, or null for a status query. */
    private fun JsonObject.range(): List<Long>? = this["range"]?.jsonArray?.map { it.jsonPrimitive.content.toLong() }

    private enum class Fault { DROP_ANSWER, ANSWER_503, LOSE_SESSION }

    /**
     * Faults for the session `PUT`s that carry bytes, by their order in the test (0 is the first):
     * the fixture stores a chunk whose answer is then dropped, never sees one answered 503 here, and
     * answers 404 to one sent to a session id it never issued.
     */
    private class ChunkFaults(private val faults: Map<Int, Fault>) : Interceptor {
        private var chunks = 0

        fun client(fixture: DriveFixture): OkHttpClient = fixture.http.client.newBuilder().addInterceptor(this).build()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val range = request.header("Content-Range")
            if (request.method != "PUT" || range == null || range.startsWith("bytes */")) return chain.proceed(request)
            return when (faults[chunks++]) {
                null -> chain.proceed(request)
                Fault.DROP_ANSWER -> {
                    chain.proceed(request).close()
                    throw IOException("fixture interceptor: the connection closed before the answer")
                }
                Fault.ANSWER_503 -> Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(503).message("Service Unavailable")
                    .body("""{"error": {"errors": [{"reason": "backendError"}], "code": 503}}""".toResponseBody("application/json".toMediaType()))
                    .build()
                Fault.LOSE_SESSION -> chain.proceed(
                    request.newBuilder().url(request.url.newBuilder().setQueryParameter("upload_id", "a-session-the-fixture-never-issued").build()).build(),
                )
            }
        }
    }

    private companion object {
        val plain = OkHttpClient()
    }
}

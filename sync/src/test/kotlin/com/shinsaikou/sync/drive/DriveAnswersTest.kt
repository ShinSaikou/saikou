package com.shinsaikou.sync.drive

import com.shinsaikou.sync.SecretStore
import com.shinsaikou.sync.SoftwareSecretCipher
import com.shinsaikou.sync.SyncContent
import com.shinsaikou.sync.SyncSignedOutException
import com.shinsaikou.sync.format.SyncLayout
import com.shinsaikou.sync.google.GoogleClientCredentials
import com.shinsaikou.sync.google.GoogleEndpoints
import com.shinsaikou.sync.google.GoogleOAuthClient
import com.shinsaikou.sync.mapSecretStore
import com.shinsaikou.sync.webdav.TEST_DISPATCHERS
import com.shinsaikou.sync.webdav.syncHttpClient
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Item 2's decisions on Drive's answers and item 1's no-redirect rule (plan unit H), against a
 * MockWebServer standing in for Google, because unit D's fixture produces none of these answers: it
 * never answers a Drive call 429, 5xx, a rate-limit or storage 403, or a redirect, never lists a file
 * without its size or time, never gives two files one creation time, never rotates a refresh token,
 * and always names its own host as a session's address. Fix round 1 adds what it cannot produce
 * either: a persistent 503 on a read-back, a listing page or a session (counting each budget's
 * requests), a `modifiedTime` without a fraction, with nanoseconds or with an offset, and a revoke
 * held unanswered. Everything the fixture can produce is tested against it in
 * [DriveSyncBackendTest] and [DriveAccountTest].
 *
 * Each answer is decided on the status and `error.errors[0].reason`, never on `message` (R83 item 3).
 */
class DriveAnswersTest {

    @TempDir
    lateinit var dir: File

    private val google = MockWebServer()
    private val elsewhere = MockWebServer()

    /** How Drive answers each call; the token endpoint is answered apart. */
    private var drive: (RecordedRequest) -> MockResponse = { MockResponse.Builder().code(500).build() }
    private var tokenAnswer: String = TOKEN

    /** Answers the next token requests take before [tokenAnswer], in order. */
    private val tokenAnswers = ArrayDeque<String>()

    /** Whether the revoke endpoint holds its answer back longer than any bound here. */
    private var revokeHeld = false

    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val waits = mutableListOf<Duration>()
    private val values = mutableMapOf<String, Any>()
    private val secrets = mapSecretStore(values, SoftwareSecretCipher())

    @BeforeEach
    fun start() {
        google.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return when (request.url.encodedPath) {
                    "/token" -> json(200, synchronized(tokenAnswers) { tokenAnswers.removeFirstOrNull() } ?: tokenAnswer)
                    "/revoke" -> if (revokeHeld) {
                        MockResponse.Builder().code(200).headersDelay(HELD_S, TimeUnit.SECONDS).build()
                    } else {
                        MockResponse.Builder().code(200).build()
                    }
                    else -> drive(request)
                }
            }
        }
        elsewhere.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse.Builder().code(200).body("{}").build()
        }
        google.start(InetAddress.getByName("127.0.0.1"), 0)
        elsewhere.start(InetAddress.getByName("127.0.0.2"), 0)
        secrets.put(DriveAccount.REFRESH_TOKEN, "1//0stored")
        secrets.put(DriveAccount.EMAIL, "owner@example.invalid")
        secrets.put(DriveAccount.ACCOUNT_ID, "permission-1")
    }

    @AfterEach
    fun stop() {
        google.close()
        elsewhere.close()
    }

    private fun account(store: SecretStore = secrets): DriveAccount {
        val http = syncHttpClient(File(dir, "cache"))
        val endpoints = GoogleEndpoints.at("http://127.0.0.1:${google.port}/".toHttpUrl())
        return DriveAccount(
            secrets = store,
            oauthClient = { GoogleOAuthClient(http, endpoints, GoogleClientCredentials("", ""), Clock.System, TEST_DISPATCHERS) },
            httpClient = http,
            availability = DriveAvailability({ endpoints }, GoogleClientCredentials("", "")),
            clock = Clock.System,
            backoff = DriveBackoff(DriveBackoff.WAITS) { waits += it },
            dispatchers = TEST_DISPATCHERS,
        )
    }

    private fun backend(): DriveSyncBackend = runBlocking { account().backend()!! }

    private fun driveCalls(): List<RecordedRequest> = requests.filter { it.url.encodedPath !in setOf("/token", "/revoke") }

    /** The refresh token each token request sent, in order. */
    private fun refreshTokensSent(): List<String?> = requests.filter { it.url.encodedPath == "/token" }.map { request ->
        request.body?.utf8()?.split('&')?.firstOrNull { it.startsWith("refresh_token=") }
            ?.let { URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
    }

    @Test
    fun `a 403 is decided by its reason as signed out, storage full or a plain HTTP failure, whatever its message says`() {
        for (reason in DriveHttp.SIGNED_OUT_REASONS) {
            drive = { error(403, reason, "The user's Drive storage quota has been exceeded.") }
            withClue(reason) { shouldThrow<SyncSignedOutException> { runBlocking { backend().list(SyncLayout.ROOT_PREFIX) } } }
        }
        drive = { error(403, "storageQuotaExceeded", "Request had insufficient authentication scopes.") }
        shouldThrow<DriveStorageFullException> { runBlocking { backend().create("saikou-sync/v1/x", SyncContent(ByteArray(3))) } }

        drive = { error(403, "forbidden", "The user's Drive storage quota has been exceeded.") }
        shouldThrow<DriveHttpException> { runBlocking { backend().list(SyncLayout.ROOT_PREFIX) } }.let {
            it.status shouldBe 403
            it.reason shouldBe "forbidden"
            it.errorDetail shouldBe "403 forbidden"
        }
        drive = { MockResponse.Builder().code(400).body("not JSON").build() }
        shouldThrow<DriveHttpException> { runBlocking { backend().list(SyncLayout.ROOT_PREFIX) } }.errorDetail shouldBe "400"

        // Decided in fix round 1: a daily limit does not recover within a run's seconds, and the other
        // two do not arise in an appDataFolder of a personal account, so each is a plain HTTP failure
        // the worker retries from 60 s.
        for (reason in listOf("dailyLimitExceeded", "sharingRateLimitExceeded", "domainPolicy")) {
            drive = { error(403, reason, "x") }
            withClue(reason) {
                shouldThrow<DriveHttpException> { runBlocking { backend().list(SyncLayout.ROOT_PREFIX) } }.reason shouldBe reason
            }
        }
        waits shouldBe emptyList()
    }

    @Test
    fun `a rate limit, a 429 and a 5xx are waited out, four attempts in all, then the last propagates`() {
        val failures = ArrayDeque(
            listOf(error(403, "rateLimitExceeded", "x"), error(429, "rateLimitExceeded", "x"), error(503, "backendError", "x")),
        )
        drive = { failures.removeFirstOrNull() ?: json(200, """{"files": []}""") }
        runBlocking { backend().list(SyncLayout.ROOT_PREFIX) } shouldBe emptyList()
        waits shouldBe listOf(1.seconds, 2.seconds, 4.seconds)
        driveCalls().size shouldBe 4

        waits.clear()
        requests.clear()
        drive = { error(403, "userRateLimitExceeded", "x") }
        shouldThrow<DriveUnavailableException> { runBlocking { backend().list(SyncLayout.ROOT_PREFIX) } }.let {
            it.status shouldBe 403
            it.reason shouldBe "userRateLimitExceeded"
        }
        waits shouldBe listOf(1.seconds, 2.seconds, 4.seconds)
        driveCalls().size shouldBe 4
    }

    @Test
    fun `a persistent 503 on the read-back takes four requests in one budget, never a budget per attempt`() {
        drive = { request -> if (request.method == "POST") json(200, """{"id": "created"}""") else error(503, "backendError", "x") }
        shouldThrow<DriveUnavailableException> {
            runBlocking { backend().create("saikou-sync/v1/x", SyncContent(ByteArray(10))) }
        }.status shouldBe 503
        driveCalls().count { it.method == "GET" } shouldBe 4
        waits shouldBe listOf(1.seconds, 2.seconds, 4.seconds)
    }

    @Test
    fun `a persistent 503 on one listing page takes four requests`() {
        drive = { error(503, "backendError", "x") }
        shouldThrow<DriveUnavailableException> { runBlocking { backend().list(SyncLayout.ROOT_PREFIX) } }.status shouldBe 503
        driveCalls().size shouldBe 4
        waits shouldBe listOf(1.seconds, 2.seconds, 4.seconds)
    }

    @Test
    fun `a persistent 503 on a session takes four requests, the chunk and three status queries`() {
        drive = { request ->
            if (request.method == "POST") {
                MockResponse.Builder().code(200).setHeader("Location", "http://127.0.0.1:${google.port}/upload/drive/v3/files?upload_id=s").build()
            } else {
                error(503, "backendError", "x")
            }
        }
        val backend = runBlocking { account().backend(DriveSyncBackend.PAGE_SIZE, multipartLimit = 4, chunkSize = 1024) }!!
        shouldThrow<DriveUnavailableException> { runBlocking { backend.create("saikou-sync/v1/x", SyncContent(ByteArray(10))) } }
            .status shouldBe 503
        driveCalls().filter { it.method == "PUT" }.map { it.headers["Content-Range"] } shouldBe
            listOf("bytes 0-9/10", "bytes */10", "bytes */10", "bytes */10")
        waits shouldBe listOf(1.seconds, 2.seconds, 4.seconds)
    }

    @Test
    fun `a modifiedTime without a fraction, with nanoseconds or with an offset is the right epoch milliseconds`() {
        val cases = listOf(
            "2026-09-26T12:00:00Z" to NOON,
            "2026-09-26T12:00:00.123456789Z" to NOON + 123,
            "2026-09-26T14:00:00.5+02:00" to NOON + 500,
        )
        for ((text, millis) in cases) {
            drive = { json(200, """{"files": [{"id": "a", "name": "saikou-sync/v1/x", "size": "3", "modifiedTime": "$text", "createdTime": "$TIME"}]}""") }
            withClue(text) { runBlocking { backend().list(SyncLayout.ROOT_PREFIX) }.single().modifiedAt shouldBe millis }
        }
    }

    @Test
    fun `no redirect is followed, for a listing or an upload, and the target receives nothing`() {
        for (code in listOf(301, 302, 307, 308)) {
            drive = {
                MockResponse.Builder().code(code)
                    .setHeader("Location", "http://127.0.0.2:${elsewhere.port}/drive/v3/files").build()
            }
            withClue(code) {
                shouldThrow<DriveHttpException> { runBlocking { backend().list(SyncLayout.ROOT_PREFIX) } }.status shouldBe code
                shouldThrow<DriveHttpException> {
                    runBlocking { backend().create("saikou-sync/v1/x", SyncContent(ByteArray(10))) }
                }.status shouldBe code
            }
        }
        elsewhere.requestCount shouldBe 0
    }

    @Test
    fun `a resumable session on another host is refused before any chunk carries the token there`() {
        drive = {
            MockResponse.Builder().code(200).setHeader("Location", "http://127.0.0.2:${elsewhere.port}/upload?upload_id=x").build()
        }
        val backend = runBlocking { account().backend(DriveSyncBackend.PAGE_SIZE, multipartLimit = 4, chunkSize = 1024) }!!
        shouldThrow<DriveMalformedResponseException> { runBlocking { backend.create("saikou-sync/v1/x", SyncContent(ByteArray(10))) } }
        elsewhere.requestCount shouldBe 0
    }

    @Test
    fun `a listed file without its size or modifiedTime, or an answer that is not JSON, fails the listing`() {
        val cases = listOf(
            """{"files": [{"id": "a", "name": "saikou-sync/v1/x", "modifiedTime": "$TIME", "createdTime": "$TIME"}]}""" to "size",
            """{"files": [{"id": "a", "name": "saikou-sync/v1/x", "size": "3", "createdTime": "$TIME"}]}""" to "modifiedTime",
            """{"files": [{"id": "a", "name": "saikou-sync/v1/x", "size": "3", "modifiedTime": "yesterday", "createdTime": "$TIME"}]}""" to "modifiedTime",
            """<html>not Drive</html>""" to "JSON",
        )
        for ((body, named) in cases) {
            drive = { json(200, body) }
            withClue(named) {
                shouldThrow<DriveMalformedResponseException> { runBlocking { backend().list(SyncLayout.ROOT_PREFIX) } }.message shouldContain named
            }
        }
        waits shouldBe emptyList()
    }

    @Test
    fun `two files with one name and one creation time are the one with the greater id`() {
        val listing = """{"files": [
            {"id": "b-file", "name": "saikou-sync/v1/x", "size": "1", "modifiedTime": "$TIME", "createdTime": "$TIME"},
            {"id": "a-file", "name": "saikou-sync/v1/x", "size": "2", "modifiedTime": "$TIME", "createdTime": "$TIME"}]}"""
        drive = { request ->
            when (request.url.encodedPath) {
                "/drive/v3/files" -> json(200, listing)
                else -> MockResponse.Builder().code(200).body(request.url.encodedPath.substringAfterLast('/')).build()
            }
        }
        val backend = backend()
        runBlocking { backend.list(SyncLayout.ROOT_PREFIX) }.single().size shouldBe 1
        runBlocking { backend.read("saikou-sync/v1/x") { it.readBytes().decodeToString() } } shouldBe "b-file"
    }

    @Test
    fun `a refresh answer that carries a refresh token stores it before its access token is used`() {
        tokenAnswer = """{"access_token": "ya29.rotated", "expires_in": 3599, "refresh_token": "1//0rotated", "refresh_token_expires_in": 7200}"""
        // What the secret store held when the Drive call signed with the new access token arrived.
        var storedAtCall: String? = null
        drive = {
            storedAtCall = secrets.get(DriveAccount.REFRESH_TOKEN)
            json(200, """{"files": []}""")
        }
        runBlocking { backend().list(SyncLayout.ROOT_PREFIX) } shouldBe emptyList()
        storedAtCall shouldBe "1//0rotated"
        driveCalls().single().headers["Authorization"] shouldBe "Bearer ya29.rotated"
        requests.first().url.encodedPath shouldBe "/token"
        secrets.get(DriveAccount.REFRESH_TOKEN) shouldBe "1//0rotated"
        secrets.get(DriveAccount.REFRESH_TOKEN_EXPIRES_AT).shouldBeInstanceOf<String>()
    }

    @Test
    fun `a rotated refresh token the store refuses fails the run, and the next refresh stores it and refreshes with it`() {
        var refused = false
        val refusing = object : SecretStore by secrets {
            override fun put(name: String, value: String) {
                if (!refused && name == DriveAccount.REFRESH_TOKEN && value == "1//0rotated") {
                    refused = true
                    throw IOException("fixture: the store refused the rotated token")
                }
                secrets.put(name, value)
            }
        }
        tokenAnswers += """{"access_token": "ya29.first", "expires_in": 3599, "refresh_token": "1//0rotated"}"""
        drive = { json(200, """{"files": []}""") }
        val backend = runBlocking { account(refusing).backend()!! }

        shouldThrow<IOException> { runBlocking { backend.list(SyncLayout.ROOT_PREFIX) } }.message shouldBe
            "fixture: the store refused the rotated token"
        secrets.get(DriveAccount.REFRESH_TOKEN) shouldBe "1//0stored"
        driveCalls() shouldBe emptyList()

        runBlocking { backend.list(SyncLayout.ROOT_PREFIX) } shouldBe emptyList()
        secrets.get(DriveAccount.REFRESH_TOKEN) shouldBe "1//0rotated"
        refreshTokensSent() shouldBe listOf("1//0stored", "1//0rotated")
        driveCalls().single().headers["Authorization"] shouldBe "Bearer ya29.mock"
    }

    @Test
    fun `a revoke Google holds unanswered does not hold the sign-out, and the secrets are gone`() {
        revokeHeld = true
        val account = account()
        account.revokeTimeout = 300.milliseconds
        val started = System.nanoTime()
        runBlocking { account.signOut() }
        (System.nanoTime() - started).nanoseconds shouldBeLessThan 5.seconds
        requests.count { it.url.encodedPath == "/revoke" } shouldBe 1
        listOf(DriveAccount.REFRESH_TOKEN, DriveAccount.REFRESH_TOKEN_EXPIRES_AT, DriveAccount.EMAIL, DriveAccount.ACCOUNT_ID)
            .map { secrets.get(it) } shouldBe listOf(null, null, null, null)
        DriveAccount.REVOKE_TIMEOUT shouldBe 10.seconds
    }

    private fun json(code: Int, body: String): MockResponse =
        MockResponse.Builder().code(code).setHeader("Content-Type", "application/json; charset=UTF-8").body(body).build()

    /** Google's error shape; the [message] is there to prove nothing reads it. */
    private fun error(code: Int, reason: String, message: String): MockResponse = json(
        code,
        """{"error": {"errors": [{"domain": "global", "reason": "$reason", "message": "$message"}], "code": $code, "message": "$message"}}""",
    )

    private companion object {
        const val TOKEN = """{"access_token": "ya29.mock", "expires_in": 3599, "scope": "https://www.googleapis.com/auth/drive.appdata"}"""
        const val TIME = "2026-09-26T10:00:00.000Z"

        /** 2026-09-26T12:00:00Z in epoch milliseconds, worked out apart from any parser here. */
        const val NOON = 1_790_424_000_000L

        /** How long a held revoke waits before it answers: longer than the bound, short for the server's close. */
        const val HELD_S = 5L
    }
}

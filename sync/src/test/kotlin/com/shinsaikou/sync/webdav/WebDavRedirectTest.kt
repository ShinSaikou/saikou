package com.shinsaikou.sync.webdav

import com.shinsaikou.sync.SoftwareSecretCipher
import com.shinsaikou.sync.mapSecretStore
import com.shinsaikou.sync.SyncContent
import com.shinsaikou.sync.format.SyncLayout
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress

/**
 * The WebDAV backend follows no redirect (unit F's fix round). The configured server is an HTTPS
 * stub whose certificate the test's sync client trusts; it answers 307 or 308 with a `Location` on
 * `http://`, either on its own host or on another one (127.0.0.2). OkHttp's default would resend a
 * `PUT` and its body there unchanged, in cleartext; here every call, the write, its read-back, the
 * `MKCOL`, a read, a listing, a probe and a delete, fails by name with the `Location`'s scheme and
 * host only, and the redirect targets receive no request at all. A sign-in against a redirecting
 * address stores nothing.
 */
class WebDavRedirectTest {

    @TempDir
    lateinit var dir: File

    private val origin = MockWebServer()
    private val sameHost = MockWebServer()
    private val otherHost = MockWebServer()
    private val servers = listOf(origin, sameHost, otherHost)

    /** The sync client, trusting the configured server's certificate. */
    private lateinit var trusted: OkHttpClient

    /** How the configured server answers each request. */
    private var answer: (RecordedRequest) -> MockResponse = { MockResponse.Builder().code(500).build() }

    private val path = SyncLayout.segmentPath("device-1", 0, 5)
    private val content = ByteArray(4_096) { it.toByte() }

    @BeforeEach
    fun start() {
        val certificate = certificate("127.0.0.1")
        origin.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory())
        origin.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = answer(request)
        }
        // A request reaching either target would be answered, so the test sees it rather than hanging.
        for (target in listOf(sameHost, otherHost)) {
            target.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse.Builder().code(201).build()
            }
        }
        origin.start(InetAddress.getByName("127.0.0.1"), 0)
        sameHost.start(InetAddress.getByName("127.0.0.1"), 0)
        otherHost.start(InetAddress.getByName("127.0.0.2"), 0)
        trusted = syncHttpClient(dir).trusting(certificate)
    }

    @AfterEach
    fun stop() {
        servers.forEach(MockWebServer::close)
    }

    private fun backend(): WebDavSyncBackend {
        val base = WebDavUrlPolicy.forBuild(debug = false).baseUrl("https://127.0.0.1:${origin.port}/dav/")
        return WebDavSyncBackend(base, "owner", "secret", trusted, Dispatchers.IO)
    }

    /** A redirect to [target] over `http://`, with credentials and a query that must never be kept. */
    private fun redirect(code: Int, target: MockWebServer): MockResponse = MockResponse.Builder().code(code)
        .setHeader("Location", "http://mover:hunter2@${target.socketAddress.address.hostAddress}:${target.port}/moved/dav/file?token=query-secret")
        .build()

    /** Runs [call] once per status and target and checks each failure, and that no target was reached. */
    private fun eachRedirect(call: suspend WebDavSyncBackend.() -> Unit, method: String, before: (Int, MockWebServer) -> Unit) {
        for (code in listOf(307, 308)) {
            for (target in listOf(sameHost, otherHost)) {
                before(code, target)
                val failure = shouldThrow<WebDavRedirectException> { runBlocking { backend().call() } }
                failure.code shouldBe code
                failure.scheme shouldBe "http"
                failure.host shouldBe target.socketAddress.address.hostAddress
                failure.message shouldContain method
                failure.message shouldNotContain "hunter2"
                failure.message shouldNotContain "query-secret"
                failure.message shouldNotContain "moved"
            }
        }
        sameHost.requestCount shouldBe 0
        otherHost.requestCount shouldBe 0
    }

    @Test
    fun `a redirected PUT is not resent, and no target receives the body`() {
        eachRedirect({ create(path, SyncContent(content)) }, "PUT") { code, target -> answer = { redirect(code, target) } }
        // One PUT per case reached the configured server, with its body; nothing was retried.
        origin.requestCount shouldBe 4
        repeat(4) {
            val request = origin.takeRequest()
            request.method shouldBe "PUT"
            request.bodySize shouldBe content.size.toLong()
        }
    }

    @Test
    fun `a redirected read-back is a redirect, not a write taken as stored`() {
        eachRedirect({ create(path, SyncContent(content)) }, "GET") { code, target ->
            answer = { if (it.method == "PUT") MockResponse.Builder().code(201).build() else redirect(code, target) }
        }
    }

    @Test
    fun `a redirected MKCOL is a redirect`() {
        eachRedirect({ create(path, SyncContent(content)) }, "MKCOL") { code, target ->
            answer = { if (it.method == "PUT") MockResponse.Builder().code(409).build() else redirect(code, target) }
        }
    }

    @Test
    fun `a redirected GET is not read from the target`() {
        eachRedirect({ read(path) { it.readBytes() } }, "GET") { code, target -> answer = { redirect(code, target) } }
    }

    @Test
    fun `a redirected PROPFIND is not listed from the target, in a listing or a probe`() {
        eachRedirect({ list(SyncLayout.ROOT_PREFIX) }, "PROPFIND") { code, target -> answer = { redirect(code, target) } }
        eachRedirect({ probe() }, "PROPFIND") { code, target -> answer = { redirect(code, target) } }
    }

    @Test
    fun `a redirected DELETE is a redirect`() {
        eachRedirect({ delete(path) }, "DELETE") { code, target -> answer = { redirect(code, target) } }
    }

    @Test
    fun `a sign-in against an address that redirects stores nothing`() {
        // Plain HTTP on loopback, as the JVM tests' policy takes, redirecting to another host.
        val plain = MockWebServer()
        plain.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = redirect(308, otherHost)
        }
        plain.start(InetAddress.getByName("127.0.0.1"), 0)
        val preferences = mutableMapOf<String, Any>()
        val account = WebDavAccount(
            mapSecretStore(preferences, SoftwareSecretCipher()),
            syncHttpClient(dir),
            JVM_TEST_POLICY,
            TEST_DISPATCHERS,
        )
        try {
            shouldThrow<WebDavRedirectException> {
                runBlocking { account.signIn("http://127.0.0.1:${plain.port}/dav/", "owner", "secret") }
            }.host shouldBe "127.0.0.2"
            plain.requestCount shouldBe 1
        } finally {
            plain.close()
        }
        preferences.keys.shouldBeEmpty()
        runBlocking { account.backend() } shouldBe null
        otherHost.requestCount shouldBe 0
    }
}

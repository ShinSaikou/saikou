package com.shinsaikou.sync.google

import com.shinsaikou.core.common.oauth.Pkce
import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.sync.webdav.TEST_DISPATCHERS
import com.shinsaikou.sync.webdav.syncHttpClient
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.net.InetAddress
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The hand-written Google client against MockWebServer, through the real sync client: what each
 * request carries (the client secret on both grants), how each answer is read, and expiry from an
 * injected clock. MockWebServer is a test base, so every request carries the fixture's test client
 * ([GoogleClientCredentials.TEST_BASE]); the build's own client, a sentinel here, never leaves.
 */
class GoogleOAuthClientTest {

    @TempDir
    lateinit var cacheDir: File

    private val server = MockWebServer()
    private val now = Instant.fromEpochSeconds(1_790_000_000)
    private val clock = object : Clock {
        override fun now(): Instant = this@GoogleOAuthClientTest.now
    }
    private val buildCredentials = GoogleClientCredentials(BUILD_CLIENT_ID, BUILD_SECRET)
    private val testClientId = GoogleClientCredentials.TEST_BASE.clientId
    private val testSecret = GoogleClientCredentials.TEST_BASE.clientSecret
    private val pkce = Pkce("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
    private lateinit var client: GoogleOAuthClient

    // The server binds 127.0.0.1 and the client is given that address, never the `localhost` that
    // MockWebServer's own URL writes: on this host `localhost` resolves to ::1 first, where another
    // process on the same port can answer (TG1's cause for MyAnimeListFixture, 2026-10-11).
    private val base: HttpUrl get() = "http://127.0.0.1:${server.port}/".toHttpUrl()

    @BeforeEach
    fun start() {
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        client = GoogleOAuthClient(syncHttpClient(cacheDir), GoogleEndpoints.at(base), buildCredentials, clock, TEST_DISPATCHERS)
    }

    @AfterEach
    fun stop() {
        server.close()
    }

    @Test
    fun `the authorization URL asks for drive appdata alone, offline, with consent, PKCE S256, the state and the loopback`() {
        val url = client.authorizationUrl("http://127.0.0.1:41234/callback", "the-state", pkce)

        url.encodedPath shouldBe "/o/oauth2/v2/auth"
        url.queryParameterNames shouldBe setOf(
            "client_id", "redirect_uri", "response_type", "scope", "access_type", "prompt",
            "code_challenge", "code_challenge_method", "state",
        )
        url.queryParameter("client_id") shouldBe testClientId
        url.queryParameter("redirect_uri") shouldBe "http://127.0.0.1:41234/callback"
        url.queryParameter("response_type") shouldBe "code"
        url.queryParameter("scope") shouldBe "https://www.googleapis.com/auth/drive.appdata"
        url.queryParameter("access_type") shouldBe "offline"
        url.queryParameter("prompt") shouldBe "consent"
        url.queryParameter("code_challenge") shouldBe "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        url.queryParameter("code_challenge_method") shouldBe "S256"
        url.queryParameter("state") shouldBe "the-state"
        // The secret never reaches the browser.
        url.toString() shouldNotContain testSecret
        url.toString() shouldNotContain BUILD_SECRET
    }

    @Test
    fun `the code exchange sends the verifier, the redirect and the client secret, and dates expiry from the clock`() {
        runBlocking {
            server.enqueue(
                json(
                    200,
                    """{"access_token":"ya29.a","expires_in":3599,"refresh_token":"1//r","scope":"https://www.googleapis.com/auth/drive.appdata","token_type":"Bearer","refresh_token_expires_in":604799}""",
                ),
            )

            val tokens = client.exchange("4/0code", pkce, "http://127.0.0.1:41234/callback")

            val form = server.takeRequest().form()
            form shouldBe mapOf(
                "grant_type" to "authorization_code",
                "code" to "4/0code",
                "code_verifier" to pkce.verifier,
                "redirect_uri" to "http://127.0.0.1:41234/callback",
                "client_id" to testClientId,
                "client_secret" to testSecret,
            )
            tokens.accessToken.value shouldBe "ya29.a"
            tokens.accessToken.expiresAt shouldBe now + 3599.seconds
            tokens.refreshToken shouldBe "1//r"
            tokens.refreshTokenExpiresAt shouldBe now + 604799.seconds
            tokens.scope shouldBe "https://www.googleapis.com/auth/drive.appdata"
            tokens.toString() shouldNotContain "ya29.a"
            tokens.toString() shouldNotContain "1//r"
        }
    }

    @Test
    fun `a refresh sends the refresh token and the client secret, and a response without a refresh token keeps none`() {
        runBlocking {
            server.enqueue(json(200, """{"access_token":"ya29.b","expires_in":120,"token_type":"Bearer"}"""))

            val tokens = client.refresh("1//r")

            val request = server.takeRequest()
            request.method shouldBe "POST"
            request.url.encodedPath shouldBe "/token"
            request.form() shouldBe mapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to "1//r",
                "client_id" to testClientId,
                "client_secret" to testSecret,
            )
            tokens.accessToken.expiresAt shouldBe now + 120.seconds
            tokens.refreshToken.shouldBeNull()
            tokens.refreshTokenExpiresAt.shouldBeNull()
        }
    }

    @Test
    fun `an access token counts as expired from a minute before its expiry, on the injected clock`() {
        val token = GoogleAccessToken("ya29.c", now + 3599.seconds)

        token.isExpired(now) shouldBe false
        token.isExpired(now + 3538.seconds) shouldBe false
        token.isExpired(now + 3539.seconds) shouldBe true
        token.isExpired(now + 4000.seconds) shouldBe true
    }

    @Test
    fun `invalid_grant is its own failure, apart from a 503 and a dropped connection`() {
        runBlocking {
            server.enqueue(json(400, """{"error":"invalid_grant","error_description":"Token has been expired or revoked."}"""))
            val signedOut = shouldThrow<GoogleInvalidGrantException> { client.refresh("1//gone") }
            ErrorKey.of(signedOut) shouldBe ErrorKey.GOOGLE_INVALID_GRANT

            server.enqueue(json(503, """{"error":"temporarily_unavailable"}"""))
            val busy = shouldThrow<GoogleUnavailableException> { client.refresh("1//r") }
            busy.status shouldBe 503
            ErrorKey.of(busy) shouldBe ErrorKey.GOOGLE_UNAVAILABLE
            ErrorKey.detail(busy) shouldBe "503"

            // OkHttp's own retry on a connection failure (retryOnConnectionFailure, which the sync
            // client inherits) sends a POST dropped before its answer twice more on new connections,
            // so a drop reaches the caller only when three attempts in a row drop.
            repeat(3) { server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection).build()) }
            val before = server.requestCount
            val dropped = shouldThrow<GoogleUnavailableException> { client.refresh("1//r") }
            server.requestCount - before shouldBe 3
            dropped.status.shouldBeNull()
            dropped.cause.shouldBeInstanceOf<IOException>()
            ErrorKey.of(dropped) shouldBe ErrorKey.GOOGLE_UNAVAILABLE
            ErrorKey.detail(dropped).shouldBeNull()

            server.enqueue(json(429, """{"error":"rate_limit_exceeded"}"""))
            shouldThrow<GoogleUnavailableException> { client.exchange("4/0code", pkce, "http://127.0.0.1:1/callback") }.status shouldBe 429
        }
    }

    @Test
    fun `any other refusal names Google's error code, never its description`() {
        runBlocking {
            server.enqueue(json(401, """{"error":"invalid_client","error_description":"Unauthorized"}"""))
            val refused = shouldThrow<GoogleAuthRefusedException> { client.exchange("4/0code", pkce, "http://127.0.0.1:1/callback") }
            refused.error shouldBe "invalid_client"
            ErrorKey.of(refused) shouldBe ErrorKey.GOOGLE_AUTH_REFUSED
            ErrorKey.detail(refused) shouldBe "invalid_client"

            server.enqueue(MockResponse.Builder().code(400).body("<html>not json</html>").build())
            shouldThrow<GoogleAuthRefusedException> { client.refresh("1//r") }.error shouldBe "http_400"

            server.enqueue(json(200, """{"token_type":"Bearer"}"""))
            shouldThrow<GoogleAuthRefusedException> { client.refresh("1//r") }.error shouldBe "malformed_response"
        }
    }

    /**
     * Fix round 1 item 4: the code grant asks for offline access with consent, so an answer without
     * a refresh token, or with an empty one, is malformed; a refresh answer without one is normal.
     */
    @Test
    fun `a code exchange without a refresh token is a malformed response, and a refresh without one is not`() {
        runBlocking {
            server.enqueue(json(200, """{"access_token":"ya29.a","expires_in":3599,"token_type":"Bearer"}"""))
            val missing = shouldThrow<GoogleAuthRefusedException> { client.exchange("4/0code", pkce, "http://127.0.0.1:1/callback") }
            missing.error shouldBe GoogleAuthRefusedException.MALFORMED_RESPONSE
            missing.status shouldBe 200
            ErrorKey.of(missing) shouldBe ErrorKey.GOOGLE_AUTH_REFUSED

            server.enqueue(json(200, """{"access_token":"ya29.a","expires_in":3599,"refresh_token":""}"""))
            shouldThrow<GoogleAuthRefusedException> { client.exchange("4/0code", pkce, "http://127.0.0.1:1/callback") }
                .error shouldBe GoogleAuthRefusedException.MALFORMED_RESPONSE

            server.enqueue(json(200, """{"access_token":"ya29.b","expires_in":3599,"refresh_token":""}"""))
            client.refresh("1//r").refreshToken.shouldBeNull()
        }
    }

    /** A token request carries the code, the verifier and the secret; OkHttp would resend it on a 307. */
    @Test
    fun `a redirect from the token endpoint is not followed`() {
        runBlocking {
            server.enqueue(MockResponse.Builder().code(307).setHeader("Location", base.resolve("/elsewhere")!!).build())
            server.enqueue(json(200, """{"access_token":"ya29.x","expires_in":1}"""))

            shouldThrow<GoogleAuthRefusedException> { client.refresh("1//r") }.error shouldBe "http_307"
            server.requestCount shouldBe 1
        }
    }

    @Test
    fun `revoke sends the token in the body, and a token already revoked is no failure`() {
        runBlocking {
            server.enqueue(MockResponse.Builder().code(200).build())
            client.revoke("1//r") shouldBe true
            val request = server.takeRequest()
            request.url.encodedPath shouldBe "/revoke"
            request.url.query.shouldBeNull()
            request.form() shouldBe mapOf("token" to "1//r")

            server.enqueue(json(400, """{"error":"invalid_token","error_description":"Token expired or revoked"}"""))
            client.revoke("1//r") shouldBe false

            server.enqueue(json(503, "{}"))
            shouldThrow<GoogleUnavailableException> { client.revoke("1//r") }
        }
    }

    /**
     * Fix round 1 item 1: a test base, whoever set it, receives the fixture's test client and never
     * the build's. Every request of a whole round trip is searched for the build's id and secret.
     */
    @Test
    fun `with a test base in effect no request carries the build's client, only the fixture's test client`() {
        runBlocking {
            val url = client.authorizationUrl("http://127.0.0.1:41234/callback", "the-state", pkce)
            server.enqueue(json(200, """{"access_token":"ya29.a","expires_in":3599,"refresh_token":"1//r"}"""))
            server.enqueue(json(200, """{"access_token":"ya29.b","expires_in":3599}"""))
            server.enqueue(MockResponse.Builder().code(200).build())

            client.exchange("4/0code", pkce, "http://127.0.0.1:41234/callback")
            client.refresh("1//r")
            client.revoke("1//r")

            client.isConfigured shouldBe true
            url.queryParameter("client_id") shouldBe testClientId
            url.toString() shouldNotContain BUILD_CLIENT_ID
            val requests = List(3) { server.takeRequest() }
            requests.map { it.url.encodedPath } shouldBe listOf("/token", "/token", "/revoke")
            requests.take(2).map { it.form().getValue("client_secret") } shouldBe listOf(testSecret, testSecret)
            requests.take(2).map { it.form().getValue("client_id") } shouldBe listOf(testClientId, testClientId)
            for (request in requests) {
                val sent = request.url.toString() + "\n" + request.headers.toString() + "\n" + request.body?.utf8().orEmpty()
                sent shouldNotContain BUILD_SECRET
                sent shouldNotContain BUILD_CLIENT_ID
            }
        }
    }

    /** The other half of item 1: on Google's own endpoints the build's client is the one used. */
    @Test
    fun `on Google's endpoints the build's client is used, and a build without one is not configured`() {
        val google = GoogleOAuthClient(syncHttpClient(cacheDir), GoogleEndpoints.GOOGLE, buildCredentials, clock, TEST_DISPATCHERS)
        google.isConfigured shouldBe true
        google.authorizationUrl("http://127.0.0.1:41234/callback", "the-state", pkce).queryParameter("client_id") shouldBe BUILD_CLIENT_ID

        val unconfigured = GoogleClientCredentials("", "")
        GoogleOAuthClient(syncHttpClient(cacheDir), GoogleEndpoints.GOOGLE, unconfigured, clock, TEST_DISPATCHERS)
            .isConfigured shouldBe false
        GoogleOAuthClient(syncHttpClient(cacheDir), GoogleEndpoints.at(base), unconfigured, clock, TEST_DISPATCHERS)
            .isConfigured shouldBe true
    }

    @Test
    fun `the client secret stays out of the credentials' text`() {
        buildCredentials.toString() shouldNotContain BUILD_SECRET
        buildCredentials.isConfigured shouldBe true
        GoogleClientCredentials("", "").isConfigured shouldBe false
        GoogleClientCredentials("id", " ").isConfigured shouldBe false
    }

    private fun json(code: Int, body: String): MockResponse =
        MockResponse.Builder().code(code).setHeader("Content-Type", "application/json; charset=utf-8").body(body).build()

    private fun RecordedRequest.form(): Map<String, String> {
        headers["Content-Type"] shouldBe "application/x-www-form-urlencoded"
        val text = body?.utf8().orEmpty()
        return "http://form.invalid/?$text".toHttpUrl().let { url -> url.queryParameterNames.associateWith { url.queryParameter(it)!! } }
    }

    private companion object {
        const val BUILD_CLIENT_ID = "client-1.apps.example.invalid"
        const val BUILD_SECRET = "BUILD-CONFIG-SECRET-SENTINEL"
    }
}

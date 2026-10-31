package com.shinsaikou.sync.google

import com.shinsaikou.core.common.oauth.LoopbackListener
import com.shinsaikou.core.common.oauth.LoopbackPages
import com.shinsaikou.core.common.oauth.Pkce
import com.shinsaikou.sync.webdav.SyncFixtureProcess
import com.shinsaikou.sync.webdav.TEST_DISPATCHERS
import com.shinsaikou.sync.webdav.syncHttpClient
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * One whole sign-in against unit D's fixture (`tools/bench/sync_fixture_server.py`), which fakes
 * Google's authorization, token and revoke endpoints on Google's paths: the loopback listener, the
 * authorization URL followed by a plain client standing in for the browser (the fixture's consent
 * is already given, so it answers 302 to the loopback at once), the code exchange, refreshes, the
 * fixture's refresh failures, revoke, and `refresh_token_expires_in` when the fixture issues grants
 * that lapse. The fixture's own log then shows the client secret on every token request.
 */
class GoogleSignInFixtureTest {

    private val pages = LoopbackPages("Saikou", "Signed in", "Not signed in")

    @Test
    fun `a sign-in, refreshes, the fixture's failures and revoke, end to end`(@TempDir workDir: File) {
        val fixture = SyncFixtureProcess(workDir)
        try {
            runBlocking {
                val client = client(fixture, workDir)

                val first = signIn(client)
                first.refreshToken.shouldNotBeNull()
                first.refreshTokenExpiresAt.shouldBeNull()
                first.scope shouldBe GoogleOAuthClient.DRIVE_APPDATA_SCOPE
                val refreshToken = first.refreshToken!!

                client.refresh(refreshToken).accessToken.value shouldContain "ya29."

                // OkHttp resends a POST whose connection dropped before the answer twice more on its
                // own, so three drops in a row are what reach the client as one failure.
                fixture.modes("""{"refreshFailures": ["503", "drop", "drop", "drop"]}""")
                shouldThrow<GoogleUnavailableException> { client.refresh(refreshToken) }.status shouldBe 503
                shouldThrow<GoogleUnavailableException> { client.refresh(refreshToken) }.status.shouldBeNull()
                client.refresh(refreshToken).refreshToken.shouldBeNull()

                client.revoke(refreshToken) shouldBe true
                shouldThrow<GoogleInvalidGrantException> { client.refresh(refreshToken) }
                client.revoke(refreshToken) shouldBe false

                fixture.modes("""{"refreshTokenExpiresIn": 3600}""")
                val lapsing = signIn(client)
                val expiresAt = lapsing.refreshTokenExpiresAt.shouldNotBeNull()
                // The fixture answers the lifetime left, in whole seconds, on the host's clock.
                ((expiresAt - Clock.System.now()).inWholeSeconds in 3590L..3600L) shouldBe true
            }
        } finally {
            fixture.close()
        }

        val tokenLines = fixture.log.readLines().map { Json.parseToJsonElement(it).jsonObject }
            .filter { it.string("kind") == "oauth.token" }
        tokenLines.map { it.string("grant") }.toSet() shouldBe setOf("authorization_code", "refresh_token")
        tokenLines.map { it.string("client_secret") }.toSet() shouldBe setOf("present")
        tokenLines.mapNotNull { it["injected"]?.jsonPrimitive?.contentOrNullSafe() } shouldBe listOf("503", "drop", "drop", "drop")
    }

    /**
     * The client sends [GoogleClientCredentials.TEST_BASE] to any test base, so the wrong secret is
     * the fixture's: one started with another `--client-secret` refuses the test client.
     */
    @Test
    fun `a code used twice is invalid_grant, and a fixture expecting another secret refuses the client as invalid_client`(
        @TempDir workDir: File,
    ) {
        val fixture = SyncFixtureProcess(workDir)
        try {
            runBlocking {
                val client = client(fixture, workDir)
                val listener = LoopbackListener.open(0, pages)
                val pkce = Pkce.generate()
                val code = authorize(client, listener, pkce)
                client.exchange(code, pkce, listener.redirectUri).refreshToken.shouldNotBeNull()
                shouldThrow<GoogleInvalidGrantException> { client.exchange(code, pkce, listener.redirectUri) }
            }
        } finally {
            fixture.close()
        }
        val otherDir = File(workDir, "other").apply { mkdirs() }
        val other = SyncFixtureProcess(otherDir, listOf("--client-secret", "another-fixture-secret"))
        try {
            runBlocking {
                shouldThrow<GoogleAuthRefusedException> { client(other, otherDir).refresh("1//anything") }.error shouldBe "invalid_client"
            }
        } finally {
            other.close()
        }
    }

    /**
     * A blank build client: on a test base the client sends [GoogleClientCredentials.TEST_BASE], so a
     * fixture accepting it proves those are the fixture's defaults.
     */
    private fun client(fixture: SyncFixtureProcess, workDir: File) = GoogleOAuthClient(
        syncHttpClient(File(workDir, "cache")),
        GoogleEndpoints.forBuild(debug = true, testBase = "http://127.0.0.1:${fixture.port}"),
        GoogleClientCredentials("", ""),
        Clock.System,
        TEST_DISPATCHERS,
    )

    private suspend fun signIn(client: GoogleOAuthClient): GoogleTokens {
        val listener = LoopbackListener.open(0, pages)
        val pkce = Pkce.generate()
        val code = authorize(client, listener, pkce)
        return client.exchange(code, pkce, listener.redirectUri)
    }

    /** What the browser does: open the authorization URL, follow the 302 to the loopback, show the page. */
    private suspend fun authorize(client: GoogleOAuthClient, listener: LoopbackListener, pkce: Pkce): String =
        coroutineScope {
            val waiting = async(Dispatchers.IO) { listener.await() }
            val url = client.authorizationUrl(listener.redirectUri, listener.state, pkce)
            val page = async(Dispatchers.IO) {
                browser.newCall(Request.Builder().url(url).build()).execute().use { it.code to it.body.string() }
            }.await()
            page.first shouldBe 200
            page.second shouldContain "Signed in"
            withTimeout(10.seconds) { waiting.await() }.code
        }

    private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNullSafe()

    private fun JsonPrimitive.contentOrNullSafe(): String? = if (isString) content else null

    private companion object {
        val browser = OkHttpClient()
    }
}

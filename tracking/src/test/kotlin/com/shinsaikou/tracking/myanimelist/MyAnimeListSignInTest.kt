package com.shinsaikou.tracking.myanimelist

import com.shinsaikou.core.common.oauth.Pkce
import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.tracking.TrackerIds
import com.shinsaikou.tracking.TrackerSignInRefusedException
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.ACCESS_1
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.BUILD_CLIENT_ID
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.NOW_SECONDS
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.ONE_MONTH
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.REFRESH_1
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.USER_BODY
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.USER_NAME
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.form
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.json
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.tokenBody
import eu.kanade.tachiyomi.network.HttpException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

/**
 * MyAnimeList's browser sign-in through T1's seam (phase 3 unit T2): the page it builds, the code
 * exchange and the user fetch against a fake token endpoint and API, what it stores and in which
 * order, and what a failure leaves (R95 item 13). Whether it is configured follows the build's
 * client id and the test base.
 */
class MyAnimeListSignInTest {

    @TempDir
    lateinit var cacheDir: File

    private val fixture by lazy { MyAnimeListFixture(cacheDir) }
    private val pkce = Pkce("v".repeat(20) + "E".repeat(20) + "-._~0123456789")

    @AfterEach
    fun close() {
        fixture.close()
    }

    @Test
    fun `the authorization URL carries the build's client id, the verifier as a plain challenge, the state and the loopback`() {
        fixture.endpoints = MyAnimeListEndpoints.MYANIMELIST

        val url = fixture.tracker.authorizationUrl("http://127.0.0.1:18796/callback", "the-state", pkce)

        url.scheme shouldBe "https"
        url.host shouldBe "myanimelist.net"
        url.encodedPath shouldBe "/v1/oauth2/authorize"
        url.queryParameterNames shouldBe setOf(
            "client_id", "code_challenge", "code_challenge_method", "response_type", "redirect_uri", "state",
        )
        url.queryParameter("client_id") shouldBe BUILD_CLIENT_ID
        url.queryParameter("code_challenge") shouldBe pkce.verifier
        url.queryParameter("code_challenge_method") shouldBe "plain"
        url.queryParameter("response_type") shouldBe "code"
        url.queryParameter("redirect_uri") shouldBe "http://127.0.0.1:18796/callback"
        url.queryParameter("state") shouldBe "the-state"
        // Building the page calls nothing and stores nothing.
        fixture.server.requestCount shouldBe 0
        fixture.preferences.values.keys.none { "track_token" in it } shouldBe true
    }

    @Test
    fun `on a test base every address moves to it and the fake's client id replaces the build's`() {
        val url = fixture.tracker.authorizationUrl("http://127.0.0.1:18796/callback", "the-state", pkce)

        url.host shouldBe fixture.baseUrl.host
        url.port shouldBe fixture.server.port
        url.encodedPath shouldBe "/v1/oauth2/authorize"
        url.queryParameter("client_id") shouldBe MyAnimeListClient.TEST_BASE_CLIENT_ID
        url.toString() shouldNotContain BUILD_CLIENT_ID
        fixture.endpoints.token.encodedPath shouldBe "/v1/oauth2/token"
        fixture.endpoints.api.encodedPath shouldBe "/v2"
    }

    @Test
    fun `a blank client id is not configured unless a test base is set, and a set one is`() {
        MyAnimeListFixture(cacheDir, buildClientId = "").use { blank ->
            blank.endpoints = MyAnimeListEndpoints.MYANIMELIST
            blank.tracker.isConfigured shouldBe false
            blank.endpoints = MyAnimeListEndpoints.at(blank.baseUrl)
            blank.tracker.isConfigured shouldBe true
        }
        MyAnimeListFixture(cacheDir, buildClientId = "  ").use { spaces ->
            spaces.endpoints = MyAnimeListEndpoints.MYANIMELIST
            spaces.tracker.isConfigured shouldBe false
        }
        fixture.endpoints = MyAnimeListEndpoints.MYANIMELIST
        fixture.tracker.isConfigured shouldBe true
        // Not configured is still in the graph under its id, as restored rows need.
        fixture.tracker.id shouldBe TrackerIds.MYANIMELIST
    }

    @Test
    fun `a sign-in exchanges the code with the same redirect and verifier, fetches the user with the new token, then stores all three`() {
        runBlocking {
            fixture.server.enqueue(json(200, tokenBody(ACCESS_1, REFRESH_1)))
            fixture.server.enqueue(json(200, USER_BODY))
            fixture.trackPreferences.trackAuthExpired(TrackerIds.MYANIMELIST).set(true)

            fixture.tracker.completeSignIn("the-code", "http://127.0.0.1:18796/callback", pkce)

            val exchange = fixture.server.takeRequest()
            exchange.method shouldBe "POST"
            exchange.url.encodedPath shouldBe "/v1/oauth2/token"
            exchange.form() shouldBe mapOf(
                "client_id" to MyAnimeListClient.TEST_BASE_CLIENT_ID,
                "code" to "the-code",
                "code_verifier" to pkce.verifier,
                "grant_type" to "authorization_code",
                "redirect_uri" to "http://127.0.0.1:18796/callback",
            )
            val user = fixture.server.takeRequest()
            user.method shouldBe "GET"
            user.url.pathSegments shouldContainExactly listOf("v2", "users", "@me")
            user.headers["Authorization"] shouldBe "Bearer $ACCESS_1"

            val stored = checkNotNull(fixture.storedToken())
            stored.accessToken shouldBe ACCESS_1
            stored.refreshToken shouldBe REFRESH_1
            stored.expiresIn shouldBe ONE_MONTH
            stored.createdAt shouldBe NOW_SECONDS
            fixture.tracker.getDisplayUsername() shouldBe USER_NAME
            fixture.tracker.getUsername() shouldBe USER_NAME
            fixture.tracker.isLoggedIn shouldBe true
            fixture.tracker.isAuthExpired shouldBe false
        }
    }

    @Test
    fun `the three writes land in order, token, display name, credentials`() {
        runBlocking {
            val order = mutableListOf<String>()
            val watched = listOf("track_token_1", "pref_mangasync_displayname_1", "pref_mangasync_username_1")
            val recording = object : LinkedHashMap<String, Any>() {
                override fun put(key: String, value: Any): Any? {
                    watched.firstOrNull { key.endsWith(it) }?.let(order::add)
                    return super.put(key, value)
                }
            }
            MyAnimeListFixture(cacheDir, values = recording).use { recorded ->
                recorded.server.enqueue(json(200, tokenBody(ACCESS_1, REFRESH_1)))
                recorded.server.enqueue(json(200, USER_BODY))

                recorded.tracker.completeSignIn("the-code", "http://127.0.0.1:18796/callback", pkce)
            }

            order shouldContainExactly listOf("track_token_1", "pref_mangasync_displayname_1", "pref_mangasync_username_1")
        }
    }

    @Test
    fun `a refused code exchange throws the OAuth error and stores nothing`() {
        runBlocking {
            fixture.server.enqueue(
                json(400, """{"error":"invalid_grant","message":"Authorization code has expired","hint":"the-code"}"""),
            )

            val refused = shouldThrow<TrackerSignInRefusedException> {
                fixture.tracker.completeSignIn("the-code", "http://127.0.0.1:18796/callback", pkce)
            }

            refused.error shouldBe "invalid_grant"
            ErrorKey.of(refused) shouldBe ErrorKey.TRACKER_SIGN_IN_REFUSED
            ErrorKey.detail(refused) shouldBe "invalid_grant"
            fixture.server.requestCount shouldBe 1
            fixture.storedToken().shouldBeNull()
            fixture.tracker.isLoggedIn shouldBe false
        }
    }

    @Test
    fun `a refusal without an OAuth error names the status, never the body`() {
        runBlocking {
            fixture.server.enqueue(MockResponse.Builder().code(403).body("<html>the-code was refused</html>").build())

            val refused = shouldThrow<TrackerSignInRefusedException> {
                fixture.tracker.completeSignIn("the-code", "http://127.0.0.1:18796/callback", pkce)
            }

            refused.error shouldBe "http_403"
            refused.message.orEmpty() shouldNotContain "the-code"

            // An error field that is not a short OAuth code, which could echo the request, is not taken.
            fixture.server.enqueue(json(400, """{"error":"Invalid code: the-code"}"""))
            val echoed = shouldThrow<TrackerSignInRefusedException> {
                fixture.tracker.completeSignIn("the-code", "http://127.0.0.1:18796/callback", pkce)
            }
            echoed.error shouldBe "http_400"
            echoed.message.orEmpty() shouldNotContain "the-code"
        }
    }

    @Test
    fun `a failed user fetch after a good exchange stores nothing and leaves an expired sign-in expired`() {
        runBlocking {
            fixture.signedIn(access = "old-access", refresh = "old-refresh")
            fixture.tracker.setAuthExpired()
            fixture.server.enqueue(json(200, tokenBody(ACCESS_1, REFRESH_1)))
            fixture.server.enqueue(json(401, """{"error":"invalid_token"}"""))

            val failed = shouldThrow<HttpException> {
                fixture.tracker.completeSignIn("the-code", "http://127.0.0.1:18796/callback", pkce)
            }

            failed.code shouldBe 401
            ErrorKey.of(failed) shouldBe ErrorKey.HTTP
            checkNotNull(fixture.storedToken()).accessToken shouldBe "old-access"
            fixture.tracker.isAuthExpired shouldBe true
            fixture.tracker.isLoggedIn shouldBe true
            fixture.tracker.getDisplayUsername() shouldBe USER_NAME
        }
    }

    @Test
    fun `an unreachable token endpoint throws its IOException and stores nothing`() {
        runBlocking {
            repeat(3) { fixture.server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection).build()) }

            shouldThrow<IOException> {
                fixture.tracker.completeSignIn("the-code", "http://127.0.0.1:18796/callback", pkce)
            }

            fixture.storedToken().shouldBeNull()
            fixture.tracker.isLoggedIn shouldBe false
        }
    }

    @Test
    fun `sign-out clears the token, the credentials and the expired flag`() {
        fixture.signedIn()
        fixture.tracker.setAuthExpired()

        fixture.tracker.logout()

        fixture.storedToken().shouldBeNull()
        fixture.tracker.isLoggedIn shouldBe false
        fixture.tracker.isAuthExpired shouldBe false
    }
}

package com.shinsaikou.tracking.mangabaka

import com.shinsaikou.core.common.oauth.LoopbackResponse
import com.shinsaikou.core.common.oauth.Pkce
import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.tracking.TrackerIds
import com.shinsaikou.tracking.TrackerSignInRefusedException
import com.shinsaikou.tracking.mangabaka.MangaBakaFixture.Companion.ACCESS_1
import com.shinsaikou.tracking.mangabaka.MangaBakaFixture.Companion.ACCESS_2
import com.shinsaikou.tracking.mangabaka.MangaBakaFixture.Companion.BUILD_CLIENT_ID
import com.shinsaikou.tracking.mangabaka.MangaBakaFixture.Companion.NOW_SECONDS
import com.shinsaikou.tracking.mangabaka.MangaBakaFixture.Companion.ONE_WEEK
import com.shinsaikou.tracking.mangabaka.MangaBakaFixture.Companion.REFRESH_1
import com.shinsaikou.tracking.mangabaka.MangaBakaFixture.Companion.REFRESH_2
import com.shinsaikou.tracking.mangabaka.MangaBakaFixture.Companion.REVOKE_PATH
import com.shinsaikou.tracking.mangabaka.MangaBakaFixture.Companion.TOKEN_PATH
import com.shinsaikou.tracking.mangabaka.MangaBakaFixture.Companion.USER_ID
import com.shinsaikou.tracking.mangabaka.MangaBakaFixture.Companion.USER_NAME
import com.shinsaikou.tracking.mangabaka.MangaBakaFixture.Companion.form
import com.shinsaikou.tracking.mangabaka.MangaBakaFixture.Companion.json
import com.shinsaikou.tracking.mangabaka.MangaBakaFixture.Companion.profileBody
import com.shinsaikou.tracking.mangabaka.MangaBakaFixture.Companion.tokenBody
import com.shinsaikou.tracking.mangabaka.dto.MangaBakaOAuth
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
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
import java.util.concurrent.TimeUnit

/**
 * MangaBaka's browser sign-in through the OAuth seam: the code flow with an S256 challenge on the
 * loopback redirect, asking for `openid` (which the API requires) and the library scopes.
 * [MangaBaka.completeSignIn] exchanges the code with the verifier, reads the profile with the new
 * token, then stores the score step, the grant, the name and the credentials, in that order. A
 * failure stores nothing. Sign-out clears the sign-in at once and revokes the refresh token at
 * MangaBaka after, best effort. Whether MangaBaka is configured follows the build's client id.
 */
class MangaBakaSignInTest {

    @TempDir
    lateinit var cacheDir: File

    private val fixture by lazy { MangaBakaFixture(cacheDir) }
    private val pkce = Pkce("v".repeat(43))
    private val redirect = "http://127.0.0.1:18796/callback"

    @AfterEach
    fun close() {
        fixture.close()
    }

    @Test
    fun `the authorization URL asks MangaBaka for a code with the build's client id, the loopback, the scopes, the state and an S256 challenge`() {
        fixture.endpoints = MangaBakaEndpoints.MANGABAKA

        val url = fixture.tracker.authorizationUrl(redirect, "the-state", pkce)

        url.scheme shouldBe "https"
        url.host shouldBe "mangabaka.org"
        url.encodedPath shouldBe "/auth/oauth2/authorize"
        url.queryParameterNames shouldBe
            setOf("response_type", "client_id", "redirect_uri", "scope", "state", "code_challenge", "code_challenge_method")
        url.queryParameter("response_type") shouldBe "code"
        url.queryParameter("client_id") shouldBe BUILD_CLIENT_ID
        url.queryParameter("redirect_uri") shouldBe redirect
        url.queryParameter("scope")!!.split(" ").toSet() shouldBe
            setOf("openid", "profile", "library.read", "library.write", "offline_access")
        url.queryParameter("state") shouldBe "the-state"
        url.queryParameter("code_challenge") shouldBe pkce.challenge
        url.queryParameter("code_challenge_method") shouldBe "S256"
        // The verifier stays on the device until the exchange.
        url.toString() shouldNotContain pkce.verifier
        fixture.tracker.loopbackResponse shouldBe LoopbackResponse.CODE
        // Building the page calls nothing and stores nothing.
        fixture.server.requestCount shouldBe 0
        fixture.preferences.values.keys.none { "track_token" in it } shouldBe true
    }

    @Test
    fun `on a test base every address moves to it and the fixture's client id replaces the build's`() {
        val url = fixture.tracker.authorizationUrl(redirect, "the-state", pkce)

        url.host shouldBe fixture.baseUrl.host
        url.port shouldBe fixture.server.port
        url.encodedPath shouldBe "/auth/oauth2/authorize"
        url.queryParameter("client_id") shouldBe MangaBakaClient.TEST_BASE_CLIENT_ID
        url.toString() shouldNotContain BUILD_CLIENT_ID
        fixture.endpoints.token.encodedPath shouldBe TOKEN_PATH
        fixture.endpoints.revocation.encodedPath shouldBe REVOKE_PATH
        fixture.endpoints.api.encodedPath shouldBe "/"
    }

    @Test
    fun `a blank client id is not configured, a set one is, and either way MangaBaka keeps its id`() {
        MangaBakaFixture(cacheDir, buildClientId = "").use { blank ->
            blank.endpoints = MangaBakaEndpoints.MANGABAKA
            blank.tracker.isConfigured shouldBe false
            blank.tracker.id shouldBe TrackerIds.MANGABAKA
        }
        MangaBakaFixture(cacheDir, buildClientId = "  ").use { spaces ->
            spaces.endpoints = MangaBakaEndpoints.MANGABAKA
            spaces.tracker.isConfigured shouldBe false
        }
        fixture.endpoints = MangaBakaEndpoints.MANGABAKA
        fixture.tracker.isConfigured shouldBe true
    }

    @Test
    fun `a sign-in exchanges the code with the verifier, reads the profile with the new token, then stores the step, the grant, the name and the id`() {
        runBlocking {
            fixture.server.enqueue(json(200, tokenBody(ACCESS_1, REFRESH_1)))
            fixture.server.enqueue(json(200, profileBody(ratingSteps = 20)))
            fixture.trackPreferences.trackAuthExpired(TrackerIds.MANGABAKA).set(true)

            fixture.tracker.completeSignIn("the-code", redirect, pkce)

            val exchange = fixture.server.takeRequest()
            exchange.method shouldBe "POST"
            exchange.url.encodedPath shouldBe TOKEN_PATH
            exchange.form() shouldBe mapOf(
                "grant_type" to "authorization_code",
                "code" to "the-code",
                "redirect_uri" to redirect,
                "client_id" to MangaBakaClient.TEST_BASE_CLIENT_ID,
                "code_verifier" to pkce.verifier,
            )
            val profile = fixture.server.takeRequest()
            profile.method shouldBe "GET"
            profile.url.encodedPath shouldBe "/v1/my/profile"
            profile.headers["Authorization"] shouldBe "Bearer $ACCESS_1"

            fixture.storedToken() shouldBe MangaBakaOAuth(ACCESS_1, REFRESH_1, NOW_SECONDS + ONE_WEEK)
            fixture.trackPreferences.mangabakaScoreType.get() shouldBe MangaBaka.STEP_20
            fixture.tracker.getDisplayUsername() shouldBe USER_NAME
            fixture.tracker.getUsername() shouldBe USER_ID
            fixture.tracker.isLoggedIn shouldBe true
            fixture.tracker.isAuthExpired shouldBe false
        }
    }

    @Test
    fun `the writes land in order, score step, token, display name, credentials`() {
        runBlocking {
            val order = mutableListOf<String>()
            val watched = listOf("mangabaka_score_type", "track_token_11", "pref_mangasync_displayname_11", "pref_mangasync_username_11")
            val recording = object : LinkedHashMap<String, Any>() {
                override fun put(key: String, value: Any): Any? {
                    watched.firstOrNull { key.endsWith(it) }?.let(order::add)
                    return super.put(key, value)
                }
            }
            MangaBakaFixture(cacheDir, values = recording).use { recorded ->
                recorded.server.enqueue(json(200, tokenBody(ACCESS_1, REFRESH_1)))
                recorded.server.enqueue(json(200, profileBody()))

                recorded.tracker.completeSignIn("the-code", redirect, pkce)
            }

            order shouldContainExactly watched
        }
    }

    @Test
    fun `the display name falls back from the nickname to the username to the id, and an unknown or absent step reads as 1`() {
        for ((profile, expected) in listOf(
            profileBody(ratingSteps = null, nickname = null) to "fixture_handle",
            profileBody(ratingSteps = 7, nickname = " ", preferredUsername = null) to USER_ID,
        )) {
            MangaBakaFixture(cacheDir).use { local ->
                runBlocking {
                    local.trackPreferences.mangabakaScoreType.set(MangaBaka.STEP_25)
                    local.server.enqueue(json(200, tokenBody(ACCESS_1, REFRESH_1)))
                    local.server.enqueue(json(200, profile))

                    local.tracker.completeSignIn("the-code", redirect, pkce)

                    local.tracker.getDisplayUsername() shouldBe expected
                    local.trackPreferences.mangabakaScoreType.get() shouldBe MangaBaka.STEP_1
                }
            }
        }
    }

    @Test
    fun `a code the token endpoint refuses throws its OAuth error, never the body, and stores nothing`() {
        runBlocking {
            fixture.signedIn(access = ACCESS_1)
            fixture.tracker.setAuthExpired()
            fixture.server.enqueue(json(401, """{"error":"invalid_request","error_description":"bad verifier for the-code"}"""))

            val refused = shouldThrow<TrackerSignInRefusedException> { fixture.tracker.completeSignIn("the-code", redirect, pkce) }

            refused.error shouldBe "invalid_request"
            ErrorKey.of(refused) shouldBe ErrorKey.TRACKER_SIGN_IN_REFUSED
            refused.message.orEmpty() shouldNotContain "the-code"
            fixture.server.requestCount shouldBe 1
            checkNotNull(fixture.storedToken()).accessToken shouldBe ACCESS_1
            fixture.tracker.isAuthExpired shouldBe true
            fixture.tracker.isLoggedIn shouldBe true
        }
    }

    @Test
    fun `a profile read that fails after the exchange stores nothing`() {
        runBlocking {
            fixture.server.enqueue(json(200, tokenBody(ACCESS_2, REFRESH_2)))
            fixture.server.enqueue(json(401, """{"message":"No session found","status":401,"code":null}"""))

            shouldThrow<Exception> { fixture.tracker.completeSignIn("the-code", redirect, pkce) }

            fixture.storedToken().shouldBeNull()
            fixture.tracker.isLoggedIn shouldBe false
            fixture.trackPreferences.mangabakaScoreType.get() shouldBe MangaBaka.STEP_1
        }
    }

    @Test
    fun `an unreachable MangaBaka throws its IOException and stores nothing`() {
        runBlocking {
            repeat(3) { fixture.server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection).build()) }

            shouldThrow<IOException> { fixture.tracker.completeSignIn("the-code", redirect, pkce) }

            fixture.storedToken().shouldBeNull()
            fixture.tracker.isLoggedIn shouldBe false
        }
    }

    @Test
    fun `sign-out clears the token, the credentials and the expired flag at once, then revokes the refresh token`() {
        fixture.signedIn()
        fixture.tracker.setAuthExpired()
        fixture.server.enqueue(json(200, "{}"))

        fixture.tracker.logout()

        fixture.storedToken().shouldBeNull()
        fixture.tracker.isLoggedIn shouldBe false
        fixture.tracker.isAuthExpired shouldBe false
        val revoke = fixture.server.takeRequest(10, TimeUnit.SECONDS).shouldNotBeNull()
        revoke.method shouldBe "POST"
        revoke.url.encodedPath shouldBe REVOKE_PATH
        revoke.headers["Authorization"].shouldBeNull()
        revoke.form() shouldBe mapOf(
            "token" to REFRESH_1,
            "token_type_hint" to "refresh_token",
            "client_id" to MangaBakaClient.TEST_BASE_CLIENT_ID,
        )
        fixture.uncaught.shouldBeEmpty()
    }

    @Test
    fun `a sign-out offline, or one MangaBaka refuses to revoke, still signs out and throws nothing`() {
        val failures = listOf(
            MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection).build(),
            json(400, """{"error":"invalid_request"}"""),
        )
        for (failure in failures) {
            MangaBakaFixture(cacheDir).use { local ->
                local.signedIn()
                repeat(3) { local.server.enqueue(failure) }

                local.tracker.logout()

                local.storedToken().shouldBeNull()
                local.tracker.isLoggedIn shouldBe false
                local.server.takeRequest(10, TimeUnit.SECONDS).shouldNotBeNull().url.encodedPath shouldBe REVOKE_PATH
                // Give the failed revoke time to land in the scope's handler, were it uncaught.
                Thread.sleep(300)
                local.uncaught.shouldBeEmpty()
            }
        }
    }

    @Test
    fun `a sign-out with no grant stored sends nothing`() {
        fixture.trackPreferences.setCredentials(TrackerIds.MANGABAKA, USER_ID, ACCESS_1)

        fixture.tracker.logout()

        fixture.tracker.isLoggedIn shouldBe false
        Thread.sleep(200)
        fixture.server.requestCount shouldBe 0
    }
}

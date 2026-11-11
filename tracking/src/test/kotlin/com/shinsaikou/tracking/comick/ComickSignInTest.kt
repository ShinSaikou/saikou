package com.shinsaikou.tracking.comick

import com.shinsaikou.core.common.oauth.LoopbackResponse
import com.shinsaikou.core.common.oauth.Pkce
import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.tracking.TrackerIds
import com.shinsaikou.tracking.TrackerPermissionMissingException
import com.shinsaikou.tracking.TrackerSignInRefusedException
import com.shinsaikou.tracking.comick.ComickFixture.Companion.ACCESS_1
import com.shinsaikou.tracking.comick.ComickFixture.Companion.ACCESS_2
import com.shinsaikou.tracking.comick.ComickFixture.Companion.BUILD_CLIENT_ID
import com.shinsaikou.tracking.comick.ComickFixture.Companion.GRANTED
import com.shinsaikou.tracking.comick.ComickFixture.Companion.NOW_SECONDS
import com.shinsaikou.tracking.comick.ComickFixture.Companion.ONE_HOUR
import com.shinsaikou.tracking.comick.ComickFixture.Companion.REFRESH_1
import com.shinsaikou.tracking.comick.ComickFixture.Companion.REFRESH_2
import com.shinsaikou.tracking.comick.ComickFixture.Companion.RESOURCE
import com.shinsaikou.tracking.comick.ComickFixture.Companion.TOKEN_PATH
import com.shinsaikou.tracking.comick.ComickFixture.Companion.USER_ID
import com.shinsaikou.tracking.comick.ComickFixture.Companion.USER_NAME
import com.shinsaikou.tracking.comick.ComickFixture.Companion.form
import com.shinsaikou.tracking.comick.ComickFixture.Companion.json
import com.shinsaikou.tracking.comick.ComickFixture.Companion.tokenBody
import com.shinsaikou.tracking.comick.ComickFixture.Companion.userBody
import com.shinsaikou.tracking.comick.dto.ComickOAuth
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
 * Comick's browser sign-in through the OAuth seam: the code flow with an S256 challenge on the
 * loopback redirect, asking for the library scopes and `offline_access`, and naming Comick's library
 * API as the resource on the page and the exchange. [Comick.completeSignIn] exchanges the code with
 * the verifier, reads the account with the new token, then stores the grant, the name and the
 * credentials, in that order. A grant without `offline_access` or `library:write` is refused with
 * the scope it lacks, and like any failure stores nothing. Whether Comick is configured follows the
 * build's client id.
 */
class ComickSignInTest {

    @TempDir
    lateinit var cacheDir: File

    private val fixture by lazy { ComickFixture(cacheDir) }
    private val pkce = Pkce("v".repeat(43))
    private val redirect = "http://127.0.0.1:18796/callback"

    @AfterEach
    fun close() {
        fixture.close()
    }

    @Test
    fun `the authorization URL asks Comick for a code with the build's client id, the loopback, the scopes, the resource, the state and an S256 challenge`() {
        fixture.endpoints = ComickEndpoints.COMICK

        val url = fixture.tracker.authorizationUrl(redirect, "the-state", pkce)

        url.scheme shouldBe "https"
        url.host shouldBe "comick.dev"
        url.encodedPath shouldBe "/api/auth/oauth2/authorize"
        url.queryParameterNames shouldBe setOf(
            "response_type", "client_id", "redirect_uri", "scope", "resource", "state", "code_challenge", "code_challenge_method",
        )
        url.queryParameter("response_type") shouldBe "code"
        url.queryParameter("client_id") shouldBe BUILD_CLIENT_ID
        url.queryParameter("redirect_uri") shouldBe redirect
        url.queryParameter("scope")!!.split(" ").toSet() shouldBe setOf("library:read", "library:write", "offline_access")
        url.queryParameter("resource") shouldBe RESOURCE
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
    fun `on a test base every address moves to it, the fixture's client id replaces the build's, and the resource stays Comick's`() {
        val url = fixture.tracker.authorizationUrl(redirect, "the-state", pkce)

        url.host shouldBe fixture.baseUrl.host
        url.port shouldBe fixture.server.port
        url.encodedPath shouldBe "/api/auth/oauth2/authorize"
        url.queryParameter("client_id") shouldBe ComickClient.TEST_BASE_CLIENT_ID
        url.queryParameter("resource") shouldBe RESOURCE
        url.toString() shouldNotContain BUILD_CLIENT_ID
        fixture.endpoints.token.encodedPath shouldBe TOKEN_PATH
        fixture.endpoints.api.encodedPath shouldBe "/integrations/v1/"
    }

    @Test
    fun `a blank client id is not configured, a set one is, and either way Comick keeps its id`() {
        ComickFixture(cacheDir, buildClientId = "").use { blank ->
            blank.endpoints = ComickEndpoints.COMICK
            blank.tracker.isConfigured shouldBe false
            blank.tracker.id shouldBe TrackerIds.COMICK
        }
        ComickFixture(cacheDir, buildClientId = "  ").use { spaces ->
            spaces.endpoints = ComickEndpoints.COMICK
            spaces.tracker.isConfigured shouldBe false
        }
        fixture.endpoints = ComickEndpoints.COMICK
        fixture.tracker.isConfigured shouldBe true
        fixture.tracker.name shouldBe "Comick"
    }

    @Test
    fun `a sign-in exchanges the code with the verifier and the resource, reads the account with the new token, then stores the grant, the name and the id`() {
        runBlocking {
            fixture.server.enqueue(json(200, tokenBody(ACCESS_1, REFRESH_1)))
            fixture.server.enqueue(json(200, userBody()))
            fixture.trackPreferences.trackAuthExpired(TrackerIds.COMICK).set(true)

            fixture.tracker.completeSignIn("the-code", redirect, pkce)

            val exchange = fixture.server.takeRequest()
            exchange.method shouldBe "POST"
            exchange.url.encodedPath shouldBe TOKEN_PATH
            exchange.form() shouldBe mapOf(
                "grant_type" to "authorization_code",
                "client_id" to ComickClient.TEST_BASE_CLIENT_ID,
                "code" to "the-code",
                "redirect_uri" to redirect,
                "code_verifier" to pkce.verifier,
                "resource" to RESOURCE,
            )
            exchange.headers["Accept"] shouldBe "application/json; charset=utf-8"
            val account = fixture.server.takeRequest()
            account.method shouldBe "GET"
            account.url.encodedPath shouldBe "/integrations/v1/me"
            account.headers["Authorization"] shouldBe "Bearer $ACCESS_1"

            fixture.storedToken() shouldBe ComickOAuth(ACCESS_1, NOW_SECONDS + ONE_HOUR, REFRESH_1, GRANTED)
            fixture.tracker.getDisplayUsername() shouldBe USER_NAME
            fixture.tracker.getUsername() shouldBe USER_ID
            fixture.tracker.isLoggedIn shouldBe true
            fixture.tracker.isAuthExpired shouldBe false
        }
    }

    @Test
    fun `the standard expires_in wins over expires_at, read on the tracker's clock`() {
        runBlocking {
            fixture.server.enqueue(json(200, tokenBody(ACCESS_1, REFRESH_1, expiresAt = 1L, expiresIn = 900L)))
            fixture.server.enqueue(json(200, userBody()))

            fixture.tracker.completeSignIn("the-code", redirect, pkce)

            fixture.storedToken()!!.expiresAt shouldBe NOW_SECONDS + 900L
        }
    }

    @Test
    fun `the writes land in order, token, display name, credentials`() {
        runBlocking {
            val order = mutableListOf<String>()
            val watched = listOf("track_token_12", "pref_mangasync_displayname_12", "pref_mangasync_username_12")
            val recording = object : LinkedHashMap<String, Any>() {
                override fun put(key: String, value: Any): Any? {
                    watched.firstOrNull { key.endsWith(it) }?.let(order::add)
                    return super.put(key, value)
                }
            }
            ComickFixture(cacheDir, values = recording).use { recorded ->
                recorded.server.enqueue(json(200, tokenBody(ACCESS_1, REFRESH_1)))
                recorded.server.enqueue(json(200, userBody()))

                recorded.tracker.completeSignIn("the-code", redirect, pkce)
            }

            order shouldContainExactly watched
        }
    }

    @Test
    fun `the display name falls back from the username to the id`() {
        for (username in listOf(null, " ")) {
            ComickFixture(cacheDir).use { local ->
                runBlocking {
                    local.server.enqueue(json(200, tokenBody(ACCESS_1, REFRESH_1)))
                    local.server.enqueue(json(200, userBody(username)))

                    local.tracker.completeSignIn("the-code", redirect, pkce)

                    local.tracker.getDisplayUsername() shouldBe USER_ID
                }
            }
        }
    }

    @Test
    fun `a grant without a refresh token is refused as missing offline_access, reads the account never, and stores nothing`() {
        runBlocking {
            fixture.server.enqueue(json(200, tokenBody(ACCESS_1, refresh = null, scope = "library:read library:write")))

            val missing = shouldThrow<TrackerPermissionMissingException> { fixture.tracker.completeSignIn("the-code", redirect, pkce) }

            missing.scope shouldBe "offline_access"
            missing.trackerId shouldBe TrackerIds.COMICK
            ErrorKey.of(missing) shouldBe ErrorKey.TRACKER_PERMISSION_MISSING
            ErrorKey.detail(missing) shouldBe "offline_access"
            missing.message.orEmpty() shouldNotContain ACCESS_1
            fixture.server.requestCount shouldBe 1
            fixture.storedToken().shouldBeNull()
            fixture.tracker.isLoggedIn shouldBe false
        }
    }

    @Test
    fun `a grant whose scopes lack library_write, or name none, is refused as missing library_write and stores nothing`() {
        for (scope in listOf("library:read offline_access", null)) {
            ComickFixture(cacheDir).use { local ->
                runBlocking {
                    local.server.enqueue(json(200, tokenBody(ACCESS_1, REFRESH_1, scope = scope)))

                    val missing = shouldThrow<TrackerPermissionMissingException> { local.tracker.completeSignIn("the-code", redirect, pkce) }

                    missing.scope shouldBe "library:write"
                    ErrorKey.detail(missing) shouldBe "library:write"
                    missing.message.orEmpty() shouldNotContain REFRESH_1
                    local.server.requestCount shouldBe 1
                    local.storedToken().shouldBeNull()
                    local.tracker.isLoggedIn shouldBe false
                }
            }
        }
    }

    @Test
    fun `a refused permission leaves an earlier sign-in as it was`() {
        runBlocking {
            fixture.signedIn(access = ACCESS_1)
            fixture.tracker.setAuthExpired()
            fixture.server.enqueue(json(200, tokenBody(ACCESS_2, REFRESH_2, scope = "library:read offline_access")))

            shouldThrow<TrackerPermissionMissingException> { fixture.tracker.completeSignIn("the-code", redirect, pkce) }

            checkNotNull(fixture.storedToken()).accessToken shouldBe ACCESS_1
            fixture.tracker.isAuthExpired shouldBe true
            fixture.tracker.isLoggedIn shouldBe true
        }
    }

    @Test
    fun `a code the token endpoint refuses throws its OAuth error, never the body, and stores nothing`() {
        runBlocking {
            fixture.signedIn(access = ACCESS_1)
            fixture.tracker.setAuthExpired()
            fixture.server.enqueue(json(400, """{"error":"invalid_grant","error_description":"bad verifier for the-code"}"""))

            val refused = shouldThrow<TrackerSignInRefusedException> { fixture.tracker.completeSignIn("the-code", redirect, pkce) }

            refused.error shouldBe "invalid_grant"
            ErrorKey.of(refused) shouldBe ErrorKey.TRACKER_SIGN_IN_REFUSED
            refused.message.orEmpty() shouldNotContain "the-code"
            fixture.server.requestCount shouldBe 1
            checkNotNull(fixture.storedToken()).accessToken shouldBe ACCESS_1
            fixture.tracker.isAuthExpired shouldBe true
            fixture.tracker.isLoggedIn shouldBe true
        }
    }

    @Test
    fun `an account read that fails after the exchange stores nothing`() {
        runBlocking {
            fixture.server.enqueue(json(200, tokenBody(ACCESS_2, REFRESH_2)))
            fixture.server.enqueue(json(401, """{"statusCode":401,"message":"Unauthorized"}"""))

            shouldThrow<Exception> { fixture.tracker.completeSignIn("the-code", redirect, pkce) }

            fixture.storedToken().shouldBeNull()
            fixture.tracker.isLoggedIn shouldBe false
        }
    }

    @Test
    fun `an unreachable Comick throws its IOException and stores nothing`() {
        runBlocking {
            repeat(3) { fixture.server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection).build()) }

            shouldThrow<IOException> { fixture.tracker.completeSignIn("the-code", redirect, pkce) }

            fixture.storedToken().shouldBeNull()
            fixture.tracker.isLoggedIn shouldBe false
        }
    }

    @Test
    fun `sign-out clears the token, the credentials and the expired flag, and sends nothing`() {
        fixture.signedIn()
        fixture.tracker.setAuthExpired()

        fixture.tracker.logout()

        fixture.storedToken().shouldBeNull()
        fixture.tracker.isLoggedIn shouldBe false
        fixture.tracker.isAuthExpired shouldBe false
        Thread.sleep(200)
        fixture.server.requestCount shouldBe 0
    }

    @Test
    fun `there is no password sign-in`() {
        runBlocking {
            shouldThrow<UnsupportedOperationException> { fixture.tracker.login("user", "password") }
        }
    }
}

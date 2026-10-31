package com.shinsaikou.tracking.anilist

import com.shinsaikou.core.common.oauth.LoopbackResponse
import com.shinsaikou.core.common.oauth.Pkce
import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.tracking.TrackerIds
import com.shinsaikou.tracking.anilist.AnilistFixture.Companion.ACCESS_1
import com.shinsaikou.tracking.anilist.AnilistFixture.Companion.ACCESS_2
import com.shinsaikou.tracking.anilist.AnilistFixture.Companion.BUILD_CLIENT_ID
import com.shinsaikou.tracking.anilist.AnilistFixture.Companion.NOW_MILLIS
import com.shinsaikou.tracking.anilist.AnilistFixture.Companion.ONE_YEAR_MILLIS
import com.shinsaikou.tracking.anilist.AnilistFixture.Companion.USER_ID
import com.shinsaikou.tracking.anilist.AnilistFixture.Companion.USER_NAME
import com.shinsaikou.tracking.anilist.AnilistFixture.Companion.graphql
import com.shinsaikou.tracking.anilist.AnilistFixture.Companion.json
import com.shinsaikou.tracking.anilist.AnilistFixture.Companion.viewerBody
import com.shinsaikou.tracking.anilist.dto.ALOAuth
import eu.kanade.tachiyomi.network.HttpException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
 * AniList's browser sign-in through the OAuth seam: its implicit grant asks for a token on the
 * loopback redirect, the listener reads the token from the fragment, and [Anilist.completeSignIn]
 * is handed it as the code. Nothing is exchanged: the account is read with the token first, then the
 * score format, the grant, the name and the credentials are stored, in that order. A failure stores
 * nothing. Whether AniList is configured follows the build's client id.
 */
class AnilistSignInTest {

    @TempDir
    lateinit var cacheDir: File

    private val fixture by lazy { AnilistFixture(cacheDir) }
    private val pkce = Pkce("v".repeat(43))
    private val redirect = "http://127.0.0.1:18796/callback"

    @AfterEach
    fun close() {
        fixture.close()
    }

    @Test
    fun `the authorization URL asks AniList for a token with the build's client id, the loopback and the state`() {
        fixture.endpoints = AnilistEndpoints.ANILIST

        val url = fixture.tracker.authorizationUrl(redirect, "the-state", pkce)

        url.scheme shouldBe "https"
        url.host shouldBe "anilist.co"
        url.encodedPath shouldBe "/api/v2/oauth/authorize"
        // No redirect_uri: AniList refuses an implicit grant that names one (unsupported_grant_type)
        // and uses the client's registered address instead.
        url.queryParameterNames shouldBe setOf("client_id", "response_type", "state")
        url.queryParameter("client_id") shouldBe BUILD_CLIENT_ID
        url.queryParameter("response_type") shouldBe "token"
        url.queryParameter("state") shouldBe "the-state"
        // The implicit grant takes no challenge, so the pair is never sent.
        url.toString() shouldNotContain pkce.verifier
        fixture.tracker.loopbackResponse shouldBe LoopbackResponse.TOKEN
        // Building the page calls nothing and stores nothing.
        fixture.server.requestCount shouldBe 0
        fixture.preferences.values.keys.none { "track_token" in it } shouldBe true
    }

    @Test
    fun `on a test base every address moves to it and the fixture's client id replaces the build's`() {
        val url = fixture.tracker.authorizationUrl(redirect, "the-state", pkce)

        url.host shouldBe fixture.baseUrl.host
        url.port shouldBe fixture.server.port
        url.encodedPath shouldBe "/api/v2/oauth/authorize"
        url.queryParameter("client_id") shouldBe AnilistClient.TEST_BASE_CLIENT_ID
        url.toString() shouldNotContain BUILD_CLIENT_ID
        fixture.endpoints.api.encodedPath shouldBe "/"
    }

    @Test
    fun `a blank client id is not configured, a set one is, and either way AniList keeps its id`() {
        AnilistFixture(cacheDir, buildClientId = "").use { blank ->
            blank.endpoints = AnilistEndpoints.ANILIST
            blank.tracker.isConfigured shouldBe false
            blank.tracker.id shouldBe TrackerIds.ANILIST
        }
        AnilistFixture(cacheDir, buildClientId = "  ").use { spaces ->
            spaces.endpoints = AnilistEndpoints.ANILIST
            spaces.tracker.isConfigured shouldBe false
        }
        fixture.endpoints = AnilistEndpoints.ANILIST
        fixture.tracker.isConfigured shouldBe true
    }

    @Test
    fun `a sign-in reads the account with the token, then stores the score format, the grant for a year, the name and the id`() {
        runBlocking {
            fixture.server.enqueue(json(200, viewerBody(scoreFormat = "POINT_100")))
            fixture.trackPreferences.trackAuthExpired(TrackerIds.ANILIST).set(true)

            fixture.tracker.completeSignIn(ACCESS_1, redirect, pkce)

            fixture.server.requestCount shouldBe 1
            val viewer = fixture.server.takeRequest()
            viewer.headers["Authorization"] shouldBe "Bearer $ACCESS_1"
            viewer.graphql().first shouldContain "Viewer"
            fixture.storedToken() shouldBe ALOAuth(ACCESS_1, NOW_MILLIS + ONE_YEAR_MILLIS)
            fixture.trackPreferences.anilistScoreType.get() shouldBe Anilist.POINT_100
            fixture.tracker.getDisplayUsername() shouldBe USER_NAME
            fixture.tracker.getUsername() shouldBe USER_ID.toString()
            fixture.tracker.isLoggedIn shouldBe true
            fixture.tracker.isAuthExpired shouldBe false
        }
    }

    @Test
    fun `the writes land in order, score format, token, display name, credentials`() {
        runBlocking {
            val order = mutableListOf<String>()
            val watched = listOf("anilist_score_type", "track_token_2", "pref_mangasync_displayname_2", "pref_mangasync_username_2")
            val recording = object : LinkedHashMap<String, Any>() {
                override fun put(key: String, value: Any): Any? {
                    watched.firstOrNull { key.endsWith(it) }?.let(order::add)
                    return super.put(key, value)
                }
            }
            AnilistFixture(cacheDir, values = recording).use { recorded ->
                recorded.server.enqueue(json(200, viewerBody()))

                recorded.tracker.completeSignIn(ACCESS_1, redirect, pkce)
            }

            order shouldContainExactly watched
        }
    }

    @Test
    fun `a token AniList refuses stores nothing and leaves an expired sign-in expired`() {
        runBlocking {
            fixture.signedIn(access = ACCESS_1)
            fixture.tracker.setAuthExpired()
            fixture.server.enqueue(json(400, """{"errors":[{"message":"Invalid token","status":400}],"data":null}"""))

            val failed = shouldThrow<HttpException> { fixture.tracker.completeSignIn(ACCESS_2, redirect, pkce) }

            failed.code shouldBe 400
            ErrorKey.of(failed) shouldBe ErrorKey.HTTP
            failed.message.orEmpty() shouldNotContain ACCESS_2
            checkNotNull(fixture.storedToken()).accessToken shouldBe ACCESS_1
            fixture.tracker.isAuthExpired shouldBe true
            fixture.tracker.isLoggedIn shouldBe true
            fixture.trackPreferences.anilistScoreType.get() shouldBe Anilist.POINT_10
        }
    }

    @Test
    fun `an unreachable AniList throws its IOException and stores nothing`() {
        runBlocking {
            repeat(3) { fixture.server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection).build()) }

            shouldThrow<IOException> { fixture.tracker.completeSignIn(ACCESS_1, redirect, pkce) }

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

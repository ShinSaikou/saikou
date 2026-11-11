package com.shinsaikou.tracking.comick

import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.tracking.TrackerAuthExpiredException
import com.shinsaikou.tracking.comick.ComickFixture.Companion.ACCESS_1
import com.shinsaikou.tracking.comick.ComickFixture.Companion.ACCESS_2
import com.shinsaikou.tracking.comick.ComickFixture.Companion.GRANTED
import com.shinsaikou.tracking.comick.ComickFixture.Companion.LIBRARY_PATH
import com.shinsaikou.tracking.comick.ComickFixture.Companion.NOT_FOUND_BODY
import com.shinsaikou.tracking.comick.ComickFixture.Companion.NOW_SECONDS
import com.shinsaikou.tracking.comick.ComickFixture.Companion.ONE_HOUR
import com.shinsaikou.tracking.comick.ComickFixture.Companion.REFRESH_1
import com.shinsaikou.tracking.comick.ComickFixture.Companion.REFRESH_2
import com.shinsaikou.tracking.comick.ComickFixture.Companion.RESOURCE
import com.shinsaikou.tracking.comick.ComickFixture.Companion.TOKEN_PATH
import com.shinsaikou.tracking.comick.ComickFixture.Companion.WRITTEN_BODY
import com.shinsaikou.tracking.comick.ComickFixture.Companion.entryBody
import com.shinsaikou.tracking.comick.ComickFixture.Companion.form
import com.shinsaikou.tracking.comick.ComickFixture.Companion.json
import com.shinsaikou.tracking.comick.ComickFixture.Companion.jsonBody
import com.shinsaikou.tracking.comick.ComickFixture.Companion.tokenBody
import com.shinsaikou.tracking.comick.ComickFixture.Companion.track
import com.shinsaikou.tracking.comick.dto.ComickOAuth
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.util.Collections

/**
 * Comick's token refresh. Its documentation is not readable from here, so refresh tokens are taken
 * to rotate as MangaBaka's do: the new grant is stored at once, the refresh is single-flight, every
 * refresh names the library API as its resource, and a refusal (400, 401, or an answer that cannot
 * be read as a grant) ends in "sign in again" and is never tried again, while a refresh that never
 * reached the endpoint, or a server error, stays retryable with the grant left as it was. An answer
 * that carries no new refresh token keeps the one sent.
 *
 * [Comick.refresh] itself, the call the retry worker makes before every push: an entry in the
 * owner's library comes back as the library has it; an entry the library lacks is added as the row
 * has it.
 */
class ComickRefreshTest {

    @TempDir
    lateinit var cacheDir: File

    private val fixture by lazy { ComickFixture(cacheDir) }

    @AfterEach
    fun close() {
        fixture.close()
    }

    /** One call that needs the token: removing the entry from the library. */
    private suspend fun authorizedCall() = fixture.tracker.delete(track())

    @Test
    fun `an expired token is refreshed with the resource before the call, and the rotated grant is stored and sent`() {
        runBlocking {
            fixture.expiredSignIn()
            fixture.server.enqueue(json(200, tokenBody(ACCESS_2, REFRESH_2)))
            fixture.server.enqueue(json(200, WRITTEN_BODY))

            authorizedCall()

            val refresh = fixture.server.takeRequest()
            refresh.method shouldBe "POST"
            refresh.url.encodedPath shouldBe TOKEN_PATH
            refresh.form() shouldBe mapOf(
                "grant_type" to "refresh_token",
                "client_id" to ComickClient.TEST_BASE_CLIENT_ID,
                "refresh_token" to REFRESH_1,
                "resource" to RESOURCE,
            )
            refresh.headers["Accept"] shouldBe "application/json; charset=utf-8"
            refresh.headers["Authorization"] shouldBe null
            val call = fixture.server.takeRequest()
            call.method shouldBe "DELETE"
            call.url.encodedPath shouldBe LIBRARY_PATH
            call.headers["Authorization"] shouldBe "Bearer $ACCESS_2"

            fixture.storedToken() shouldBe ComickOAuth(ACCESS_2, NOW_SECONDS + ONE_HOUR, REFRESH_2, GRANTED)
            fixture.tracker.isAuthExpired shouldBe false
        }
    }

    @Test
    fun `the next refresh sends the rotated refresh token, never the retired one`() {
        runBlocking {
            fixture.expiredSignIn()
            fixture.server.enqueue(json(200, tokenBody(ACCESS_2, REFRESH_2)))
            fixture.server.enqueue(json(200, WRITTEN_BODY))
            authorizedCall()

            fixture.now = fixture.now.plus(kotlin.time.Duration.parse("2h"))
            fixture.server.enqueue(
                json(200, tokenBody("fixture-access-token-3", "fixture-refresh-token-3", expiresAt = NOW_SECONDS + 3 * ONE_HOUR)),
            )
            fixture.server.enqueue(json(200, WRITTEN_BODY))
            authorizedCall()

            fixture.server.takeRequest()
            fixture.server.takeRequest()
            fixture.server.takeRequest().form()["refresh_token"] shouldBe REFRESH_2
            fixture.server.takeRequest().headers["Authorization"] shouldBe "Bearer fixture-access-token-3"
            checkNotNull(fixture.storedToken()).refreshToken shouldBe "fixture-refresh-token-3"
        }
    }

    @Test
    fun `a refresh answer without a refresh token or scopes keeps the ones it was sent`() {
        runBlocking {
            fixture.expiredSignIn()
            fixture.server.enqueue(json(200, tokenBody(ACCESS_2, refresh = null, scope = null)))
            fixture.server.enqueue(json(200, WRITTEN_BODY))

            authorizedCall()

            fixture.storedToken() shouldBe ComickOAuth(ACCESS_2, NOW_SECONDS + ONE_HOUR, REFRESH_1, GRANTED)
            fixture.tracker.isAuthExpired shouldBe false
        }
    }

    @Test
    fun `a live token is sent as it is, with no refresh`() {
        runBlocking {
            fixture.signedIn()
            fixture.server.enqueue(json(200, WRITTEN_BODY))

            authorizedCall()

            fixture.server.requestCount shouldBe 1
            fixture.server.takeRequest().headers["Authorization"] shouldBe "Bearer $ACCESS_1"
        }
    }

    @Test
    fun `a grant an upstream backup restored reads as it was written`() {
        val restored = mutableMapOf<String, Any>(
            "__PRIVATE_track_token_12" to
                """{"access_token":"fixture-access","expires_at":${NOW_SECONDS + ONE_HOUR},"refresh_token":"fixture-refresh",""" +
                """"scope":"$GRANTED"}""",
            "__PRIVATE_pref_mangasync_username_12" to "user",
            "__PRIVATE_pref_mangasync_password_12" to "fixture-access",
        )
        ComickFixture(cacheDir, values = restored).use { local ->
            runBlocking {
                local.storedToken() shouldBe ComickOAuth("fixture-access", NOW_SECONDS + ONE_HOUR, "fixture-refresh", GRANTED)
                local.tracker.isLoggedIn shouldBe true
                local.server.enqueue(json(200, WRITTEN_BODY))

                local.tracker.delete(track())

                local.server.takeRequest().headers["Authorization"] shouldBe "Bearer fixture-access"
            }
        }
    }

    @Test
    fun `a refresh refused as invalid_grant, or with no readable error, sets auth expired, throws its key, and is never tried again`() {
        val refusals = listOf(
            json(400, """{"error":"invalid_grant","error_description":"The refresh token is invalid"}"""),
            json(401, """{"error":"invalid_grant"}"""),
            json(401, "<html>Unauthorized</html>"),
            json(400, """{"message":"no OAuth error here"}"""),
        )
        for (refusal in refusals) {
            ComickFixture(cacheDir).use { local ->
                runBlocking {
                    local.expiredSignIn()
                    local.server.enqueue(refusal)

                    val expired = shouldThrow<TrackerAuthExpiredException> { local.tracker.delete(track()) }

                    ErrorKey.of(expired) shouldBe ErrorKey.TRACKER_AUTH_EXPIRED
                    local.tracker.isAuthExpired shouldBe true
                    // The refresh alone went out; the call was never sent.
                    local.server.requestCount shouldBe 1
                    local.server.takeRequest().url.encodedPath shouldBe TOKEN_PATH
                    // The credentials stay, so settings shows "Sign in again" rather than signed out.
                    local.tracker.isLoggedIn shouldBe true

                    // Nothing more reaches Comick, the refresh token above all, until a new sign-in.
                    shouldThrow<TrackerAuthExpiredException> { local.tracker.delete(track()) }
                    shouldThrow<TrackerAuthExpiredException> { local.tracker.refresh(track()) }
                    local.server.requestCount shouldBe 1
                }
            }
        }
    }

    @Test
    fun `a refresh refused for the client or the request stays retryable and keeps the sign-in and the grant`() {
        val refusals = listOf(
            json(401, """{"error":"invalid_client","error_description":"Unknown client"}"""),
            json(400, """{"error":"invalid_request"}"""),
            json(400, """{"error":"unauthorized_client"}"""),
        )
        for (refusal in refusals) {
            ComickFixture(cacheDir).use { local ->
                runBlocking {
                    local.expiredSignIn()
                    local.server.enqueue(refusal)

                    val thrown = shouldThrow<IOException> { local.tracker.delete(track()) }

                    thrown.shouldBeInstanceOf<ComickTokenRefreshFailed>()
                    // Signing in again would not fix the client, so settings must not ask for it.
                    local.tracker.isAuthExpired shouldBe false
                    checkNotNull(local.storedToken()).refreshToken shouldBe REFRESH_1
                    local.server.requestCount shouldBe 1
                }
            }
        }
    }

    @Test
    fun `a 2xx refresh answer that is not a grant is treated as refused, since the token sent may be retired`() {
        val unreadable = listOf(
            // No access token.
            """{"refresh_token":"$REFRESH_2","expires_at":${NOW_SECONDS + ONE_HOUR}}""",
            // No expiry of either kind.
            tokenBody(ACCESS_2, REFRESH_2, expiresAt = null),
            "not json",
        )
        for (body in unreadable) {
            ComickFixture(cacheDir).use { local ->
                runBlocking {
                    local.expiredSignIn()
                    local.server.enqueue(json(200, body))

                    shouldThrow<TrackerAuthExpiredException> { local.tracker.delete(track()) }

                    local.tracker.isAuthExpired shouldBe true
                    local.server.requestCount shouldBe 1
                    checkNotNull(local.storedToken()).refreshToken shouldBe REFRESH_1
                }
            }
        }
    }

    @Test
    fun `a refresh that never reaches the endpoint, or a server error, stays retryable and leaves the grant alone`() {
        val failures = listOf(
            MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection).build(),
            json(503, """{"error":"temporarily_unavailable"}"""),
        )
        for (failure in failures) {
            ComickFixture(cacheDir).use { local ->
                runBlocking {
                    local.expiredSignIn()
                    // Three, since OkHttp may retry a dropped connection once on its own.
                    repeat(3) { local.server.enqueue(failure) }

                    val thrown = shouldThrow<IOException> { local.tracker.delete(track()) }

                    thrown.shouldBeInstanceOf<ComickTokenRefreshFailed>()
                    local.tracker.isAuthExpired shouldBe false
                    checkNotNull(local.storedToken()).refreshToken shouldBe REFRESH_1
                }
            }
        }
    }

    @Test
    fun `calls on an expired token from several threads refresh it once, and all send the new token`() {
        runBlocking {
            fixture.expiredSignIn()
            val refreshTokensSent = Collections.synchronizedList(mutableListOf<String?>())
            val bearers = Collections.synchronizedList(mutableListOf<String?>())
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.url.encodedPath == TOKEN_PATH) {
                        refreshTokensSent += request.form()["refresh_token"]
                        // Long enough for the other calls to reach the lock while this one holds it.
                        Thread.sleep(300)
                        // A second refresh with the same token is what a rotating server refuses.
                        if (refreshTokensSent.size == 1) {
                            json(200, tokenBody(ACCESS_2, REFRESH_2))
                        } else {
                            json(400, """{"error":"invalid_grant"}""")
                        }
                    } else {
                        bearers += request.headers["Authorization"]
                        json(200, WRITTEN_BODY)
                    }
            }

            (1..4).map { async(Dispatchers.IO) { authorizedCall() } }.awaitAll()

            refreshTokensSent shouldContainExactly listOf(REFRESH_1)
            bearers shouldContainExactly List(4) { "Bearer $ACCESS_2" }
            fixture.tracker.isAuthExpired shouldBe false
        }
    }

    @Test
    fun `a live token the API refuses with 401 is refreshed once and the call sent again`() {
        runBlocking {
            fixture.signedIn()
            fixture.server.enqueue(json(401, """{"statusCode":401,"message":"Unauthorized"}"""))
            fixture.server.enqueue(json(200, tokenBody(ACCESS_2, REFRESH_2)))
            fixture.server.enqueue(json(200, WRITTEN_BODY))

            authorizedCall()

            fixture.server.takeRequest().headers["Authorization"] shouldBe "Bearer $ACCESS_1"
            fixture.server.takeRequest().form()["refresh_token"] shouldBe REFRESH_1
            fixture.server.takeRequest().headers["Authorization"] shouldBe "Bearer $ACCESS_2"
            checkNotNull(fixture.storedToken()).accessToken shouldBe ACCESS_2
        }
    }

    @Test
    fun `a signed-out tracker throws an IOException and sends nothing`() {
        runBlocking {
            shouldThrow<IOException> { authorizedCall() }

            fixture.server.requestCount shouldBe 0
        }
    }

    @Test
    fun `refresh lays the library entry's status, progress, rating and title over the row and writes nothing`() {
        runBlocking {
            fixture.signedIn()
            fixture.server.enqueue(json(200, entryBody(status = 3, progress = "\"12.5\"", rating = "8")))

            val refreshed = fixture.tracker.refresh(track(lastChapterRead = 3.0, status = Comick.READING))

            refreshed shouldBe track().copy(
                title = "Fixture Title One",
                status = Comick.ON_HOLD,
                lastChapterRead = 12.5,
                score = 8.0,
            )
            val entry = fixture.server.takeRequest()
            entry.method shouldBe "GET"
            entry.url.encodedPath shouldBe LIBRARY_PATH
            entry.headers["Authorization"] shouldBe "Bearer $ACCESS_1"
            fixture.server.requestCount shouldBe 1
        }
    }

    @Test
    fun `refresh reads an entry with no progress or rating as none`() {
        runBlocking {
            fixture.signedIn()
            fixture.server.enqueue(json(200, entryBody(status = 5, progress = "null", rating = "null")))

            val refreshed = fixture.tracker.refresh(track(lastChapterRead = 3.0, score = 6.0))

            refreshed.status shouldBe Comick.PLAN_TO_READ
            refreshed.lastChapterRead shouldBe 0.0
            refreshed.score shouldBe 0.0
        }
    }

    @Test
    fun `refresh adds an entry the library lacks, as the row has it`() {
        runBlocking {
            fixture.signedIn()
            fixture.server.enqueue(json(404, NOT_FOUND_BODY))
            fixture.server.enqueue(json(200, WRITTEN_BODY))
            val row = track(lastChapterRead = 4.0, status = Comick.READING, score = 6.0)

            fixture.tracker.refresh(row) shouldBe row

            fixture.server.takeRequest().method shouldBe "GET"
            val added = fixture.server.takeRequest()
            added.method shouldBe "PUT"
            added.url.encodedPath shouldBe LIBRARY_PATH
            val body = added.jsonBody()
            body["status"] shouldBe JsonPrimitive(1)
            body["progress"]!!.jsonObject["number"] shouldBe JsonPrimitive("4")
        }
    }
}

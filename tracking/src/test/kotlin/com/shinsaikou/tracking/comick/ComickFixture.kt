package com.shinsaikou.tracking.comick

import android.content.Context
import com.shinsaikou.core.common.network.CloudflareSolveCoordinator
import com.shinsaikou.core.common.network.NetworkUserMessages
import com.shinsaikou.domain.preferences.TrackPreferences
import com.shinsaikou.tracking.FakeTrackRepository
import com.shinsaikou.tracking.MANGA_ID
import com.shinsaikou.tracking.MapPreferenceStore
import com.shinsaikou.tracking.TrackerHttpClient
import com.shinsaikou.tracking.TrackerIds
import com.shinsaikou.tracking.comick.dto.ComickOAuth
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.track.model.Track
import java.io.File
import java.net.InetAddress
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Comick as the graph builds it, against a MockWebServer bound to the IPv4 loopback on a port the
 * system picks, addressed as `127.0.0.1` ([baseUrl]), as `MangaBakaFixture` does. That is a test
 * base: every call, the token endpoint's included, goes to it with the fixture's client id. The HTTP
 * client is the real [TrackerHttpClient] over a real [NetworkHelper] with verbose logging on. The
 * build's client id is a sentinel that must never leave.
 *
 * The clock is the test's, so a stored token is expired or live by what [now] says. Every HID here is
 * invented.
 */
class ComickFixture(
    cacheDir: File,
    buildClientId: String = BUILD_CLIENT_ID,
    values: MutableMap<String, Any> = mutableMapOf(),
    val repository: FakeTrackRepository = FakeTrackRepository(),
) : AutoCloseable {

    val server = MockWebServer().apply { start(InetAddress.getByName(LOOPBACK), 0) }
    val baseUrl: HttpUrl = "http://$LOOPBACK:${server.port}/".toHttpUrl()
    val preferences = MapPreferenceStore(values)
    val trackPreferences = TrackPreferences(preferences)
    var now: Instant = Instant.fromEpochSeconds(NOW_SECONDS)
    var endpoints: ComickEndpoints = ComickEndpoints.at(baseUrl)

    val networkPreferences = NetworkPreferences(InMemoryPreferenceStore(), verboseLoggingDefault = true)
    private val networkHelper = NetworkHelper(
        mockk<Context>(relaxed = true).also { every { it.cacheDir } returns cacheDir },
        networkPreferences,
        CloudflareSolveCoordinator(),
        mockk<NetworkUserMessages>(relaxed = true),
    )
    val httpClient = TrackerHttpClient(networkHelper, networkPreferences)

    val tracker = Comick(
        trackPreferences = trackPreferences,
        trackRepository = repository,
        httpClient = httpClient,
        clock = object : Clock {
            override fun now(): Instant = this@ComickFixture.now
        },
        endpointsProvider = { endpoints },
        buildClient = ComickClient(buildClientId),
        json = JSON,
    )

    /** A stored sign-in: the grant as the tracker stores it, and the credentials that make it signed in. */
    fun signedIn(
        access: String = ACCESS_1,
        refresh: String = REFRESH_1,
        expiresAt: Long = NOW_SECONDS + ONE_HOUR,
    ) {
        trackPreferences.trackToken(TrackerIds.COMICK).set(JSON.encodeToString(ComickOAuth(access, expiresAt, refresh, GRANTED)))
        trackPreferences.trackDisplayUsername(TrackerIds.COMICK).set(USER_NAME)
        trackPreferences.setCredentials(TrackerIds.COMICK, USER_ID, access)
    }

    /** A stored sign-in whose access token expired an hour ago, by the clock. */
    fun expiredSignIn() = signedIn(expiresAt = NOW_SECONDS - ONE_HOUR)

    /** The grant stored now, or null. */
    fun storedToken(): ComickOAuth? = tracker.loadOAuth()

    override fun close() {
        server.close()
    }

    companion object {
        const val BUILD_CLIENT_ID = "build-client-id-sentinel"
        const val LOOPBACK = "127.0.0.1"
        const val NOW_SECONDS = 1_790_000_000L
        const val ONE_HOUR = 3_600L
        const val ACCESS_1 = "fixture-access-token-1"
        const val REFRESH_1 = "fixture-refresh-token-1"
        const val ACCESS_2 = "fixture-access-token-2"
        const val REFRESH_2 = "fixture-refresh-token-2"
        const val USER_ID = "fixture-user-0001"
        const val USER_NAME = "fixture-reader"

        /** Every scope Saikou asks for, as Comick's token endpoint lists them. */
        const val GRANTED = "library:read library:write offline_access"

        /** The resource every authorize, token and refresh call must name. */
        const val RESOURCE = "https://api.comick.dev/integrations/v1"

        const val TOKEN_PATH = "/api/auth/oauth2/token"

        /** An invented HID and the remote id it decodes to. */
        const val HID = "xK3pQ9aZ"
        const val REMOTE_ID = 216_251_221_923_481L
        const val LIBRARY_PATH = "/integrations/v1/me/library/$HID"

        /** The app's Json (`PlatformBindings.provideJson`). */
        val JSON = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        fun json(code: Int, body: String): MockResponse =
            MockResponse.Builder().code(code).setHeader("Content-Type", "application/json; charset=utf-8").body(body).build()

        /**
         * A token response shaped like Comick's, with `expires_at` as upstream reads it and the fields
         * the app ignores. [refresh] null leaves the refresh token out, as a grant without
         * `offline_access` does; [expiresIn] adds the standard field.
         */
        fun tokenBody(
            access: String,
            refresh: String?,
            scope: String? = GRANTED,
            expiresAt: Long? = NOW_SECONDS + ONE_HOUR,
            expiresIn: Long? = null,
        ): String = buildList {
            add(""""access_token":"$access"""")
            add(""""token_type":"Bearer"""")
            refresh?.let { add(""""refresh_token":"$it"""") }
            expiresAt?.let { add(""""expires_at":$it""") }
            expiresIn?.let { add(""""expires_in":$it""") }
            scope?.let { add(""""scope":"$it"""") }
        }.joinToString(",", "{", "}")

        /** `GET /me`. */
        fun userBody(username: String? = USER_NAME): String =
            """{"id":"$USER_ID","username":${username.quoted()},"avatar_url":null}"""

        /** One title as search and `GET /titles/{hid}` answer it, with fields the app ignores. */
        fun title(
            hid: String = HID,
            title: String = "Fixture Title One",
            status: Int? = 1,
            country: String? = "jp",
            rating: Double? = 8.42,
        ): String =
            """{"hid":"$hid","slug":"fixture-title-one","title":"$title","year":2020,"description":"  A fixture story.  ",""" +
                """"status":$status,"bayesian_rating":$rating,"cover_url":"https://img.example.invalid/$hid.jpg",""" +
                """"country":${country.quoted()},"url":"https://comick.dev/comic/fixture-title-one","follow_count":12}"""

        fun titleBody(title: String = title()): String = """{"data":$title}"""

        fun searchBody(vararg titles: String): String = """{"data":[${titles.joinToString(",")}]}"""

        /** The owner's library entry as `GET /me/library/{hid}` answers it. */
        fun entryBody(status: Int = 1, progress: String = "\"7\"", rating: String = "7", title: String = "Fixture Title One"): String =
            """{"data":{"hid":"$HID","title":"$title","status":$status,"progress":{"number":$progress},"rating":$rating,""" +
                """"updated_at":"2026-01-02T00:00:00.000Z"}}"""

        const val WRITTEN_BODY = """{"data":{"hid":"$HID"}}"""
        const val NOT_FOUND_BODY = """{"statusCode":404,"message":"Not Found"}"""

        private fun String?.quoted(): String = this?.let { "\"$it\"" } ?: "null"

        /** A form request's fields. */
        fun RecordedRequest.form(): Map<String, String> {
            headers["Content-Type"] shouldBe "application/x-www-form-urlencoded"
            val text = body?.utf8().orEmpty()
            return "http://form.invalid/?$text".toHttpUrl().let { url -> url.queryParameterNames.associateWith { url.queryParameter(it)!! } }
        }

        /** A JSON request's body. */
        fun RecordedRequest.jsonBody(): JsonObject {
            headers["Content-Type"].orEmpty().startsWith("application/json") shouldBe true
            return JSON.parseToJsonElement(body?.utf8().orEmpty()).jsonObject
        }

        fun track(
            id: Long = 10L,
            remoteId: Long = REMOTE_ID,
            lastChapterRead: Double = 0.0,
            totalChapters: Long = 0L,
            status: Long = Comick.READING,
            score: Double = 0.0,
        ) = Track(
            id = id,
            mangaId = MANGA_ID,
            trackerId = TrackerIds.COMICK,
            remoteId = remoteId,
            libraryId = null,
            title = "Fixture Title",
            lastChapterRead = lastChapterRead,
            totalChapters = totalChapters,
            status = status,
            score = score,
            remoteUrl = "https://comick.dev/comic/fixture-title-one",
            startDate = 0L,
            finishDate = 0L,
            private = false,
        )
    }
}

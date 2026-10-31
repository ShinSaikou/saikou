package com.shinsaikou.tracking.myanimelist

import android.content.Context
import com.shinsaikou.core.common.network.CloudflareSolveCoordinator
import com.shinsaikou.core.common.network.NetworkUserMessages
import com.shinsaikou.domain.preferences.TrackPreferences
import com.shinsaikou.tracking.FakeTrackRepository
import com.shinsaikou.tracking.MapPreferenceStore
import com.shinsaikou.tracking.TrackerHttpClient
import com.shinsaikou.tracking.TrackerIds
import com.shinsaikou.tracking.myanimelist.dto.MALOAuth
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import java.io.File
import java.net.InetAddress
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * MyAnimeList as the graph builds it, against a MockWebServer on a port the system picks (never the
 * tracker port) and addressed as `127.0.0.1` ([baseUrl]), which is a test base: every call, the
 * token endpoint's included, goes to it with the fake's client id. The HTTP client is the real
 * [TrackerHttpClient] derived from a real [NetworkHelper] with verbose logging on, so the shared user
 * agent and the tracker client's own logger run as they do in the app. The build's client id is a
 * sentinel that must never leave.
 *
 * The clock is the test's, so a stored token is expired or fresh by what [now] says. The tracker
 * stores its answers through [trackRepository], [repository] unless a test wraps it to record the
 * calls (phase 4 unit TC).
 */
class MyAnimeListFixture(
    cacheDir: File,
    buildClientId: String = BUILD_CLIENT_ID,
    values: MutableMap<String, Any> = mutableMapOf(),
    val repository: FakeTrackRepository = FakeTrackRepository(),
    trackRepository: TrackRepository = repository,
) : AutoCloseable {

    val server = MockWebServer().apply { start(InetAddress.getByName(LOOPBACK), 0) }

    /**
     * The server as the tracker is pointed at it: the IPv4 loopback it is bound to, written as the
     * address and never as `localhost` (which `server.url` writes). This host resolves `localhost` to
     * `::1` first and OkHttp tries IPv6 first, so a process listening on `[::1]` at the port the
     * kernel gave the server (an emulator, a local dev server) took the call: an answer from
     * another server, or none within the 30 s read timeout (phase 5 unit TG1).
     */
    val baseUrl: HttpUrl = "http://$LOOPBACK:${server.port}/".toHttpUrl()
    val preferences = MapPreferenceStore(values)
    val trackPreferences = TrackPreferences(preferences)
    var now: Instant = Instant.fromEpochSeconds(NOW_SECONDS)
    var endpoints: MyAnimeListEndpoints = MyAnimeListEndpoints.at(baseUrl)

    val networkPreferences = NetworkPreferences(InMemoryPreferenceStore(), verboseLoggingDefault = true)
    private val networkHelper = NetworkHelper(
        mockk<Context>(relaxed = true).also { every { it.cacheDir } returns cacheDir },
        networkPreferences,
        CloudflareSolveCoordinator(),
        mockk<NetworkUserMessages>(relaxed = true),
    )
    val httpClient = TrackerHttpClient(networkHelper, networkPreferences)

    val tracker = MyAnimeList(
        trackPreferences = trackPreferences,
        trackRepository = trackRepository,
        httpClient = httpClient,
        clock = object : Clock {
            override fun now(): Instant = this@MyAnimeListFixture.now
        },
        endpointsProvider = { endpoints },
        buildClient = MyAnimeListClient(buildClientId),
        json = JSON,
    )

    /** A stored sign-in: the grant as the tracker stores it, and the credentials that make it signed in. */
    fun signedIn(
        access: String = ACCESS_1,
        refresh: String = REFRESH_1,
        createdAt: Long = NOW_SECONDS,
        expiresIn: Long = ONE_MONTH,
    ) {
        trackPreferences.trackToken(TrackerIds.MYANIMELIST)
            .set(JSON.encodeToString(MALOAuth("Bearer", refresh, access, expiresIn, createdAt)))
        trackPreferences.trackDisplayUsername(TrackerIds.MYANIMELIST).set(USER_NAME)
        trackPreferences.setCredentials(TrackerIds.MYANIMELIST, USER_NAME, access)
    }

    /** The grant stored now, or null. */
    fun storedToken(): MALOAuth? = tracker.loadOAuth()

    override fun close() {
        server.close()
    }

    companion object {
        const val BUILD_CLIENT_ID = "build-client-id-sentinel"
        const val LOOPBACK = "127.0.0.1"
        const val NOW_SECONDS = 1_790_000_000L
        const val ONE_MONTH = 2_678_400L
        const val ACCESS_1 = "fixture-access-token-1"
        const val REFRESH_1 = "fixture-refresh-token-1"
        const val ACCESS_2 = "fixture-access-token-2"
        const val REFRESH_2 = "fixture-refresh-token-2"
        const val USER_NAME = "fixture-reader"

        /** The app's Json (`PlatformBindings.provideJson`). */
        val JSON = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        fun json(code: Int, body: String): MockResponse =
            MockResponse.Builder().code(code).setHeader("Content-Type", "application/json; charset=utf-8").body(body).build()

        /** A token response shaped like MyAnimeList's. */
        fun tokenBody(access: String, refresh: String, expiresIn: Long = ONE_MONTH): String =
            """{"token_type":"Bearer","expires_in":$expiresIn,"access_token":"$access","refresh_token":"$refresh"}"""

        /** `GET users/@me`, with fields the app ignores. */
        const val USER_BODY = """{"id":4242,"name":"$USER_NAME","location":"","joined_at":"2020-01-01T00:00:00+00:00"}"""

        /** A list status as `PUT manga/{id}/my_list_status` answers it, and as an entry's `my_list_status`. */
        fun statusBody(
            status: String,
            chapters: Int,
            score: Int = 0,
            rereading: Boolean = false,
            start: String? = null,
            finish: String? = null,
        ): String = buildString {
            append("""{"status":"$status","score":$score,"num_volumes_read":0,"num_chapters_read":$chapters,"is_rereading":$rereading,""")
            start?.let { append(""""start_date":"$it",""") }
            finish?.let { append(""""finish_date":"$it",""") }
            append(""""updated_at":"2026-09-26T10:00:00+00:00","priority":0,"num_times_reread":0,"reread_value":0,"tags":[],"comments":""}""")
        }

        /** A form request's fields. */
        fun RecordedRequest.form(): Map<String, String> {
            headers["Content-Type"] shouldBe "application/x-www-form-urlencoded"
            val text = body?.utf8().orEmpty()
            return "http://form.invalid/?$text".toHttpUrl().let { url -> url.queryParameterNames.associateWith { url.queryParameter(it)!! } }
        }

        fun track(
            id: Long = 10L,
            remoteId: Long = 1001L,
            lastChapterRead: Double = 0.0,
            totalChapters: Long = 0L,
            status: Long = MyAnimeList.READING,
            score: Double = 0.0,
            startDate: Long = 0L,
            finishDate: Long = 0L,
        ) = Track(
            id = id,
            mangaId = 42L,
            trackerId = TrackerIds.MYANIMELIST,
            remoteId = remoteId,
            libraryId = null,
            title = "Fixture Title",
            lastChapterRead = lastChapterRead,
            totalChapters = totalChapters,
            status = status,
            score = score,
            remoteUrl = "https://myanimelist.net/manga/$remoteId",
            startDate = startDate,
            finishDate = finishDate,
            private = false,
        )
    }
}

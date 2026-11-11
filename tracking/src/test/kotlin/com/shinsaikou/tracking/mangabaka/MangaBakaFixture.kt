package com.shinsaikou.tracking.mangabaka

import android.content.Context
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.core.common.network.CloudflareSolveCoordinator
import com.shinsaikou.core.common.network.NetworkUserMessages
import com.shinsaikou.domain.preferences.TrackPreferences
import com.shinsaikou.tracking.FakeTrackRepository
import com.shinsaikou.tracking.MANGA_ID
import com.shinsaikou.tracking.MapPreferenceStore
import com.shinsaikou.tracking.TrackerHttpClient
import com.shinsaikou.tracking.TrackerIds
import com.shinsaikou.tracking.mangabaka.dto.MangaBakaOAuth
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
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
import java.util.Collections
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers as KotlinDispatchers

/**
 * MangaBaka as the graph builds it, against a MockWebServer bound to the IPv4 loopback on a port the
 * system picks, addressed as `127.0.0.1` ([baseUrl]), as `AnilistFixture` does. That is a test base:
 * every call, the token and revocation endpoints' included, goes to it with the fixture's client id.
 * The HTTP client is the real [TrackerHttpClient] over a real [NetworkHelper] with verbose logging
 * on. The build's client id is a sentinel that must never leave.
 *
 * The clock is the test's, so a stored token is expired or live by what [now] says. The app scope a
 * sign-out revokes on runs on the real IO dispatcher, and anything it fails to catch lands in
 * [uncaught].
 */
class MangaBakaFixture(
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
    var endpoints: MangaBakaEndpoints = MangaBakaEndpoints.at(baseUrl)

    val uncaught: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
    private val appScope = CoroutineScope(
        SupervisorJob() + CoroutineExceptionHandler { _, e -> uncaught += e },
    )

    val networkPreferences = NetworkPreferences(InMemoryPreferenceStore(), verboseLoggingDefault = true)
    private val networkHelper = NetworkHelper(
        mockk<Context>(relaxed = true).also { every { it.cacheDir } returns cacheDir },
        networkPreferences,
        CloudflareSolveCoordinator(),
        mockk<NetworkUserMessages>(relaxed = true),
    )
    val httpClient = TrackerHttpClient(networkHelper, networkPreferences)

    val tracker = MangaBaka(
        trackPreferences = trackPreferences,
        trackRepository = repository,
        httpClient = httpClient,
        clock = object : Clock {
            override fun now(): Instant = this@MangaBakaFixture.now
        },
        endpointsProvider = { endpoints },
        buildClient = MangaBakaClient(buildClientId),
        json = JSON,
        appScope = appScope,
        dispatchers = Dispatchers(KotlinDispatchers.IO, KotlinDispatchers.Default, KotlinDispatchers.Unconfined),
    )

    /** A stored sign-in: the grant as the tracker stores it, and the credentials that make it signed in. */
    fun signedIn(
        access: String = ACCESS_1,
        refresh: String = REFRESH_1,
        expiresAt: Long = NOW_SECONDS + ONE_WEEK,
    ) {
        trackPreferences.trackToken(TrackerIds.MANGABAKA).set(JSON.encodeToString(MangaBakaOAuth(access, refresh, expiresAt)))
        trackPreferences.trackDisplayUsername(TrackerIds.MANGABAKA).set(USER_NAME)
        trackPreferences.setCredentials(TrackerIds.MANGABAKA, USER_ID, access)
    }

    /** A stored sign-in whose access token expired an hour ago, by the clock. */
    fun expiredSignIn() = signedIn(expiresAt = NOW_SECONDS - 3600)

    /** The grant stored now, or null. */
    fun storedToken(): MangaBakaOAuth? = tracker.loadOAuth()

    override fun close() {
        appScope.cancel()
        server.close()
    }

    companion object {
        const val BUILD_CLIENT_ID = "build-client-id-sentinel"
        const val LOOPBACK = "127.0.0.1"
        const val NOW_SECONDS = 1_790_000_000L
        const val ONE_WEEK = 604_800L
        const val ACCESS_1 = "fixture-access-token-1"
        const val REFRESH_1 = "fixture-refresh-token-1"
        const val ACCESS_2 = "fixture-access-token-2"
        const val REFRESH_2 = "fixture-refresh-token-2"
        const val USER_ID = "fixture-user-0001"
        const val USER_NAME = "fixture-reader"

        const val TOKEN_PATH = "/auth/oauth2/token"
        const val REVOKE_PATH = "/auth/oauth2/revoke"

        /** The app's Json (`PlatformBindings.provideJson`). */
        val JSON = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        fun json(code: Int, body: String): MockResponse =
            MockResponse.Builder().code(code).setHeader("Content-Type", "application/json; charset=utf-8").body(body).build()

        /** A token response shaped like MangaBaka's, with the fields the app ignores. */
        fun tokenBody(access: String, refresh: String, expiresIn: Long = ONE_WEEK): String =
            """{"access_token":"$access","token_type":"Bearer","expires_in":$expiresIn,"expires_at":${NOW_SECONDS + expiresIn},""" +
                """"scope":"openid profile library.read library.write offline_access","refresh_token":"$refresh","id_token":"fixture.id.token"}"""

        /** `GET /v1/my/profile`, with fields the app ignores. */
        fun profileBody(ratingSteps: Int? = 10, nickname: String? = USER_NAME, preferredUsername: String? = "fixture_handle"): String =
            """{"status":200,"data":{"id":"$USER_ID","nickname":${nickname.quoted()},"preferred_username":${preferredUsername.quoted()},""" +
                """"picture":null,"updated_at":null,"role":"user","auth_type":"oauth","scopes":["openid"],"rating_steps":$ratingSteps}}"""

        /** One series as search and `GET /v1/series/{id}` answer it, with fields the app ignores. */
        fun series(
            id: Long = 1001L,
            titles: String = """[{"language":"ja","traits":["native"],"title":"Fixture Native","is_primary":true},""" +
                """{"language":"en","traits":["official"],"title":"Fixture Title One","is_primary":true}]""",
            totalChapters: String? = "40",
            rating: Double? = 81.2345,
            type: String = "manga",
            canonicalUrl: String? = "https://mangabaka.org/manga/$id/Fixture-Title-One",
        ): String =
            """{"id":$id,"state":"active","merged_with":null,"canonical_url":${canonicalUrl.quoted()},"title":"Fixture Legacy Title",""" +
                """"cover":{"raw":{"url":null},"x250":{"x1":"https://img.example.invalid/$id.jpg","x2":null,"x3":null}},""" +
                """"authors":["Fixture Author"],"artists":["Fixture Artist"],"description":"  A fixture story.  ",""" +
                """"published":{"start_date":"2020-04-15","end_date":null},"status":"releasing","type":"$type",""" +
                """"rating":$rating,"total_chapters":${totalChapters.quoted()},"titles":$titles}"""

        fun seriesBody(series: String = series()): String = """{"status":200,"data":$series}"""

        fun searchBody(vararg series: String): String =
            """{"status":200,"pagination":{"count":${series.size},"page":1,"limit":50,"next":null,"previous":null},""" +
                """"data":[${series.joinToString(",")}]}"""

        /** The owner's library entry as `GET /v1/my/library/{series_id}` answers it. */
        fun entryBody(
            state: String = "reading",
            progress: String = "7",
            rating: String = "70",
            isPrivate: Boolean = false,
            startDate: String = "\"2026-01-02T00:00:00.000Z\"",
            finishDate: String = "null",
        ): String =
            """{"status":200,"data":{"id":9001,"series_id":1001,"user_id":"$USER_ID","note":null,"read_link":null,""" +
                """"rating":$rating,"state":"$state","priority":20,"is_private":$isPrivate,"number_of_rereads":null,""" +
                """"progress_chapter":$progress,"progress_volume":null,"start_date":$startDate,"finish_date":$finishDate}}"""

        const val WRITTEN_BODY = """{"status":200,"data":true}"""
        const val CREATED_BODY = """{"status":201,"data":true}"""
        const val NOT_FOUND_BODY = """{"status":404,"message":"NOT_FOUND"}"""

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

        /** The stored form of a day: its midnight on the device. */
        fun day(year: Int, month: Int, day: Int): Long =
            LocalDate(year, month, day).atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds()

        fun track(
            id: Long = 10L,
            remoteId: Long = 1001L,
            lastChapterRead: Double = 0.0,
            totalChapters: Long = 0L,
            status: Long = MangaBaka.READING,
            score: Double = 0.0,
            startDate: Long = 0L,
            finishDate: Long = 0L,
            private: Boolean = false,
        ) = Track(
            id = id,
            mangaId = MANGA_ID,
            trackerId = TrackerIds.MANGABAKA,
            remoteId = remoteId,
            libraryId = null,
            title = "Fixture Title",
            lastChapterRead = lastChapterRead,
            totalChapters = totalChapters,
            status = status,
            score = score,
            remoteUrl = "https://mangabaka.org/manga/$remoteId",
            startDate = startDate,
            finishDate = finishDate,
            private = private,
        )
    }
}

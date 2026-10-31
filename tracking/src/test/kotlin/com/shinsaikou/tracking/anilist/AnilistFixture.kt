package com.shinsaikou.tracking.anilist

import android.content.Context
import com.shinsaikou.core.common.network.CloudflareSolveCoordinator
import com.shinsaikou.core.common.network.NetworkUserMessages
import com.shinsaikou.domain.preferences.TrackPreferences
import com.shinsaikou.tracking.FakeTrackRepository
import com.shinsaikou.tracking.MANGA_ID
import com.shinsaikou.tracking.MapPreferenceStore
import com.shinsaikou.tracking.TrackerHttpClient
import com.shinsaikou.tracking.TrackerIds
import com.shinsaikou.tracking.anilist.dto.ALOAuth
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * AniList as the graph builds it, against a MockWebServer bound to the IPv4 loopback on a port the
 * system picks, addressed as `127.0.0.1` ([baseUrl]), as `MyAnimeListFixture` does and for its
 * reason. That is a test base: every call goes to it, with the fixture's client id. The HTTP client is
 * the real [TrackerHttpClient] over a real [NetworkHelper] with verbose logging on. The build's
 * client id is a sentinel that must never leave.
 *
 * Every AniList call is a GraphQL POST to `/`; [graphql] reads one back as its query and variables.
 * The clock is the test's, so a stored token is expired or live by what [now] says.
 */
class AnilistFixture(
    cacheDir: File,
    buildClientId: String = BUILD_CLIENT_ID,
    values: MutableMap<String, Any> = mutableMapOf(),
    val repository: FakeTrackRepository = FakeTrackRepository(),
) : AutoCloseable {

    val server = MockWebServer().apply { start(InetAddress.getByName(LOOPBACK), 0) }
    val baseUrl: HttpUrl = "http://$LOOPBACK:${server.port}/".toHttpUrl()
    val preferences = MapPreferenceStore(values)
    val trackPreferences = TrackPreferences(preferences)
    var now: Instant = Instant.fromEpochMilliseconds(NOW_MILLIS)
    var endpoints: AnilistEndpoints = AnilistEndpoints.at(baseUrl)

    val networkPreferences = NetworkPreferences(InMemoryPreferenceStore(), verboseLoggingDefault = true)
    private val networkHelper = NetworkHelper(
        mockk<Context>(relaxed = true).also { every { it.cacheDir } returns cacheDir },
        networkPreferences,
        CloudflareSolveCoordinator(),
        mockk<NetworkUserMessages>(relaxed = true),
    )
    val httpClient = TrackerHttpClient(networkHelper, networkPreferences)

    val tracker = Anilist(
        trackPreferences = trackPreferences,
        trackRepository = repository,
        httpClient = httpClient,
        clock = object : Clock {
            override fun now(): Instant = this@AnilistFixture.now
        },
        endpointsProvider = { endpoints },
        buildClient = AnilistClient(buildClientId),
        json = JSON,
    )

    /** A stored sign-in: the grant as the tracker stores it, and the credentials upstream stores. */
    fun signedIn(access: String = ACCESS_1, expires: Long = NOW_MILLIS + ONE_YEAR_MILLIS) {
        trackPreferences.trackToken(TrackerIds.ANILIST).set(JSON.encodeToString(ALOAuth(access, expires)))
        trackPreferences.trackDisplayUsername(TrackerIds.ANILIST).set(USER_NAME)
        trackPreferences.setCredentials(TrackerIds.ANILIST, USER_ID.toString(), access)
    }

    /** The grant stored now, or null. */
    fun storedToken(): ALOAuth? = tracker.loadOAuth()

    override fun close() {
        server.close()
    }

    companion object {
        const val BUILD_CLIENT_ID = "build-client-id-sentinel"
        const val LOOPBACK = "127.0.0.1"
        const val NOW_MILLIS = 1_790_000_000_000L
        const val ONE_YEAR_MILLIS = 365L * 24 * 60 * 60 * 1000
        const val ACCESS_1 = "fixture-access-token-1"
        const val ACCESS_2 = "fixture-access-token-2"
        const val USER_ID = 4242
        const val USER_NAME = "fixture-reader"

        /** The app's Json (`PlatformBindings.provideJson`). */
        val JSON = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        fun json(code: Int, body: String): MockResponse =
            MockResponse.Builder().code(code).setHeader("Content-Type", "application/json; charset=utf-8").body(body).build()

        /** A GraphQL request's query and variables (an empty object when it sent none). */
        fun RecordedRequest.graphql(): Pair<String, JsonObject> {
            method shouldBe "POST"
            url.encodedPath shouldBe "/"
            val body = JSON.parseToJsonElement(this.body?.utf8().orEmpty()).jsonObject
            return body.getValue("query").jsonPrimitive.content to (body["variables"]?.jsonObject ?: JsonObject(emptyMap()))
        }

        /** A fuzzy date's JSON, or AniList's all-null date. */
        fun date(year: Int?, month: Int?, day: Int?): String = """{"year":$year,"month":$month,"day":$day}"""

        /** The stored form of a day: its midnight on the device. */
        fun day(year: Int, month: Int, day: Int): Long =
            LocalDate(year, month, day).atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds()

        /** One `media` object as search and the list read answer it, with the fields AniList sends. */
        fun media(
            id: Long = 1001L,
            title: String = "Fixture Title One",
            format: String = "MANGA",
            countryOfOrigin: String? = "JP",
            status: String? = "RELEASING",
            chapters: Long? = 40L,
            description: String? = "A fixture story.",
            averageScore: Int? = 81,
            startDate: String = date(2020, 4, 15),
        ): String = buildString {
            append("""{"id":$id,"staff":{"edges":[""")
            append("""{"role":"Story & Art","id":1,"node":{"name":{"full":"Fixture Author","userPreferred":"Fixture Author","native":null}}},""")
            append("""{"role":"Art","id":2,"node":{"name":{"full":"Fixture Artist","userPreferred":null,"native":null}}},""")
            append("""{"role":"Translator (English)","id":3,"node":{"name":{"full":null,"userPreferred":null,"native":null}}}""")
            append("""]},"title":{"userPreferred":"$title"},"coverImage":{"large":"https://img.example.invalid/$id.jpg"},""")
            append(""""format":"$format",""")
            countryOfOrigin?.let { append(""""countryOfOrigin":"$it",""") }
            append(""""status":${status?.let { "\"$it\"" }},"chapters":$chapters,""")
            append(""""description":${description?.let { JSON.encodeToString(it) }},"startDate":$startDate,"averageScore":$averageScore}""")
        }

        fun searchBody(vararg media: String): String = """{"data":{"Page":{"media":[${media.joinToString(",")}]}}}"""

        /** The owner's list entry as the list read answers it. */
        fun listEntry(
            libraryId: Long = 5001L,
            status: String = "CURRENT",
            scoreRaw: Int = 70,
            progress: Int = 7,
            private: Boolean = false,
            startedAt: String = date(2026, 1, 2),
            completedAt: String = date(null, null, null),
            media: String = media(),
        ): String =
            """{"id":$libraryId,"status":"$status","scoreRaw":$scoreRaw,"progress":$progress,"private":$private,""" +
                """"startedAt":$startedAt,"completedAt":$completedAt,"media":$media}"""

        fun listBody(vararg entries: String): String = """{"data":{"Page":{"mediaList":[${entries.joinToString(",")}]}}}"""

        /** `SaveMediaListEntry` as the add and the update answer it. */
        fun savedBody(libraryId: Long = 5001L, status: String = "CURRENT"): String =
            """{"data":{"SaveMediaListEntry":{"id":$libraryId,"status":"$status","progress":0}}}"""

        const val DELETED_BODY = """{"data":{"DeleteMediaListEntry":{"deleted":true}}}"""

        fun viewerBody(scoreFormat: String = "POINT_10_DECIMAL"): String =
            """{"data":{"Viewer":{"id":$USER_ID,"name":"$USER_NAME","mediaListOptions":{"scoreFormat":"$scoreFormat"}}}}"""

        fun track(
            id: Long = 10L,
            remoteId: Long = 1001L,
            libraryId: Long? = 5001L,
            lastChapterRead: Double = 0.0,
            totalChapters: Long = 0L,
            status: Long = Anilist.READING,
            score: Double = 0.0,
            startDate: Long = 0L,
            finishDate: Long = 0L,
            private: Boolean = false,
        ) = Track(
            id = id,
            mangaId = MANGA_ID,
            trackerId = TrackerIds.ANILIST,
            remoteId = remoteId,
            libraryId = libraryId,
            title = "Fixture Title",
            lastChapterRead = lastChapterRead,
            totalChapters = totalChapters,
            status = status,
            score = score,
            remoteUrl = "https://anilist.co/manga/$remoteId",
            startDate = startDate,
            finishDate = finishDate,
            private = private,
        )
    }
}

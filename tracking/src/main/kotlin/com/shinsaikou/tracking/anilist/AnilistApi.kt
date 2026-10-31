// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/data/track/anilist/AnilistApi.kt (modified: yes)
package com.shinsaikou.tracking.anilist

import com.shinsaikou.tracking.TrackSearchResult
import com.shinsaikou.tracking.anilist.dto.ALAddMangaResult
import com.shinsaikou.tracking.anilist.dto.ALCurrentUserResult
import com.shinsaikou.tracking.anilist.dto.ALSearchResult
import com.shinsaikou.tracking.anilist.dto.ALUserListMangaQueryResult
import com.shinsaikou.tracking.anilist.dto.ALUserViewerData
import com.shinsaikou.tracking.awaitSuccess
import com.shinsaikou.tracking.parseAs
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.track.model.Track
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Modified:
 *
 * - **No client id on the companion.** Upstream kept its own client id as a constant; the id is
 *   [clientId], read per call (the build's on AniList, a test base's elsewhere), and no upstream id
 *   is in this tree.
 * - **The authorization URL** ([authUrl]) adds a `state` to upstream's implicit grant, so the
 *   listener can tell this sign-in's redirect from any other. It sends no `redirect_uri`: AniList
 *   answers an implicit grant that names one with `unsupported_grant_type`, and redirects to the
 *   client's registered address instead, which must be the loopback's.
 * - **[getCurrentUser] takes the new access token itself,** so nothing is stored until it answered.
 * - **Addresses** come from [endpoints], read on each call, as OkHttp [HttpUrl]s rather than
 *   `android.net.Uri`; every query and its variables are upstream's.
 * - **The immutable domain row** in place of upstream's bean: [addLibManga] returns the row with the
 *   entry's list id, and [findLibManga] lays the list entry over the row it was given and returns
 *   it, or null when the owner's list lacks the entry. [deleteLibManga] takes the list id.
 * - Search answers are [TrackSearchResult], with the entry's average score as its score, as upstream
 *   sets it. [getMangaDetails] is upstream v0.21.0's `id:` search (a81b7bce9), and the rate limit
 *   upstream v0.21.0's 25 calls a minute (2d1a8bbef, triage E75).
 * - No custom user agent: upstream's named itself, and the shared one is sent instead.
 */
class AnilistApi(
    private val client: OkHttpClient,
    interceptor: Interceptor,
    private val endpoints: () -> AnilistEndpoints,
    private val clientId: () -> String,
    private val json: Json,
) {

    private val authClient = client.newBuilder()
        .addInterceptor(interceptor)
        .rateLimit(permits = 25, period = 1.minutes)
        .build()

    /**
     * The page the browser opens for one sign-in: upstream's implicit grant, whose token comes back
     * in the redirect's fragment, with the sign-in's state. The redirect is the client's registered
     * one, since AniList refuses the grant when the page names it.
     */
    fun authUrl(state: String): HttpUrl = endpoints().authorization.newBuilder()
        .addQueryParameter("client_id", clientId())
        .addQueryParameter("response_type", "token")
        .addQueryParameter("state", state)
        .build()

    suspend fun addLibManga(track: Track): Track {
        return withIOContext {
            val query = $$"""
            |mutation AddManga($mangaId: Int, $progress: Int, $status: MediaListStatus, $private: Boolean) {
                |SaveMediaListEntry (mediaId: $mangaId, progress: $progress, status: $status, private: $private) {
                |   id
                |   status
                |}
            |}
            |
            """.trimMargin()
            val payload = buildJsonObject {
                put("query", query)
                putJsonObject("variables") {
                    put("mangaId", track.remoteId)
                    put("progress", track.lastChapterRead.toInt())
                    put("status", track.toApiStatus() ?: DEFAULT_STATUS)
                    put("private", track.private)
                }
            }
            authClient.newCall(post(payload))
                .awaitSuccess()
                .parseAs<ALAddMangaResult>(json)
                .let { track.copy(libraryId = it.data.entry.id) }
        }
    }

    suspend fun updateLibManga(track: Track): Track {
        return withIOContext {
            val query = $$"""
            |mutation UpdateManga(
                |$listId: Int, $progress: Int, $status: MediaListStatus, $private: Boolean,
                |$score: Int, $startedAt: FuzzyDateInput, $completedAt: FuzzyDateInput
            |) {
                |SaveMediaListEntry(
                    |id: $listId, progress: $progress, status: $status, private: $private,
                    |scoreRaw: $score, startedAt: $startedAt, completedAt: $completedAt
                |) {
                    |id
                    |status
                    |progress
                |}
            |}
            |
            """.trimMargin()
            val payload = buildJsonObject {
                put("query", query)
                putJsonObject("variables") {
                    put("listId", track.libraryId)
                    put("progress", track.lastChapterRead.toInt())
                    put("status", track.toApiStatus() ?: DEFAULT_STATUS)
                    put("score", track.score.toInt())
                    put("startedAt", createDate(track.startDate))
                    put("completedAt", createDate(track.finishDate))
                    put("private", track.private)
                }
            }
            authClient.newCall(post(payload))
                .awaitSuccess()
                .close()
            track
        }
    }

    suspend fun deleteLibManga(libraryId: Long) {
        withIOContext {
            val query = $$"""
            |mutation DeleteManga($listId: Int) {
                |DeleteMediaListEntry(id: $listId) {
                    |deleted
                |}
            |}
            |
            """.trimMargin()
            val payload = buildJsonObject {
                put("query", query)
                putJsonObject("variables") {
                    put("listId", libraryId)
                }
            }
            authClient.newCall(post(payload))
                .awaitSuccess()
                .close()
        }
    }

    suspend fun search(search: String): List<TrackSearchResult> {
        return withIOContext {
            val query = $$"""
            |query Search($query: String) {
                |Page (perPage: 50) {
                    |media(search: $query, type: MANGA, format_not_in: [NOVEL]) {
                        |id
                        |staff {
                            |edges {
                                |role
                                |id
                                |node {
                                    |name {
                                        |full
                                        |userPreferred
                                        |native
                                    |}
                                |}
                            |}
                        |}
                        |title {
                            |userPreferred
                        |}
                        |coverImage {
                            |large
                        |}
                        |format
                        |countryOfOrigin
                        |status
                        |chapters
                        |description
                        |startDate {
                            |year
                            |month
                            |day
                        |}
                        |averageScore
                    |}
                |}
            |}
            |
            """.trimMargin()
            val payload = buildJsonObject {
                put("query", query)
                putJsonObject("variables") {
                    put("query", search)
                }
            }
            authClient.newCall(post(payload))
                .awaitSuccess()
                .parseAs<ALSearchResult>(json)
                .data.page.media
                .map { it.toALManga().toTrackSearchResult() }
        }
    }

    /** The entry with AniList id [id], or null when AniList has no manga under it (upstream v0.21.0). */
    suspend fun getMangaDetails(id: Int): TrackSearchResult? {
        return withIOContext {
            val query = $$"""
            |query Search($manga_id: Int) {
                |Page (perPage: 1) {
                    |media(id: $manga_id, type: MANGA, format_not_in: [NOVEL]) {
                        |id
                        |staff {
                            |edges {
                                |role
                                |id
                                |node {
                                    |name {
                                        |full
                                        |userPreferred
                                        |native
                                    |}
                                |}
                            |}
                        |}
                        |title {
                            |userPreferred
                        |}
                        |coverImage {
                            |large
                        |}
                        |format
                        |countryOfOrigin
                        |status
                        |chapters
                        |description
                        |startDate {
                            |year
                            |month
                            |day
                        |}
                        |averageScore
                    |}
                |}
            |}
            |
            """.trimMargin()
            val payload = buildJsonObject {
                put("query", query)
                putJsonObject("variables") {
                    put("manga_id", id)
                }
            }
            authClient.newCall(post(payload))
                .awaitSuccess()
                .parseAs<ALSearchResult>(json)
                .data.page.media
                .firstOrNull()
                ?.toALManga()
                ?.toTrackSearchResult()
        }
    }

    /**
     * [track] with the owner's list entry for its manga laid over it (status, progress, score, dates,
     * privacy, list id, title and chapter count), or null when the list of user [userId] lacks it.
     */
    suspend fun findLibManga(track: Track, userId: Int): Track? {
        return withIOContext {
            val query = $$"""
            |query ($id: Int!, $manga_id: Int!) {
                |Page {
                    |mediaList(userId: $id, type: MANGA, mediaId: $manga_id) {
                        |id
                        |status
                        |scoreRaw: score(format: POINT_100)
                        |progress
                        |private
                        |startedAt {
                            |year
                            |month
                            |day
                        |}
                        |completedAt {
                            |year
                            |month
                            |day
                        |}
                        |media {
                            |id
                            |title {
                                |userPreferred
                            |}
                            |coverImage {
                                |large
                            |}
                            |format
                            |status
                            |chapters
                            |description
                            |startDate {
                                |year
                                |month
                                |day
                            |}
                            |staff {
                                |edges {
                                    |role
                                    |id
                                    |node {
                                        |name {
                                            |full
                                            |userPreferred
                                            |native
                                        |}
                                    |}
                                |}
                            |}
                        |}
                    |}
                |}
            |}
            |
            """.trimMargin()
            val payload = buildJsonObject {
                put("query", query)
                putJsonObject("variables") {
                    put("id", userId)
                    put("manga_id", track.remoteId)
                }
            }
            authClient.newCall(post(payload))
                .awaitSuccess()
                .parseAs<ALUserListMangaQueryResult>(json)
                .data.page.mediaList
                .map { it.toALUserManga() }
                .firstOrNull()
                ?.toTrack(track)
        }
    }

    /** The account [accessToken] signs in as, asked for with it before anything is stored. */
    suspend fun getCurrentUser(accessToken: String): ALUserViewerData {
        return withIOContext {
            val query = """
            |query User {
                |Viewer {
                    |id
                    |name
                    |mediaListOptions {
                        |scoreFormat
                    |}
                |}
            |}
            |
            """.trimMargin()
            val payload = buildJsonObject {
                put("query", query)
            }
            val headers = Headers.Builder().add("Authorization", "Bearer $accessToken").build()
            client.newCall(post(payload, headers))
                .awaitSuccess()
                .parseAs<ALCurrentUserResult>(json)
                .data.viewer
        }
    }

    private fun post(payload: JsonObject, headers: Headers? = null): Request {
        val body = payload.toString().toRequestBody(JSON_MIME)
        val url = endpoints().api.toString()
        return if (headers == null) POST(url, body = body) else POST(url, headers = headers, body = body)
    }

    private fun createDate(dateValue: Long): JsonObject {
        if (dateValue == 0L) {
            return buildJsonObject {
                put("year", JsonNull)
                put("month", JsonNull)
                put("day", JsonNull)
            }
        }

        val dateTime = Instant.fromEpochMilliseconds(dateValue).toLocalDateTime(TimeZone.currentSystemDefault())
        return buildJsonObject {
            put("year", dateTime.year)
            put("month", dateTime.month.number)
            put("day", dateTime.day)
        }
    }

    companion object {
        private const val BASE_MANGA_URL = "https://anilist.co/manga/"

        private val JSON_MIME = "application/json; charset=utf-8".toMediaType()

        /** What a row whose status this code does not know is sent as, as MyAnimeList sends reading. */
        private const val DEFAULT_STATUS = "CURRENT"

        fun mangaUrl(mediaId: Long): String {
            return BASE_MANGA_URL + mediaId
        }
    }
}

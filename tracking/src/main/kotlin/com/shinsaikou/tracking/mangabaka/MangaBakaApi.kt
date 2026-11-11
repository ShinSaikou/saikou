// Vendored from upstream v0.21.3 (524fa53): app/src/main/java/eu/kanade/tachiyomi/data/track/mangabaka/MangaBakaApi.kt (modified: yes)
package com.shinsaikou.tracking.mangabaka

import com.shinsaikou.core.common.oauth.Pkce
import com.shinsaikou.tracking.PkceMethod
import com.shinsaikou.tracking.TrackSearchResult
import com.shinsaikou.tracking.TrackerSignInRefusedException
import com.shinsaikou.tracking.await
import com.shinsaikou.tracking.awaitSuccess
import com.shinsaikou.tracking.mangabaka.dto.MangaBakaItem
import com.shinsaikou.tracking.mangabaka.dto.MangaBakaItemResult
import com.shinsaikou.tracking.mangabaka.dto.MangaBakaListResult
import com.shinsaikou.tracking.mangabaka.dto.MangaBakaOAuth
import com.shinsaikou.tracking.mangabaka.dto.MangaBakaSearchResult
import com.shinsaikou.tracking.mangabaka.dto.MangaBakaTokenResponse
import com.shinsaikou.tracking.mangabaka.dto.MangaBakaUserProfile
import com.shinsaikou.tracking.mangabaka.dto.MangaBakaUserProfileResponse
import com.shinsaikou.tracking.parseAs
import eu.kanade.tachiyomi.network.DELETE
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.PUT
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.track.model.Track
import java.math.RoundingMode
import java.util.Locale
import kotlin.time.Instant

/**
 * Modified:
 *
 * - **No client id, redirect, verifier or state on the companion.** Upstream kept its own client id
 *   and redirect as constants, and the PKCE verifier and the state as companion `var`s every sign-in
 *   overwrote. The id is [clientId], read per call (the build's on MangaBaka, a test base's
 *   elsewhere); the redirect is the loopback's, and the verifier and the state come with each
 *   sign-in, which the caller made for that sign-in alone.
 * - **The authorization URL** ([authUrl]) asks for `openid`, which the API requires, with upstream's
 *   `library.read`, `library.write` and `offline_access`, and `profile` for the account's name.
 *   [getAccessToken] sends the five fields MangaBaka's guide names, where upstream added the scope
 *   and the challenge method again.
 * - **A refused code exchange** is [TrackerSignInRefusedException] with the OAuth `error` code, or
 *   `http_<status>`, never the body. [getCurrentUser] takes the new access token itself, so nothing
 *   is stored until the exchange and the profile read have both succeeded.
 * - **The refresh** ([refreshTokenRequest]) sends the three fields MangaBaka's guide names; upstream
 *   added the redirect. **Sign-out revokes the refresh token** ([revoke]), which upstream never did.
 * - **Addresses** come from [endpoints], read on each call, as OkHttp [HttpUrl]s rather than
 *   `android.net.Uri`. Every call is API v1's, as upstream's are: MangaBaka marks each of them
 *   stable, and v2 has no call for a single library entry.
 * - **The immutable domain row** in place of upstream's bean: [findLibManga] lays the owner's entry,
 *   the series' title and its chapter count over the row it was given; [updateLibManga] adds the
 *   entry when the list no longer has it (404), as MyAnimeList's send does; [deleteLibManga] takes
 *   the series id and counts an entry already gone as deleted.
 * - **Search** asks for 50 hits, where upstream took MangaBaka's default of 10, and answers
 *   [TrackSearchResult] with the series' chapter count, and its canonical address when it has one.
 *   Search and the series reads carry no token: they are public.
 * - **Dates** are calendar days on the device ([formatDate], [parseDate]), as the track sheet stores
 *   them; the entry's are sent as bare `yyyy-MM-dd`, which MangaBaka prefers.
 * - No custom user agent: upstream's named itself, and the shared one is sent instead.
 */
class MangaBakaApi(
    private val trackId: Long,
    private val client: OkHttpClient,
    interceptor: MangaBakaInterceptor,
    private val endpoints: () -> MangaBakaEndpoints,
    private val clientId: () -> String,
    private val json: Json,
    private val nowEpochSeconds: () -> Long,
) {

    private val authClient = client.newBuilder().addInterceptor(interceptor).build()

    /** The page the browser opens for one sign-in: the code flow with an S256 challenge, MangaBaka's only method. */
    fun authUrl(redirectUri: String, state: String, pkce: Pkce): HttpUrl = endpoints().authorization.newBuilder()
        .addQueryParameter("response_type", "code")
        .addQueryParameter("client_id", clientId())
        .addQueryParameter("redirect_uri", redirectUri)
        .addQueryParameter("scope", SCOPES)
        .addQueryParameter("state", state)
        .addQueryParameter("code_challenge", PkceMethod.S256.challenge(pkce))
        .addQueryParameter("code_challenge_method", PkceMethod.S256.id)
        .build()

    suspend fun getAccessToken(code: String, redirectUri: String, pkce: Pkce): MangaBakaOAuth {
        return withIOContext {
            val formBody = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", redirectUri)
                .add("client_id", clientId())
                .add("code_verifier", pkce.verifier)
                .build()
            client.newCall(POST(endpoints().token.toString(), body = formBody)).await().use { response ->
                if (!response.isSuccessful) throw TrackerSignInRefusedException(trackId, oauthError(response))
                json.decodeFromString<MangaBakaTokenResponse>(response.body.string()).toOAuth(nowEpochSeconds())
            }
        }
    }

    /** The signed-in account, asked for with [accessToken] before anything is stored. */
    suspend fun getCurrentUser(accessToken: String): MangaBakaUserProfile {
        return withIOContext {
            val headers = Headers.Builder().add("Authorization", "Bearer $accessToken").build()
            client.newCall(GET(api("v1", "my", "profile"), headers))
                .awaitSuccess()
                .parseAs<MangaBakaUserProfileResponse>(json)
                .data
        }
    }

    /**
     * Revokes [refreshToken] at MangaBaka, which ends the grant it belongs to. Sign-out sends it
     * after the sign-in is already cleared here, so a failure only leaves the grant to expire.
     */
    suspend fun revoke(refreshToken: String) {
        withIOContext {
            val formBody = FormBody.Builder()
                .add("token", refreshToken)
                .add("token_type_hint", "refresh_token")
                .add("client_id", clientId())
                .build()
            client.newCall(POST(endpoints().revocation.toString(), body = formBody))
                .awaitSuccess()
                .close()
        }
    }

    suspend fun search(query: String): List<TrackSearchResult> {
        return withIOContext {
            val url = api("v1", "series", "search").newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("type_not", "novel")
                .addQueryParameter("limit", SEARCH_LIMIT.toString())
                .build()
            client.newCall(GET(url))
                .awaitSuccess()
                .parseAs<MangaBakaSearchResult>(json)
                .data
                .map { parseSearchItem(it) }
        }
    }

    /** The series with MangaBaka id [id], or null when MangaBaka has none under it. */
    suspend fun getMangaDetails(id: Long): TrackSearchResult? {
        return withIOContext {
            try {
                parseSearchItem(getSeries(id))
            } catch (e: HttpException) {
                if (e.code == 404) return@withIOContext null
                throw e
            }
        }
    }

    /**
     * [track] with the owner's library entry laid over it (status, progress, score, dates and
     * privacy) and the series' title and chapter count, or null when the library lacks the entry.
     */
    suspend fun findLibManga(track: Track): Track? {
        return withIOContext {
            val entry = try {
                authClient.newCall(GET(libraryUrl(track.remoteId)))
                    .awaitSuccess()
                    .parseAs<MangaBakaListResult>(json)
                    .data
            } catch (e: HttpException) {
                if (e.code == 404) return@withIOContext null
                throw e
            }
            val series = getSeries(track.remoteId)

            track.copy(
                title = series.chooseBestTitle(),
                totalChapters = series.chapterCount().takeIf { it > 0 } ?: track.totalChapters,
                status = toMangaBakaStatus(entry.state),
                score = entry.rating ?: 0.0,
                startDate = parseDate(entry.startDate) ?: 0L,
                finishDate = parseDate(entry.finishDate) ?: 0L,
                lastChapterRead = entry.progressChapter ?: 0.0,
                private = entry.isPrivate,
            )
        }
    }

    /** Puts [track] on the owner's library. MangaBaka answers `true` alone, so the row is returned as sent. */
    suspend fun addLibManga(track: Track): Track {
        return withIOContext {
            val body = buildJsonObject {
                put("is_private", track.private)
                put("state", track.toApiStatus())
                if (track.lastChapterRead > 0.0) {
                    put("progress_chapter", track.lastChapterRead)
                }
                if (track.score > 0) {
                    put("rating", track.score.toInt().coerceIn(0, 100))
                }
                formatDate(track.startDate)?.let { put("start_date", it) }
                formatDate(track.finishDate)?.let { put("finish_date", it) }
            }
            authClient.newCall(POST(libraryUrl(track.remoteId).toString(), body = body.toRequestBody()))
                .awaitSuccess()
                .close()
            track
        }
    }

    /**
     * Writes every field of [track]'s entry, a cleared one as null, as upstream does. An entry the
     * library no longer has (removed on the website) is added back as the row has it.
     */
    suspend fun updateLibManga(track: Track): Track {
        return withIOContext {
            val body = buildJsonObject {
                put("state", track.toApiStatus())
                put("is_private", track.private)
                putOrNull("progress_chapter", track.lastChapterRead.takeIf { it > 0.0 }?.let(::JsonPrimitive))
                putOrNull("rating", track.score.takeIf { it > 0 }?.let { JsonPrimitive(it.toInt().coerceIn(0, 100)) })
                putOrNull("start_date", formatDate(track.startDate)?.let(::JsonPrimitive))
                putOrNull("finish_date", formatDate(track.finishDate)?.let(::JsonPrimitive))
            }
            try {
                authClient.newCall(PUT(libraryUrl(track.remoteId).toString(), body = body.toRequestBody()))
                    .awaitSuccess()
                    .close()
                track
            } catch (e: HttpException) {
                if (e.code != 404) throw e
                addLibManga(track)
            }
        }
    }

    suspend fun deleteLibManga(remoteId: Long) {
        withIOContext {
            try {
                authClient.newCall(DELETE(libraryUrl(remoteId).toString()))
                    .awaitSuccess()
                    .close()
            } catch (e: HttpException) {
                // Already gone from the library: what the delete asked for.
                if (e.code != 404) throw e
            }
        }
    }

    private suspend fun getSeries(id: Long): MangaBakaItem =
        client.newCall(GET(api("v1", "series", id.toString())))
            .awaitSuccess()
            .parseAs<MangaBakaItemResult>(json)
            .data

    private fun parseSearchItem(item: MangaBakaItem): TrackSearchResult {
        return TrackSearchResult(
            trackerId = trackId,
            remoteId = item.id,
            title = item.chooseBestTitle(),
            summary = item.description?.trim().orEmpty(),
            score = item.rating?.toBigDecimal()?.setScale(2, RoundingMode.HALF_UP)?.toDouble() ?: -1.0,
            coverUrl = item.cover.x250.x1.orEmpty(),
            trackingUrl = item.canonicalUrl?.takeIf { it.isNotBlank() } ?: seriesPage(item.id),
            startDate = item.published.startDate.orEmpty(),
            publishingStatus = item.status,
            publishingType = item.type.replaceFirstChar { c ->
                if (c.isLowerCase()) c.titlecase(Locale.getDefault()) else c.toString()
            },
            totalChapters = item.chapterCount(),
            authors = item.authors.orEmpty(),
            artists = item.artists.orEmpty(),
        )
    }

    private fun JsonObjectBuilder.putOrNull(key: String, value: JsonPrimitive?) {
        put(key, value ?: JsonNull)
    }

    private fun JsonObject.toRequestBody() = toString().toRequestBody(JSON_MIME)

    private fun api(vararg segments: String): HttpUrl =
        endpoints().api.newBuilder().apply { segments.forEach { addPathSegment(it) } }.build()

    private fun libraryUrl(remoteId: Long): HttpUrl = api("v1", "my", "library", remoteId.toString())

    /**
     * The OAuth `error` code of a refused token request, or `http_<status>` when the body names
     * none. Only a short lowercase code is taken, so no part of the body that could echo a request
     * value reaches the error list or the screen.
     */
    private fun oauthError(response: Response): String {
        val error = runCatching {
            (json.parseToJsonElement(response.body.string()) as? JsonObject)?.get("error") as? JsonPrimitive
        }.getOrNull()?.takeIf { it.isString }?.content
        return error?.takeIf { OAUTH_ERROR.matches(it) } ?: "http_${response.code}"
    }

    companion object {
        /** `openid` is required: MangaBaka's API refuses a token issued without it. */
        const val SCOPES = "openid profile library.read library.write offline_access"

        private const val SEARCH_LIMIT = 50

        private val JSON_MIME = "application/json; charset=utf-8".toMediaType()

        private val OAUTH_ERROR = Regex("[a-z_]{1,64}")

        /** A series' page on the website, for a series whose answer named no canonical address. */
        fun seriesPage(id: Long): String = "https://mangabaka.org/manga/$id"

        /**
         * The refresh grant for [refreshToken], sent to [tokenUrl] with [clientId]: the three fields
         * MangaBaka's guide names. The interceptor sends it down its own chain.
         */
        fun refreshTokenRequest(tokenUrl: HttpUrl, clientId: String, refreshToken: String): Request = POST(
            tokenUrl.toString(),
            body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", clientId)
                .build(),
        )

        /** A stored date as the calendar day it names on the device, or null for none. */
        internal fun formatDate(epochMillis: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String? {
            if (epochMillis <= 0L) return null
            return Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone).date.toString()
        }

        /**
         * MangaBaka's date as a stored one: the day's midnight on the device. API v1 answers a full
         * timestamp at midnight UTC whatever the time of the change, so only its date counts, as
         * upstream reads it; one that does not parse is none.
         */
        internal fun parseDate(isoDate: String?, zone: TimeZone = TimeZone.currentSystemDefault()): Long? =
            isoDate?.substringBefore("T")
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?.atStartOfDayIn(zone)
                ?.toEpochMilliseconds()
    }
}

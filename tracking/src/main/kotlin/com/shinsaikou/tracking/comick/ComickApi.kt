// Vendored from upstream v0.21.3 (524fa53): app/src/main/java/eu/kanade/tachiyomi/data/track/comick/ComickApi.kt (modified: yes)
package com.shinsaikou.tracking.comick

import com.shinsaikou.core.common.oauth.Pkce
import com.shinsaikou.tracking.PkceMethod
import com.shinsaikou.tracking.TrackSearchResult
import com.shinsaikou.tracking.TrackerPermissionMissingException
import com.shinsaikou.tracking.TrackerSignInRefusedException
import com.shinsaikou.tracking.await
import com.shinsaikou.tracking.awaitSuccess
import com.shinsaikou.tracking.comick.dto.ComickLibraryEntry
import com.shinsaikou.tracking.comick.dto.ComickOAuth
import com.shinsaikou.tracking.comick.dto.ComickSearchItem
import com.shinsaikou.tracking.comick.dto.ComickSearchResult
import com.shinsaikou.tracking.comick.dto.ComickTitleLookupResult
import com.shinsaikou.tracking.comick.dto.ComickTokenResponse
import com.shinsaikou.tracking.comick.dto.ComickUser
import com.shinsaikou.tracking.parseAs
import eu.kanade.tachiyomi.network.DELETE
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.PUT
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.track.model.Track
import java.math.RoundingMode
import kotlin.time.Duration.Companion.minutes

/**
 * Modified:
 *
 * - **No client id, redirect, verifier or state on the companion.** Upstream kept its own client id
 *   and its custom-scheme redirect as constants, and the PKCE verifier and the state as companion
 *   `var`s every sign-in overwrote. The id is [clientId], read per call (the build's on Comick, a
 *   test base's elsewhere); the redirect is the one the caller names, the trackers' loopback today,
 *   and the verifier and the state come with each sign-in, which the caller made for that sign-in
 *   alone. So upstream's `verifyOAuthState` is the loopback listener's own check.
 * - **Every authorize, token and refresh call names [RESOURCE]**, as upstream's do: Comick refuses
 *   an authorization that does not ask for its library API.
 * - **Permissions the owner left off fail the sign-in** ([getAccessToken]), as upstream's
 *   `ComickMissingScopesException` did: an answer without a refresh token (`offline_access`), or whose
 *   scopes lack `library:write`, is [TrackerPermissionMissingException] naming the scope, and nothing
 *   is stored. **A refused code exchange** is [TrackerSignInRefusedException] with the OAuth `error`
 *   code, or `http_<status>`, never the body. [getCurrentUser] takes the new access token itself, so
 *   nothing is stored until the exchange and the account read have both succeeded.
 * - **Addresses** come from [endpoints], read on each call, as OkHttp [HttpUrl]s rather than
 *   `android.net.Uri`.
 * - **The immutable domain row** in place of upstream's bean: [findLibManga] lays the owner's entry
 *   (status, progress, rating and title) over the row it was given; [updateLibManga] adds the entry
 *   when the library no longer has it (404), as MangaBaka's does; [deleteLibManga] takes the remote
 *   id and counts an entry already gone as deleted.
 * - **Search** answers [TrackSearchResult] and skips a title whose HID does not decode; a lookup by
 *   HID whose answer does not decode is no result. Both carry the token, as upstream's do.
 * - The rate limit (55 calls a minute) and the `Accept` header are upstream's. No custom user agent:
 *   upstream's named itself, and the shared one is sent instead.
 */
class ComickApi(
    private val trackId: Long,
    baseClient: OkHttpClient,
    interceptor: ComickInterceptor,
    private val endpoints: () -> ComickEndpoints,
    private val clientId: () -> String,
    private val json: Json,
    private val nowEpochSeconds: () -> Long,
) {

    private val client = baseClient.newBuilder()
        .rateLimit(permits = 55, period = 1.minutes)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("Accept", JSON_MIME.toString()).build())
        }
        .build()

    private val authClient = client.newBuilder().addInterceptor(interceptor).build()

    /** The page the browser opens for one sign-in: the code flow with an S256 challenge, for [RESOURCE]. */
    fun authUrl(redirectUri: String, state: String, pkce: Pkce): HttpUrl = endpoints().authorization.newBuilder()
        .addQueryParameter("response_type", "code")
        .addQueryParameter("client_id", clientId())
        .addQueryParameter("redirect_uri", redirectUri)
        .addQueryParameter("scope", SCOPES)
        .addQueryParameter("resource", RESOURCE)
        .addQueryParameter("state", state)
        .addQueryParameter("code_challenge", PkceMethod.S256.challenge(pkce))
        .addQueryParameter("code_challenge_method", PkceMethod.S256.id)
        .build()

    /**
     * Exchanges [code] for the grant to store.
     *
     * @throws TrackerPermissionMissingException when the owner did not grant `offline_access` or
     *   `library:write`
     * @throws TrackerSignInRefusedException when the token endpoint refuses the code
     */
    suspend fun getAccessToken(code: String, redirectUri: String, pkce: Pkce): ComickOAuth {
        return withIOContext {
            val formBody = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("client_id", clientId())
                .add("code", code)
                .add("redirect_uri", redirectUri)
                .add("code_verifier", pkce.verifier)
                .add("resource", RESOURCE)
                .build()
            val answer = client.newCall(POST(endpoints().token.toString(), body = formBody)).await().use { response ->
                if (!response.isSuccessful) throw TrackerSignInRefusedException(trackId, oauthError(response))
                json.decodeFromString<ComickTokenResponse>(response.body.string())
            }
            if (answer.refreshToken.isNullOrBlank()) {
                throw TrackerPermissionMissingException(trackId, OFFLINE_ACCESS_SCOPE)
            }
            // Users may forget to enable this since it's off by default
            if (LIBRARY_WRITE_SCOPE !in answer.scopes()) {
                throw TrackerPermissionMissingException(trackId, LIBRARY_WRITE_SCOPE)
            }
            answer.toOAuth(nowEpochSeconds())
        }
    }

    /** The signed-in account, asked for with [accessToken] before anything is stored. */
    suspend fun getCurrentUser(accessToken: String): ComickUser {
        return withIOContext {
            val headers = Headers.Builder().add("Authorization", "Bearer $accessToken").build()
            client.newCall(GET(api("me"), headers))
                .awaitSuccess()
                .parseAs<ComickUser>(json)
        }
    }

    suspend fun search(query: String): List<TrackSearchResult> {
        return withIOContext {
            val url = api("titles").newBuilder()
                // Comick throws a 400 when the query is over 100 characters
                .addQueryParameter("q", query.take(MAX_QUERY_LENGTH))
                .addQueryParameter("media_type", "manga")
                .addQueryParameter("limit", SEARCH_LIMIT.toString())
                .build()
            authClient.newCall(GET(url))
                .awaitSuccess()
                .parseAs<ComickSearchResult>(json)
                .data
                .mapNotNull { parseSearchItem(it) }
        }
    }

    /** The title with HID [hid], or null when Comick has none under it. */
    suspend fun getMangaDetails(hid: String): TrackSearchResult? {
        return withIOContext {
            try {
                authClient.newCall(GET(api("titles", hid)))
                    .awaitSuccess()
                    .parseAs<ComickTitleLookupResult>(json)
                    .data
                    .let(::parseSearchItem)
            } catch (e: HttpException) {
                if (e.code == 404) return@withIOContext null
                throw e
            }
        }
    }

    /**
     * [track] with the owner's library entry laid over it (status, progress, rating and title), or
     * null when the library lacks the entry.
     */
    suspend fun findLibManga(track: Track): Track? {
        return withIOContext {
            val entry = try {
                authClient.newCall(GET(libraryUrl(track.remoteId)))
                    .awaitSuccess()
                    .parseAs<ComickLibraryEntry>(json)
                    .data
            } catch (e: HttpException) {
                if (e.code == 404) return@withIOContext null
                throw e
            }
            track.copy(
                title = entry.title.ifBlank { track.title },
                status = entry.status.fromApiListStatus(),
                lastChapterRead = entry.progress?.number?.toDoubleOrNull() ?: 0.0,
                score = entry.rating?.toDouble() ?: 0.0,
            )
        }
    }

    /** Puts [track] on the owner's library. Comick answers the entry back with nothing more, so the row is returned as sent. */
    suspend fun addLibManga(track: Track): Track {
        return withIOContext {
            val body = buildJsonObject {
                put("status", track.status.toApiListStatus())
                if (track.lastChapterRead > 0) {
                    putJsonObject("progress") { put("number", chapterNumber(track.lastChapterRead)) }
                } else {
                    put("progress", null)
                }
            }
            authClient.newCall(PUT(libraryUrl(track.remoteId).toString(), body = body.toRequestBody()))
                .awaitSuccess()
                .close()
            track
        }
    }

    /**
     * Writes [track]'s status, progress and rating, a cleared rating as null, as upstream does. An
     * entry the library no longer has (removed on the website) is added back as the row has it.
     */
    suspend fun updateLibManga(track: Track): Track {
        return withIOContext {
            val body = buildJsonObject {
                put("rating", track.score.toInt().takeIf { it > 0 }?.coerceAtMost(MAX_SCORE))
                put("status", track.status.toApiListStatus())
                putJsonObject("progress") { put("number", chapterNumber(track.lastChapterRead)) }
            }
            val request = Request.Builder()
                .url(libraryUrl(track.remoteId))
                .patch(body.toRequestBody())
                .build()
            try {
                authClient.newCall(request)
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
                authClient.newCall(DELETE(libraryUrl(remoteId).toString(), body = RequestBody.EMPTY))
                    .awaitSuccess()
                    .close()
            } catch (e: HttpException) {
                // Already gone from the library: what the delete asked for.
                if (e.code != 404) throw e
            }
        }
    }

    /** [item] as a search result, or null when its HID does not decode to a remote id. */
    private fun parseSearchItem(item: ComickSearchItem): TrackSearchResult? {
        val remoteId = item.hid.hidToLongOrNull() ?: return null
        return TrackSearchResult(
            trackerId = trackId,
            remoteId = remoteId,
            title = item.title.ifBlank { item.hid },
            summary = item.description?.trim().orEmpty(),
            score = item.bayesianRating ?: -1.0,
            coverUrl = item.coverUrl.orEmpty(),
            trackingUrl = item.url,
            startDate = item.year?.toString().orEmpty(),
            publishingStatus = item.publishingStatus(),
            publishingType = item.publishingType(),
            totalChapters = 0L,
            authors = emptyList(),
            artists = emptyList(),
        )
    }

    private fun JsonObject.toRequestBody() = toString().toRequestBody(JSON_MIME)

    private fun api(vararg segments: String): HttpUrl =
        endpoints().api.newBuilder().apply { segments.forEach { addPathSegment(it) } }.build()

    private fun libraryUrl(remoteId: Long): HttpUrl = api("me", "library", remoteId.toHid())

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
        const val LIBRARY_WRITE_SCOPE = "library:write"
        const val OFFLINE_ACCESS_SCOPE = "offline_access"
        const val SCOPES = "library:read $LIBRARY_WRITE_SCOPE $OFFLINE_ACCESS_SCOPE"

        /** The API Comick's tokens are for, which every authorize, token and refresh call names. */
        const val RESOURCE = "https://api.comick.dev/integrations/v1"

        /** Comick's highest rating; its scores are 0 to 10. */
        const val MAX_SCORE = 10

        private const val MAX_QUERY_LENGTH = 100

        private const val SEARCH_LIMIT = 20

        private val JSON_MIME = "application/json; charset=utf-8".toMediaType()

        private val OAUTH_ERROR = Regex("[a-z_]{1,64}")

        /**
         * The refresh grant for [refreshToken], sent to [tokenUrl] with [clientId] and [RESOURCE],
         * as upstream's. The interceptor sends it down its own chain.
         */
        fun refreshTokenRequest(tokenUrl: HttpUrl, clientId: String, refreshToken: String): Request = POST(
            tokenUrl.toString(),
            body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("client_id", clientId)
                .add("refresh_token", refreshToken)
                .add("resource", RESOURCE)
                .build(),
            headers = headersOf("Accept", JSON_MIME.toString()),
        )

        /** A chapter as Comick takes it: at most two decimals, none when whole (`"12"`, `"12.5"`). */
        internal fun chapterNumber(chapter: Double): String =
            chapter.toBigDecimal()
                .setScale(2, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString()
    }
}

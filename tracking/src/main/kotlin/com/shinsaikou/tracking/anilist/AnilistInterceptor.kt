// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/data/track/anilist/AnilistInterceptor.kt (modified: yes)
package com.shinsaikou.tracking.anilist

import com.shinsaikou.tracking.TrackerAuthExpiredException
import com.shinsaikou.tracking.anilist.dto.ALOAuth
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * Modified:
 *
 * - **An expired or refused token is "sign in again".** AniList's implicit grant has no refresh
 *   token, so a token past its year by the clock (upstream v0.21.0's expiry, 21af65b10, triage E35)
 *   calls [Anilist.setAuthExpired] and throws [TrackerAuthExpiredException] before anything is sent,
 *   where upstream signed the owner out. A token the API refuses (401, or 400 with the GraphQL error
 *   `Invalid token`) does the same, so the retry worker keeps the push for the next sign-in and
 *   settings offers "Sign in again". The credentials are kept.
 * - **The grant is read from the stored token on every call**, not held in a field loaded once, as
 *   MyAnimeList's interceptor reads its own: a token restored from a backup or cleared by a sign-out
 *   is seen at once. A call refused while a new sign-in stored another token is sent once more with
 *   that token instead of marking the new sign-in expired.
 * - Expiry reads the tracker's clock. No custom user agent: upstream's named itself.
 * - Nothing here logs; no exception message names a token.
 */
class AnilistInterceptor(
    private val anilist: Anilist,
    private val json: Json,
    private val nowEpochMillis: () -> Long,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        if (anilist.isAuthExpired) {
            throw TrackerAuthExpiredException(anilist.id)
        }
        val originalRequest = chain.request()

        val oauth = anilist.loadOAuth() ?: throw IOException("AniList: User is not authenticated")
        if (oauth.isExpired(nowEpochMillis())) {
            anilist.setAuthExpired()
            throw TrackerAuthExpiredException(anilist.id)
        }

        val response = chain.proceed(originalRequest.authorized(oauth))
        if (!refusesToken(response)) return response
        response.close()

        val current = synchronized(this) {
            val stored = anilist.loadOAuth()
            if (stored == null || stored.accessToken == oauth.accessToken) {
                anilist.setAuthExpired()
                throw TrackerAuthExpiredException(anilist.id)
            }
            stored
        }
        // A new sign-in stored another token while this call was on the wire: its answer is final.
        return chain.proceed(originalRequest.authorized(current))
    }

    // Add the authorization header to the original request.
    private fun Request.authorized(oauth: ALOAuth): Request = newBuilder()
        .header("Authorization", "Bearer ${oauth.accessToken}")
        .build()

    /**
     * Whether AniList refused the token itself: 401, or 400 whose GraphQL errors include
     * `Invalid token`. Any other answer is the call's own.
     */
    private fun refusesToken(response: Response): Boolean = when (response.code) {
        401 -> true
        400 -> runCatching {
            val body = json.parseToJsonElement(response.peekBody(MAX_ERROR_BYTES).string()) as? JsonObject
            (body?.get("errors") as? JsonArray).orEmpty().any { error ->
                val message = ((error as? JsonObject)?.get("message") as? JsonPrimitive)?.content
                message.equals(INVALID_TOKEN, ignoreCase = true)
            }
        }.getOrDefault(false)
        else -> false
    }

    private companion object {
        const val INVALID_TOKEN = "Invalid token"
        const val MAX_ERROR_BYTES = 16L * 1024
    }
}

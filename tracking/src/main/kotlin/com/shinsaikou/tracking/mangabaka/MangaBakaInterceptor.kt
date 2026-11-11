// Vendored from upstream v0.21.3 (524fa53): app/src/main/java/eu/kanade/tachiyomi/data/track/mangabaka/MangaBakaInterceptor.kt (modified: yes)
package com.shinsaikou.tracking.mangabaka

import com.shinsaikou.tracking.TrackerAuthExpiredException
import com.shinsaikou.tracking.mangabaka.dto.MangaBakaOAuth
import com.shinsaikou.tracking.mangabaka.dto.MangaBakaTokenResponse
import eu.kanade.tachiyomi.network.HttpException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * Modified, on MyAnimeList's interceptor:
 *
 * - **Refresh tokens rotate, and a retired one revokes everything.** MangaBaka retires the refresh
 *   token each refresh sends and answers a second refresh with it `400 invalid_grant`; sending it
 *   again after that revokes every token the app holds for the account. So the refresh is
 *   single-flight on [MangaBaka.grantLock], the lock sign-in and sign-out store under too: under it
 *   the stored grant is read again, and a call that waited for another call's refresh uses its grant
 *   instead of refreshing a second time. The new grant is stored before the lock is released.
 * - **A refused grant is "sign in again", never retried.** A 400 or 401 from the token endpoint
 *   whose OAuth `error` is `invalid_grant`, or names no error that can be read, calls
 *   [MangaBaka.setAuthExpired] and throws [TrackerAuthExpiredException], so the retry worker keeps the
 *   push for the next sign-in. So does a 2xx answer that does not read as a grant, including one whose
 *   body could not be read: MangaBaka has already answered, so the refresh token sent may be retired,
 *   and sending it again would revoke every token the account holds for the app, other devices'
 *   included. Upstream kept the old grant on any refused refresh and sent it on, so the next call
 *   refreshed with it again.
 * - **Any other refusal leaves the sign-in alone.** A 400 or 401 naming another code
 *   (`invalid_client`, `invalid_request` and the like) is a problem with the client or the request,
 *   which signing in again would not fix, and the refresh token was not spent. It is
 *   [MangaBakaTokenRefreshFailed], an `IOException` the worker retries, as are any other answer and
 *   a refresh that never reached the endpoint.
 * - **A 401 from the API forces one refresh,** as MyAnimeList's does: a token the clock still counts
 *   as live can be refused. The call is sent once more with the new token; a second 401 is its own
 *   answer.
 * - **The grant is read from the stored token on every call**, not held in a field loaded once, so a
 *   token restored from a backup or cleared by a sign-out is seen at once. A signed-out tracker
 *   throws an `IOException` rather than upstream's bare `Exception`.
 * - Expiry reads the tracker's clock; the token URL and client id are the tracker's for this call.
 * - Nothing here logs; no exception message names a token.
 */
class MangaBakaInterceptor(
    private val mangaBaka: MangaBaka,
    private val json: Json,
    private val nowEpochSeconds: () -> Long,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        if (mangaBaka.isAuthExpired) {
            throw TrackerAuthExpiredException(mangaBaka.id)
        }
        val originalRequest = chain.request()

        var oauth = mangaBaka.loadOAuth() ?: throw IOException("MangaBaka: User is not authenticated")
        if (oauth.isExpired(nowEpochSeconds())) {
            oauth = refreshToken(chain)
        }

        val response = chain.proceed(originalRequest.authorized(oauth))
        if (response.code != 401) return response

        // A live token refused: one forced refresh, and the call once more.
        response.close()
        return chain.proceed(originalRequest.authorized(refreshToken(chain, refused = oauth.accessToken)))
    }

    // Add the authorization header to the original request
    private fun Request.authorized(oauth: MangaBakaOAuth): Request = newBuilder()
        .header("Authorization", "Bearer ${oauth.accessToken}")
        .build()

    /**
     * The stored grant, refreshed when it has expired by the clock, or, with [refused], when its
     * access token is still the one the API just answered 401 to. A call that waited here for
     * another call's refresh finds a newer grant and uses it as it is.
     */
    private fun refreshToken(chain: Interceptor.Chain, refused: String? = null): MangaBakaOAuth =
        synchronized(mangaBaka.grantLock) {
            if (mangaBaka.isAuthExpired) throw TrackerAuthExpiredException(mangaBaka.id)
            val oauth = mangaBaka.loadOAuth() ?: throw IOException("MangaBaka: User is not authenticated")
            val stale = if (refused != null) oauth.accessToken == refused else oauth.isExpired(nowEpochSeconds())
            if (!stale) return@synchronized oauth

            val request = MangaBakaApi.refreshTokenRequest(mangaBaka.endpoints().token, mangaBaka.clientId(), oauth.refreshToken)
            val response = try {
                chain.proceed(request)
            } catch (e: IOException) {
                throw MangaBakaTokenRefreshFailed(e)
            }

            if (response.code == 400 || response.code == 401) {
                val error = response.use { oauthError(it) }
                if (error == null || error == INVALID_GRANT) {
                    mangaBaka.setAuthExpired()
                    throw TrackerAuthExpiredException(mangaBaka.id)
                }
                throw MangaBakaTokenRefreshFailed(HttpException(response.code))
            }

            if (!response.isSuccessful) {
                response.close()
                throw MangaBakaTokenRefreshFailed(HttpException(response.code))
            }

            val refreshed = runCatching {
                response.use { json.decodeFromString<MangaBakaTokenResponse>(it.body.string()) }
            }.getOrElse {
                // The refresh token sent may be retired already: never send it again.
                mangaBaka.setAuthExpired()
                throw TrackerAuthExpiredException(mangaBaka.id)
            }
            refreshed.toOAuth(nowEpochSeconds()).also { mangaBaka.saveOAuth(it) }
        }

    /** The OAuth `error` code a refused token request names, or null when its body names none. */
    private fun oauthError(response: Response): String? = runCatching {
        (json.parseToJsonElement(response.body.string()) as? JsonObject)?.get("error") as? JsonPrimitive
    }.getOrNull()?.takeIf { it.isString }?.content

    private companion object {
        const val INVALID_GRANT = "invalid_grant"
    }
}

class MangaBakaTokenRefreshFailed(cause: Throwable? = null) : IOException("MangaBaka: Failed to refresh account token", cause)

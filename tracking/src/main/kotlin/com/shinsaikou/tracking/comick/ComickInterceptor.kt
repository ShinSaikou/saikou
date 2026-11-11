// Vendored from upstream v0.21.3 (524fa53): app/src/main/java/eu/kanade/tachiyomi/data/track/comick/ComickInterceptor.kt (modified: yes)
package com.shinsaikou.tracking.comick

import com.shinsaikou.tracking.TrackerAuthExpiredException
import com.shinsaikou.tracking.comick.dto.ComickOAuth
import com.shinsaikou.tracking.comick.dto.ComickTokenResponse
import eu.kanade.tachiyomi.network.HttpException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * Modified, on MangaBaka's interceptor:
 *
 * - **Refresh tokens may rotate.** Comick's documentation is not readable from here, so the refresh
 *   is written as if each one retires the refresh token it sends, as MangaBaka's do: single-flight on
 *   [Comick.grantLock], the lock sign-in and sign-out store under too. Under it the stored grant is
 *   read again, and a call that waited for another call's refresh uses its grant instead of
 *   refreshing a second time. The new grant is stored before the lock is released. An answer
 *   without a refresh token keeps the one sent ([ComickTokenResponse.toOAuth]).
 * - **A refused grant is "sign in again", never retried.** A 400 or 401 from the token endpoint
 *   whose OAuth `error` is `invalid_grant`, or names no error that can be read, calls
 *   [Comick.setAuthExpired] and throws [TrackerAuthExpiredException], so the retry worker keeps the
 *   push for the next sign-in. So does a 2xx answer that does not read as a grant, including one whose
 *   body could not be read: Comick has already answered, so the refresh token sent may be retired.
 *   Upstream kept the old grant on any refused refresh and sent it on.
 * - **Any other refusal leaves the sign-in alone.** A 400 or 401 naming another code
 *   (`invalid_client`, `invalid_request` and the like) is a problem with the client or the request,
 *   which signing in again would not fix, and the refresh token was not spent. It is
 *   [ComickTokenRefreshFailed], an `IOException` the worker retries, as are any other answer and a
 *   refresh that never reached the endpoint.
 * - **A 401 from the API forces one refresh,** as MangaBaka's does: a token the clock still counts
 *   as live can be refused. The call is sent once more with the new token; a second 401 is its own
 *   answer.
 * - **The grant is read from the stored token on every call**, not held in a field loaded once, so a
 *   token restored from a backup or cleared by a sign-out is seen at once. A signed-out tracker
 *   throws an `IOException` rather than upstream's bare `Exception`.
 * - Expiry reads the tracker's clock; the token URL and client id are the tracker's for this call.
 * - Nothing here logs; no exception message names a token.
 */
class ComickInterceptor(
    private val comick: Comick,
    private val json: Json,
    private val nowEpochSeconds: () -> Long,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        if (comick.isAuthExpired) {
            throw TrackerAuthExpiredException(comick.id)
        }
        val originalRequest = chain.request()

        var oauth = comick.loadOAuth() ?: throw IOException("Comick: User is not authenticated")
        if (oauth.isExpired(nowEpochSeconds())) {
            oauth = refreshToken(chain)
        }

        val response = chain.proceed(originalRequest.authorized(oauth))
        if (response.code != 401) return response

        // A live token refused: one forced refresh, and the call once more.
        response.close()
        return chain.proceed(originalRequest.authorized(refreshToken(chain, refused = oauth.accessToken)))
    }

    private fun Request.authorized(oauth: ComickOAuth): Request = newBuilder()
        .header("Authorization", "Bearer ${oauth.accessToken}")
        .build()

    /**
     * The stored grant, refreshed when it has expired by the clock, or, with [refused], when its
     * access token is still the one the API just answered 401 to. A call that waited here for
     * another call's refresh finds a newer grant and uses it as it is.
     */
    private fun refreshToken(chain: Interceptor.Chain, refused: String? = null): ComickOAuth =
        synchronized(comick.grantLock) {
            if (comick.isAuthExpired) throw TrackerAuthExpiredException(comick.id)
            val oauth = comick.loadOAuth() ?: throw IOException("Comick: User is not authenticated")
            val stale = if (refused != null) oauth.accessToken == refused else oauth.isExpired(nowEpochSeconds())
            if (!stale) return@synchronized oauth

            val request = ComickApi.refreshTokenRequest(comick.endpoints().token, comick.clientId(), oauth.refreshToken)
            val response = try {
                chain.proceed(request)
            } catch (e: IOException) {
                throw ComickTokenRefreshFailed(e)
            }

            if (response.code == 400 || response.code == 401) {
                val error = response.use { oauthError(it) }
                if (error == null || error == INVALID_GRANT) {
                    comick.setAuthExpired()
                    throw TrackerAuthExpiredException(comick.id)
                }
                throw ComickTokenRefreshFailed(HttpException(response.code))
            }

            if (!response.isSuccessful) {
                response.close()
                throw ComickTokenRefreshFailed(HttpException(response.code))
            }

            val refreshed = runCatching {
                response.use { json.decodeFromString<ComickTokenResponse>(it.body.string()) }
                    .toOAuth(nowEpochSeconds(), previous = oauth)
            }.getOrElse {
                // The refresh token sent may be retired already: never send it again.
                comick.setAuthExpired()
                throw TrackerAuthExpiredException(comick.id)
            }
            refreshed.also { comick.saveOAuth(it) }
        }

    /** The OAuth `error` code a refused token request names, or null when its body names none. */
    private fun oauthError(response: Response): String? = runCatching {
        (json.parseToJsonElement(response.body.string()) as? JsonObject)?.get("error") as? JsonPrimitive
    }.getOrNull()?.takeIf { it.isString }?.content

    private companion object {
        const val INVALID_GRANT = "invalid_grant"
    }
}

class ComickTokenRefreshFailed(cause: Throwable? = null) : IOException("Comick: Failed to refresh account token", cause)

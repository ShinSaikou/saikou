// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/data/track/myanimelist/MyAnimeListInterceptor.kt (modified: yes)
package com.shinsaikou.tracking.myanimelist

import com.shinsaikou.tracking.TrackerAuthExpiredException
import com.shinsaikou.tracking.myanimelist.dto.MALOAuth
import eu.kanade.tachiyomi.network.HttpException
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * Modified (phase 3 unit T2):
 *
 * - **A refused refresh is "sign in again".** Any 400 or 401 from the token endpoint on refresh
 *   calls [MyAnimeList.setAuthExpired] and then throws [TrackerAuthExpiredException] (R95 item 15),
 *   so the retry worker keeps the push for the next sign-in instead of retrying it. Upstream handled
 *   401 alone, and MyAnimeList answers a refused refresh with 400 `invalid_grant`. Any other answer,
 *   and a refresh that never reached the endpoint, is [MALTokenRefreshFailed], an `IOException` the
 *   worker retries.
 * - **A 401 from the API forces one refresh.** A token the clock still counts as live can be refused
 *   (revoked early, or the device's clock is off); upstream refreshed only by the clock, so that call
 *   failed as a plain HTTP 401 until the owner signed out and in. The grant is refreshed once, and the
 *   call sent once more with the new token. Only the token endpoint's refusal marks the sign-in
 *   expired; a second 401 is the call's own answer.
 * - **The grant is read from the stored token on every call**, not held in a field loaded once: a
 *   token restored from a backup or cleared by a sign-out is seen at once. The single-flight refresh
 *   is upstream's: under the lock the stored grant is read again, so a call that waited for another
 *   call's refresh uses its token instead of refreshing a second time.
 * - Expiry reads the tracker's clock; the token URL and client id are the tracker's for this call
 *   (a test base's, or MyAnimeList's with the build's id); `MALTokenExpired` is replaced by T1's
 *   [TrackerAuthExpiredException]; the refresh's cause is kept on [MALTokenRefreshFailed].
 * - **Any other refused refresh carries its status** (phase 4 unit TC): an answer outside 2xx is
 *   [MALTokenRefreshFailed] with `HttpException(status)` as its cause, so `ErrorKey.httpCode` reads
 *   a token endpoint's 429 one cause deep; a 2xx that does not decode keeps its decoding failure.
 * - Nothing here logs; no exception message names a token.
 */
class MyAnimeListInterceptor(
    private val myanimelist: MyAnimeList,
    private val json: Json,
    private val nowEpochSeconds: () -> Long,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        if (myanimelist.isAuthExpired) {
            throw TrackerAuthExpiredException(myanimelist.id)
        }
        val originalRequest = chain.request()

        var oauth = myanimelist.loadOAuth()
        if (oauth?.isExpired(nowEpochSeconds()) == true) {
            oauth = refreshToken(chain)
        }

        if (oauth == null) {
            throw IOException("MAL: User is not authenticated")
        }

        val response = chain.proceed(originalRequest.authorized(oauth))
        if (response.code != 401) return response

        // A live token refused: one forced refresh, and the call once more.
        response.close()
        return chain.proceed(originalRequest.authorized(refreshToken(chain, refused = oauth.accessToken)))
    }

    // Add the authorization header to the original request
    private fun Request.authorized(oauth: MALOAuth): Request = newBuilder()
        .header("Authorization", "Bearer ${oauth.accessToken}")
        // TODO(antsy): Add back custom user agent when they stop blocking us for no apparent reason
        .build()

    /**
     * The stored grant, refreshed when it has expired by the clock, or, with [refused], when its
     * access token is still the one the API just answered 401 to. A call that waited here for
     * another call's refresh finds a newer grant and uses it as it is.
     */
    private fun refreshToken(chain: Interceptor.Chain, refused: String? = null): MALOAuth = synchronized(this) {
        if (myanimelist.isAuthExpired) throw TrackerAuthExpiredException(myanimelist.id)
        val oauth = myanimelist.loadOAuth() ?: throw IOException("MAL: User is not authenticated")
        val stale = if (refused != null) oauth.accessToken == refused else oauth.isExpired(nowEpochSeconds())
        if (!stale) return@synchronized oauth

        val request = MyAnimeListApi.refreshTokenRequest(myanimelist.endpoints().token, myanimelist.clientId(), oauth)
        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            throw MALTokenRefreshFailed(e)
        }

        if (response.code == 400 || response.code == 401) {
            response.close()
            myanimelist.setAuthExpired()
            throw TrackerAuthExpiredException(myanimelist.id)
        }

        if (!response.isSuccessful) {
            response.close()
            throw MALTokenRefreshFailed(HttpException(response.code))
        }

        runCatching {
            response.use { json.decodeFromString<MALOAuth>(it.body.string()) }
        }
            .getOrElse { throw MALTokenRefreshFailed(it) }
            .copy(createdAt = nowEpochSeconds())
            .also { myanimelist.saveOAuth(it) }
    }
}

class MALTitleNotApproved : IOException("MAL: This title can't be added because it is waiting for approval.")
class MALTokenRefreshFailed(cause: Throwable? = null) : IOException("MAL: Failed to refresh account token", cause)

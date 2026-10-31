package com.shinsaikou.tracking

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import logcat.LogPriority
import logcat.logcat
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

/**
 * The OkHttp client every tracker talks through (phase 3 unit T1, Q9's shape for T2), derived from
 * [NetworkHelper.client] so it shares its connection pool, dispatcher, timeouts, resolver and user
 * agent, with what is wrong for a tracker taken back out:
 *
 * - no [CloudflareInterceptor], because the retry worker pushes in the background with no screen
 *   to solve a challenge on;
 * - none of the shared client's logging, which prints every header unredacted, and a tracker's
 *   carry a bearer token;
 * - no cache, because a heuristically fresh answer would hide the service's own progress;
 * - no cookie jar, because a tracker's session has no business in the one the extensions share.
 *
 * Unlike `SyncHttpClient` it keeps the shared resolver, which is the owner's DNS over HTTPS choice
 * when one is set: sync took the system resolver for a LAN server's name, and trackers are public
 * hosts. It also keeps the shared two-minute call timeout, since no tracker call streams.
 *
 * Each tracker adds its own application interceptors on top (a bearer token and its refresh, for
 * an OAuth tracker) with `client.newBuilder()`. With verbose logging on when the client is built,
 * it logs each call at [HttpLoggingInterceptor.Level.BASIC] with `Authorization` redacted, through
 * logcat. Like [NetworkHelper], it reads the preference once.
 */
@Inject
@SingleIn(AppScope::class)
class TrackerHttpClient(networkHelper: NetworkHelper, networkPreferences: NetworkPreferences) {

    val client: OkHttpClient = networkHelper.client.newBuilder()
        .apply {
            interceptors().removeAll { it is CloudflareInterceptor || it is HttpLoggingInterceptor }
            networkInterceptors().removeAll { it is CloudflareInterceptor || it is HttpLoggingInterceptor }
        }
        .cache(null)
        .cookieJar(CookieJar.NO_COOKIES)
        .apply {
            if (networkPreferences.verboseLogging.get()) {
                addInterceptor(
                    HttpLoggingInterceptor { message -> logcat(TAG, LogPriority.DEBUG) { message } }.apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                        redactHeader(AUTHORIZATION)
                    },
                )
            }
        }
        .build()

    private companion object {
        const val TAG = "TrackerHttp"
        const val AUTHORIZATION = "Authorization"
    }
}

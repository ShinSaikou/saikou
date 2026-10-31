package com.shinsaikou.sync

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import logcat.LogPriority
import logcat.logcat
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import kotlin.time.Duration

/**
 * The one OkHttp client every sync backend talks through (phase 3 Q9), derived from
 * [NetworkHelper.client] so it shares its connection pool, dispatcher, timeouts and user agent,
 * with everything that is wrong for sync taken back out:
 *
 * - no [CloudflareInterceptor], because a background sync has no screen to solve a challenge on;
 * - none of the shared client's logging, which prints every header unredacted;
 * - no cache, because a heuristically fresh manifest would hide another device's push;
 * - no cookie jar, because sync credentials have no business in the one the extensions share;
 * - the system resolver, because a DNS over HTTPS provider refuses a LAN server's name;
 * - no call timeout, because a streamed apply outlives any fixed deadline. The connect and read
 *   timeouts are kept.
 *
 * A request carries its credentials as a [SyncAuthorization] tag, which an application interceptor
 * turns into the `Authorization` header. Never a network interceptor: OkHttp drops the header on a
 * redirect to another host only when it was present before its own redirect handling ran.
 *
 * With verbose logging on when the client is built, it logs each call at [HttpLoggingInterceptor.Level.BASIC]
 * with `Authorization` redacted, through logcat. Like [NetworkHelper], it reads the preference once.
 */
@Inject
@SingleIn(AppScope::class)
class SyncHttpClient(networkHelper: NetworkHelper, networkPreferences: NetworkPreferences) {

    val client: OkHttpClient = networkHelper.client.newBuilder()
        .apply {
            interceptors().removeAll { it is CloudflareInterceptor || it is HttpLoggingInterceptor }
            networkInterceptors().removeAll { it is CloudflareInterceptor || it is HttpLoggingInterceptor }
        }
        .cache(null)
        .cookieJar(CookieJar.NO_COOKIES)
        .dns(Dns.SYSTEM)
        .callTimeout(Duration.ZERO)
        .addInterceptor(AuthorizationInterceptor)
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

    private object AuthorizationInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val authorization = request.tag(SyncAuthorization::class) ?: return chain.proceed(request)
            return chain.proceed(request.newBuilder().header(AUTHORIZATION, authorization.headerValue).build())
        }
    }

    private companion object {
        const val TAG = "SyncHttp"
        const val AUTHORIZATION = "Authorization"
    }
}

/**
 * The `Authorization` value one sync request is sent with, supplied by the backend per request:
 * `Request.Builder().tag(SyncAuthorization::class, SyncAuthorization(Credentials.basic(user, password)))`.
 * A request without the tag is sent without the header.
 */
class SyncAuthorization(val headerValue: String) {
    override fun toString(): String = "SyncAuthorization(██)"
}

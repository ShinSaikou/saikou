// Fresh: a compile-only stub of the host extension ABI for the library update fixture, never packaged.
package eu.kanade.tachiyomi.network.interceptor

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

// The lib 1.4 form every published extension links against: RateLimitInterceptorKt.rateLimit with
// all three arguments, which both hosts keep through R8.
@Suppress("UNUSED_PARAMETER")
fun OkHttpClient.Builder.rateLimit(permits: Int, period: Long, unit: TimeUnit): OkHttpClient.Builder =
    throw RuntimeException("Stub!")

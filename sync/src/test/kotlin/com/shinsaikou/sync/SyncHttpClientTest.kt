package com.shinsaikou.sync

import android.content.Context
import com.shinsaikou.core.common.network.CloudflareSolveCoordinator
import com.shinsaikou.core.common.network.NetworkUserMessages
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.network.PREF_DOH_CLOUDFLARE
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import logcat.LogPriority
import logcat.LogcatLogger
import okhttp3.Credentials
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import java.io.File

/**
 * Q9's client, derived from the real [NetworkHelper] with every setting it would pass on switched
 * to the worst case for sync: verbose logging on, so the shared client carries its header logger,
 * and a DNS over HTTPS provider chosen, so it does not resolve through the system. Each check on
 * the derived client is paired with the same check on the shared one, which is what shows the
 * setting was there to be taken out.
 *
 * No request reaches the network. A recorder appended after the sync client's own interceptors
 * answers every call, so the authorization and logging interceptors run exactly as they would.
 */
class SyncHttpClientTest {

    @TempDir
    lateinit var cacheDir: File

    private val logged = mutableListOf<String>()

    private val capture = object : LogcatLogger {
        override fun isLoggable(priority: LogPriority, tag: String): Boolean = true
        override fun log(priority: LogPriority, tag: String, message: String) {
            logged += "$tag: $message"
        }
    }

    @BeforeEach
    fun installCapture() {
        if (!LogcatLogger.isInstalled) LogcatLogger.install()
        LogcatLogger.loggers += capture
    }

    @AfterEach
    fun removeCapture() {
        LogcatLogger.loggers -= capture
    }

    /** The owner's network settings at their worst for sync: DoH chosen, and verbose logging as asked. */
    private fun networkPreferences(verbose: Boolean): NetworkPreferences {
        val store = InMemoryPreferenceStore(
            sequenceOf(InMemoryPreference("doh_provider", PREF_DOH_CLOUDFLARE, -1)),
        )
        return NetworkPreferences(store, verboseLoggingDefault = verbose)
    }

    private fun networkHelper(preferences: NetworkPreferences): NetworkHelper {
        val context = mockk<Context>(relaxed = true)
        every { context.cacheDir } returns cacheDir
        return NetworkHelper(
            context,
            preferences,
            CloudflareSolveCoordinator(),
            mockk<NetworkUserMessages>(relaxed = true),
        )
    }

    private fun syncClient(verbose: Boolean): OkHttpClient {
        val preferences = networkPreferences(verbose)
        return SyncHttpClient(networkHelper(preferences), preferences).client
    }

    @Test
    fun `the derived client drops the challenge solver, the shared logger, the cache, the cookies, DoH and the call timeout`() {
        val shared = networkHelper(networkPreferences(verbose = true)).client
        shared.interceptors.filterIsInstance<CloudflareInterceptor>().shouldNotBeEmpty()
        shared.networkInterceptors.filterIsInstance<HttpLoggingInterceptor>().shouldNotBeEmpty()
        shared.cache.shouldNotBeNull()
        shared.cookieJar shouldNotBe CookieJar.NO_COOKIES
        shared.dns shouldNotBe Dns.SYSTEM
        shared.callTimeoutMillis shouldBe 120_000

        val sync = syncClient(verbose = true)

        (sync.interceptors + sync.networkInterceptors).filterIsInstance<CloudflareInterceptor>().shouldBeEmpty()
        // The only logger left is the sync client's own application interceptor, at BASIC.
        sync.networkInterceptors.shouldBeEmpty()
        val loggers = sync.interceptors.filterIsInstance<HttpLoggingInterceptor>()
        loggers shouldHaveSize 1
        loggers.single().level shouldBe HttpLoggingInterceptor.Level.BASIC
        sync.cache.shouldBeNull()
        sync.cookieJar shouldBeSameInstanceAs CookieJar.NO_COOKIES
        sync.dns shouldBeSameInstanceAs Dns.SYSTEM
        sync.callTimeoutMillis shouldBe 0
        sync.connectTimeoutMillis shouldBe shared.connectTimeoutMillis
        sync.readTimeoutMillis shouldBe shared.readTimeoutMillis
    }

    @Test
    fun `building the sync client leaves the shared client as it was`() {
        val preferences = networkPreferences(verbose = true)
        val helper = networkHelper(preferences)
        val shared = helper.client
        val interceptors = shared.interceptors.toList()
        val networkInterceptors = shared.networkInterceptors.toList()
        val cache = shared.cache
        val cookieJar = shared.cookieJar
        val dns = shared.dns

        SyncHttpClient(helper, preferences)

        helper.client shouldBeSameInstanceAs shared
        shared.interceptors shouldBe interceptors
        shared.networkInterceptors shouldBe networkInterceptors
        shared.cache shouldBeSameInstanceAs cache
        shared.cookieJar shouldBeSameInstanceAs cookieJar
        shared.dns shouldBeSameInstanceAs dns
        shared.callTimeoutMillis shouldBe 120_000
        shared.interceptors.filterIsInstance<CloudflareInterceptor>() shouldHaveSize 1
    }

    @Test
    fun `a request's authorization tag becomes its header, and a request without one carries none`() {
        val recorder = Recorder()
        val client = syncClient(verbose = false).newBuilder().addInterceptor(recorder).build()

        client.execute(authorized(Credentials.basic("owner", "hunter2")))
        client.execute(Request.Builder().url(URL).build())

        recorder.authorizations shouldBe listOf(Credentials.basic("owner", "hunter2"), null)
    }

    @Test
    fun `verbose logging logs each call with the authorization header redacted`() {
        val credential = Credentials.basic("owner", "hunter2")
        val sync = syncClient(verbose = true)
        val client = sync.newBuilder().addInterceptor(Recorder()).build()

        client.execute(authorized(credential))

        logged.joinToString("\n") shouldContain "--> GET $URL"
        logged.forEach { it shouldNotContain credential }

        // BASIC prints no header at all. Raised to HEADERS, every logger the client holds must
        // still print the header's name with its value blanked.
        logged.clear()
        sync.interceptors.filterIsInstance<HttpLoggingInterceptor>().forEach {
            it.level = HttpLoggingInterceptor.Level.HEADERS
        }
        client.execute(authorized(credential))

        logged shouldContain "SyncHttp: Authorization: ██"
        logged.forEach { it shouldNotContain credential }
    }

    @Test
    fun `with verbose logging off the client holds no logger and logs nothing`() {
        val sync = syncClient(verbose = false)

        (sync.interceptors + sync.networkInterceptors).filterIsInstance<HttpLoggingInterceptor>().shouldBeEmpty()
        sync.newBuilder().addInterceptor(Recorder()).build().execute(authorized(Credentials.basic("owner", "hunter2")))
        logged.filter { it.startsWith("SyncHttp") }.shouldBeEmpty()
    }

    private fun authorized(value: String): Request =
        Request.Builder().url(URL).tag(SyncAuthorization::class, SyncAuthorization(value)).build()

    private fun OkHttpClient.execute(request: Request) {
        newCall(request).execute().use { it.code shouldBe 200 }
    }

    /** Answers every call with an empty 200 and keeps the `Authorization` each one arrived with. */
    private class Recorder : Interceptor {
        val authorizations = mutableListOf<String?>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            authorizations += request.header("Authorization")
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ByteArray(0).toResponseBody())
                .build()
        }
    }

    private companion object {
        const val URL = "https://dav.example.invalid/saikou/manifest.json"
    }
}

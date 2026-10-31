package com.shinsaikou.tracking

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
import okhttp3.CookieJar
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
 * The trackers' client (phase 3 unit T1, Q9's shape), derived from the real [NetworkHelper] with
 * verbose logging on, so the shared client carries its header logger, and a DNS over HTTPS provider
 * chosen. Each check on the derived client is paired with the same check on the shared one, which
 * is what shows the setting was there to be taken out, or kept.
 *
 * No request reaches the network: a recorder appended after the client's own interceptors answers
 * every call, so the logging interceptor runs exactly as it would.
 */
class TrackerHttpClientTest {

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

    @Test
    fun `the derived client drops the challenge solver, the shared logger, the cache and the cookies, and keeps the resolver and timeouts`() {
        val preferences = networkPreferences(verbose = true)
        val helper = networkHelper(preferences)
        val shared = helper.client
        shared.interceptors.filterIsInstance<CloudflareInterceptor>().shouldNotBeEmpty()
        shared.networkInterceptors.filterIsInstance<HttpLoggingInterceptor>().shouldNotBeEmpty()
        shared.cache.shouldNotBeNull()
        shared.cookieJar shouldNotBe CookieJar.NO_COOKIES

        val tracker = TrackerHttpClient(helper, preferences).client

        (tracker.interceptors + tracker.networkInterceptors).filterIsInstance<CloudflareInterceptor>().shouldBeEmpty()
        // The only logger left is the tracker client's own application interceptor, at BASIC.
        tracker.networkInterceptors.shouldBeEmpty()
        val loggers = tracker.interceptors.filterIsInstance<HttpLoggingInterceptor>()
        loggers shouldHaveSize 1
        loggers.single().level shouldBe HttpLoggingInterceptor.Level.BASIC
        tracker.cache.shouldBeNull()
        tracker.cookieJar shouldBeSameInstanceAs CookieJar.NO_COOKIES
        // Kept on purpose: the owner's resolver choice, and the shared timeouts, call timeout included.
        tracker.dns shouldBeSameInstanceAs shared.dns
        tracker.callTimeoutMillis shouldBe shared.callTimeoutMillis
        tracker.connectTimeoutMillis shouldBe shared.connectTimeoutMillis
        tracker.readTimeoutMillis shouldBe shared.readTimeoutMillis
    }

    @Test
    fun `building the tracker client leaves the shared client as it was`() {
        val preferences = networkPreferences(verbose = true)
        val helper = networkHelper(preferences)
        val shared = helper.client
        val interceptors = shared.interceptors.toList()
        val networkInterceptors = shared.networkInterceptors.toList()
        val cache = shared.cache
        val cookieJar = shared.cookieJar

        TrackerHttpClient(helper, preferences)

        helper.client shouldBeSameInstanceAs shared
        shared.interceptors shouldBe interceptors
        shared.networkInterceptors shouldBe networkInterceptors
        shared.cache shouldBeSameInstanceAs cache
        shared.cookieJar shouldBeSameInstanceAs cookieJar
        shared.interceptors.filterIsInstance<CloudflareInterceptor>() shouldHaveSize 1
    }

    @Test
    fun `verbose logging logs each call with the authorization header redacted`() {
        val preferences = networkPreferences(verbose = true)
        val tracker = TrackerHttpClient(networkHelper(preferences), preferences).client
        val client = tracker.newBuilder().addInterceptor(Recorder()).build()

        client.execute(authorized())

        logged.joinToString("\n") shouldContain "--> GET $URL"
        logged.forEach { it shouldNotContain TOKEN }

        // BASIC prints no header at all. Raised to HEADERS, every logger the client holds must
        // still print the header's name with its value blanked.
        logged.clear()
        tracker.interceptors.filterIsInstance<HttpLoggingInterceptor>().forEach {
            it.level = HttpLoggingInterceptor.Level.HEADERS
        }
        client.execute(authorized())

        logged shouldContain "TrackerHttp: Authorization: ██"
        logged.forEach { it shouldNotContain TOKEN }
    }

    @Test
    fun `with verbose logging off the client holds no logger and logs nothing`() {
        val preferences = networkPreferences(verbose = false)
        val tracker = TrackerHttpClient(networkHelper(preferences), preferences).client

        (tracker.interceptors + tracker.networkInterceptors).filterIsInstance<HttpLoggingInterceptor>().shouldBeEmpty()
        tracker.newBuilder().addInterceptor(Recorder()).build().execute(authorized())
        logged.filter { it.startsWith("TrackerHttp") }.shouldBeEmpty()
    }

    private fun authorized(): Request = Request.Builder().url(URL).header("Authorization", "Bearer $TOKEN").build()

    private fun OkHttpClient.execute(request: Request) {
        newCall(request).execute().use { it.code shouldBe 200 }
    }

    /** Answers every call with an empty 200. */
    private class Recorder : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response = Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(ByteArray(0).toResponseBody())
            .build()
    }

    private companion object {
        const val URL = "https://tracker.example.invalid/v2/users/@me"
        const val TOKEN = "access-token-that-must-not-be-logged"
    }
}

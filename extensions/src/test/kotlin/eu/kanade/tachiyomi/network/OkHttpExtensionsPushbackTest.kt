// Fresh: exercises the vendored awaitSuccess and asObservableSuccess, which now build HttpException
// from the response so its Retry-After and cf-mitigated headers reach the library update (site-rate-limit).
package eu.kanade.tachiyomi.network

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The host's two success checks build the exception from the response, so the library update can
 * read what a refusal said about being asked again. The client's only interceptor fabricates the
 * answer; nothing touches a network.
 */
class OkHttpExtensionsPushbackTest {

    private fun clientAnswering(code: Int, vararg headers: Pair<String, String>) = OkHttpClient.Builder()
        .addInterceptor(
            Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("Fixture")
                    .apply { headers.forEach { (name, value) -> header(name, value) } }
                    .body("".toResponseBody())
                    .build()
            },
        )
        .build()

    private val request = Request.Builder().url("https://example.invalid/manga").build()

    @Test
    fun `awaitSuccess throws an HttpException carrying a 429's Retry-After`() = runTest {
        val call = clientAnswering(429, "Retry-After" to "5").newCall(request)

        val thrown = shouldThrow<HttpException> { call.awaitSuccess() }

        thrown.code shouldBe 429
        thrown.pushback?.retryAfter shouldBe 5.seconds
        thrown.pushback?.challenge shouldBe false
    }

    @Test
    fun `awaitSuccess throws an HttpException carrying a Cloudflare challenge mark`() = runTest {
        val call = clientAnswering(403, "cf-mitigated" to "challenge").newCall(request)

        val thrown = shouldThrow<HttpException> { call.awaitSuccess() }

        thrown.code shouldBe 403
        thrown.pushback?.challenge shouldBe true
    }

    @Test
    fun `awaitSuccess on a refusal that named nothing leaves the pushback null`() = runTest {
        val thrown = shouldThrow<HttpException> { clientAnswering(500).newCall(request).awaitSuccess() }

        thrown.code shouldBe 500
        thrown.pushback shouldBe null
    }

    @Test
    fun `awaitSuccess returns a successful response unchanged`() = runTest {
        clientAnswering(200).newCall(request).awaitSuccess().use { it.code shouldBe 200 }
    }

    @Suppress("DEPRECATION")
    @Test
    fun `asObservableSuccess errors with an HttpException carrying a 429's Retry-After`() {
        val call = clientAnswering(429, "Retry-After" to "5").newCall(request)

        val thrown = runCatching { call.asObservableSuccess().toBlocking().single() }.exceptionOrNull()

        (thrown as HttpException).code shouldBe 429
        thrown.pushback?.retryAfter shouldBe 5.seconds
    }
}

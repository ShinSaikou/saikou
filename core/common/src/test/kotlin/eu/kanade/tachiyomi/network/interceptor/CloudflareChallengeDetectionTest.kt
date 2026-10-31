// Fresh: written for Saikou. Placed in the vendored package so it can read the internal challenge check.
package eu.kanade.tachiyomi.network.interceptor

import io.kotest.matchers.shouldBe
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test

/**
 * What [CloudflareInterceptor] solves: a challenge page, told apart by its `cf-mitigated` header
 * rather than by element ids in its HTML, which an interactive challenge page does not carry.
 */
class CloudflareChallengeDetectionTest {

    private fun response(code: Int, vararg headers: Pair<String, String>, body: String = "<html></html>") =
        Response.Builder()
            .request(Request.Builder().url("https://example.invalid/a").build())
            .protocol(Protocol.HTTP_2)
            .code(code)
            .message("")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .body(body.toResponseBody())
            .build()

    @Test
    fun `a challenge page is detected by its header, whatever its body`() {
        isCloudflareChallenge(response(403, "Server" to "cloudflare", "cf-mitigated" to "challenge")) shouldBe true
        isCloudflareChallenge(response(503, "Server" to "cloudflare", "CF-Mitigated" to "Challenge")) shouldBe true
    }

    @Test
    fun `a Cloudflare error without the header is the site's own answer`() {
        // Whatever the markup says: a block page is never worth a solve.
        val geoBlock = response(
            403,
            "Server" to "cloudflare",
            body = "<div id=\"challenge-error-title\"></div><div id=\"challenge-error-text\"></div>",
        )
        isCloudflareChallenge(geoBlock) shouldBe false
        isCloudflareChallenge(response(403, "Server" to "nginx", "cf-mitigated" to "challenge")) shouldBe false
        isCloudflareChallenge(response(200, "Server" to "cloudflare")) shouldBe false
    }

    @Test
    fun `a challenge to a request other than GET is solved on the site's root`() {
        val post = Request.Builder()
            .url("https://example.invalid:8443/api/search?q=1#top")
            .post("q=1".toRequestBody())
            .build()
        val get = Request.Builder().url("https://example.invalid/manga/1?page=2").build()

        challengeUrlOf(post) shouldBe "https://example.invalid:8443/"
        challengeUrlOf(get) shouldBe "https://example.invalid/manga/1?page=2"
    }
}

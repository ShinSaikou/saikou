// Fresh: written for Saikou. Placed in the vendored package so it can read the internal logger factory.
package eu.kanade.tachiyomi.network

import com.shinsaikou.core.common.network.FixtureInterceptor
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.string.shouldNotContain
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Test

/** What the verbose logging setting writes: every header's name, but no credential or cookie. */
class VerboseLoggingTest {

    @Test
    fun `credentials and cookies are redacted both ways, other headers are logged`() {
        val lines = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor(verboseLoggingInterceptor(lines::add))
            .addInterceptor(
                FixtureInterceptor(
                    mapOf(
                        FixtureInterceptor.Key("GET", URL) to FixtureInterceptor.Fixture(
                            body = "{}",
                            headers = mapOf("Set-Cookie" to "session=set-secret"),
                        ),
                    ),
                ),
            )
            .build()
        val request = Request.Builder()
            .url(URL)
            .header("Authorization", "Bearer token-secret")
            .header("Auth", "auth-secret")
            .header("Cookie", "cf_clearance=cookie-secret")
            .header("X-Api-Key", "key-secret")
            .header("X-Auth-Token", "token-header-secret")
            .header("Proxy-Authorization", "Basic proxy-secret")
            .header("Accept", "application/json")
            .build()

        client.newCall(request).execute().close()

        lines shouldContain "Accept: application/json"
        lines shouldContain "Authorization: ██"
        lines shouldContain "Set-Cookie: ██"
        lines shouldNotContain "Cookie: cf_clearance=cookie-secret"
        lines.joinToString("\n").let { log ->
            listOf(
                "token-secret",
                "auth-secret",
                "cookie-secret",
                "set-secret",
                "key-secret",
                "token-header-secret",
                "proxy-secret",
            ).forEach { log shouldNotContain it }
        }
    }

    private companion object {
        const val URL = "https://example.invalid/api"
    }
}

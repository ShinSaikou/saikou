package com.shinsaikou.core.common.network

import eu.kanade.tachiyomi.network.HttpException
import io.kotest.matchers.shouldBe
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** What a refusal said about being asked again: the `Retry-After` wait and Cloudflare's challenge mark. */
class PushbackTest {

    private fun response(code: Int = 429, receivedAtMillis: Long = 0L, vararg headers: Pair<String, String>): Response =
        Response.Builder()
            .request(Request.Builder().url("https://example.invalid/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("Fixture")
            .receivedResponseAtMillis(receivedAtMillis)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()

    // region parseRetryAfter

    @Test
    fun `a Retry-After in whole seconds is that many seconds`() {
        Pushback.parseRetryAfter("5", now = null, receivedAtMillis = 0L) shouldBe 5.seconds
        Pushback.parseRetryAfter("0", now = null, receivedAtMillis = 0L) shouldBe 0.seconds
    }

    @Test
    fun `whitespace around the seconds is ignored`() {
        Pushback.parseRetryAfter("  12 \t", now = null, receivedAtMillis = 0L) shouldBe 12.seconds
    }

    @Test
    fun `a huge number of seconds is clamped to a day`() {
        Pushback.parseRetryAfter("999999999", now = null, receivedAtMillis = 0L) shouldBe 86_400.seconds
        // Too long even for a Long: still a day, not null and not an overflow.
        Pushback.parseRetryAfter("99999999999999999999999999", now = null, receivedAtMillis = 0L) shouldBe 86_400.seconds
    }

    @Test
    fun `an HTTP date is measured from the Date header of the same response`() {
        val wait = Pushback.parseRetryAfter(
            value = "Wed, 21 Oct 2026 07:28:30 GMT",
            now = "Wed, 21 Oct 2026 07:28:00 GMT",
            // The device clock is wildly off; the response's own Date must win.
            receivedAtMillis = 12_345L,
        )

        wait shouldBe 30.seconds
    }

    @Test
    fun `an HTTP date with no Date header is measured from when the response arrived`() {
        val receivedAt = java.time.Instant.parse("2026-10-21T07:28:00Z").toEpochMilli()

        val wait = Pushback.parseRetryAfter("Wed, 21 Oct 2026 07:28:45 GMT", now = null, receivedAtMillis = receivedAt)

        wait shouldBe 45_000.milliseconds
    }

    @Test
    fun `an unreadable Date header falls back to when the response arrived`() {
        val receivedAt = java.time.Instant.parse("2026-10-21T07:28:00Z").toEpochMilli()

        val wait = Pushback.parseRetryAfter("Wed, 21 Oct 2026 07:28:10 GMT", now = "garbage", receivedAtMillis = receivedAt)

        wait shouldBe 10.seconds
    }

    @Test
    fun `an HTTP date already past is no wait at all`() {
        val wait = Pushback.parseRetryAfter(
            value = "Wed, 21 Oct 2026 07:27:00 GMT",
            now = "Wed, 21 Oct 2026 07:28:00 GMT",
            receivedAtMillis = 0L,
        )

        wait shouldBe 0.seconds
    }

    @Test
    fun `a value that is neither seconds nor a date is null`() {
        Pushback.parseRetryAfter("soon", now = null, receivedAtMillis = 0L) shouldBe null
        Pushback.parseRetryAfter("", now = null, receivedAtMillis = 0L) shouldBe null
        Pushback.parseRetryAfter("-5", now = null, receivedAtMillis = 0L) shouldBe null
        Pushback.parseRetryAfter("1.5", now = null, receivedAtMillis = 0L) shouldBe null
    }

    // endregion

    // region of

    @Test
    fun `a cf-mitigated challenge header is a challenge, whatever its case`() {
        Pushback.of(response(403, headers = arrayOf("cf-mitigated" to "challenge"))) shouldBe
            Pushback(retryAfter = null, challenge = true)
        Pushback.of(response(403, headers = arrayOf("CF-Mitigated" to "Challenge"))) shouldBe
            Pushback(retryAfter = null, challenge = true)
    }

    @Test
    fun `a cf-mitigated header with another value is not a challenge`() {
        Pushback.of(response(403, headers = arrayOf("cf-mitigated" to "block"))) shouldBe null
    }

    @Test
    fun `a Retry-After alone gives a wait and no challenge`() {
        Pushback.of(response(headers = arrayOf("Retry-After" to "7"))) shouldBe
            Pushback(retryAfter = 7.seconds, challenge = false)
    }

    @Test
    fun `both headers are both kept`() {
        Pushback.of(response(403, headers = arrayOf("Retry-After" to "3", "cf-mitigated" to "challenge"))) shouldBe
            Pushback(retryAfter = 3.seconds, challenge = true)
    }

    @Test
    fun `a response with neither header has no pushback`() {
        Pushback.of(response()) shouldBe null
    }

    @Test
    fun `an unreadable Retry-After with no challenge has no pushback`() {
        Pushback.of(response(headers = arrayOf("Retry-After" to "soon"))) shouldBe null
    }

    @Test
    fun `a date Retry-After is measured from the response's Date header`() {
        val pushback = Pushback.of(
            response(
                headers = arrayOf(
                    "Retry-After" to "Wed, 21 Oct 2026 07:28:20 GMT",
                    "Date" to "Wed, 21 Oct 2026 07:28:00 GMT",
                ),
            ),
        )

        pushback?.retryAfter shouldBe 20.seconds
    }

    // endregion

    // region HttpException

    @Test
    fun `an HttpException built from a response carries its pushback and the plain message`() {
        val error = HttpException(response(429, headers = arrayOf("Retry-After" to "5")))

        error.code shouldBe 429
        error.pushback shouldBe Pushback(retryAfter = 5.seconds, challenge = false)
        error.message shouldBe "HTTP error 429"
    }

    @Test
    fun `an HttpException built from a response with no headers has no pushback`() {
        HttpException(response(500)).pushback shouldBe null
    }

    @Test
    fun `an HttpException built from a code alone has no pushback and the plain message`() {
        val error = HttpException(403)

        error.pushback shouldBe null
        error.message shouldBe "HTTP error 403"
    }

    // endregion
}

package com.shinsaikou.core.common.network

import okhttp3.Response
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What a failed response said about being asked again, kept on the
 * [eu.kanade.tachiyomi.network.HttpException] the host builds from it so the library update can
 * read it after the response is gone.
 *
 * [retryAfter] is the wait its `Retry-After` header names, never negative, or null when it named
 * none or named something unreadable. [challenge] is whether it carried `cf-mitigated: challenge`,
 * the header Cloudflare documents as the way to tell its challenge page from the site's own answer
 * (upstream v0.21.0's `CloudflareInterceptor` reads the same header since `ac249e366`).
 */
data class Pushback(val retryAfter: Duration?, val challenge: Boolean) {

    companion object {

        /** The response's pushback, or null when it carried neither header. */
        fun of(response: Response): Pushback? {
            val retryAfter = response.header("Retry-After")?.let {
                parseRetryAfter(it, now = response.header("Date"), receivedAtMillis = response.receivedResponseAtMillis)
            }
            val challenge = response.header("cf-mitigated").equals("challenge", ignoreCase = true)
            return if (retryAfter == null && !challenge) null else Pushback(retryAfter, challenge)
        }

        /**
         * A `Retry-After` value as a wait (RFC 9110 section 10.2.3): either whole seconds or an
         * HTTP date. A date is measured from the response's own `Date` header [now] when it has a
         * readable one, so a device clock that is off does not move it, and from [receivedAtMillis],
         * the device's wall clock when the response arrived, otherwise. A date already past is no
         * wait at all.
         */
        fun parseRetryAfter(value: String, now: String?, receivedAtMillis: Long): Duration? {
            val trimmed = value.trim()
            if (trimmed.isNotEmpty() && trimmed.all { it in '0'..'9' }) {
                // Anything past a day is as good as forever to a caller that caps the wait anyway.
                return trimmed.toLongOrNull()?.coerceAtMost(MAX_SECONDS)?.seconds ?: MAX_SECONDS.seconds
            }
            val at = httpDateMillis(trimmed) ?: return null
            val from = now?.let(::httpDateMillis) ?: receivedAtMillis
            return (at - from).coerceAtLeast(0L).milliseconds
        }

        private fun httpDateMillis(value: String): Long? = try {
            ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }

        private const val MAX_SECONDS = 86_400L
    }
}

package com.shinsaikou.sync.google

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Where the app sends every Google call (phase 3 unit O): the authorization page the browser opens,
 * the token and revoke endpoints, and the base Drive v3 and its upload path resolve against. The
 * app graph binds one instance through [forBuild].
 *
 * The sync fixture serves the same paths (R83 item 1), so a test instance swaps only the scheme,
 * host and port of all four at once and keeps every path; the code under test is the code that
 * ships.
 */
class GoogleEndpoints private constructor(
    val authorization: HttpUrl,
    val token: HttpUrl,
    val revoke: HttpUrl,
    /** Ends in `/`: `drive/v3/files` and `upload/drive/v3/files` resolve against it. */
    val driveBase: HttpUrl,
) {

    /** Whether these are Google's own endpoints rather than a test base's. */
    val isGoogle: Boolean get() = this === GOOGLE

    override fun toString(): String = if (isGoogle) "GoogleEndpoints(Google)" else "GoogleEndpoints(test base ${driveBase})"

    companion object {

        /** Google's own endpoints, the only ones a release build uses. */
        val GOOGLE = GoogleEndpoints(
            authorization = "https://accounts.google.com/o/oauth2/v2/auth".toHttpUrl(),
            token = "https://oauth2.googleapis.com/token".toHttpUrl(),
            revoke = "https://oauth2.googleapis.com/revoke".toHttpUrl(),
            driveBase = "https://www.googleapis.com/".toHttpUrl(),
        )

        /**
         * The endpoints a build uses. A release build ([debug] false) uses [GOOGLE] whatever
         * [testBase] says. A debug build, the one instrumentation runs against, uses [at] the test
         * base when one is given and [GOOGLE] when it is absent or blank (R29: blank is absent).
         * A test base that is not an `http://` or `https://` address is refused, never read as absent,
         * so a mistyped argument cannot send a test's sign-in to Google.
         */
        fun forBuild(debug: Boolean, testBase: String?): GoogleEndpoints {
            if (!debug || testBase.isNullOrBlank()) return GOOGLE
            val base = requireNotNull(testBase.trim().toHttpUrlOrNull()) { "Not an http:// or https:// test base" }
            return at(base)
        }

        /** [GOOGLE]'s four paths on [base]'s scheme, host and port. */
        fun at(base: HttpUrl): GoogleEndpoints {
            fun HttpUrl.moved(): HttpUrl = newBuilder().scheme(base.scheme).host(base.host).port(base.port).build()
            return GoogleEndpoints(
                authorization = GOOGLE.authorization.moved(),
                token = GOOGLE.token.moved(),
                revoke = GOOGLE.revoke.moved(),
                driveBase = GOOGLE.driveBase.moved(),
            )
        }
    }
}

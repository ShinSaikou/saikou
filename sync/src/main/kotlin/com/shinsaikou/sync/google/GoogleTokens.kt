package com.shinsaikou.sync.google

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The Desktop app client the owner registers (Q1, owner item 2), from the build's
 * `saikou.drive.client.id` and `saikou.drive.client.secret`. Google documents an installed app's
 * secret as not confidential; it still stays out of [toString] and every log.
 */
class GoogleClientCredentials(val clientId: String, val clientSecret: String) {

    /** Both present. A build without them has no Drive sign-in to offer. */
    val isConfigured: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    override fun toString(): String = "GoogleClientCredentials(configured=$isConfigured)"

    companion object {
        /**
         * The sync fixture's default test client (`sync_fixture_server.py` DEFAULT_CLIENT_ID and
         * DEFAULT_CLIENT_SECRET), public test values: what `GoogleOAuthClient` sends whenever its
         * endpoints are a test base, so the build's own client never leaves for anything but Google.
         */
        val TEST_BASE = GoogleClientCredentials(
            clientId = "fixture-desktop-client.apps.googleusercontent.com",
            clientSecret = "fixture-desktop-client-secret",
        )
    }
}

/** An access token and the moment it stops working, from the injected clock at the time it arrived. */
class GoogleAccessToken(val value: String, val expiresAt: Instant) {

    /**
     * Whether [now] is within [EXPIRY_MARGIN] of [expiresAt] or past it, so a call made now could
     * reach Drive with a token that expired on the way.
     */
    fun isExpired(now: Instant): Boolean = now >= expiresAt - EXPIRY_MARGIN

    override fun toString(): String = "GoogleAccessToken(██, expiresAt=$expiresAt)"

    companion object {
        val EXPIRY_MARGIN: Duration = 60.seconds
    }
}

/**
 * What one token response carried. [refreshToken] is present on a code exchange made with
 * `access_type=offline` and `prompt=consent`, and absent on a refresh, since Google does not rotate
 * it. [refreshTokenExpiresAt] is present only when the response carried `refresh_token_expires_in`,
 * which Google sends for a grant that will lapse (a project in Testing, or a time-limited grant).
 *
 * The caller stores what it keeps (H stores the refresh token in `SecretStore`); nothing here does.
 */
class GoogleTokens(
    val accessToken: GoogleAccessToken,
    val refreshToken: String?,
    val refreshTokenExpiresAt: Instant?,
    val scope: String?,
) {
    override fun toString(): String =
        "GoogleTokens(access=$accessToken, refreshToken=${if (refreshToken == null) "absent" else "██"}, " +
            "refreshTokenExpiresAt=$refreshTokenExpiresAt, scope=$scope)"
}

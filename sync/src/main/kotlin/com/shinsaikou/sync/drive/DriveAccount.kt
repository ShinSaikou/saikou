package com.shinsaikou.sync.drive

import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.core.common.oauth.Pkce
import com.shinsaikou.sync.SecretStore
import com.shinsaikou.sync.SyncHttpClient
import com.shinsaikou.sync.SyncSignedOutException
import com.shinsaikou.sync.google.GoogleAccessToken
import com.shinsaikou.sync.google.GoogleAuthRefusedException
import com.shinsaikou.sync.google.GoogleInvalidGrantException
import com.shinsaikou.sync.google.GoogleOAuthClient
import com.shinsaikou.sync.google.GoogleTokens
import com.shinsaikou.sync.google.GoogleUnavailableException
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import logcat.LogPriority
import logcat.logcat
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The Google Drive sign-in (plan unit H), on [com.shinsaikou.sync.webdav.WebDavAccount]'s pattern: the
 * refresh token, its expiry when Google gave one, the account's email address and its
 * `permissionId` live in [SecretStore] under [REFRESH_TOKEN], [REFRESH_TOKEN_EXPIRES_AT], [EMAIL] and
 * [ACCOUNT_ID], names that never change once shipped (R84 item 1), and a backend is built from them
 * only while the three required ones can be read and this build offers Drive ([DriveAvailability]).
 *
 * The access token lives only in memory, with the expiry the injected [clock] gave it, and is
 * refreshed 60 s before that ([GoogleAccessToken.EXPIRY_MARGIN]), one refresh at a time, so
 * concurrent calls share one. A refresh retries a token endpoint that did not answer within item 3's
 * budget ([DriveBackoff]); `invalid_grant` is [SyncSignedOutException] with Google's refusal as its
 * cause, and the secrets stay until the owner signs in again or out; any other refusal is
 * [GoogleAuthRefusedException] as itself, Q1's standing fallback. A refresh answer that carries a
 * refresh token is stored before its access token is used.
 *
 * Every secret store call is Keystore IPC and runs on the IO dispatcher (R84 item 4). Clearing the
 * backend's cursors and the chosen backend on sign-out is `SyncSession`'s work, not this class's.
 */
@Inject
@SingleIn(AppScope::class)
class DriveAccount(
    private val secrets: SecretStore,
    private val oauthClient: () -> GoogleOAuthClient,
    private val httpClient: SyncHttpClient,
    private val availability: DriveAvailability,
    private val clock: Clock,
    private val backoff: DriveBackoff,
    private val dispatchers: Dispatchers,
) {

    /** Held by every refresh of the stored grant, a sign-in's store and a sign-out. */
    private val lock = Mutex()

    @Volatile
    private var access: GoogleAccessToken? = null

    /**
     * A refresh token Google rotated to that the store refused, under [lock]: this process refreshes
     * with it from then on, and the next refresh stores it before anything else. A process that dies
     * first falls back to the stored one, which Google may have retired: that run is signed out.
     */
    private var rotated: String? = null

    /** How long a sign-out waits for Google's revoke before it deletes the secrets anyway. */
    internal var revokeTimeout: Duration = REVOKE_TIMEOUT

    /** What signs the stored sign-in's Drive calls: [access], refreshed under [lock]. */
    private val stored = object : DriveAuthorization {
        override suspend fun accessToken(): String = fresh(rejected = null) ?: lock.withLock { fresh(rejected = null) ?: refreshStored() }

        override suspend fun refreshed(rejected: String): String = lock.withLock { fresh(rejected) ?: refreshStored() }
    }

    /**
     * Google's authorization page for one sign-in, from the client [signIn] exchanges the code with:
     * the loopback [redirectUri] and [state] of O's listener, and [pkce]'s challenge.
     */
    fun authorizationUrl(redirectUri: String, state: String, pkce: Pkce): HttpUrl =
        oauthClient().authorizationUrl(redirectUri, state, pkce)

    /**
     * Exchanges [code] from Google's authorization page (O's `BrowserSignIn` in the app, or a test's
     * plain request to the fixture's authorization endpoint) for a grant, reads the account with
     * `about.get`, and only then stores the four secrets. Returns the account's email address.
     *
     * Throws what the exchange throws ([GoogleAuthRefusedException] for a refusal or an answer without
     * a refresh token, Q1's fallback; [GoogleInvalidGrantException] for a used or expired code;
     * [GoogleUnavailableException]), what `about.get` throws, and what the store throws; nothing is
     * left stored after a failure. The code is used once, so the exchange is not retried.
     */
    suspend fun signIn(code: String, pkce: Pkce, redirectUri: String): String {
        val oauth = oauthClient()
        val grant = PendingGrant(oauth, oauth.exchange(code, pkce, redirectUri))
        val user = DriveHttp(oauth.endpoints, grant, httpClient.client, dispatchers.io, backoff).user()
        lock.withLock {
            withContext(dispatchers.io) {
                try {
                    // A cancel between two writes is seen at the next one, so no half sign-in stays.
                    secrets.put(REFRESH_TOKEN, grant.refreshToken)
                    ensureActive()
                    val expiresAt = grant.refreshTokenExpiresAt
                    if (expiresAt == null) secrets.delete(REFRESH_TOKEN_EXPIRES_AT) else secrets.put(REFRESH_TOKEN_EXPIRES_AT, expiresAt.millis())
                    ensureActive()
                    secrets.put(EMAIL, user.email)
                    ensureActive()
                    secrets.put(ACCOUNT_ID, user.permissionId)
                    ensureActive()
                } catch (e: Exception) {
                    // A cancellation too: the cleanup runs regardless, and the failure or the
                    // cancellation is rethrown unchanged. A delete can fail as the put did (the
                    // store commits each write); the first failure is the one the sign-in reports.
                    withContext(NonCancellable) {
                        NAMES.forEach { name ->
                            try {
                                secrets.delete(name)
                            } catch (cleanup: Exception) {
                                e.addSuppressed(cleanup)
                            }
                        }
                    }
                    throw e
                }
            }
            access = grant.access
            rotated = null
        }
        return user.email
    }

    /**
     * Revokes the stored grant (or a rotated one the store refused), best effort and within
     * [revokeTimeout] (a failure or a timeout is logged by its class name only and does not stop the
     * sign-out), then deletes the four secrets. Nothing is revoked without a refresh token. The remote files and the local library are untouched.
     */
    suspend fun signOut() {
        lock.withLock {
            access = null
            val pending = rotated
            rotated = null
            withContext(dispatchers.io) {
                (pending ?: secrets.get(REFRESH_TOKEN))?.let { revoke(it) }
                NAMES.forEach(secrets::delete)
            }
        }
    }

    /**
     * The backend the stored sign-in reaches, or null when signed out: a required secret absent, or
     * one the store could not read (which it deletes, R84 item 3), or a build that does not offer
     * Drive ([DriveAvailability]).
     */
    suspend fun backend(): DriveSyncBackend? = backend(
        pageSize = DriveSyncBackend.PAGE_SIZE,
        multipartLimit = DriveSyncBackend.MULTIPART_LIMIT,
        chunkSize = DriveSyncBackend.CHUNK_SIZE,
    )

    /** [backend] with a test's page, multipart and chunk sizes, and a test's client under the sync client's. */
    internal suspend fun backend(
        pageSize: Int,
        multipartLimit: Long,
        chunkSize: Int,
        client: OkHttpClient = httpClient.client,
    ): DriveSyncBackend? {
        if (!availability.offered) return null
        val accountId = withContext(dispatchers.io) {
            if (secrets.get(REFRESH_TOKEN) == null || secrets.get(EMAIL) == null) null else secrets.get(ACCOUNT_ID)
        } ?: return null
        val oauth = oauthClient()
        return DriveSyncBackend(
            id = DriveSyncBackend.idFor(oauth.clientId, accountId),
            http = DriveHttp(oauth.endpoints, stored, client, dispatchers.io, backoff),
            pageSize = pageSize,
            multipartLimit = multipartLimit,
            chunkSize = chunkSize,
        )
    }

    /**
     * The stored account's email address, for Sync settings to show, or null when it is absent or
     * cannot be read. It never reads a token (G's ruling draft item 9), and it says nothing about
     * whether the grant still works: [backend] is what a run uses.
     */
    suspend fun signedInAs(): String? = withContext(dispatchers.io) { secrets.get(EMAIL) }

    /**
     * When Google said the stored grant lapses, in epoch milliseconds, or null when no token response
     * carried `refresh_token_expires_in`. Sync settings says when sign-in will expire.
     */
    suspend fun refreshTokenExpiresAt(): Long? = withContext(dispatchers.io) { secrets.get(REFRESH_TOKEN_EXPIRES_AT)?.toLongOrNull() }

    /** The access token in memory, unless it is [rejected] or within the margin of its expiry. */
    private fun fresh(rejected: String?): String? =
        access?.takeIf { it.value != rejected && !it.isExpired(clock.now()) }?.value

    /**
     * A new access token for the stored grant. The caller holds [lock]. A [rotated] token is stored
     * first; a rotated token in the answer is kept in [rotated] before it is stored, so a store that
     * refuses it fails the run with the store's failure and leaves no access token in memory, and
     * the next call's refresh stores it and refreshes with it.
     */
    private suspend fun refreshStored(): String {
        val oauth = oauthClient()
        val refreshToken = withContext(dispatchers.io) {
            rotated?.also { secrets.put(REFRESH_TOKEN, it) } ?: secrets.get(REFRESH_TOKEN)
        } ?: throw SyncSignedOutException("No Google Drive sign-in is stored")
        rotated = null
        val tokens = refresh(oauth, refreshToken)
        tokens.refreshToken?.let { rotated = it }
        withContext(dispatchers.io) {
            tokens.refreshToken?.let { secrets.put(REFRESH_TOKEN, it) }
            tokens.refreshTokenExpiresAt?.let { secrets.put(REFRESH_TOKEN_EXPIRES_AT, it.millis()) }
        }
        rotated = null
        access = tokens.accessToken
        return tokens.accessToken.value
    }

    /**
     * [oauth]'s refresh of [refreshToken], retried within item 3's budget while Google does not
     * answer; the last [GoogleUnavailableException] propagates. `invalid_grant` is signed out.
     */
    private suspend fun refresh(oauth: GoogleOAuthClient, refreshToken: String): GoogleTokens = try {
        backoff.run({ it is GoogleUnavailableException }) { oauth.refresh(refreshToken) }
    } catch (e: GoogleInvalidGrantException) {
        throw SyncSignedOutException("Google no longer honours this device's Drive grant", e)
    }

    /**
     * O's revoke of [refreshToken], bounded by [revokeTimeout] (the shared client has no call
     * timeout; cancelling the coroutine cancels the call). A failure or a timeout is logged by its
     * class name only and does not stop the sign-out.
     */
    private suspend fun revoke(refreshToken: String) {
        try {
            withTimeout(revokeTimeout) { oauthClient().revoke(refreshToken) }
        } catch (e: TimeoutCancellationException) {
            logcat(LogPriority.WARN) { "Revoking the Drive grant at sign-out failed: ${e.javaClass.name}" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN) { "Revoking the Drive grant at sign-out failed: ${e.javaClass.name}" }
        }
    }

    /**
     * A grant just exchanged, before its secrets are stored: `about.get` at sign-in runs on it, and a
     * refresh keeps whatever the answer carried in memory for the store that follows.
     */
    private inner class PendingGrant(private val oauth: GoogleOAuthClient, tokens: GoogleTokens) : DriveAuthorization {
        var refreshToken: String = tokens.refreshToken
            ?: throw GoogleAuthRefusedException(GoogleAuthRefusedException.MALFORMED_RESPONSE, 200)
        var refreshTokenExpiresAt: Instant? = tokens.refreshTokenExpiresAt
        var access: GoogleAccessToken = tokens.accessToken

        override suspend fun accessToken(): String = if (access.isExpired(clock.now())) renew() else access.value

        override suspend fun refreshed(rejected: String): String =
            if (access.value == rejected || access.isExpired(clock.now())) renew() else access.value

        private suspend fun renew(): String {
            val tokens = refresh(oauth, refreshToken)
            tokens.refreshToken?.let { refreshToken = it }
            tokens.refreshTokenExpiresAt?.let { refreshTokenExpiresAt = it }
            access = tokens.accessToken
            return access.value
        }
    }

    companion object {
        /** The secret names. Renaming one signs out every device that stored it (R84 item 1). */
        const val REFRESH_TOKEN = "drive_refresh_token"
        const val REFRESH_TOKEN_EXPIRES_AT = "drive_refresh_token_expires_at"
        const val EMAIL = "drive_account_email"
        const val ACCOUNT_ID = "drive_account_id"

        private val NAMES = listOf(REFRESH_TOKEN, REFRESH_TOKEN_EXPIRES_AT, EMAIL, ACCOUNT_ID)

        /** Fix round 1 item 4: a revoke Google does not answer does not hold a sign-out longer. */
        val REVOKE_TIMEOUT: Duration = 10.seconds

        private fun Instant.millis(): String = toEpochMilliseconds().toString()
    }
}

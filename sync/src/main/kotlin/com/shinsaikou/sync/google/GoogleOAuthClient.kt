package com.shinsaikou.sync.google

import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.core.common.oauth.Pkce
import com.shinsaikou.sync.SyncHttpClient
import com.shinsaikou.sync.await
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * Google's OAuth for the Drive backend, written by hand over the sync client (phase 3 unit O, Q1,
 * Q9): the authorization URL the browser opens, the code exchange, the refresh and the revoke. Both
 * grants send the client secret, as Google requires of a Desktop app client. The scope is
 * `drive.appdata` alone.
 *
 * Nothing here stores a token: [exchange] and [refresh] return them, and the caller keeps the
 * refresh token in `SecretStore` (H). Expiry is read from the injected [clock] when a response
 * arrives. Failures are decided on the status and the OAuth `error` code alone (R83 item 3):
 * [GoogleInvalidGrantException], [GoogleAuthRefusedException] and [GoogleUnavailableException].
 *
 * The build's [GoogleClientCredentials] go only to Google's own endpoints (fix round 1 item 1): with
 * any test base in effect, every request and the authorization URL carry
 * [GoogleClientCredentials.TEST_BASE], the sync fixture's public test client, so a test base cannot
 * collect the owner's client secret whoever set it.
 *
 * No redirect is followed: a token request carries the code, the verifier and the secret, and
 * OkHttp would resend a POST's body on a 307 or 308. Nothing is logged beyond the sync client's own
 * `BASIC` line, which names no query or body; revoke sends its token in the body for that reason.
 */
@Inject
class GoogleOAuthClient(
    httpClient: SyncHttpClient,
    val endpoints: GoogleEndpoints,
    buildCredentials: GoogleClientCredentials,
    private val clock: Clock,
    private val dispatchers: Dispatchers,
) {

    /** The build's client for Google's endpoints, and the fixture's public test client for any other. */
    private val credentials: GoogleClientCredentials =
        if (endpoints.isGoogle) buildCredentials else GoogleClientCredentials.TEST_BASE

    private val client: OkHttpClient =
        httpClient.client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    /**
     * Whether there is a client to sign in with: the build's id and secret on Google's endpoints, and
     * always on a test base, whose client is [GoogleClientCredentials.TEST_BASE].
     */
    val isConfigured: Boolean get() = credentials.isConfigured

    /**
     * The id of the client this instance signs in with: the build's on Google's endpoints, the test
     * client's on a test base. Not a secret; the Drive backend's id is derived from it (unit H),
     * since an `appDataFolder` belongs to one client's project.
     */
    val clientId: String get() = credentials.clientId

    /**
     * The authorization page for one sign-in: the client, the loopback [redirectUri], `code`,
     * `drive.appdata`, `access_type=offline` and `prompt=consent` (so every sign-in returns a refresh
     * token), [pkce]'s S256 challenge and [state].
     */
    fun authorizationUrl(redirectUri: String, state: String, pkce: Pkce): HttpUrl =
        endpoints.authorization.newBuilder()
            .addQueryParameter("client_id", credentials.clientId)
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("scope", DRIVE_APPDATA_SCOPE)
            .addQueryParameter("access_type", "offline")
            .addQueryParameter("prompt", "consent")
            .addQueryParameter("code_challenge", pkce.challenge)
            .addQueryParameter("code_challenge_method", Pkce.METHOD)
            .addQueryParameter("state", state)
            .build()

    /**
     * Exchanges the [code] the loopback received for tokens; [redirectUri] is the one the URL named.
     * The answer always carries [GoogleTokens.refreshToken]: one without is
     * [GoogleAuthRefusedException] with [GoogleAuthRefusedException.MALFORMED_RESPONSE].
     */
    suspend fun exchange(code: String, pkce: Pkce, redirectUri: String): GoogleTokens = token(
        GRANT_CODE,
        FormBody.Builder()
            .add("grant_type", GRANT_CODE)
            .add("code", code)
            .add("code_verifier", pkce.verifier)
            .add("redirect_uri", redirectUri)
            .add("client_id", credentials.clientId)
            .add("client_secret", credentials.clientSecret)
            .build(),
    )

    /** A new access token for [refreshToken]. The answer's [GoogleTokens.refreshToken] is normally absent. */
    suspend fun refresh(refreshToken: String): GoogleTokens = token(
        GRANT_REFRESH,
        FormBody.Builder()
            .add("grant_type", GRANT_REFRESH)
            .add("refresh_token", refreshToken)
            .add("client_id", credentials.clientId)
            .add("client_secret", credentials.clientSecret)
            .build(),
    )

    /**
     * Revokes the grant [token] (a refresh or an access token) belongs to. True when Google revoked
     * it; false when Google answered `invalid_token`, a grant already revoked or expired, which is
     * the same end for a sign-out. Throws [GoogleUnavailableException] when Google did not answer
     * usably and [GoogleAuthRefusedException] for any other refusal.
     */
    suspend fun revoke(token: String): Boolean {
        val request = Request.Builder().url(endpoints.revoke).post(FormBody.Builder().add("token", token).build()).build()
        val (status, body) = send(request)
        return when {
            status in 200..299 -> true
            status == 429 || status >= 500 -> throw GoogleUnavailableException(status)
            else -> {
                val error = errorCode(body)
                if (error == INVALID_TOKEN) false else throw GoogleAuthRefusedException(error ?: "http_$status", status)
            }
        }
    }

    private suspend fun token(grantType: String, form: FormBody): GoogleTokens {
        val (status, body) = send(Request.Builder().url(endpoints.token).post(form).build())
        when {
            status in 200..299 -> Unit
            status == 429 || status >= 500 -> throw GoogleUnavailableException(status)
            else -> when (val error = errorCode(body)) {
                INVALID_GRANT -> throw GoogleInvalidGrantException(grantType)
                else -> throw GoogleAuthRefusedException(error ?: "http_$status", status)
            }
        }
        val response = try {
            JSON.decodeFromString<TokenResponse>(body)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        val accessToken = response?.accessToken?.takeIf { it.isNotEmpty() }
        val expiresIn = response?.expiresIn?.takeIf { it > 0 }
        val refreshToken = response?.refreshToken?.takeIf { it.isNotEmpty() }
        // The code grant asks for offline access with consent, so its answer carries a refresh
        // token; one without is a sign-in that cannot last (fix round 1 item 4). A refresh answer
        // normally has none.
        if (accessToken == null || expiresIn == null || (grantType == GRANT_CODE && refreshToken == null)) {
            throw GoogleAuthRefusedException(GoogleAuthRefusedException.MALFORMED_RESPONSE, status)
        }
        val now = clock.now()
        return GoogleTokens(
            accessToken = GoogleAccessToken(accessToken, now + expiresIn.seconds),
            refreshToken = refreshToken,
            refreshTokenExpiresAt = response.refreshTokenExpiresIn?.let { now + it.seconds },
            scope = response.scope,
        )
    }

    /** The status and the whole body; any transport failure is [GoogleUnavailableException]. */
    private suspend fun send(request: Request): Pair<Int, String> = try {
        client.newCall(request).await().use { response ->
            response.code to withContext(dispatchers.io) { response.body.string() }
        }
    } catch (e: IOException) {
        throw GoogleUnavailableException(status = null, cause = e)
    }

    private fun errorCode(body: String): String? = try {
        JSON.decodeFromString<ErrorResponse>(body).error?.takeIf { it.isNotEmpty() }
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    @Serializable
    private class TokenResponse(
        @SerialName("access_token") val accessToken: String? = null,
        @SerialName("expires_in") val expiresIn: Long? = null,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("refresh_token_expires_in") val refreshTokenExpiresIn: Long? = null,
        val scope: String? = null,
    )

    @Serializable
    private class ErrorResponse(val error: String? = null)

    companion object {
        /** The one scope the app asks for (Q1): its own hidden folder in the owner's Drive. */
        const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"

        private const val GRANT_CODE = "authorization_code"
        private const val GRANT_REFRESH = "refresh_token"
        private const val INVALID_GRANT = "invalid_grant"
        private const val INVALID_TOKEN = "invalid_token"

        private val JSON = Json { ignoreUnknownKeys = true }
    }
}

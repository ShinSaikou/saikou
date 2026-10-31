package com.shinsaikou.sync.drive

import com.shinsaikou.sync.SyncAuthorization
import com.shinsaikou.sync.SyncSignedOutException
import com.shinsaikou.sync.await
import com.shinsaikou.sync.google.GoogleEndpoints
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * What signs each Drive call: an access token that is not about to expire, and after a 401 one that
 * is not the token Drive refused. [DriveAccount] keeps the stored sign-in's; a sign-in in progress
 * has its own until its secrets are stored.
 */
internal interface DriveAuthorization {
    suspend fun accessToken(): String

    /** A token other than [rejected]: the one another call refreshed meanwhile, else a new one. */
    suspend fun refreshed(rejected: String): String
}

/**
 * The transport under the Drive backend and the account's `about.get` (phase 3 unit H): Drive's
 * addresses on the build's [GoogleEndpoints], each request signed with a Bearer token as a
 * [SyncAuthorization] tag (R84 item 5), and item 2's decisions on an answer, taken on the status and
 * `error.errors[0].reason` alone, never on `message` (R83 item 3). `error.code` repeats the status
 * at Google and at the fixture, so the status stands for both.
 *
 * No redirect is followed: [client] is derived from the sync client with both switches off, as the
 * WebDAV backend's is (R88 item 5), since OkHttp would resend a create's body and its token to
 * wherever a 307 or 308 points. Every 3xx is [DriveHttpException].
 */
internal class DriveHttp(
    val endpoints: GoogleEndpoints,
    val authorization: DriveAuthorization,
    client: OkHttpClient,
    val io: CoroutineDispatcher,
    val backoff: DriveBackoff,
) {

    private val client: OkHttpClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    val files: HttpUrl = endpoints.driveBase.resolve("drive/v3/files")!!
    val about: HttpUrl = endpoints.driveBase.resolve("drive/v3/about")!!
    val upload: HttpUrl = endpoints.driveBase.resolve("upload/drive/v3/files")!!

    /**
     * [block], again after each of item 3's waits while it throws [DriveUnavailableException]: a
     * transport failure, a 5xx, a 429 or a rate-limit 403. The last attempt's failure propagates.
     * One budget per call: nothing inside [block] may reach [retrying] again, or a failure would
     * take the product of the attempts (fix round 1 item 1).
     */
    suspend fun <T> retrying(block: suspend () -> T): T = backoff.run({ it is DriveUnavailableException }, block)

    /**
     * Sends [request] with the access token; a 401 refreshes the token once and sends it again, and
     * a second 401 is [SyncSignedOutException]. Any other answer is returned for the caller to decide
     * on, and closes with it. [request] is built afresh for each send.
     */
    suspend fun signed(what: String, request: () -> Request.Builder): Response {
        val token = authorization.accessToken()
        val first = send(what, request(), token)
        if (first.code != UNAUTHORIZED) return first
        first.close()
        val again = send(what, request(), authorization.refreshed(token))
        if (again.code != UNAUTHORIZED) return again
        again.close()
        throw SyncSignedOutException("Google Drive refused a refreshed access token ($what)")
    }

    /**
     * Sends [request] with [token] and returns whatever Drive answered but a redirect. No answer at
     * all is [DriveUnavailableException] with the transport failure as its cause. A resumable
     * session's `PUT` passes [resumable], since there 308 is Drive's "Resume Incomplete", not a
     * redirect, and has no `Location` to follow.
     */
    suspend fun send(what: String, request: Request.Builder, token: String, resumable: Boolean = false): Response {
        val response = try {
            client.newCall(request.tag(SyncAuthorization::class, SyncAuthorization("Bearer $token")).build()).await()
        } catch (e: IOException) {
            throw DriveUnavailableException(status = null, reason = null, cause = e)
        }
        if (response.code in REDIRECTS && !(resumable && response.code == RESUME_INCOMPLETE)) {
            response.close()
            throw DriveHttpException(response.code, null, what)
        }
        return response
    }

    /** The whole body of a JSON answer; a connection lost while reading it is [DriveUnavailableException]. */
    suspend fun text(response: Response): String = withContext(io) {
        try {
            response.body.string()
        } catch (e: IOException) {
            throw DriveUnavailableException(status = null, reason = null, cause = e)
        }
    }

    /** [text] decoded as [T]; JSON that does not decode is [DriveMalformedResponseException]. */
    suspend inline fun <reified T> json(response: Response, what: String): T {
        val body = text(response)
        return try {
            JSON.decodeFromString<T>(body)
        } catch (e: SerializationException) {
            throw DriveMalformedResponseException("Google Drive's answer to $what is not the JSON asked for", e)
        } catch (e: IllegalArgumentException) {
            throw DriveMalformedResponseException("Google Drive's answer to $what is not the JSON asked for", e)
        }
    }

    /**
     * The failure a non-success [response] to [what] is (item 2). A 403 whose reason says the grant
     * lacks `drive.appdata` is [SyncSignedOutException]; `storageQuotaExceeded` is
     * [DriveStorageFullException]; a rate limit, a 429 and a 5xx are [DriveUnavailableException],
     * which [retrying] waits out; any other status is [DriveHttpException]. A 401 never gets here:
     * [signed] decides it. The caller closes [response].
     */
    fun failure(response: Response, what: String): IOException {
        val status = response.code
        val reason = reasonOf(response)
        return when {
            status == FORBIDDEN && reason in SIGNED_OUT_REASONS ->
                SyncSignedOutException("Google Drive refused $what with $reason: the grant lacks drive.appdata")
            status == FORBIDDEN && reason == STORAGE_QUOTA_EXCEEDED -> DriveStorageFullException(what)
            status == FORBIDDEN && reason in RATE_LIMIT_REASONS -> DriveUnavailableException(status, reason)
            status == TOO_MANY_REQUESTS || status >= SERVER_ERROR -> DriveUnavailableException(status, reason)
            else -> DriveHttpException(status, reason, what)
        }
    }

    /** `error.errors[0].reason` from a bounded peek at the body, or null when it names none. */
    private fun reasonOf(response: Response): String? = try {
        JSON.decodeFromString<ErrorAnswer>(response.peekBody(ERROR_BODY_LIMIT).string()).error?.errors?.firstOrNull()?.reason
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IOException) {
        null
    }?.takeIf { it.isNotEmpty() }

    /** Drive's `about.get` for the signed-in account: its email address and its `permissionId`. */
    suspend fun user(): DriveUser = retrying {
        val url = about.newBuilder().addQueryParameter("fields", "user(emailAddress,permissionId)").build()
        signed("about.get") { Request.Builder().url(url).get() }.use { response ->
            if (!response.isSuccessful) throw failure(response, "about.get")
            val user = json<AboutAnswer>(response, "about.get").user
            val email = user?.emailAddress?.takeIf { it.isNotEmpty() }
            val id = user?.permissionId?.takeIf { it.isNotEmpty() }
            if (email == null || id == null) {
                throw DriveMalformedResponseException("Google Drive's about.get named no email address or permissionId")
            }
            DriveUser(email, id)
        }
    }

    @Serializable
    private class ErrorAnswer(val error: ErrorBody? = null)

    @Serializable
    private class ErrorBody(val code: Int? = null, val errors: List<ErrorItem>? = null)

    @Serializable
    private class ErrorItem(val reason: String? = null)

    @Serializable
    private class AboutAnswer(val user: AboutUser? = null)

    @Serializable
    private class AboutUser(val emailAddress: String? = null, val permissionId: String? = null)

    companion object {
        const val UNAUTHORIZED = 401
        const val NOT_FOUND = 404
        const val RESUME_INCOMPLETE = 308
        private const val FORBIDDEN = 403
        private const val TOO_MANY_REQUESTS = 429
        private const val SERVER_ERROR = 500
        private val REDIRECTS = 300..399
        private const val ERROR_BODY_LIMIT = 64L * 1024

        /** The 403 reasons that mean the grant does not reach the file: the owner signs in again. */
        val SIGNED_OUT_REASONS = setOf("insufficientPermissions", "insufficientFilePermissions", "appNotAuthorizedToFile")
        const val STORAGE_QUOTA_EXCEEDED = "storageQuotaExceeded"
        val RATE_LIMIT_REASONS = setOf("rateLimitExceeded", "userRateLimitExceeded")

        val JSON = Json { ignoreUnknownKeys = true }
    }
}

/** The account a Drive grant belongs to: [email] for Sync settings, [permissionId] for the backend id. */
internal class DriveUser(val email: String, val permissionId: String)

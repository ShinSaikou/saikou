package com.shinsaikou.sync.webdav

import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.sync.BackendStatus
import com.shinsaikou.sync.SecretStore
import com.shinsaikou.sync.SyncHttpClient
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.withContext

/**
 * The WebDAV sign-in (plan unit F): the base address, user name and password live in [SecretStore]
 * under [URL], [USER] and [PASSWORD], names that never change once shipped (R84 item 1), and a
 * backend is built from them only while all three can be read.
 *
 * Every secret store call is Keystore IPC and runs on the IO dispatcher (R84 item 4). Sync settings
 * builds on this; clearing the backend's cursors and the chosen backend on sign-out is its work,
 * not this class's.
 */
@Inject
@SingleIn(AppScope::class)
class WebDavAccount(
    private val secrets: SecretStore,
    private val httpClient: SyncHttpClient,
    private val urlPolicy: WebDavUrlPolicy,
    private val dispatchers: Dispatchers,
) {

    /**
     * Checks [url] against this build's [WebDavUrlPolicy], probes the server with these credentials,
     * and stores the three secrets only when the probe answers reachable and authenticated. Returns
     * the probe's answer; a refused user name or password is `authenticated = false` and stores
     * nothing.
     *
     * Throws [WebDavUrlRefusedException] before any request for an address this build does not take,
     * and whatever the probe throws otherwise: [WebDavUntrustedCertificateException],
     * [WebDavHostNameMismatchException], [WebDavRedirectException] for an address that redirects
     * (nothing is followed or stored), [WebDavHttpException], [WebDavMalformedResponseException]
     * for a server that is not WebDAV there, [WebDavUnreachableException] when nothing answered, or
     * OkHttp's own exception for any other transport failure. A secret the store cannot write fails
     * the sign-in with what it threw, and nothing is left stored. [user] must be non-blank without a colon (Basic authentication splits on the first)
     * and [password] non-empty, since the store reads an empty value as absent.
     */
    suspend fun signIn(url: String, user: String, password: String): BackendStatus {
        require(user.isNotBlank() && ':' !in user) { "A WebDAV user name is non-blank and has no colon" }
        require(password.isNotEmpty()) { "A WebDAV password is not empty" }
        val base = urlPolicy.baseUrl(url)
        val status = WebDavSyncBackend(base, user, password, httpClient.client, dispatchers.io).probe()
        if (status.reachable && status.authenticated) {
            withContext(dispatchers.io) {
                try {
                    secrets.put(URL, base.toString())
                    secrets.put(USER, user)
                    secrets.put(PASSWORD, password)
                } catch (e: Exception) {
                    // A delete can fail as the put did (the store commits each write); the first
                    // failure is the one the sign-in reports.
                    NAMES.forEach { name ->
                        try {
                            secrets.delete(name)
                        } catch (cleanup: Exception) {
                            e.addSuppressed(cleanup)
                        }
                    }
                    throw e
                }
            }
        }
        return status
    }

    /** Forgets the three secrets. The remote files and the local library are untouched. */
    suspend fun signOut() {
        withContext(dispatchers.io) { NAMES.forEach(secrets::delete) }
    }

    /**
     * The backend the stored secrets sign in to, or null when signed out: a secret absent, or one
     * the store could not read (which it deletes, R84 item 3), or an address this build does not
     * take, such as a debug build's `http://10.0.2.2` read by a release build over the same data.
     */
    suspend fun backend(): WebDavSyncBackend? = withContext(dispatchers.io) {
        val url = secrets.get(URL)
        val user = secrets.get(USER)
        val password = secrets.get(PASSWORD)
        if (url == null || user == null || password == null) return@withContext null
        val base = try {
            urlPolicy.baseUrl(url)
        } catch (_: WebDavUrlRefusedException) {
            return@withContext null
        }
        WebDavSyncBackend(base, user, password, httpClient.client, dispatchers.io)
    }

    /**
     * The stored address and user name, for Sync settings to show, or null when either is absent or
     * cannot be read. It never reads the password, and it says nothing about whether the three still
     * sign in: [backend] is what a run uses.
     */
    suspend fun signedInAs(): WebDavSignIn? = withContext(dispatchers.io) {
        val url = secrets.get(URL) ?: return@withContext null
        val user = secrets.get(USER) ?: return@withContext null
        WebDavSignIn(url = url, user = user)
    }

    companion object {
        /** The secret names. Renaming one signs out every device that stored it (R84 item 1). */
        const val URL = "webdav_url"
        const val USER = "webdav_user"
        const val PASSWORD = "webdav_password"

        private val NAMES = listOf(URL, USER, PASSWORD)
    }
}

/** What Sync settings shows of a WebDAV sign-in: the stored base address and user name, never the password. */
data class WebDavSignIn(val url: String, val user: String)

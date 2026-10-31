package com.shinsaikou.sync.webdav

import com.shinsaikou.sync.webdav.WebDavUrlRefusedException.Reason
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Which WebDAV addresses this build signs in to (plan unit F, R84 carry-over 1). Every build takes
 * `https://` to any host. `http://` is taken only to a host in [cleartextHosts]: none in a release
 * build, and the emulator's view of the host machine ([EMULATOR_HOST], any port) in a debug build,
 * where the sync fixture listens. The app graph picks the policy by build type.
 *
 * `network_security_config.xml` permits cleartext app-wide, because sources need it, so this is the
 * only thing that keeps sync credentials off plain HTTP.
 */
class WebDavUrlPolicy(private val cleartextHosts: Set<String>) {

    /**
     * [url] as the base address every backend path resolves against: trimmed, in OkHttp's canonical
     * form (scheme and host lower case, a default port left out, percent-encoding normalized), and
     * ending in `/`. Throws [WebDavUrlRefusedException] for an address this build does not take.
     */
    fun baseUrl(url: String): HttpUrl {
        val parsed = url.trim().toHttpUrlOrNull()
            ?: throw WebDavUrlRefusedException(Reason.NOT_A_URL, "Not an http:// or https:// address")
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty() || parsed.query != null || parsed.fragment != null) {
            throw WebDavUrlRefusedException(
                Reason.NOT_A_BASE_URL,
                "A WebDAV address carries no user name, password, query or fragment",
            )
        }
        if (!parsed.isHttps && parsed.host !in cleartextHosts) {
            throw WebDavUrlRefusedException(Reason.CLEARTEXT, "Sync signs in over https:// only, not to ${parsed.host}")
        }
        return if (parsed.pathSegments.last().isEmpty()) parsed else parsed.newBuilder().addPathSegment("").build()
    }

    companion object {

        /** The address an Android emulator reaches its host machine's loopback at. */
        const val EMULATOR_HOST = "10.0.2.2"

        /** Release takes `https://` only; debug also takes `http://10.0.2.2` for the sync fixture. */
        fun forBuild(debug: Boolean): WebDavUrlPolicy =
            WebDavUrlPolicy(if (debug) setOf(EMULATOR_HOST) else emptySet())
    }
}

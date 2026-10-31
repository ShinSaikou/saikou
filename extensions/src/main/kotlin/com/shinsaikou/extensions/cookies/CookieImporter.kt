package com.shinsaikou.extensions.cookies

import android.webkit.CookieManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.network.NetworkHelper
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Cookie plumbing for the WebView flows.
 *
 * There is deliberately very little of it: `AndroidCookieJar` is a thin wrapper over the
 * process-wide `android.webkit.CookieManager`, every client derived from `NetworkHelper.client`
 * (including every `client.newBuilder()` clone an extension makes) uses that one jar, and every
 * WebView in the process writes to the same manager. A cookie a WebView session obtains is
 * therefore already attached to the next OkHttp request for that host, with nothing copied. The
 * solved Cloudflare clearance reaches extensions the same way.
 *
 * Values are never logged.
 */
@SingleIn(AppScope::class)
@Inject
class CookieImporter(private val networkHelper: NetworkHelper) {

    private val jar: AndroidCookieJar get() = networkHelper.cookieJar

    /** Cookies OkHttp currently sends for [url], for the settings screen's list. */
    fun cookiesFor(url: String): List<Cookie> {
        val httpUrl = url.toHttpUrlOrNull() ?: return emptyList()
        return jar.get(httpUrl)
    }

    /**
     * Imports a raw `Cookie` header ("a=1; b=2") the user pasted for [url]'s host. Returns how many
     * pairs were written; blank names and malformed pairs are skipped.
     */
    fun importCookieHeader(url: String, header: String): Int {
        url.toHttpUrlOrNull() ?: return 0
        val manager = CookieManager.getInstance()
        var written = 0
        header.split(';').forEach { pair ->
            val name = pair.substringBefore('=', "").trim()
            if (name.isEmpty() || !pair.contains('=')) return@forEach
            val value = pair.substringAfter('=').trim()
            manager.setCookie(url, "$name=$value; Path=/")
            written++
        }
        if (written > 0) flush()
        return written
    }

    /** Removes the named cookies, or all of them when [names] is null, for [url]'s host. */
    fun clear(url: String, names: List<String>? = null): Int {
        val httpUrl = url.toHttpUrlOrNull() ?: return 0
        return jar.remove(httpUrl, names)
    }

    /** Persists the manager's in-memory state. Call it when a WebView screen closes. */
    fun flush() {
        CookieManager.getInstance().flush()
    }
}

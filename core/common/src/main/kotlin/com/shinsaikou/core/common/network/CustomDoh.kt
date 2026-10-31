package com.shinsaikou.core.common.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps

/**
 * The `doh_provider` value of a DNS over HTTPS server the owner names by URL, kept in
 * [eu.kanade.tachiyomi.network.NetworkPreferences.dohProviderCustom]. The value and that key are the
 * ones upstream's open pull request 4217 uses, so a backup would carry the choice across if it lands,
 * and the value sits well past upstream's numbered presets, so a preset added later cannot take it.
 */
const val PREF_DOH_CUSTOM = 100

/**
 * [value] as a DNS over HTTPS server, or null unless it is an absolute `https` URL with a host.
 * Surrounding spaces are ignored. OkHttp reads a URL as a browser does, where `https:///dns-query`
 * names the host `dns-query`, so only the plain form, the host right after the two slashes, is taken.
 */
fun customDohUrl(value: String): HttpUrl? {
    val text = value.trim()
    if (!text.startsWith(HTTPS_PREFIX, ignoreCase = true)) return null
    if (text.getOrNull(HTTPS_PREFIX.length) == '/' || text.getOrNull(HTTPS_PREFIX.length) == '\\') return null
    return text.toHttpUrlOrNull()
}

private const val HTTPS_PREFIX = "https://"

/**
 * The server at [url], built as the presets are but with no bootstrap hosts: the system resolver
 * looks up the server's own host. OkHttp follows a redirect from `https` to plain `http` by default,
 * which would carry the lookups and their answers in the clear, so the resolver's client does not and
 * such a lookup fails instead. A value [customDohUrl] refuses, which only a restored backup or a
 * hand-edited file can store, leaves the builder on the system resolver rather than failing every
 * lookup.
 */
fun OkHttpClient.Builder.dohCustom(url: String): OkHttpClient.Builder {
    val server = customDohUrl(url) ?: return this
    val client = build().newBuilder().followSslRedirects(false).build()
    return dns(DnsOverHttps.Builder().client(client).url(server).build())
}

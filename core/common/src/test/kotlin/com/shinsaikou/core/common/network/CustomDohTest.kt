package com.shinsaikou.core.common.network

import android.content.Context
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.network.PREF_DOH_QUAD9
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.dnsoverhttps.DnsOverHttps
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import java.io.File

/**
 * The custom DNS over HTTPS server: which URLs it accepts, the resolver the shared client gets from
 * one, and that a stored value it refuses leaves the client on the system resolver. No lookup is
 * made; the resolver is only built.
 */
class CustomDohTest {

    @TempDir
    lateinit var cacheDir: File

    private fun networkHelper(preferences: NetworkPreferences): NetworkHelper {
        val context = mockk<Context>(relaxed = true)
        every { context.cacheDir } returns cacheDir
        return NetworkHelper(context, preferences, CloudflareSolveCoordinator(), mockk(relaxed = true))
    }

    /** The preferences as a restored backup or an older build can leave them, the custom URL included. */
    private fun stored(provider: Int, url: String?): NetworkPreferences = NetworkPreferences(
        InMemoryPreferenceStore(
            listOfNotNull(
                InMemoryPreference("doh_provider", provider, -1),
                url?.let { InMemoryPreference("doh_provider_custom", it, "") },
            ).asSequence(),
        ),
    )

    @Test
    fun `an absolute https URL with a host is accepted`() {
        listOf(
            "https://doh.example.invalid/dns-query",
            "https://doh.example.invalid",
            "https://doh.example.invalid:8443/resolve?ct=application/dns-message",
            "https://192.0.2.53/dns-query",
            "https://[2001:db8::53]/dns-query",
            "HTTPS://Doh.Example.Invalid/dns-query",
            "  https://doh.example.invalid/dns-query \n",
        ).forEach { value ->
            withClue(value) { customDohUrl(value).shouldNotBeNull().isHttps shouldBe true }
        }
        customDohUrl(" https://doh.example.invalid/dns-query ") shouldBe "https://doh.example.invalid/dns-query".toHttpUrl()
    }

    @Test
    fun `anything else is refused`() {
        listOf(
            "",
            "   ",
            "http://doh.example.invalid/dns-query",
            "doh.example.invalid/dns-query",
            "//doh.example.invalid/dns-query",
            "/dns-query",
            "https://",
            "https:///dns-query",
            "https:////doh.example.invalid/dns-query",
            "https:/doh.example.invalid/dns-query",
            "https:doh.example.invalid/dns-query",
            "https://\\doh.example.invalid/dns-query",
            "https://doh example.invalid/dns-query",
            "https://doh.example.invalid:99999/dns-query",
            "tls://doh.example.invalid",
            "ftp://doh.example.invalid/dns-query",
            "not a url",
        ).forEach { value ->
            withClue(value) { customDohUrl(value).shouldBeNull() }
        }
    }

    @Test
    fun `the custom URL is a preference of its own, blank until written`() {
        val preferences = NetworkPreferences(InMemoryPreferenceStore())
        val url = preferences.dohProviderCustom
        url.key() shouldBe "doh_provider_custom"
        url.get() shouldBe ""

        preferences.dohProvider.set(PREF_DOH_CUSTOM)
        url.set("https://doh.example.invalid/dns-query")

        preferences.dohProvider.get() shouldBe PREF_DOH_CUSTOM
        url.get() shouldBe "https://doh.example.invalid/dns-query"
    }

    @Test
    fun `the shared client resolves through the written custom server, with no bootstrap hosts`() {
        val preferences = NetworkPreferences(InMemoryPreferenceStore())
        preferences.dohProvider.set(PREF_DOH_CUSTOM)
        preferences.dohProviderCustom.set("https://doh.example.invalid/dns-query")

        val dns = networkHelper(preferences).client.dns.shouldBeInstanceOf<DnsOverHttps>()

        dns.url shouldBe "https://doh.example.invalid/dns-query".toHttpUrl()
        // Without bootstrap hosts the resolver's own client finds the server through the system.
        dns.client.dns shouldBeSameInstanceAs Dns.SYSTEM
    }

    @Test
    fun `the custom server's redirect to plain http is not followed, the shared client's still is`() {
        val preferences = NetworkPreferences(InMemoryPreferenceStore())
        preferences.dohProvider.set(PREF_DOH_CUSTOM)
        preferences.dohProviderCustom.set("https://doh.example.invalid/dns-query")

        val client = networkHelper(preferences).client
        val dns = client.dns.shouldBeInstanceOf<DnsOverHttps>()

        dns.client.followSslRedirects shouldBe false
        dns.client.followRedirects shouldBe true
        client.followSslRedirects shouldBe true
    }

    @Test
    fun `a preset ignores a stored custom URL and still finds its server through its bootstrap hosts`() {
        val dns = networkHelper(stored(PREF_DOH_QUAD9, url = "https://doh.example.invalid/dns-query"))
            .client.dns.shouldBeInstanceOf<DnsOverHttps>()

        dns.url.host shouldNotBe "doh.example.invalid"
        dns.client.dns shouldNotBe Dns.SYSTEM
    }

    @Test
    fun `a blank, missing or refused stored URL leaves the client on the system resolver`() {
        listOf(null, "", "   ", "http://doh.example.invalid/dns-query", "not a url").forEach { url ->
            withClue(url.toString()) {
                networkHelper(stored(PREF_DOH_CUSTOM, url)).client.dns shouldBeSameInstanceAs Dns.SYSTEM
            }
        }
    }
}

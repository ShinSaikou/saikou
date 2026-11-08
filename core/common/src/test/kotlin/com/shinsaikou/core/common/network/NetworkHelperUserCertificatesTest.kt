package com.shinsaikou.core.common.network

import android.content.Context
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.network.PREF_DOH_CLOUDFLARE
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.security.cert.CertPathBuilderException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/**
 * The Experimental switch "Trust certificates you installed" as [NetworkHelper] reads it: off, the
 * client is the one the network security config governs; on, it trusts the CAs in the (fake)
 * Android CA store, the system ones kept, and every client derived from it inherits that. A site
 * behind a user CA is served over a real TLS handshake, so the trust is shown at work, not only read.
 */
class NetworkHelperUserCertificatesTest {

    @TempDir
    lateinit var cacheDir: File

    private val server = MockWebServer()

    @AfterEach
    fun stopServer() = server.close()

    private fun networkHelper(trustUserCertificates: Boolean, doh: Int = -1): NetworkHelper {
        val preferences = NetworkPreferences(InMemoryPreferenceStore())
        preferences.trustUserCertificates.set(trustUserCertificates)
        preferences.dohProvider.set(doh)
        val context = mockk<Context>(relaxed = true)
        every { context.cacheDir } returns cacheDir
        return NetworkHelper(context, preferences, CloudflareSolveCoordinator(), mockk(relaxed = true))
    }

    private fun userCa(): HeldCertificate = HeldCertificate.Builder().commonName("Installed CA").certificateAuthority(0).build()

    private fun leaf(ca: HeldCertificate, host: String = "localhost"): HeldCertificate =
        HeldCertificate.Builder().commonName(host).addSubjectAlternativeName(host).signedBy(ca).build()

    private fun serve(leaf: HeldCertificate, ca: HeldCertificate) {
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(leaf, ca.certificate).build().sslSocketFactory())
        server.enqueue(MockResponse.Builder().code(200).body("hello").build())
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    /**
     * The shared client's trust with the cookie jar set aside: its jar is Android's CookieManager,
     * which is not here, and the name resolved to the server's address. A derived client keeps the trust, which is the property under test anyway.
     */
    private fun get(client: OkHttpClient, host: String = "localhost"): String =
        client.newBuilder().cookieJar(CookieJar.NO_COOKIES).dns { listOf(InetAddress.getByName("127.0.0.1")) }.build().newCall(Request.Builder().url("https://$host:${server.port}/").build()).execute().use { it.body.string() }

    /**
     * The call fails on the certificate: OkHttp retries a failed handshake on other routes, so the
     * last error thrown may be a connect error, and the TLS one is found among the causes and the
     * suppressed errors. The server saw no request.
     */
    private fun assertRefused(client: OkHttpClient) {
        val failure = shouldThrow<IOException> { get(client) }
        generateSequence(listOf<Throwable>(failure)) { level ->
            level.flatMap { listOfNotNull(it.cause) + it.suppressed }.ifEmpty { null }
        }.flatten().any { it is SSLException } shouldBe true
        server.requestCount shouldBe 0
    }

    private fun trusts(client: OkHttpClient, leaf: HeldCertificate): Boolean = try {
        client.x509TrustManager!!.checkServerTrusted(arrayOf(leaf.certificate), "ECDHE_ECDSA")
        true
    } catch (e: CertificateException) {
        // Only an untrusted chain is a no; any other failure (an unknown authType, say) is a broken test.
        if (e.cause !is CertPathBuilderException) throw e
        false
    }

    @Test
    fun `the switch is off by default and does not reach the CA store`() {
        // No FakeAndroidCaStore here: with the switch off the client must not ask for one.
        val helper = networkHelper(trustUserCertificates = false)

        helper.trustsUserCertificates shouldBe false
        NetworkPreferences(InMemoryPreferenceStore()).trustUserCertificates.get() shouldBe false
    }

    @Test
    fun `with the switch off a site behind a user CA is refused`() {
        val ca = userCa()
        serve(leaf(ca), ca)
        FakeAndroidCaStore(user = listOf(ca.certificate)).use {
            assertRefused(networkHelper(trustUserCertificates = false).client)
        }
    }

    @Test
    fun `with the switch on a site behind a user CA loads`() {
        val ca = userCa()
        serve(leaf(ca), ca)
        FakeAndroidCaStore(user = listOf(ca.certificate)).use {
            val helper = networkHelper(trustUserCertificates = true)

            helper.trustsUserCertificates shouldBe true
            get(helper.client) shouldBe "hello"
        }
    }

    @Test
    fun `with the switch on the hostname is still checked`() {
        val ca = userCa()
        serve(leaf(ca, host = "elsewhere.example.test"), ca)
        FakeAndroidCaStore(user = listOf(ca.certificate)).use {
            assertRefused(networkHelper(trustUserCertificates = true).client)
        }
    }

    @Test
    fun `with the switch on a CA in neither store is still refused`() {
        val stranger = userCa()
        serve(leaf(stranger), stranger)
        FakeAndroidCaStore(user = listOf(userCa().certificate)).use {
            assertRefused(networkHelper(trustUserCertificates = true).client)
        }
    }

    @Test
    fun `with the switch on the system CAs are kept`() {
        val systemCa = userCa()
        FakeAndroidCaStore(system = listOf(systemCa.certificate)).use {
            trusts(networkHelper(trustUserCertificates = true).client, leaf(systemCa)) shouldBe true
        }
    }

    @Test
    fun `with the switch off the client trusts no more than the platform default`() {
        val ca = userCa()
        FakeAndroidCaStore(user = listOf(ca.certificate)).use {
            val client = networkHelper(trustUserCertificates = false).client
            trusts(client, leaf(ca)) shouldBe false
            trusts(OkHttpClient(), leaf(ca)) shouldBe false
            client.x509TrustManager!!.acceptedIssuers.size shouldBe OkHttpClient().x509TrustManager!!.acceptedIssuers.size
        }
    }

    @Test
    fun `a client derived from the shared one inherits the trust`() {
        val ca = userCa()
        serve(leaf(ca), ca)
        FakeAndroidCaStore(user = listOf(ca.certificate)).use {
            val derived = networkHelper(trustUserCertificates = true).client.newBuilder().build()
            get(derived) shouldBe "hello"
        }
    }

    @Test
    fun `the DNS over HTTPS resolver is built with the same trust`() {
        val ca = userCa()
        FakeAndroidCaStore(user = listOf(ca.certificate)).use {
            val on = networkHelper(trustUserCertificates = true, doh = PREF_DOH_CLOUDFLARE).client.dns
                .shouldBeInstanceOf<DnsOverHttps>()
            val off = networkHelper(trustUserCertificates = false, doh = PREF_DOH_CLOUDFLARE).client.dns
                .shouldBeInstanceOf<DnsOverHttps>()

            trusts(on.client, leaf(ca)) shouldBe true
            trusts(off.client, leaf(ca)) shouldBe false
        }
    }

    @Test
    fun `the CA store is read once, when the client is built`() {
        val ca = userCa()
        val helper = FakeAndroidCaStore(user = listOf(ca.certificate)).use { networkHelper(trustUserCertificates = true) }

        // The store is gone, as if the owner removed the CA after the process started.
        trusts(helper.client, leaf(ca)) shouldBe true
    }
}

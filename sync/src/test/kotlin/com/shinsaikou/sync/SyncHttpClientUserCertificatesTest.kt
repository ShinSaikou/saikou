package com.shinsaikou.sync

import android.content.Context
import com.shinsaikou.core.common.network.CloudflareSolveCoordinator
import com.shinsaikou.core.common.network.NetworkUserMessages
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import okhttp3.OkHttpClient
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.io.File
import java.security.cert.CertPathBuilderException
import java.security.cert.CertificateException

/**
 * The sync client (Drive and WebDAV), which carries a token and a password. The shared client is built from the real [NetworkHelper] with the Experimental switch "Trust
 * certificates you installed" on or off, over a fake Android CA store holding one installed CA; the
 * check is whether each client accepts a certificate that CA signed. No request is made.
 */
class SyncHttpClientUserCertificatesTest {

    @TempDir
    lateinit var cacheDir: File

    private val installedCa = HeldCertificate.Builder().commonName("Installed CA").certificateAuthority(0).build()

    private val leaf = HeldCertificate.Builder().commonName("site.example.test")
        .addSubjectAlternativeName("site.example.test").signedBy(installedCa).build()

    private fun preferences(trustUserCertificates: Boolean): NetworkPreferences =
        NetworkPreferences(InMemoryPreferenceStore()).also { it.trustUserCertificates.set(trustUserCertificates) }

    private fun networkHelper(preferences: NetworkPreferences): NetworkHelper {
        val context = mockk<Context>(relaxed = true)
        every { context.cacheDir } returns cacheDir
        return NetworkHelper(context, preferences, CloudflareSolveCoordinator(), mockk<NetworkUserMessages>(relaxed = true))
    }

    private fun trusts(client: OkHttpClient): Boolean = try {
        client.x509TrustManager!!.checkServerTrusted(arrayOf(leaf.certificate), "ECDHE_ECDSA")
        true
    } catch (e: CertificateException) {
        // Only an untrusted chain is a no; any other failure (an unknown authType, say) is a broken test.
        if (e.cause !is CertPathBuilderException) throw e
        false
    }

    /** The shared client and the one derived from it, under a switch setting. */
    private fun clients(trustUserCertificates: Boolean): Pair<OkHttpClient, OkHttpClient> =
        FakeAndroidCaStore(user = listOf(installedCa.certificate)).use {
            val preferences = preferences(trustUserCertificates)
            val helper = networkHelper(preferences)
            helper.client to SyncHttpClient(helper, preferences).client
        }

    @Test
    fun `with the switch on the shared client trusts the installed CA and the derived client does not`() {
        val (shared, derived) = clients(trustUserCertificates = true)

        trusts(shared) shouldBe true
        trusts(derived) shouldBe false
    }

    @Test
    fun `with the switch off the derived client keeps the shared client's trust, which refuses the installed CA`() {
        val (shared, derived) = clients(trustUserCertificates = false)

        trusts(shared) shouldBe false
        trusts(derived) shouldBe false
        derived.x509TrustManager!! shouldBeSameInstanceAs shared.x509TrustManager!!
        derived.sslSocketFactory shouldBeSameInstanceAs shared.sslSocketFactory
    }

    @Test
    fun `with the switch on the derived client has its own trust manager`() {
        val (shared, derived) = clients(trustUserCertificates = true)

        (derived.x509TrustManager === shared.x509TrustManager) shouldBe false
    }
}

package com.shinsaikou.extensions.repo

import com.shinsaikou.core.common.network.trustUserCertificates
import eu.kanade.tachiyomi.network.NetworkHelper
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.OkHttpClient
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.Test
import java.security.cert.CertPathBuilderException
import java.security.cert.CertificateException

/**
 * The extension stores' client under the Experimental switch "Trust certificates you installed":
 * index fetches and APK downloads bring in code the app installs, so with the shared client widened
 * they take system trust back, and with the switch off they go through the shared client itself.
 * No request is made.
 */
class ExtensionRepoClientUserCertificatesTest {

    private val installedCa = HeldCertificate.Builder().commonName("Installed CA").certificateAuthority(0).build()

    private val leaf = HeldCertificate.Builder().commonName("store.example.test")
        .addSubjectAlternativeName("store.example.test").signedBy(installedCa).build()

    private fun trusts(client: OkHttpClient): Boolean = try {
        client.x509TrustManager!!.checkServerTrusted(arrayOf(leaf.certificate), "ECDHE_ECDSA")
        true
    } catch (e: CertificateException) {
        // Only an untrusted chain is a no; any other failure (an unknown authType, say) is a broken test.
        if (e.cause !is CertPathBuilderException) throw e
        false
    }

    /**
     * A [NetworkHelper] whose client is built the way it builds its own: widened to the installed CA
     * when the switch is on, the platform's trust otherwise. The helper's other parts (its cookie
     * jar is Android's) are not needed to build the repository client's.
     */
    private fun clients(trustUserCertificates: Boolean): Pair<OkHttpClient, OkHttpClient> =
        FakeAndroidCaStore(user = listOf(installedCa.certificate)).use {
            val shared = OkHttpClient.Builder().apply { if (trustUserCertificates) trustUserCertificates() }.build()
            val helper = mockk<NetworkHelper> {
                every { client } returns shared
                every { trustsUserCertificates } returns trustUserCertificates
            }
            shared to ExtensionRepoClient(helper, Json, ProtoBuf).client
        }

    @Test
    fun `with the switch on the repository client refuses a user-installed CA the shared client trusts`() {
        val (shared, repo) = clients(trustUserCertificates = true)

        trusts(shared) shouldBe true
        trusts(repo) shouldBe false
    }

    @Test
    fun `with the switch off the repository client is the shared client`() {
        val (shared, repo) = clients(trustUserCertificates = false)

        trusts(repo) shouldBe false
        repo shouldBeSameInstanceAs shared
        repo.x509TrustManager!! shouldBeSameInstanceAs shared.x509TrustManager!!
    }
}

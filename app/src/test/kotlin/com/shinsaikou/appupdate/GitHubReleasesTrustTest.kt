package com.shinsaikou.appupdate

import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.core.common.network.trustUserCertificates
import eu.kanade.tachiyomi.network.NetworkHelper
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import okhttp3.OkHttpClient
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.Test
import java.security.cert.CertPathBuilderException
import java.security.cert.CertificateException

/**
 * The updater's two clients under the Experimental switch "Trust certificates you installed": what
 * they fetch is code the app installs, so with the shared client widened they take system trust
 * back, and with the switch off they are the shared client's own trust. The clients are private and
 * built on first use, so they are read from the lazy fields the class keeps them in. No request is made.
 */
class GitHubReleasesTrustTest {

    private val installedCa = HeldCertificate.Builder().commonName("Installed CA").certificateAuthority(0).build()

    private val leaf = HeldCertificate.Builder().commonName("api.example.test")
        .addSubjectAlternativeName("api.example.test").signedBy(installedCa).build()

    private fun trusts(client: OkHttpClient): Boolean = try {
        client.x509TrustManager!!.checkServerTrusted(arrayOf(leaf.certificate), "ECDHE_ECDSA")
        true
    } catch (e: CertificateException) {
        // Only an untrusted chain is a no; any other failure (an unknown authType, say) is a broken test.
        if (e.cause !is CertPathBuilderException) throw e
        false
    }

    private fun clientsOf(releases: GitHubReleases): Map<String, OkHttpClient> =
        listOf("apiClient", "downloadClient").associateWith { name ->
            val field = GitHubReleases::class.java.getDeclaredField("$name\$delegate").apply { isAccessible = true }
            (field.get(releases) as Lazy<*>).value as OkHttpClient
        }

    /**
     * A [NetworkHelper] whose client is built the way it builds its own: widened to the installed CA
     * when the switch is on, the platform's trust otherwise. The helper's other parts (its cookie
     * jar is Android's) are not needed to build the updater's clients.
     */
    private fun shared(trustUserCertificates: Boolean): Pair<NetworkHelper, Map<String, OkHttpClient>> =
        FakeAndroidCaStore(user = listOf(installedCa.certificate)).use {
            val client = OkHttpClient.Builder().apply { if (trustUserCertificates) trustUserCertificates() }.build()
            val helper = mockk<NetworkHelper> {
                every { this@mockk.client } returns client
                every { trustsUserCertificates } returns trustUserCertificates
            }
            val dispatcher = StandardTestDispatcher()
            val releases = GitHubReleases(
                lazy { helper },
                AppUpdateBuild("owner/name", "1.0", automaticChecks = false, primaryAbi = null),
                Dispatchers(dispatcher, dispatcher, dispatcher),
            )
            helper to clientsOf(releases)
        }

    @Test
    fun `with the switch on the updater's clients refuse a user-installed CA the shared client trusts`() {
        val (helper, clients) = shared(trustUserCertificates = true)

        trusts(helper.client) shouldBe true
        clients.forEach { (name, client) -> (name to trusts(client)) shouldBe (name to false) }
    }

    @Test
    fun `with the switch off the updater's clients keep the shared client's trust`() {
        val (helper, clients) = shared(trustUserCertificates = false)

        clients.values.forEach { client ->
            trusts(client) shouldBe false
            client.x509TrustManager!! shouldBeSameInstanceAs helper.client.x509TrustManager!!
        }
    }
}

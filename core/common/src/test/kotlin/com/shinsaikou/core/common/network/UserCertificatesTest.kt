package com.shinsaikou.core.common.network

import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.Build
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import okhttp3.OkHttpClient
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.security.cert.CertPathBuilderException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit

/**
 * [isSignedByUserCertificate], the WebView's decision, and the two trust managers the source
 * client and its derived clients are given. Certificates are signed here with okhttp-tls, and the
 * CA store Android alone has is [FakeAndroidCaStore]. A [SslError] is a mock, because the platform
 * class cannot be built on the JVM; the API level is set on `Build.VERSION.SDK_INT` and put back.
 */
class UserCertificatesTest {

    private val sdkBefore: Int = Build.VERSION.SDK_INT

    @AfterEach
    fun restoreSdk() = setSdk(sdkBefore)

    private fun setSdk(level: Int) {
        val field = Build.VERSION::class.java.getField("SDK_INT")
        field.isAccessible = true
        val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe
        unsafe.putInt(unsafe.staticFieldBase(field), unsafe.staticFieldOffset(field), level)
    }

    private fun ca(name: String = "Installed CA"): HeldCertificate =
        HeldCertificate.Builder().commonName(name).certificateAuthority(1).build()

    private fun leaf(signer: HeldCertificate, host: String = HOST): HeldCertificate =
        HeldCertificate.Builder().commonName(host).addSubjectAlternativeName(host).signedBy(signer).build()

    private fun sslError(
        leaf: X509Certificate?,
        url: String? = "https://$HOST/path",
        primary: Int = SslError.SSL_UNTRUSTED,
    ): SslError {
        val certificate = mockk<SslCertificate> { every { x509Certificate } returns leaf }
        return mockk {
            every { primaryError } returns primary
            every { this@mockk.certificate } returns certificate
            every { this@mockk.url } returns url
        }
    }

    private fun decide(
        leaf: X509Certificate?,
        user: List<HeldCertificate> = emptyList(),
        system: List<HeldCertificate> = emptyList(),
        sdk: Int = Build.VERSION_CODES.Q,
        url: String? = "https://$HOST/path",
        primary: Int = SslError.SSL_UNTRUSTED,
    ): Boolean {
        setSdk(sdk)
        return FakeAndroidCaStore(user.map { it.certificate }, system.map { it.certificate }).use {
            isSignedByUserCertificate(sslError(leaf, url, primary))
        }
    }

    // -- the WebView decision ---------------------------------------------------------------------

    @Test
    fun `a leaf signed directly by a user CA proceeds`() {
        val ca = ca()
        decide(leaf(ca).certificate, user = listOf(ca)) shouldBe true
    }

    @Test
    fun `a leaf proceeds on a later API level too`() {
        val ca = ca()
        decide(leaf(ca).certificate, user = listOf(ca), sdk = 36) shouldBe true
    }

    @Test
    fun `a leaf proceeds when the matching CA is not the first user CA`() {
        val other = ca("Another CA")
        val ca = ca()
        decide(leaf(ca).certificate, user = listOf(other, ca)) shouldBe true
    }

    @Test
    fun `a leaf for a wildcard name proceeds for a host it covers`() {
        val ca = ca()
        val wildcard = HeldCertificate.Builder().commonName("*.example.test").addSubjectAlternativeName("*.example.test")
            .signedBy(ca).build()
        decide(wildcard.certificate, user = listOf(ca), url = "https://www.example.test/") shouldBe true
    }

    @Test
    fun `nothing proceeds below API 29, however good the certificate`() {
        val ca = ca()
        decide(leaf(ca).certificate, user = listOf(ca), sdk = Build.VERSION_CODES.P) shouldBe false
    }

    @Test
    fun `a primary error other than an untrusted certificate is refused`() {
        val ca = ca()
        val signed = leaf(ca).certificate
        listOf(
            SslError.SSL_NOTYETVALID,
            SslError.SSL_EXPIRED,
            SslError.SSL_IDMISMATCH,
            SslError.SSL_DATE_INVALID,
            SslError.SSL_INVALID,
        ).forEach { primary ->
            decide(signed, user = listOf(ca), primary = primary) shouldBe false
        }
    }

    @Test
    fun `an expired leaf is refused`() {
        val ca = ca()
        val now = System.currentTimeMillis()
        val expired = HeldCertificate.Builder().commonName(HOST).addSubjectAlternativeName(HOST)
            .validityInterval(now - TimeUnit.DAYS.toMillis(3), now - TimeUnit.DAYS.toMillis(1)).signedBy(ca).build()
        decide(expired.certificate, user = listOf(ca)) shouldBe false
    }

    @Test
    fun `a leaf that is not yet valid is refused`() {
        val ca = ca()
        val now = System.currentTimeMillis()
        val early = HeldCertificate.Builder().commonName(HOST).addSubjectAlternativeName(HOST)
            .validityInterval(now + TimeUnit.DAYS.toMillis(1), now + TimeUnit.DAYS.toMillis(3)).signedBy(ca).build()
        decide(early.certificate, user = listOf(ca)) shouldBe false
    }

    @Test
    fun `a leaf for another host is refused`() {
        val ca = ca()
        decide(leaf(ca, host = "other.example.test").certificate, user = listOf(ca)) shouldBe false
    }

    @Test
    fun `a leaf is refused when the page has no usable url`() {
        val ca = ca()
        val signed = leaf(ca).certificate
        decide(signed, user = listOf(ca), url = null) shouldBe false
        decide(signed, user = listOf(ca), url = "not a url") shouldBe false
    }

    @Test
    fun `an error with no X509 certificate is refused`() {
        decide(null, user = listOf(ca())) shouldBe false
    }

    @Test
    fun `a leaf signed by a system CA only is refused`() {
        val systemCa = ca("System CA")
        decide(leaf(systemCa).certificate, user = listOf(ca()), system = listOf(systemCa)) shouldBe false
    }

    @Test
    fun `a leaf signed by a CA that is in no store is refused`() {
        decide(leaf(ca("Stranger")).certificate, user = listOf(ca())) shouldBe false
    }

    @Test
    fun `a leaf is refused while the owner has installed no CA`() {
        decide(leaf(ca()).certificate) shouldBe false
    }

    @Test
    fun `a leaf whose issuer names a user CA but whose signature does not verify is refused`() {
        val installed = ca("Installed CA")
        // Same subject, another key: the issuer name matches, the signature cannot.
        val impostor = ca("Installed CA")
        decide(leaf(impostor).certificate, user = listOf(installed)) shouldBe false
    }

    @Test
    fun `a leaf signed through an intermediate is refused`() {
        val root = ca("Installed CA")
        val intermediate = HeldCertificate.Builder().commonName("Intermediate").certificateAuthority(0).signedBy(root).build()
        decide(leaf(intermediate).certificate, user = listOf(root)) shouldBe false
    }

    @Test
    fun `a missing CA store refuses instead of throwing`() {
        setSdk(Build.VERSION_CODES.Q)
        val ca = ca()
        // No FakeAndroidCaStore is registered, as on any machine that is not Android.
        isSignedByUserCertificate(sslError(leaf(ca).certificate)) shouldBe false
    }

    // -- the source client's trust ----------------------------------------------------------------

    private fun trusts(client: OkHttpClient, chain: HeldCertificate): Boolean = try {
        client.x509TrustManager!!.checkServerTrusted(arrayOf(chain.certificate), "ECDHE_ECDSA")
        true
    } catch (e: CertificateException) {
        // Only an untrusted chain is a no; any other failure (an unknown authType, say) is a broken test.
        if (e.cause !is CertPathBuilderException) throw e
        false
    }

    @Test
    fun `trustUserCertificates trusts the CAs the owner installed`() {
        val ca = ca()
        FakeAndroidCaStore(user = listOf(ca.certificate)).use {
            trusts(OkHttpClient.Builder().trustUserCertificates().build(), leaf(ca)) shouldBe true
        }
    }

    @Test
    fun `trustUserCertificates keeps the system CAs`() {
        val systemCa = ca("System CA")
        FakeAndroidCaStore(user = listOf(ca().certificate), system = listOf(systemCa.certificate)).use {
            trusts(OkHttpClient.Builder().trustUserCertificates().build(), leaf(systemCa)) shouldBe true
        }
    }

    @Test
    fun `trustUserCertificates still refuses a CA in neither store`() {
        FakeAndroidCaStore(user = listOf(ca().certificate)).use {
            trusts(OkHttpClient.Builder().trustUserCertificates().build(), leaf(ca("Stranger"))) shouldBe false
        }
    }

    @Test
    fun `trustUserCertificates fails loudly where the CA store is missing`() {
        shouldThrow<java.security.GeneralSecurityException> { OkHttpClient.Builder().trustUserCertificates() }
    }

    @Test
    fun `trustSystemCertificatesOnly takes the owner's CAs back from a client that trusted them`() {
        val ca = ca()
        FakeAndroidCaStore(user = listOf(ca.certificate)).use {
            val widened = OkHttpClient.Builder().trustUserCertificates().build()
            trusts(widened, leaf(ca)) shouldBe true
            trusts(widened.newBuilder().trustSystemCertificatesOnly().build(), leaf(ca)) shouldBe false
        }
    }

    @Test
    fun `a client derived from a widened one keeps the owner's CAs`() {
        val ca = ca()
        FakeAndroidCaStore(user = listOf(ca.certificate)).use {
            val widened = OkHttpClient.Builder().trustUserCertificates().build()
            trusts(widened.newBuilder().build(), leaf(ca)) shouldBe true
        }
    }

    private companion object {
        const val HOST = "site.example.test"
    }
}

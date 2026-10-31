package saikou.gradle.signing

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.File
import java.security.cert.X509Certificate

/**
 * The per-range comparison `verifyReleaseSigning` makes, against APKs the SDK's own apksigner signed
 * with throwaway keys ([SigningFixtures]): a match in every range, and a mismatch in each range on its
 * own, since a key the platform reads in only one range is exactly what a single answer would hide.
 */
class ReleaseSignersTest {

    private val a: X509Certificate by lazy { ReleaseSigners.certificate(SigningFixtures.storeA, SigningFixtures.PASSWORD, SigningFixtures.ALIAS_A) }
    private val b: X509Certificate by lazy { ReleaseSigners.certificate(SigningFixtures.storeB, SigningFixtures.PASSWORD, SigningFixtures.ALIAS_B) }

    private fun verdicts(apk: File, expected: X509Certificate): List<String> =
        ReleaseSigners.check(SigningFixtures.apksigner, apk, expected).map { "${it.range.key} ${it.verdict}" }

    @Test
    fun `the ranges are 26 to 27, 28 to 32, and 33 and up`() {
        ReleaseSigners.RANGES.map { it.key } shouldBe listOf("26-27", "28-32", "33-")
        ReleaseSigners.RANGES.map { it.toString() } shouldBe listOf("API 26 to 27", "API 28 to 32", "API 33 and up")
    }

    @Test
    fun `an APK signed with the expected key matches in every range`() {
        verdicts(SigningFixtures.signedA, a) shouldBe listOf("26-27 match", "28-32 match", "33- match")
    }

    @Test
    fun `an APK signed with another key matches in no range`() {
        verdicts(SigningFixtures.signedB, a) shouldBe listOf("26-27 no match", "28-32 no match", "33- no match")
    }

    @Test
    fun `a key rotated from API 33 fails only in 33 and up`() {
        verdicts(SigningFixtures.rotatedFrom33, a) shouldBe listOf("26-27 match", "28-32 match", "33- no match")
    }

    @Test
    fun `a key rotated from API 28 fails in 28 to 32 and in 33 and up`() {
        verdicts(SigningFixtures.rotatedFrom28, a) shouldBe listOf("26-27 match", "28-32 no match", "33- no match")
    }

    @Test
    fun `against the rotated-to key, the same APK fails only in 26 to 27`() {
        verdicts(SigningFixtures.rotatedFrom28, b) shouldBe listOf("26-27 no match", "28-32 match", "33- match")
    }

    @Test
    fun `a file apksigner cannot verify is no match in every range, with the exit status as the reason`() {
        val notAnApk = File(SigningFixtures.dir, "not-an-apk.apk").apply { writeText("plain text") }

        val results = ReleaseSigners.check(SigningFixtures.apksigner, notAnApk, a)

        results.map { it.match } shouldBe listOf(false, false, false)
        results.forEach { it.reason!! shouldContain "apksigner could not verify it (exit" }
    }

    @Test
    fun `no signer is never a match`() {
        ReleaseSigners.matches(a, emptyList()) shouldBe false
        ReleaseSigners.matches(a, listOf(a, b)) shouldBe false
        ReleaseSigners.matches(a, listOf(a, a)) shouldBe true
    }

    @Test
    fun `the PEM blocks are read in order and nothing else in the output is`() {
        val pem = { cert: X509Certificate ->
            "-----BEGIN CERTIFICATE-----\n" +
                java.util.Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(cert.encoded) +
                "\n-----END CERTIFICATE-----\n"
        }
        val output = "Signer #1 certificate DN: CN=Throwaway B\n" + pem(b) + "Signer #2 certificate DN: CN=Throwaway A\n" + pem(a)

        val read = ReleaseSigners.pemCertificates(output)

        read shouldHaveSize 2
        read.map { it.encoded.contentEquals(b.encoded) } shouldBe listOf(true, false)
        read.map { it.encoded.contentEquals(a.encoded) } shouldBe listOf(false, true)
    }

    @Test
    fun `a PKCS12 store and a JKS store both load, and an alias that is not there says so`() {
        a.subjectX500Principal.name shouldBe "CN=Throwaway A"
        b.subjectX500Principal.name shouldBe "CN=Throwaway B"
        val missing = shouldThrow<IllegalStateException> {
            ReleaseSigners.certificate(SigningFixtures.storeA, SigningFixtures.PASSWORD, "no-such-alias")
        }
        missing.message!! shouldContain "no certificate under the alias no-such-alias"
    }
}

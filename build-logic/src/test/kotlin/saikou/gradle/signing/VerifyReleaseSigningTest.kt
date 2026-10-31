package saikou.gradle.signing

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest

/**
 * The `verifyReleaseSigning` task itself, run through Gradle's `ProjectBuilder` over the throwaway
 * APKs of [SigningFixtures]: what it writes for the row, when it fails, and that no certificate
 * digest reaches its report or its message.
 */
class VerifyReleaseSigningTest {

    @TempDir
    lateinit var projectDir: File

    private fun task(apk: File, ownerSigning: Boolean = true, alias: String = SigningFixtures.ALIAS_A, store: File = SigningFixtures.storeA): VerifyReleaseSigning {
        val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        val apkDir = File(projectDir, "apk").apply { mkdirs() }
        apk.copyTo(File(apkDir, "app-release.apk"), overwrite = true)
        return project.tasks.register("verifyReleaseSigning", VerifyReleaseSigning::class.java) {
            apkDirectory.set(apkDir)
            this.ownerSigning.set(ownerSigning)
            storeFile.set(store)
            storePassword.set(SigningFixtures.PASSWORD)
            keyAlias.set(alias)
            apksigner.set(SigningFixtures.apksigner)
            report.set(File(projectDir, "out/verifyReleaseSigning.json"))
        }.get()
    }

    private val report get() = File(projectDir, "out/verifyReleaseSigning.json")

    /** Every digest apksigner or keytool would print for the key, in the forms they print it. */
    private fun digestsOf(store: File, alias: String): List<String> {
        val encoded = ReleaseSigners.certificate(store, SigningFixtures.PASSWORD, alias).encoded
        return listOf("SHA-256", "SHA-1", "MD5").flatMap { algorithm ->
            val hex = MessageDigest.getInstance(algorithm).digest(encoded).joinToString("") { "%02x".format(it) }
            listOf(hex, hex.uppercase().chunked(2).joinToString(":"))
        }
    }

    @Test
    fun `a release carrying the key in every range writes three matches and passes`() {
        task(SigningFixtures.signedA).verify()

        val text = report.readText()
        text shouldContain "\"ownerSigning\": true"
        text shouldContain "{\"range\": \"26-27\", \"result\": \"match\"}"
        text shouldContain "{\"range\": \"28-32\", \"result\": \"match\"}"
        text shouldContain "{\"range\": \"33-\", \"result\": \"match\"}"
        text shouldContain "app-release.apk"
    }

    @Test
    fun `a mismatch in one range writes the report and fails naming that range`() {
        val failed = shouldThrow<GradleException> { task(SigningFixtures.rotatedFrom33).verify() }

        failed.message!! shouldContain "in API 33 and up"
        failed.message!! shouldNotContain "API 26"
        val text = report.readText()
        text shouldContain "{\"range\": \"26-27\", \"result\": \"match\"}"
        text shouldContain "{\"range\": \"33-\", \"result\": \"no match\"}"
    }

    @Test
    fun `without the flag nothing is compared and the task fails`() {
        val failed = shouldThrow<GradleException> { task(SigningFixtures.signedA, ownerSigning = false).verify() }

        failed.message!! shouldContain "built without -Psaikou.ownerSigning=true"
        report.readText() shouldContain "\"ownerSigning\": false"
        report.readText() shouldContain "\"ranges\": []"
    }

    @Test
    fun `an earlier report is gone before the task decides anything`() {
        report.parentFile.mkdirs()
        report.writeText("{\"ownerSigning\": true, \"ranges\": [\"left by an earlier run\"]}")

        shouldThrow<GradleException> { task(SigningFixtures.signedB).verify() }

        report.readText() shouldNotContain "left by an earlier run"
        report.readText() shouldContain "\"no match\""
    }

    @Test
    fun `no digest of either key reaches the report or the failure`() {
        val failed = shouldThrow<GradleException> { task(SigningFixtures.rotatedFrom28).verify() }

        val written = report.readText() + failed.message
        (digestsOf(SigningFixtures.storeA, SigningFixtures.ALIAS_A) + digestsOf(SigningFixtures.storeB, SigningFixtures.ALIAS_B))
            .forEach { written.lowercase() shouldNotContain it.lowercase() }
        written shouldNotContain "BEGIN CERTIFICATE"
    }

    @Test
    fun `a directory without exactly one APK is refused`() {
        val empty = task(SigningFixtures.signedA).apply { File(projectDir, "apk/app-release.apk").delete() }

        val failed = shouldThrow<GradleException> { empty.verify() }

        failed.message!! shouldContain "expected one APK"
    }
}

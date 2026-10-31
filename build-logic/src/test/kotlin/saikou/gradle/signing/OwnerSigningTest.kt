package saikou.gradle.signing

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.gradle.api.GradleException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * `-Psaikou.ownerSigning=true` (phase 3 unit R1): the release keeps the debug key without the flag,
 * whether or not `keystore.properties` exists, and with the flag takes the owner's key or fails.
 */
class OwnerSigningTest {

    @TempDir
    lateinit var root: File

    private val properties get() = File(root, OwnerSigning.KEYSTORE_PROPERTIES)

    private fun writeProperties(vararg entries: Pair<String, String>) {
        properties.writeText(entries.joinToString("\n") { (key, value) -> "$key=$value" } + "\n")
    }

    private fun aStore(name: String = "owner.jks"): File = File(root, name).apply { writeText("not read here") }

    @Test
    fun `the flag is on as true, and off when absent or false`() {
        OwnerSigning.requested(null) shouldBe false
        OwnerSigning.requested("true") shouldBe true
        OwnerSigning.requested(" TRUE ") shouldBe true
        OwnerSigning.requested("false") shouldBe false
    }

    @Test
    fun `a blank value, which is what a bare flag reaches the build as, is refused rather than read as on`() {
        // Gradle hands a bare -Psaikou.ownerSigning over as "", the same as `=` with nothing after it.
        for (blank in listOf("", " ")) {
            val refused = shouldThrow<GradleException> { OwnerSigning.requested(blank) }
            refused.message shouldBe OwnerSigning.REFUSED
            refused.message shouldContain "-Psaikou.ownerSigning=true"
        }
    }

    @Test
    fun `any other value of the flag is refused rather than read as off, and the value is not repeated`() {
        val refused = shouldThrow<GradleException> { OwnerSigning.requested("yes-s3cret") }
        refused.message shouldBe OwnerSigning.REFUSED
        refused.message shouldContain "-Psaikou.ownerSigning"
        refused.message shouldNotContain "s3cret"
    }

    @Test
    fun `without the flag the release keeps the debug key when keystore properties is absent`() {
        OwnerSigning.releaseKey(requested = false, keystoreProperties = properties, rootDir = root) shouldBe ReleaseKey.Debug
    }

    @Test
    fun `without the flag the release keeps the debug key even when keystore properties exists`() {
        aStore()
        writeProperties("storeFile" to "owner.jks", "storePassword" to "s3cret", "keyAlias" to "owner", "keyPassword" to "k3y")

        OwnerSigning.releaseKey(requested = false, keystoreProperties = properties, rootDir = root) shouldBe ReleaseKey.Debug
    }

    @Test
    fun `with the flag and no keystore properties the release has no key and says why`() {
        val key = OwnerSigning.releaseKey(requested = true, keystoreProperties = properties, rootDir = root)

        key.shouldBeInstanceOf<ReleaseKey.Unavailable>()
        key.reason shouldContain "-Psaikou.ownerSigning=true was given and there is no"
        key.reason shouldContain OwnerSigning.KEYSTORE_PROPERTIES
    }

    @Test
    fun `with the flag and keystore properties the release takes the owner's key, a relative store from the root`() {
        val store = aStore()
        writeProperties("storeFile" to "owner.jks", "storePassword" to "s3cret", "keyAlias" to " owner ", "keyPassword" to "k3y")

        val key = OwnerSigning.releaseKey(requested = true, keystoreProperties = properties, rootDir = root)

        key.shouldBeInstanceOf<ReleaseKey.Owner>()
        key.storeFile shouldBe store
        key.storePassword shouldBe "s3cret"
        key.keyAlias shouldBe "owner"
        key.keyPassword shouldBe "k3y"
    }

    @Test
    fun `an absolute store file outside the repository is taken as it is`() {
        val elsewhere = File(root, "outside").apply { mkdirs() }
        val store = File(elsewhere, "kept.p12").apply { writeText("not read here") }
        val repository = File(root, "repo").apply { mkdirs() }
        val file = File(repository, OwnerSigning.KEYSTORE_PROPERTIES)
        file.writeText("storeFile=${store.absolutePath}\nstorePassword=a\nkeyAlias=b\nkeyPassword=c\n")

        val key = OwnerSigning.releaseKey(requested = true, keystoreProperties = file, rootDir = repository)

        key.shouldBeInstanceOf<ReleaseKey.Owner>()
        key.storeFile shouldBe store
    }

    @Test
    fun `a missing or blank key is named by its key and no value is repeated`() {
        aStore()
        writeProperties("storeFile" to "owner.jks", "storePassword" to "s3cret", "keyAlias" to " ")

        val key = OwnerSigning.releaseKey(requested = true, keystoreProperties = properties, rootDir = root)

        key.shouldBeInstanceOf<ReleaseKey.Unavailable>()
        key.reason shouldContain "has no value for keyAlias, keyPassword"
        key.reason shouldNotContain "s3cret"
    }

    @Test
    fun `a store file that is not there makes the key unavailable`() {
        writeProperties("storeFile" to "gone.jks", "storePassword" to "s3cret", "keyAlias" to "owner", "keyPassword" to "k3y")

        val key = OwnerSigning.releaseKey(requested = true, keystoreProperties = properties, rootDir = root)

        key.shouldBeInstanceOf<ReleaseKey.Unavailable>()
        key.reason shouldContain "is not a file"
        key.reason shouldNotContain "s3cret"
    }

    @Test
    fun `the owner key prints without its passwords`() {
        val key = ReleaseKey.Owner(File("/k.jks"), storePassword = "s3cret", keyAlias = "owner", keyPassword = "k3y")

        key.toString() shouldNotContain "s3cret"
        key.toString() shouldNotContain "k3y"
    }
}

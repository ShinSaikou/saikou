package com.shinsaikou.appupdate

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** The release asset's `digest` field, read and compared against the downloaded file's SHA-256. */
class AssetDigestTest {

    private fun sha256(text: String): ByteArray = AssetDigest.newSha256().digest(text.toByteArray())

    @Test
    fun `a published sha256 accepts the file that hashes to it and nothing else`() {
        val digest = AssetDigest.parse("sha256:$ABC_SHA256")

        digest shouldBe AssetDigest.Sha256(ABC_SHA256)
        digest.accepts(sha256("abc")) shouldBe true
        digest.accepts(sha256("abd")) shouldBe false
        digest.accepts(ByteArray(0)) shouldBe false
    }

    @Test
    fun `upper case hex reads the same`() {
        val digest = AssetDigest.parse("sha256:${ABC_SHA256.uppercase()}")

        digest shouldBe AssetDigest.Sha256(ABC_SHA256)
        digest.accepts(sha256("abc")) shouldBe true
    }

    @Test
    fun `no digest leaves the decision to the signature check`() {
        AssetDigest.parse(null) shouldBe AssetDigest.Absent
        AssetDigest.parse("") shouldBe AssetDigest.Absent
        AssetDigest.parse(null).accepts(sha256("anything")) shouldBe true
    }

    @Test
    fun `a digest this build cannot read refuses every file`() {
        listOf(
            "md5:900150983cd24fb0d6963f7d28e17f72",
            "sha256:xyz",
            "sha256:${ABC_SHA256.dropLast(1)}",
            "sha512:$ABC_SHA256$ABC_SHA256",
            ABC_SHA256,
        ).forEach { field ->
            val digest = AssetDigest.parse(field)
            digest.shouldBeInstanceOf<AssetDigest.Unreadable>()
            digest.accepts(sha256("abc")) shouldBe false
        }
    }

    private companion object {
        /** FIPS 180-2's first SHA-256 example, the digest of "abc". */
        const val ABC_SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }
}

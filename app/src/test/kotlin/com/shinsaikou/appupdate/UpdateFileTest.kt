package com.shinsaikou.appupdate

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/** The download's write: kept only when it hashes to the release's digest, deleted on any failure. */
class UpdateFileTest {

    @TempDir
    lateinit var directory: File

    private val target: File
        get() = File(directory, "update/0.0.2.apk")

    private val bytes = "abc".toByteArray()

    @Test
    fun `a file that matches the digest is kept whole, with its progress reported`() = runTest {
        val progress = mutableListOf<Pair<Long, Long>>()

        UpdateFile.save(ByteArrayInputStream(bytes), bytes.size.toLong(), target, AssetDigest.parse("sha256:$ABC_SHA256")) { done, total ->
            progress += done to total
        }

        target.readBytes().toList() shouldBe bytes.toList()
        progress shouldContainExactly listOf(3L to 3L)
    }

    @Test
    fun `a file that does not match the digest is deleted and refused`() = runTest {
        shouldThrow<DigestMismatchException> {
            UpdateFile.save(ByteArrayInputStream("abd".toByteArray()), 3L, target, AssetDigest.parse("sha256:$ABC_SHA256"))
        }

        target.exists() shouldBe false
    }

    @Test
    fun `a digest this build cannot read refuses the file`() = runTest {
        shouldThrow<DigestMismatchException> {
            UpdateFile.save(ByteArrayInputStream(bytes), 3L, target, AssetDigest.parse("md5:900150983cd24fb0d6963f7d28e17f72"))
        }

        target.exists() shouldBe false
    }

    @Test
    fun `a file with no published digest is kept for the signature check`() = runTest {
        UpdateFile.save(ByteArrayInputStream(bytes), -1L, target, AssetDigest.Absent)

        target.readBytes().toList() shouldBe bytes.toList()
    }

    @Test
    fun `a download that breaks off leaves no file behind`() = runTest {
        val broken = object : InputStream() {
            private var sent = false
            override fun read(): Int = throw IOException("connection reset")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (sent) throw IOException("connection reset")
                sent = true
                b[off] = 'a'.code.toByte()
                return 1
            }
        }

        shouldThrow<IOException> { UpdateFile.save(broken, 3L, target, AssetDigest.Absent) }

        target.exists() shouldBe false
    }

    private companion object {
        const val ABC_SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }
}

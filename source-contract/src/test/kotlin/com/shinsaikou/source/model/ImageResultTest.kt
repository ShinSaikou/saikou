package com.shinsaikou.source.model

import io.kotest.matchers.shouldBe
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ImageResultTest {

    private val payload = byteArrayOf(1, 2, 3, 4)

    private fun streaming(onClose: () -> Unit = {}) = ImageResult.ofSource(
        source = Buffer().write(payload),
        contentType = "Image/PNG",
        contentLength = payload.size.toLong(),
        imageUrl = "https://example.invalid/1.png",
        onClose = onClose,
    )

    @Test
    fun `the content type is lowercased`() {
        streaming().use { it.contentType shouldBe "image/png" }
        ImageResult.ofBytes(payload, contentType = "IMAGE/JPEG").contentType shouldBe "image/jpeg"
    }

    @Test
    fun `ofBytes reports the array length`() {
        val result = ImageResult.ofBytes(payload, contentType = null)

        result.contentLength shouldBe payload.size.toLong()
        result.imageUrl shouldBe null
    }

    @Test
    fun `source then close calls onClose once`() {
        var closes = 0
        val result = streaming { closes++ }

        result.source().readByteArray() shouldBe payload
        result.close()
        result.close()

        closes shouldBe 1
    }

    @Test
    fun `a second source call throws`() {
        streaming().use { result ->
            result.source()
            assertThrows<IllegalStateException> { result.source() }
        }
    }

    @Test
    fun `bytes after close throws`() {
        val result = streaming()
        result.close()

        assertThrows<IllegalStateException> { result.bytes() }
        assertThrows<IllegalStateException> { result.source() }
    }

    @Test
    fun `bytes drains the stream`() {
        streaming().use { it.bytes() shouldBe payload }
    }

    @Test
    fun `bytes after source throws`() {
        streaming().use { result ->
            result.source()
            assertThrows<IllegalStateException> { result.bytes() }
        }
    }

    @Test
    fun `ofBytes serves the array through either accessor`() {
        ImageResult.ofBytes(payload, contentType = null).use { it.bytes() shouldBe payload }
        ImageResult.ofBytes(payload, contentType = null)
            .use { it.source().readByteArray() shouldBe payload }
    }

    @Test
    fun `closing an ofBytes result is a no-op that still blocks the accessors`() {
        val result = ImageResult.ofBytes(payload, contentType = null)
        result.close()

        assertThrows<IllegalStateException> { result.bytes() }
    }
}

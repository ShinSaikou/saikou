package com.shinsaikou.extensions.adapter

import com.shinsaikou.source.model.ImageResult
import com.shinsaikou.source.model.PageInfo
import io.kotest.matchers.shouldBe
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The adapter hands the reader an [ImageResult] backed by a live OkHttp response. Whoever holds it
 * owns it, so closing it exactly once has to close the response exactly once.
 */
class ImageResultBridgeTest {

    private val closes = AtomicInteger()

    private fun response(bytes: ByteArray, contentType: String = "image/jpeg"): Response = Response.Builder()
        .request(Request.Builder().url("https://example.invalid/1.jpg").build())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .header("Content-Type", contentType)
        .body(bytes.toResponseBody(contentType.toMediaType()))
        .build()

    private fun imageResult(response: Response): ImageResult {
        val body = response.body
        return ImageResult.ofSource(
            source = body.source(),
            contentType = response.header("Content-Type"),
            contentLength = body.contentLength(),
            imageUrl = "https://example.invalid/1.jpg",
            onClose = {
                closes.incrementAndGet()
                response.close()
            },
        )
    }

    @Test
    fun `closing the result closes the response once`() {
        val result = imageResult(response(ByteArray(4) { 1 }))

        result.close()
        result.close()

        closes.get() shouldBe 1
    }

    @Test
    fun `the body is readable through the stream and the headers are carried over`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())

        imageResult(response(bytes)).use { result ->
            result.contentType shouldBe "image/jpeg"
            result.contentLength shouldBe 4L
            result.imageUrl shouldBe "https://example.invalid/1.jpg"
            result.bytes() shouldBe bytes
        }

        closes.get() shouldBe 1
    }

    @Test
    fun `a page's progress reaches the contract model through the bridged ABI page`() {
        val info = PageInfo(index = 0, url = "/page/1")
        val page = BridgedPage(info, imageUrl = null)

        page.update(bytesRead = 50, contentLength = 100, done = false)
        info.progress.value shouldBe 50
        page.progress shouldBe 50

        page.update(bytesRead = 100, contentLength = 100, done = true)
        info.progress.value shouldBe 100
    }

    @Test
    fun `an unknown content length reports indeterminate progress`() {
        val info = PageInfo(index = 1)
        val page = BridgedPage(info, imageUrl = "https://example.invalid/2.jpg")

        page.update(bytesRead = 10, contentLength = 0, done = false)

        info.progress.value shouldBe -1
        page.imageUrl shouldBe "https://example.invalid/2.jpg"
    }
}

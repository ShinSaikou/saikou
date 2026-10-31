package com.shinsaikou.reader.viewer

import android.content.Context
import android.content.res.Resources
import com.shinsaikou.i18n.R
import com.shinsaikou.reader.pipeline.ImageFormat
import com.shinsaikou.reader.pipeline.UnsupportedImageFormatException
import com.shinsaikou.source.SourceNotInstalledException
import eu.kanade.tachiyomi.network.HttpException
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * What a failed page says. Each string resolves to its own marker, so a test reads which text the
 * holder would show, and never the exception's own message.
 */
class PageErrorMessageTest {

    private val resources = mockk<Resources> {
        every { getString(R.string.error_no_network) } returns "no network"
        every { getString(R.string.error_timeout) } returns "timeout"
        every { getString(R.string.error_cloudflare) } returns "cloudflare"
        listOf(403, 404, 503).forEach { code ->
            every { getString(R.string.error_http_format, code) } returns "http $code"
        }
    }

    private val context = mockk<Context> {
        every { resources } returns this@PageErrorMessageTest.resources
        every { getString(R.string.decode_image_error) } returns "could not be loaded"
        every { getString(R.string.reader_page_decode_error) } returns "could not be read"
        every { getString(R.string.loader_not_implemented_error) } returns "source not found"
        every { getString(R.string.reader_unsupported_format, "JP2") } returns "unsupported JP2"
    }

    @Test
    fun `a status code, a timeout and being offline each say so`() {
        context.pageErrorMessage(HttpException(404)) shouldBe "http 404"
        context.pageErrorMessage(HttpException(503)) shouldBe "http 503"
        context.pageErrorMessage(SocketTimeoutException("read timed out")) shouldBe "timeout"
        context.pageErrorMessage(UnknownHostException("no address")) shouldBe "no network"
        context.pageErrorMessage(ConnectException("refused")) shouldBe "no network"
    }

    @Test
    fun `a network failure wrapped by an interceptor still says what it was`() {
        context.pageErrorMessage(IOException("wrapper", HttpException(403))) shouldBe "http 403"
        context.pageErrorMessage(IOException("wrapper", SocketTimeoutException())) shouldBe "timeout"
    }

    @Test
    fun `bytes the image view could not decode say the image could not be read`() {
        context.pageErrorMessage(PageDecodeException(IllegalStateException("decoder"))) shouldBe "could not be read"
        context.pageErrorMessage(PageDecodeException(null)) shouldBe "could not be read"
    }

    @Test
    fun `the two failures named before keep their text`() {
        context.pageErrorMessage(UnsupportedImageFormatException(ImageFormat.JP2)) shouldBe "unsupported JP2"
        context.pageErrorMessage(SourceNotInstalledException(7L)) shouldBe "source not found"
    }

    @Test
    fun `anything else is the generic text and never its own message`() {
        context.pageErrorMessage(IllegalStateException("https://example.invalid/page/3")) shouldBe
            "could not be loaded"
        context.pageErrorMessage(IOException("example.invalid closed the stream")) shouldBe "could not be loaded"
        context.pageErrorMessage(null) shouldBe "could not be loaded"
    }
}

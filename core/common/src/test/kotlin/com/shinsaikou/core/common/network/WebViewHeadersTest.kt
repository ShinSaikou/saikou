package com.shinsaikou.core.common.network

import eu.kanade.tachiyomi.util.system.toWebViewHeaders
import io.kotest.matchers.shouldBe
import okhttp3.Headers.Companion.headersOf
import org.junit.jupiter.api.Test

/**
 * What a WebView is given of a request's headers. One it refuses fails the load, and an Origin that
 * does not match the page kills the renderer, which took the app with it.
 */
class WebViewHeadersTest {

    @Test
    fun `an Origin header is never passed on, whatever its case`() {
        val headers = headersOf(
            "Referer", "https://example.invalid/",
            "Origin", "https://example.invalid",
        )

        headers.toWebViewHeaders() shouldBe mapOf("Referer" to "https://example.invalid/")
        mapOf("origin" to "https://example.invalid", "referer" to "https://example.invalid/")
            .toWebViewHeaders() shouldBe mapOf("referer" to "https://example.invalid/")
    }

    @Test
    fun `headers the network stack owns are dropped and a repeated one keeps its first value`() {
        val headers = headersOf(
            "Host", "example.invalid",
            "Content-Length", "12",
            "Proxy-Authorization", "Basic Zml4dHVyZQ==",
            "Connection", "Upgrade",
            "Accept", "text/html",
            "Accept", "image/webp",
        )

        headers.toWebViewHeaders() shouldBe mapOf("Accept" to "text/html")
    }
}

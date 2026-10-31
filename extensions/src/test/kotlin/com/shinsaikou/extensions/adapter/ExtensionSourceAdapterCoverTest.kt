package com.shinsaikou.extensions.adapter

import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSource
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import eu.kanade.tachiyomi.source.model.FilterList as AbiFilterList

/**
 * Covers the point of the whole fix: a cover request for an installed [HttpSource] delegate must
 * go through that source's own client and headers, the way its image host expects, not the app's
 * bare shared client. See [ExtensionSourceAdapter.getCoverImage].
 */
class ExtensionSourceAdapterCoverTest {

    private val coverUrl = "https://example.invalid/covers/1.jpg"
    private val coverBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())

    /**
     * A fixture [HttpSource] whose [client] is a plain [OkHttpClient] wired to [interceptor], never
     * touching the Injekt-backed `network` property that a real extension would use. The host
     * behaves like a hotlink-protected image server: it answers 403 without the source's own
     * Referer header and 200 with it, exactly as [headersBuilder] adds it.
     */
    private class FixtureHttpSource(
        private val interceptor: RecordingInterceptor,
        /** Off for the 403 test: a source whose headersBuilder adds no Referer of its own. */
        private val sendsReferer: Boolean = true,
    ) : HttpSource() {
        override val baseUrl: String = "https://example.invalid"
        override val name: String = "Fixture Source A"
        override val lang: String = "en"
        override val supportsLatest: Boolean = false
        override val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()

        override fun headersBuilder(): Headers.Builder =
            Headers.Builder().apply { if (sendsReferer) add("Referer", "$baseUrl/") }

        override suspend fun getPopularManga(page: Int): MangasPage = throw UnsupportedOperationException()
        override suspend fun getLatestUpdates(page: Int): MangasPage = throw UnsupportedOperationException()
        override suspend fun getSearchManga(page: Int, query: String, filters: AbiFilterList): MangasPage =
            throw UnsupportedOperationException()
        override suspend fun getMangaUpdate(
            manga: SManga,
            chapters: List<SChapter>,
            fetchDetails: Boolean,
            fetchChapters: Boolean,
        ): SMangaUpdate = throw UnsupportedOperationException()
        override suspend fun getPageList(chapter: SChapter): List<Page> = throw UnsupportedOperationException()
    }

    /**
     * Records every request the client sent and answers like a Referer-checking host: 403 with an
     * HTML body when the header is missing, 200 with the fixture image bytes when it is present.
     * The 200 body is wrapped in [CountingResponseBody] so a test can prove the adapter's `onClose`
     * actually reaches this response, without relying on [okhttp3.ResponseBody]'s own close being
     * idempotent across repeated `source()` calls.
     */
    private class RecordingInterceptor(
        private val bodyBytes: ByteArray,
        private val closes: AtomicInteger? = null,
    ) : okhttp3.Interceptor {
        val seenRequests = mutableListOf<okhttp3.Request>()

        override fun intercept(chain: okhttp3.Interceptor.Chain): Response {
            val request = chain.request()
            seenRequests.add(request)
            return if (request.header("Referer") == "https://example.invalid/") {
                val body = bodyBytes.toResponseBody("image/jpeg".toMediaType())
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "image/jpeg")
                    .body(if (closes != null) CountingResponseBody(body, closes) else body)
                    .build()
            } else {
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(403)
                    .message("Forbidden")
                    .header("Content-Type", "text/html")
                    .body("<html>denied</html>".toResponseBody("text/html".toMediaType()))
                    .build()
            }
        }
    }

    /**
     * Wraps a real [ResponseBody] and counts calls to [close], regardless of how many times
     * [source] is read. `Response.close()` calls `body.close()` exactly once per invocation, so
     * this is the one reliable way to count how many times the underlying response was released.
     */
    private class CountingResponseBody(
        private val delegate: ResponseBody,
        private val closes: AtomicInteger,
    ) : ResponseBody() {
        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()
        override fun source(): BufferedSource = delegate.source()
        override fun close() {
            closes.incrementAndGet()
            delegate.close()
        }
    }

    private fun adapterFor(source: eu.kanade.tachiyomi.source.Source) =
        SourceAdapters.wrap(source, "com.shinsaikou.fixture", 1.6)

    @Test
    fun `the cover request goes through the source's own client with its Referer and carries the bytes`() = runTest {
        val interceptor = RecordingInterceptor(coverBytes)
        val adapter = adapterFor(FixtureHttpSource(interceptor))

        val result = adapter.getCoverImage(coverUrl)!!

        result.use {
            it.bytes() shouldBe coverBytes
            it.contentType shouldBe "image/jpeg"
            it.imageUrl shouldBe coverUrl
        }
        interceptor.seenRequests.single().header("Referer") shouldBe "https://example.invalid/"
    }

    @Test
    fun `closing the result closes the response exactly once`() = runTest {
        val closes = AtomicInteger()
        val interceptor = RecordingInterceptor(coverBytes, closes)
        val adapter = adapterFor(FixtureHttpSource(interceptor))

        val result = adapter.getCoverImage(coverUrl)!!
        result.close()
        result.close()

        closes.get() shouldBe 1
    }

    @Test
    fun `a 403 from the host throws HttpException with the response code`() = runTest {
        val interceptor = RecordingInterceptor(coverBytes)
        val adapter = adapterFor(FixtureHttpSource(interceptor, sendsReferer = false))

        val thrown = shouldThrow<HttpException> { adapter.getCoverImage(coverUrl) }

        thrown.code shouldBe 403
    }

    @Test
    fun `a delegate that is not an HttpSource returns null without any network call`() = runTest {
        val fakeSource = FakeSource(filters = { AbiFilterList() })
        val adapter = adapterFor(fakeSource)

        adapter.getCoverImage(coverUrl) shouldBe null
    }
}

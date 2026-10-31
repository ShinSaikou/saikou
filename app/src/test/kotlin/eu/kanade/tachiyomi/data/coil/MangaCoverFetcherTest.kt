// Fresh: exercises the rewritten fetcher (contract 08 section 3.4), the fix for blank covers on
// Referer-checking hosts: a download must ask the entry's source first and fall back to the bare
// shared client only when the source has none of its own.
package eu.kanade.tachiyomi.data.coil

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.shinsaikou.source.CatalogueSource
import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceManager
import com.shinsaikou.source.StubSource
import com.shinsaikou.source.model.ChapterInfo
import com.shinsaikou.source.model.ImageResult
import com.shinsaikou.source.model.MangaInfo
import com.shinsaikou.source.model.PageInfo
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.network.HttpException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.FileSystem
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.manga.model.MangaCover
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class MangaCoverFetcherTest {

    @TempDir
    lateinit var tempDir: File

    // Built in setUp, not as property initializers: @TempDir injects tempDir by reflection after
    // the constructor runs, so a property initializer that reads it here would see it unset.
    private lateinit var context: Context
    private lateinit var coverCache: CoverCache
    private lateinit var options: Options

    private val coverBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())

    @BeforeEach
    fun setUp() {
        mockkStatic(Uri::class)
        context = mockk {
            every { filesDir } returns tempDir
        }
        coverCache = CoverCache(context)
        options = mockk {
            every { fileSystem } returns FileSystem.SYSTEM
            every { context } returns this@MangaCoverFetcherTest.context
        }
    }

    @AfterEach
    fun tearDown() = unmockkStatic(Uri::class)

    private fun cover(
        url: String? = "https://example.invalid/covers/1.jpg",
        sourceId: Long = SOURCE_ID,
        isFavorite: Boolean = false,
    ) = MangaCover(
        mangaId = MANGA_ID,
        sourceId = sourceId,
        isMangaFavorite = isFavorite,
        url = url,
        lastModified = 0L,
    )

    /** A source whose [getCoverImage] is supplied per test; everything else is out of scope. */
    private class FakeCoverSource(
        override val id: Long,
        private val coverImage: suspend (String) -> ImageResult?,
    ) : Source {
        override val name: String = "Fixture Source A"
        override val lang: String = "en"
        val coverImageCalls = AtomicInteger()

        override suspend fun getCoverImage(url: String): ImageResult? {
            coverImageCalls.incrementAndGet()
            return coverImage(url)
        }

        override suspend fun getMangaDetails(manga: MangaInfo): MangaInfo = throw UnsupportedOperationException()
        override suspend fun getChapterList(manga: MangaInfo): List<ChapterInfo> = throw UnsupportedOperationException()
        override suspend fun getPageList(chapter: ChapterInfo): List<PageInfo> = throw UnsupportedOperationException()
        override suspend fun getImage(page: PageInfo): ImageResult = throw UnsupportedOperationException()
    }

    /** A controllable registry: [isInitialized] starts however the test needs and can flip later. */
    private class FakeSourceManager(
        initialized: Boolean,
        private val sources: Map<Long, Source> = emptyMap(),
    ) : SourceManager {
        private val initializedFlow = MutableStateFlow(initialized)
        override val isInitialized: StateFlow<Boolean> get() = initializedFlow
        fun setInitialized(value: Boolean) {
            initializedFlow.value = value
        }
        override fun get(id: Long): Source? = sources[id]
        override fun getOrStub(id: Long): Source = StubSource(id, "Fixture Source A", "en")
        override fun getCatalogueSources(): Flow<List<CatalogueSource>> = throw UnsupportedOperationException()
        override fun getSources(): Flow<List<Source>> = throw UnsupportedOperationException()
        override fun getStubSources(): Flow<List<StubSource>> = throw UnsupportedOperationException()
    }

    /** Records every request and answers with the fixture bytes; proves whether it was ever called. */
    private class RecordingCallFactory(bytes: ByteArray) : Call.Factory {
        val requests = mutableListOf<Request>()
        private val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    requests.add(request)
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "image/jpeg")
                        .body(bytes.toResponseBody("image/jpeg".toMediaType()))
                        .build()
                },
            )
            .build()
        override fun newCall(request: Request): Call = client.newCall(request)
    }

    private class FailingCallFactory : Call.Factory {
        val called = AtomicInteger()
        override fun newCall(request: Request): Call {
            called.incrementAndGet()
            throw AssertionError("the shared client must not be used")
        }
    }

    private fun fetcher(
        cover: MangaCover,
        callFactory: Call.Factory = FailingCallFactory(),
        sourceManager: SourceManager = FakeSourceManager(initialized = true),
    ) = MangaCoverFetcher(
        cover = cover,
        options = options,
        callFactory = { callFactory },
        coverCache = coverCache,
        sourceManager = { sourceManager },
    )

    private fun SourceFetchResult.bytes(): ByteArray = source.source().use { it.readByteArray() }

    @Test
    fun `an installed source's getCoverImage serves the cover and the shared client is never called`() = runTest {
        val shared = RecordingCallFactory(byteArrayOf(0))
        val fakeSource = FakeCoverSource(SOURCE_ID) { url -> ImageResult.ofBytes(coverBytes, "image/jpeg", url) }
        val registry = FakeSourceManager(initialized = true, sources = mapOf(SOURCE_ID to fakeSource))

        val result = fetcher(cover(), callFactory = shared, sourceManager = registry).fetch() as SourceFetchResult

        result.bytes() shouldBe coverBytes
        fakeSource.coverImageCalls.get() shouldBe 1
        shared.requests shouldBe emptyList()
    }

    @Test
    fun `a source whose getCoverImage returns null falls back to the shared client`() = runTest {
        val shared = RecordingCallFactory(coverBytes)
        val fakeSource = FakeCoverSource(SOURCE_ID) { null }
        val registry = FakeSourceManager(initialized = true, sources = mapOf(SOURCE_ID to fakeSource))

        val result = fetcher(cover(), callFactory = shared, sourceManager = registry).fetch() as SourceFetchResult

        result.bytes() shouldBe coverBytes
        fakeSource.coverImageCalls.get() shouldBe 1
        shared.requests.size shouldBe 1
    }

    @Test
    fun `a source id the registry does not have falls back to the shared client`() = runTest {
        val shared = RecordingCallFactory(coverBytes)
        val registry = FakeSourceManager(initialized = true, sources = emptyMap())

        val result = fetcher(cover(sourceId = 999L), callFactory = shared, sourceManager = registry).fetch() as SourceFetchResult

        result.bytes() shouldBe coverBytes
        shared.requests.size shouldBe 1
    }

    @Test
    fun `a fetch started before the registry is initialized waits and then uses the source, not the bare request`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val shared = RecordingCallFactory(byteArrayOf(0))
        val fakeSource = FakeCoverSource(SOURCE_ID) { url -> ImageResult.ofBytes(coverBytes, "image/jpeg", url) }
        val registry = FakeSourceManager(initialized = false, sources = mapOf(SOURCE_ID to fakeSource))

        var result: SourceFetchResult? = null
        val job = launch(dispatcher) {
            result = fetcher(cover(), callFactory = shared, sourceManager = registry).fetch() as SourceFetchResult
        }
        dispatcher.scheduler.advanceUntilIdle()

        // Still waiting: the flow never emitted true, so the source and the network are untouched.
        result shouldBe null
        fakeSource.coverImageCalls.get() shouldBe 0
        shared.requests shouldBe emptyList()

        registry.setInitialized(true)
        dispatcher.scheduler.advanceUntilIdle()
        job.join()

        result!!.bytes() shouldBe coverBytes
        fakeSource.coverImageCalls.get() shouldBe 1
        shared.requests shouldBe emptyList()
    }

    @Test
    fun `a favourite's cover is written atomically to the cover cache file with no tmp file left behind`() = runTest {
        val fakeSource = FakeCoverSource(SOURCE_ID) { url -> ImageResult.ofBytes(coverBytes, "image/jpeg", url) }
        val registry = FakeSourceManager(initialized = true, sources = mapOf(SOURCE_ID to fakeSource))
        val favourite = cover(isFavorite = true)

        fetcher(favourite, sourceManager = registry).fetch()

        val target = coverCache.getCoverFile(favourite.url)!!
        target.exists() shouldBe true
        target.readBytes() shouldBe coverBytes
        File(target.parentFile, "${target.name}.tmp").exists() shouldBe false
    }

    @Test
    fun `a second fetch of a favourite's cover reads it from disk without asking the source again`() = runTest {
        val fakeSource = FakeCoverSource(SOURCE_ID) { url -> ImageResult.ofBytes(coverBytes, "image/jpeg", url) }
        val registry = FakeSourceManager(initialized = true, sources = mapOf(SOURCE_ID to fakeSource))
        val favourite = cover(isFavorite = true)

        fetcher(favourite, sourceManager = registry).fetch()
        val second = fetcher(favourite, sourceManager = registry).fetch() as SourceFetchResult

        second.bytes() shouldBe coverBytes
        fakeSource.coverImageCalls.get() shouldBe 1
    }

    @Test
    fun `a non-favourite's cover is not written to the cover cache`() = runTest {
        val fakeSource = FakeCoverSource(SOURCE_ID) { url -> ImageResult.ofBytes(coverBytes, "image/jpeg", url) }
        val registry = FakeSourceManager(initialized = true, sources = mapOf(SOURCE_ID to fakeSource))
        val entry = cover(isFavorite = false)

        val result = fetcher(entry, sourceManager = registry).fetch() as SourceFetchResult

        result.bytes() shouldBe coverBytes
        coverCache.getCoverFile(entry.url)!!.exists() shouldBe false
    }

    @Test
    fun `a source that throws HttpException 403 makes fetch throw and leaves no cover cache file or tmp file`() = runTest {
        val fakeSource = FakeCoverSource(SOURCE_ID) { throw HttpException(403) }
        val registry = FakeSourceManager(initialized = true, sources = mapOf(SOURCE_ID to fakeSource))
        val favourite = cover(isFavorite = true)

        shouldThrow<HttpException> { fetcher(favourite, sourceManager = registry).fetch() }

        val target = coverCache.getCoverFile(favourite.url)!!
        target.exists() shouldBe false
        File(target.parentFile, "${target.name}.tmp").exists() shouldBe false
    }

    @Test
    fun `an absolute path cover url is read from disk with no source call and no network`() = runTest {
        val deviceFile = File(tempDir, "local-cover.jpg").apply { writeBytes(coverBytes) }
        val fakeSource = FakeCoverSource(SOURCE_ID) { fail("the source must not be asked for a device file") }
        val shared = FailingCallFactory()
        val registry = FakeSourceManager(initialized = true, sources = mapOf(SOURCE_ID to fakeSource))

        val result = fetcher(cover(url = deviceFile.absolutePath), callFactory = shared, sourceManager = registry)
            .fetch() as SourceFetchResult

        result.bytes() shouldBe coverBytes
        fakeSource.coverImageCalls.get() shouldBe 0
        shared.called.get() shouldBe 0
    }

    @Test
    fun `a content uri cover is read through the content resolver with no source call and no network`() = runTest {
        val uri = "content://fixture.provider/covers/1"
        val fakeUri: Uri = mockk()
        every { Uri.parse(uri) } returns fakeUri
        val resolver: ContentResolver = mockk {
            every { openInputStream(fakeUri) } returns ByteArrayInputStream(coverBytes)
        }
        every { context.contentResolver } returns resolver
        val fakeSource = FakeCoverSource(SOURCE_ID) { fail("the source must not be asked for a device file") }
        val shared = FailingCallFactory()
        val registry = FakeSourceManager(initialized = true, sources = mapOf(SOURCE_ID to fakeSource))

        val result = fetcher(cover(url = uri), callFactory = shared, sourceManager = registry).fetch() as SourceFetchResult

        result.bytes() shouldBe coverBytes
        fakeSource.coverImageCalls.get() shouldBe 0
        shared.called.get() shouldBe 0
    }

    @Test
    fun `an existing custom cover file wins over everything else`() = runTest {
        val customBytes = byteArrayOf(9, 9, 9)
        coverCache.getCustomCoverFile(MANGA_ID).apply {
            parentFile?.mkdirs()
            writeBytes(customBytes)
        }
        val fakeSource = FakeCoverSource(SOURCE_ID) { fail("a custom cover must short-circuit before the source") }
        val shared = FailingCallFactory()
        val registry = FakeSourceManager(initialized = true, sources = mapOf(SOURCE_ID to fakeSource))

        val result = fetcher(cover(isFavorite = true), callFactory = shared, sourceManager = registry)
            .fetch() as SourceFetchResult

        result.bytes() shouldBe customBytes
        fakeSource.coverImageCalls.get() shouldBe 0
        shared.called.get() shouldBe 0
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)

    private companion object {
        const val MANGA_ID = 1L
        const val SOURCE_ID = 42L
    }
}

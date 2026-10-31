package com.shinsaikou.reader.pipeline

import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.preferences.ReaderPreferences
import com.shinsaikou.reader.cache.ChapterCache
import com.shinsaikou.reader.state.PreloadPolicy
import com.shinsaikou.source.Source
import com.shinsaikou.source.model.ChapterInfo
import com.shinsaikou.source.model.ImageResult
import com.shinsaikou.source.model.MangaInfo
import com.shinsaikou.source.model.PageInfo
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers as CoroutineDispatchers

/**
 * The online loader against a fake source and a mocked cache. Real IO dispatchers, because the
 * worker blocks in `PriorityBlockingQueue.take()` inside `runInterruptible`; every wait is bounded
 * by [TIMEOUT_MS] and every loader is recycled after the test, so no worker thread outlives it.
 */
class SourcePageLoaderTest {

    private val chapter = ReaderChapter(Chapter.create().copy(id = 5, mangaId = 1, url = "/c", name = "c"))
    private val cache = mockk<ChapterCache>(relaxed = true)
    private val probe = mockk<ImageProbe>()
    private val splitter = mockk<TallStripSplitter>()
    private val source = FakeSource()

    private val loaders = mutableListOf<PageLoader>()
    private val holder = CoroutineScope(SupervisorJob() + CoroutineDispatchers.IO)

    @AfterEach
    fun tearDown() {
        holder.cancel()
        loaders.forEach { it.recycle() }
    }

    // ---- getPages ----

    @Test
    fun `the page list is re-indexed and the cache is preferred`() = runBlocking<Unit> {
        every { cache.getPageListFromCache(any()) } returns listOf(
            PageInfo(index = 9, url = "/a"),
            PageInfo(index = 9, url = "/b"),
        )

        val pages = loader().getPages()

        pages.map { it.index } shouldContainExactly listOf(0, 1)
        pages.map { it.url } shouldContainExactly listOf("/a", "/b")
        source.pageListCalls shouldBe 0
    }

    @Test
    fun `a cache miss falls through to the source`() = runBlocking<Unit> {
        cacheMiss()

        loader().getPages().map { it.index } shouldContainExactly listOf(0, 1, 2)
        source.pageListCalls shouldBe 1
    }

    // ---- The queue ----

    @Test
    fun `the queue orders retry before default before adjacent, then by arrival`() {
        val page = ReaderPage(0)
        val retry = PriorityPage(page, PriorityPage.RETRY)
        val default = PriorityPage(page, PriorityPage.DEFAULT)
        val adjacent = PriorityPage(page, PriorityPage.ADJACENT)
        val secondDefault = PriorityPage(page, PriorityPage.DEFAULT)

        listOf(adjacent, secondDefault, retry, default).sorted() shouldContainExactly
            listOf(retry, default, secondDefault, adjacent)
    }

    // ---- Fetching ----

    @Test
    fun `loading a page fetches it and exactly the fetch window after it`() = runBlocking<Unit> {
        cacheMiss()
        readyProbe()
        val loader = loader(fetchAhead = 1)
        val pages = loader.pagesOfChapter()

        loader.loadInBackground(pages[0])
        awaitReady(pages[0])
        awaitReady(pages[1])

        Thread.sleep(QUIET_MS)
        pages[2].status shouldBe PageState.Queue
        source.calls().sorted() shouldContainExactly listOf("/i0", "/i1")
    }

    @Test
    fun `a page already in the cache is not downloaded again`() = runBlocking<Unit> {
        cacheMiss()
        every { cache.isImageInCache(any()) } returns true
        readyProbe()
        val loader = loader(fetchAhead = 0)
        val pages = loader.pagesOfChapter()

        loader.loadInBackground(pages[0])
        awaitReady(pages[0])

        source.calls() shouldContainExactly emptyList()
    }

    @Test
    fun `a failure no wait can fix sets Error and is not retried`() = runBlocking<Unit> {
        cacheMiss()
        readyProbe()
        source.failWith = IllegalStateException("no bytes")
        val loader = loader(fetchAhead = 0)
        val pages = loader.pagesOfChapter()

        loader.loadInBackground(pages[0])
        await { pages[0].status is PageState.Error }

        Thread.sleep(QUIET_MS)
        source.calls().size shouldBe 1
    }

    @Test
    fun `a not found page is not retried`() = runBlocking<Unit> {
        cacheMiss()
        readyProbe()
        source.failWith = HttpException(404)
        val loader = loader(fetchAhead = 0)
        val pages = loader.pagesOfChapter()

        loader.loadInBackground(pages[0])
        await { pages[0].status is PageState.Error }

        Thread.sleep(QUIET_MS)
        source.calls().size shouldBe 1
    }

    @Test
    fun `a timeout is retried once and the page comes through`() = runBlocking<Unit> {
        cacheMiss()
        readyProbe()
        source.failWith = SocketTimeoutException("read timed out")
        source.failures = 1
        val loader = loader(fetchAhead = 0)
        val pages = loader.pagesOfChapter()

        loader.loadInBackground(pages[0])
        awaitReady(pages[0])

        source.calls() shouldContainExactly listOf("/i0", "/i0")
    }

    @Test
    fun `a transient failure is retried only once`() = runBlocking<Unit> {
        cacheMiss()
        readyProbe()
        source.failWith = HttpException(503)
        val loader = loader(fetchAhead = 0)
        val pages = loader.pagesOfChapter()

        loader.loadInBackground(pages[0])
        await { pages[0].status is PageState.Error }

        Thread.sleep(QUIET_MS)
        source.calls().size shouldBe 2
        (pages[0].status as PageState.Error).error.shouldBeInstanceOf<HttpException>()
    }

    @Test
    fun `only a timeout, a dropped connection and a busy or broken server count as transient`() {
        listOf(
            SocketTimeoutException(),
            InterruptedIOException("timeout"),
            SocketException("Connection reset"),
            IOException("unexpected end of stream", EOFException()),
            HttpException(408),
            HttpException(500),
            HttpException(502),
            HttpException(503),
            IOException("wrapper", HttpException(504)),
        ).forEach { isTransientPageFailure(it) shouldBe true }

        listOf(
            UnknownHostException(),
            ConnectException(),
            HttpException(403),
            HttpException(404),
            HttpException(429),
            IOException("not a network failure"),
            IllegalStateException("no bytes"),
        ).forEach { isTransientPageFailure(it) shouldBe false }
    }

    @Test
    fun `retryPage re-fetches an errored page even though the bytes are in the cache`() = runBlocking<Unit> {
        cacheMiss()
        every { cache.isImageInCache(any()) } returns true
        readyProbe()
        val loader = loader(fetchAhead = 0)
        val pages = loader.pagesOfChapter()
        pages[0].status = PageState.Error(Exception())

        loader.retryPage(pages[0])
        awaitReady(pages[0])

        // Retry forces a download past the cache hit, which is what makes a corrupt entry fixable.
        source.calls() shouldContainExactly listOf("/i0")
    }

    @Test
    fun `a deferred format fails the page rather than reaching a holder`() = runBlocking<Unit> {
        cacheMiss()
        every { probe.probe(any(), any()) } returns PageRoute.Deferred(ImageFormat.AVIF)
        val loader = loader(fetchAhead = 0)
        val pages = loader.pagesOfChapter()

        loader.loadInBackground(pages[0])
        await { pages[0].status is PageState.Error }

        val error = (pages[0].status as PageState.Error).error
        error.shouldBeInstanceOf<UnsupportedImageFormatException>()
        error.format shouldBe ImageFormat.AVIF
    }

    // ---- The tall-strip split ----

    @Test
    fun `a tall page becomes bands and emits one split`() = runBlocking<Unit> {
        cacheMiss()
        every { probe.probe(any(), any()) } returns PageRoute.Region(ImageFormat.JPEG, 800, 20_000)
        every { splitter.plan(800, 20_000) } returns SplitPlan(3, emptyList())
        every { splitter.split(any(), any()) } returns 3
        every { cache.getPartFile(any(), any()) } answers { File("part-${secondArg<Int>()}") }

        val loader = loader(fetchAhead = 0)
        val pages = loader.pagesOfChapter()
        val emitted = mutableListOf<PageSplit>()
        holder.launch { loader.splits.collect { synchronized(emitted) { emitted += it } } }
        Thread.sleep(QUIET_MS)

        loader.loadInBackground(pages[0])
        awaitReady(pages[0])
        await { synchronized(emitted) { emitted.isNotEmpty() } }

        pages[0].partCount shouldBe 3
        val split = synchronized(emitted) { emitted.single() }
        split.page shouldBe pages[0]
        split.parts.map(InsertPage::partIndex) shouldContainExactly listOf(1, 2)
        split.parts.all { it.status is PageState.Ready } shouldBe true
        split.parts.all { it.index == pages[0].index } shouldBe true
    }

    @Test
    fun `a split that collapses back to one part serves the page whole`() = runBlocking<Unit> {
        cacheMiss()
        every { probe.probe(any(), any()) } returns PageRoute.Region(ImageFormat.JPEG, 800, 20_000)
        every { splitter.plan(any(), any()) } returns SplitPlan(3, emptyList())
        every { splitter.split(any(), any()) } returns 1

        val loader = loader(fetchAhead = 0)
        val pages = loader.pagesOfChapter()
        loader.loadInBackground(pages[0])
        awaitReady(pages[0])

        pages[0].partCount shouldBe 1
    }

    @Test
    fun `the split preference turns the split off without touching the route`() = runBlocking<Unit> {
        cacheMiss()
        every { probe.probe(any(), any()) } returns PageRoute.Region(ImageFormat.JPEG, 800, 20_000)
        every { splitter.plan(any(), any()) } returns SplitPlan(3, emptyList())

        val loader = loader(fetchAhead = 0, splitEnabled = false)
        val pages = loader.pagesOfChapter()
        loader.loadInBackground(pages[0])
        awaitReady(pages[0])

        pages[0].partCount shouldBe 1
        pages[0].route shouldBe PageRoute.Region(ImageFormat.JPEG, 800, 20_000)
        verify(exactly = 0) { splitter.split(any(), any()) }
    }

    @Test
    fun `a tall page only the native decoder reads is served whole`() = runBlocking<Unit> {
        cacheMiss()
        every { probe.probe(any(), any()) } returns PageRoute.Region(ImageFormat.AVIF, 800, 20_000)
        every { splitter.plan(any(), any()) } returns SplitPlan(3, emptyList())

        val loader = loader(fetchAhead = 0)
        val pages = loader.pagesOfChapter()
        loader.loadInBackground(pages[0])
        awaitReady(pages[0])

        pages[0].partCount shouldBe 1
        pages[0].route shouldBe PageRoute.Region(ImageFormat.AVIF, 800, 20_000)
        verify(exactly = 0) { splitter.split(any(), any()) }
    }

    // ---- Recycling ----

    @Test
    fun `recycle writes the page list back without the inserted parts`() = runBlocking<Unit> {
        cacheMiss()
        readyProbe()
        val loader = loader(fetchAhead = 0)
        val pages = loader.pagesOfChapter()
        val parent = pages[1]
        chapter.replacePages(listOf(pages[0], parent, InsertPage(parent, 1), pages[2]))

        loader.recycle()
        loader.isRecycled shouldBe true

        val written = slot<List<PageInfo>>()
        await { runCatching { verify { cache.putPageListToCache(any(), capture(written)) } }.isSuccess }
        written.captured.map { it.index } shouldContainExactly listOf(0, 1, 2)
    }

    @Test
    fun `recycle is idempotent`() {
        val loader = loader()
        loader.recycle()
        loader.recycle()
        verify(exactly = 0) { cache.putPageListToCache(any(), any()) }
    }

    // ---- Harness ----

    private fun loader(fetchAhead: Int = 2, splitEnabled: Boolean = true): SourcePageLoader = SourcePageLoader(
        chapter = chapter,
        source = source,
        cache = cache,
        probe = probe,
        splitter = splitter,
        readerPreferences = ReaderPreferences(store(splitEnabled)),
        dispatchers = Dispatchers(
            io = CoroutineDispatchers.IO,
            default = CoroutineDispatchers.IO,
            main = CoroutineDispatchers.IO,
        ),
        preload = { PreloadPolicy.DEFAULT.copy(fetchAhead = fetchAhead) },
        retryDelayMs = RETRY_DELAY_MS,
    ).also { loaders += it }

    /**
     * `InMemoryPreferenceStore` hands out a fresh `Preference` per call, so a value has to be seeded
     * rather than written.
     */
    private fun store(splitEnabled: Boolean) = InMemoryPreferenceStore(
        sequenceOf(InMemoryPreferenceStore.InMemoryPreference(SPLIT_KEY, splitEnabled, true)),
    )

    private suspend fun SourcePageLoader.pagesOfChapter(): List<ReaderPage> {
        val pages = getPages().onEach { it.chapter = chapter }
        chapter.state = ReaderChapter.State.Loaded(pages)
        chapter.pageLoader = this
        return pages
    }

    /** `loadPage` never returns by design, so it is always launched and never awaited. */
    private fun SourcePageLoader.loadInBackground(page: ReaderPage): Job = holder.launch { loadPage(page) }

    private fun cacheMiss() {
        every { cache.getPageListFromCache(any()) } returns null
    }

    private fun readyProbe() {
        every { probe.probe(any(), any()) } returns PageRoute.Region(ImageFormat.JPEG, 800, 1_200)
        every { splitter.plan(any(), any()) } returns null
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_MS)
        }
        error("the condition never became true inside $TIMEOUT_MS ms")
    }

    private fun awaitReady(page: ReaderPage) = await { page.status is PageState.Ready }

    private class FakeSource : Source {
        override val id = 42L
        override val name = "s"
        override val lang = "en"

        var pageListCalls = 0
            private set
        var failWith: Throwable? = null

        /** How many calls [failWith] fails before the source answers; every call by default. */
        var failures = Int.MAX_VALUE

        private val imageCalls = mutableListOf<String>()

        fun calls(): List<String> = synchronized(imageCalls) { imageCalls.toList() }

        override suspend fun getMangaDetails(manga: MangaInfo) = manga
        override suspend fun getChapterList(manga: MangaInfo) = emptyList<ChapterInfo>()

        override suspend fun getPageList(chapter: ChapterInfo): List<PageInfo> {
            pageListCalls++
            return List(3) { PageInfo(index = it, url = "/p$it", imageUrl = "/i$it") }
        }

        override suspend fun getImage(page: PageInfo): ImageResult {
            val call = synchronized(imageCalls) {
                imageCalls += page.imageUrl.orEmpty()
                imageCalls.size
            }
            failWith?.takeIf { call <= failures }?.let { throw it }
            return ImageResult.ofBytes(byteArrayOf(1, 2, 3), "image/jpeg", page.imageUrl)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 5L

        /** Long enough for a queued fetch to have run, short enough not to slow the suite. */
        const val QUIET_MS = 250L

        const val SPLIT_KEY = "reader_split_tall_images_online"

        /** Short, so a retry lands well inside [QUIET_MS]. */
        const val RETRY_DELAY_MS = 20L
    }
}

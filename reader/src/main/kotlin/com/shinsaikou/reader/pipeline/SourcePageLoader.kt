package com.shinsaikou.reader.pipeline

import android.os.Build
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.domain.preferences.ReaderPreferences
import com.shinsaikou.domain.source.toChapterInfo
import com.shinsaikou.reader.cache.ChapterCache
import com.shinsaikou.reader.state.PreloadPolicy
import com.shinsaikou.source.Source
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.InterruptedIOException
import java.net.SocketException
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * The online loader: bytes from a [Source] into the [ChapterCache], sniffed, split if tall, then
 * Ready. Contract 07 section 4.2.
 *
 * Upstream's `HttpPageLoader` shape, with the probe and the tall-strip split inserted between the
 * cache write and Ready, so a viewer never sees an unclassified page and never sees a 20000 px
 * strip. One worker coroutine, as upstream: sources are rate limited by their own interceptors, and
 * parallel fetching buys jank rather than speed.
 *
 * Unlike upstream, a download that fails for a reason a moment's wait can fix
 * ([isTransientPageFailure]) is tried once more after [retryDelayMs].
 */
class SourcePageLoader(
    private val chapter: ReaderChapter,
    private val source: Source,
    private val cache: ChapterCache,
    private val probe: ImageProbe,
    private val splitter: TallStripSplitter,
    private val readerPreferences: ReaderPreferences,
    private val dispatchers: Dispatchers,
    private val preload: () -> PreloadPolicy.Window,
    private val retryDelayMs: Long = RETRY_DELAY_MS,
) : PageLoader {

    override val isLocal = false

    override var isRecycled: Boolean = false
        private set

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val queue = PriorityBlockingQueue<PriorityPage>()
    private val _splits = MutableSharedFlow<PageSplit>(extraBufferCapacity = SPLIT_BUFFER)
    override val splits: Flow<PageSplit> = _splits.asSharedFlow()

    init {
        scope.launch {
            while (true) {
                val entry = runInterruptible { queue.take() }
                // A page that finished or was dropped while queued is skipped, unless the user
                // asked for it again.
                if (entry.page.status !is PageState.Queue && entry.priority != PriorityPage.RETRY) continue
                fetch(entry.page, force = entry.priority == PriorityPage.RETRY)
            }
        }
    }

    override suspend fun getPages(): List<ReaderPage> = withContext(dispatchers.io) {
        // The cache swallows its own read failures, so a miss and a corrupt entry both fall through.
        val pages = cache.getPageListFromCache(chapter.chapter)
            ?: source.getPageList(chapter.chapter.toChapterInfo())
        // Do not trust a source's own indexing; reading order is list order.
        pages.mapIndexed { index, page -> ReaderPage(index = index, url = page.url, imageUrl = page.imageUrl) }
    }

    override suspend fun loadPage(page: ReaderPage) {
        withContext(dispatchers.io) { requeueIfGone(page) }

        val queued = mutableListOf<PriorityPage>()
        if (page.status is PageState.Queue) {
            queued += PriorityPage(page, PriorityPage.DEFAULT).also { queue.offer(it) }
        }
        queued += enqueueAhead(page, preload().fetchAhead)

        suspendCancellableCoroutine<Nothing> { continuation ->
            continuation.invokeOnCancellation {
                queued.forEach { if (it.page.status is PageState.Queue) queue.remove(it) }
            }
        }
    }

    override fun retryPage(page: ReaderPage) {
        if (page.status is PageState.Error) page.status = PageState.Queue
        queue.offer(PriorityPage(page, PriorityPage.RETRY))
    }

    override fun recycle() {
        if (isRecycled) return
        isRecycled = true
        // Captured before the scope dies, because the write outlives it.
        val pages = chapter.pages
        scope.cancel()
        queue.clear()
        if (pages != null) {
            val info = pages.filterNot { it is InsertPage }.map(ReaderPage::info)
            CoroutineScope(dispatchers.io).launch {
                cache.putPageListToCache(chapter.chapter, info)
            }
        }
    }

    /**
     * A page the cache evicted is Ready with nothing behind it. Both the whole page and, for a
     * split page, the full run of bands have to be there for Ready to still mean Ready.
     */
    private fun requeueIfGone(page: ReaderPage) {
        when {
            page.status is PageState.Error -> page.status = PageState.Queue
            page.status is PageState.Ready -> {
                val key = keyOf(page)
                val present = if (page.partCount > 1) {
                    cache.partsInCache(key) >= page.partCount
                } else {
                    cache.isImageInCache(key)
                }
                if (!present) page.status = PageState.Queue
            }
        }
    }

    private fun enqueueAhead(from: ReaderPage, amount: Int): List<PriorityPage> {
        val pages = from.chapter.pages ?: return emptyList()
        val at = pages.indexOf(from)
        if (at < 0 || at == pages.lastIndex) return emptyList()
        return pages
            .subList(at + 1, min(at + 1 + amount, pages.size))
            .filter { it.status is PageState.Queue }
            .map { PriorityPage(it, PriorityPage.ADJACENT).also { entry -> queue.offer(entry) } }
    }

    /**
     * A page whose image URL the source resolves lazily is cached under its page URL, so the key is
     * stable across the resolution step and a retry does not re-download.
     */
    private fun keyOf(page: ReaderPage): String = page.imageUrl ?: (page.url + "#" + page.index)

    @Suppress("TooGenericExceptionCaught")
    private suspend fun fetch(page: ReaderPage, force: Boolean) {
        val key = keyOf(page)
        try {
            if (force || !cache.isImageInCache(key)) {
                download(page, key)
            }
            page.route = probe.probe({ cache.getImageFile(key).inputStream() }, Build.VERSION.SDK_INT)
            when (val route = page.route) {
                is PageRoute.Deferred -> throw UnsupportedImageFormatException(route.format)
                PageRoute.Unsupported -> throw UnsupportedImageFormatException(ImageFormat.UNKNOWN)
                is PageRoute.Region -> serve(page, key, route)
                is PageRoute.Animated, null -> serveWhole(page, key)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // The download has had its one automatic retry; the holder offers more and retryPage forces it.
            page.status = PageState.Error(e)
        }
    }

    /**
     * One automatic retry, for a timeout, a dropped connection or a busy server. The worker waits
     * out the pause, so the pages queued behind this one wait too, which is why it is short and
     * happens once.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun download(page: ReaderPage, key: String) {
        try {
            downloadOnce(page, key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!isTransientPageFailure(e)) throw e
            delay(retryDelayMs)
            downloadOnce(page, key)
        }
    }

    private suspend fun downloadOnce(page: ReaderPage, key: String) {
        if (page.imageUrl.isNullOrEmpty()) page.status = PageState.LoadPage
        page.info.resetProgress()
        val progress = scope.launch { page.info.progress.collect { page.progress = it } }
        try {
            page.status = PageState.DownloadImage
            source.getImage(page.info).use { result ->
                cache.putImageToCache(key, result.source())
                page.imageUrl = result.imageUrl ?: page.imageUrl
            }
        } finally {
            progress.cancel()
        }
    }

    /** A tall strip becomes bands in the cache; anything else is served whole. */
    private suspend fun serve(page: ReaderPage, key: String, route: PageRoute.Region) {
        val splittable = route.isSplittable &&
            readerPreferences.tallImageSplitEnabled.get() &&
            splitter.plan(route.width, route.height) != null
        if (!splittable) {
            serveWhole(page, key)
            return
        }

        val parts = splitter.split({ cache.getImageFile(key).inputStream() }, key)
        if (parts <= 1) {
            // The split failed or the plan collapsed; the parent entry is untouched.
            serveWhole(page, key)
            return
        }

        page.partCount = parts
        page.stream = { cache.getPartFile(key, 0).inputStream() }
        page.status = PageState.Ready
        val inserts = (1 until parts).map { index ->
            InsertPage(page, partIndex = index) { cache.getPartFile(key, index).inputStream() }
        }
        _splits.emit(PageSplit(page, inserts))
    }

    private fun serveWhole(page: ReaderPage, key: String) {
        page.stream = { cache.getImageFile(key).inputStream() }
        page.status = PageState.Ready
    }

    companion object {
        private const val SPLIT_BUFFER = 64

        /** Long enough for a dropped connection to come back, short enough not to stall the queue. */
        const val RETRY_DELAY_MS = 1_500L
    }
}

/**
 * Whether a page download failed for a reason a short wait can fix: a timeout, a connection that
 * reset or a body cut short, or a server that answered 408 or a 5xx. Being offline, any other
 * status, a browser check and everything after the bytes arrived are not, so they fail at once.
 */
internal fun isTransientPageFailure(error: Throwable): Boolean {
    ErrorKey.httpCode(error)?.let { return it == HTTP_REQUEST_TIMEOUT || it in HTTP_SERVER_ERRORS }
    return when (ErrorKey.of(error)) {
        ErrorKey.TIMEOUT -> true
        // A dropped connection reads as a plain IOException, with the socket's own failure as its cause.
        ErrorKey.GENERIC -> sequenceOf(error, error.cause).any {
            it is SocketException || it is EOFException || it is InterruptedIOException
        }
        else -> false
    }
}

private const val HTTP_REQUEST_TIMEOUT = 408
private val HTTP_SERVER_ERRORS = 500..599

/**
 * Queue ordering: priority first, then arrival, so two pages of the same priority keep reading
 * order. Contract 07 section 4.2.
 */
internal class PriorityPage(val page: ReaderPage, val priority: Int) : Comparable<PriorityPage> {

    private val identifier = idGenerator.incrementAndGet()

    override fun compareTo(other: PriorityPage): Int {
        val byPriority = other.priority.compareTo(priority)
        return if (byPriority != 0) byPriority else identifier.compareTo(other.identifier)
    }

    companion object {
        private val idGenerator = AtomicInteger(0)

        const val RETRY = 2
        const val DEFAULT = 1
        const val ADJACENT = 0
    }
}

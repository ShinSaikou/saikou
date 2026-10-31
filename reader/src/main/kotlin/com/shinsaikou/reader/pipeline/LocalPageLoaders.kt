package com.shinsaikou.reader.pipeline

import android.os.Build
import com.hippo.unifile.UniFile
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.preferences.ReaderPreferences
import com.shinsaikou.image.ImageUtil
import com.shinsaikou.reader.cache.ChapterCache
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import tachiyomi.core.archive.ArchiveReader
import tachiyomi.core.archive.EpubReader
import java.io.InputStream

/**
 * What the three local loaders share. Contract 07 section 4.3.
 *
 * A local chapter's pages are Ready the moment [getPages] returns, because the bytes are already on
 * disk. The probe and the tall-strip split are deferred to the first [loadPage] of each page, so
 * opening a 300-page archive does not sniff 300 entries.
 *
 * Splitting is therefore uniform for online, local and downloaded pages: [DownloadedPageLoader] is
 * one of these loaders under another name, because the download engine stores pages unsplit.
 */
abstract class LocalPageLoader(
    private val chapterId: Long,
    private val probe: ImageProbe,
    private val splitter: TallStripSplitter,
    private val cache: ChapterCache,
    private val readerPreferences: ReaderPreferences,
    protected val dispatchers: Dispatchers,
) : PageLoader {

    override val isLocal = true

    final override var isRecycled: Boolean = false
        private set

    private val _splits = MutableSharedFlow<PageSplit>(extraBufferCapacity = SPLIT_BUFFER)
    override val splits: Flow<PageSplit> = _splits.asSharedFlow()

    /** Entry name per page index, so a band key names the entry rather than a list position. */
    private val entryNames = mutableMapOf<Int, String>()

    protected fun rememberEntry(index: Int, name: String) {
        entryNames[index] = name
    }

    override suspend fun loadPage(page: ReaderPage) {
        check(!isRecycled) { "the loader has been recycled" }
        if (page.route == null) {
            withContext(dispatchers.io) { classify(page) }
        }
        suspendCancellableCoroutine<Nothing> { }
    }

    /** Nothing to re-queue: a local page's bytes cannot go missing while the reader holds them. */
    override fun retryPage(page: ReaderPage) = Unit

    override fun recycle() {
        if (isRecycled) return
        isRecycled = true
        close()
    }

    protected abstract fun close()

    @Suppress("TooGenericExceptionCaught")
    private suspend fun classify(page: ReaderPage) {
        val open = page.stream ?: return
        try {
            page.route = probe.probe(open, Build.VERSION.SDK_INT)
            when (val route = page.route) {
                is PageRoute.Deferred -> throw UnsupportedImageFormatException(route.format)
                PageRoute.Unsupported -> throw UnsupportedImageFormatException(ImageFormat.UNKNOWN)
                is PageRoute.Region -> split(page, open, route)
                is PageRoute.Animated, null -> page.status = PageState.Ready
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            page.status = PageState.Error(e)
        }
    }

    private suspend fun split(page: ReaderPage, open: () -> InputStream, route: PageRoute.Region) {
        val splittable = route.isSplittable &&
            readerPreferences.tallImageSplitEnabled.get() &&
            splitter.plan(route.width, route.height) != null
        if (!splittable) {
            page.status = PageState.Ready
            return
        }

        // The bands go into the chapter cache like online bands; the original entry is untouched.
        val key = "local:$chapterId:${entryNames[page.index].orEmpty()}"
        val parts = splitter.split(open, key)
        if (parts <= 1) {
            page.status = PageState.Ready
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

    protected companion object {
        const val SPLIT_BUFFER = 64
    }
}

/** A chapter stored as a directory of image files. */
class DirectoryPageLoader(
    private val dir: UniFile,
    chapterId: Long,
    probe: ImageProbe,
    splitter: TallStripSplitter,
    cache: ChapterCache,
    readerPreferences: ReaderPreferences,
    dispatchers: Dispatchers,
) : LocalPageLoader(chapterId, probe, splitter, cache, readerPreferences, dispatchers) {

    override suspend fun getPages(): List<ReaderPage> = withContext(dispatchers.io) {
        dir.listFiles().orEmpty()
            .filter { !it.isDirectory && ImageUtil.isImage(it.name) { it.openInputStream() } }
            .sortedWith { a, b -> a.name.orEmpty().compareToCaseInsensitiveNaturalOrder(b.name.orEmpty()) }
            .mapIndexed { index, file ->
                rememberEntry(index, file.name.orEmpty())
                ReaderPage(index).apply {
                    stream = { file.openInputStream() }
                    status = PageState.Ready
                }
            }
    }

    override fun close() = Unit
}

/** A chapter stored as one archive file, read through `:core:archive`. */
class ArchivePageLoader(
    private val reader: ArchiveReader,
    chapterId: Long,
    probe: ImageProbe,
    splitter: TallStripSplitter,
    cache: ChapterCache,
    readerPreferences: ReaderPreferences,
    dispatchers: Dispatchers,
) : LocalPageLoader(chapterId, probe, splitter, cache, readerPreferences, dispatchers) {

    override suspend fun getPages(): List<ReaderPage> = withContext(dispatchers.io) {
        reader.useEntries { entries ->
            entries
                .filter { it.isFile && ImageUtil.isImage(it.name) { reader.getInputStream(it.name)!! } }
                .sortedWith { a, b -> a.name.compareToCaseInsensitiveNaturalOrder(b.name) }
                .mapIndexed { index, entry ->
                    rememberEntry(index, entry.name)
                    ReaderPage(index).apply {
                        stream = { reader.getInputStream(entry.name)!! }
                        status = PageState.Ready
                    }
                }
                .toList()
        }
    }

    override fun close() = reader.close()
}

/** A chapter stored as an epub, whose page order comes from the package document. */
class EpubPageLoader(
    private val reader: EpubReader,
    chapterId: Long,
    probe: ImageProbe,
    splitter: TallStripSplitter,
    cache: ChapterCache,
    readerPreferences: ReaderPreferences,
    dispatchers: Dispatchers,
) : LocalPageLoader(chapterId, probe, splitter, cache, readerPreferences, dispatchers) {

    override suspend fun getPages(): List<ReaderPage> = withContext(dispatchers.io) {
        reader.getImagesFromPages().mapIndexed { index, path ->
            rememberEntry(index, path)
            ReaderPage(index).apply {
                stream = { reader.getInputStream(path)!! }
                status = PageState.Ready
            }
        }
    }

    override fun close() = reader.close()
}

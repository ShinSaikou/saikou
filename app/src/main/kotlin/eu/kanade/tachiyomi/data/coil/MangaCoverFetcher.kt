// Fresh: contract 08 section 3.4 assigns this package. Upstream's file of the same name is Coil 2, so the fetcher was written against Coil 3.6.2 rather than vendored.
package eu.kanade.tachiyomi.data.coil

import androidx.core.net.toUri
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceManager
import com.shinsaikou.source.model.ImageResult
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okio.BufferedSource
import okio.ForwardingSource
import okio.Path.Companion.toOkioPath
import okio.buffer
import okio.sink
import okio.source
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.manga.model.asMangaCover
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Resolves a cover to bytes, preferring what is already on disk (contract 08 section 3.4).
 *
 * The order is custom cover, then a cover that is a file on the device (the Local source's), then
 * the cover cache, then the network. A library entry's download is written into the cover cache, so
 * a favourite keeps its cover with no network at all after the first fetch; an entry that is only
 * being browsed streams straight to the decoder and leaves nothing behind but Coil's own disk cache
 * entry.
 *
 * A download goes through the entry's source when it is installed ([Source.getCoverImage]), as
 * upstream's does: many image hosts answer 403 to a request without the source's Referer. Only a
 * source with no network of its own, or one that is not installed, leaves the request to the
 * shared client, which carries the user agent, the cookie jar and the challenge interceptor.
 */
class MangaCoverFetcher(
    private val cover: MangaCover,
    private val options: Options,
    private val callFactory: () -> Call.Factory,
    private val coverCache: CoverCache,
    private val sourceManager: () -> SourceManager,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val custom = coverCache.getCustomCoverFile(cover.mangaId)
        if (custom.exists()) return custom.toResult(DataSource.DISK)

        val url = cover.url?.takeIf { it.isNotBlank() }
        if (url != null && url.isDeviceFile()) return readDeviceFile(url)

        val cached = coverCache.getCoverFile(url)
        if (cached != null && cached.exists()) return cached.toResult(DataSource.DISK)

        if (url == null) throw IllegalStateException("The entry has no cover")
        return download(url, cached)
    }

    private suspend fun download(url: String, target: File?): FetchResult {
        val image = source()?.getCoverImage(url) ?: requestDirectly(url)
        // Only a library entry earns a file of its own: a browse listing would otherwise fill
        // private storage with covers of entries the owner never added.
        if (cover.isMangaFavorite && target != null) {
            image.use { it.source().writeAtomicallyTo(target) }
            return target.toResult(DataSource.NETWORK)
        }
        return SourceFetchResult(
            source = ImageSource(image.closingSource(), options.fileSystem),
            mimeType = image.contentType,
            dataSource = DataSource.NETWORK,
        )
    }

    /**
     * The entry's source, or null when it is not installed. A cover can be asked for at start-up
     * before the extensions have loaded, when an installed source still reads as missing, and a
     * request made then without the source's headers would fail on the hosts that check them.
     */
    private suspend fun source(): Source? {
        val registry = sourceManager()
        registry.isInitialized.first { it }
        return registry.get(cover.sourceId)
    }

    private suspend fun requestDirectly(url: String): ImageResult {
        val response = callFactory().newCall(GET(url)).await()
        if (!response.isSuccessful) {
            response.close()
            throw HttpException(response.code)
        }
        val body = response.body
        return ImageResult.ofSource(
            source = body.source(),
            contentType = response.header("Content-Type"),
            contentLength = body.contentLength(),
            imageUrl = url,
            onClose = response::close,
        )
    }

    /**
     * The Local source names its covers by a `content://` or `file://` URI. They are read where
     * they are and never copied into the cover cache, which holds downloads only.
     */
    private fun readDeviceFile(url: String): FetchResult {
        if (url.startsWith("/")) return File(url).toResult(DataSource.DISK)
        val stream = options.context.contentResolver.openInputStream(url.toUri())
            ?: throw FileNotFoundException("No cover at $url")
        return SourceFetchResult(
            source = ImageSource(stream.source().buffer(), options.fileSystem),
            mimeType = null,
            dataSource = DataSource.DISK,
        )
    }

    private fun File.toResult(dataSource: DataSource) = SourceFetchResult(
        source = ImageSource(toOkioPath(), options.fileSystem),
        mimeType = null,
        dataSource = dataSource,
    )

    class Factory(
        private val callFactory: () -> Call.Factory,
        private val coverCache: CoverCache,
        private val sourceManager: () -> SourceManager,
    ) : Fetcher.Factory<MangaCover> {
        override fun create(data: MangaCover, options: Options, imageLoader: ImageLoader): Fetcher =
            MangaCoverFetcher(data, options, callFactory, coverCache, sourceManager)
    }

    /** The same fetcher for a row that hands Coil a whole entry rather than its cover. */
    class MangaFactory(
        private val callFactory: () -> Call.Factory,
        private val coverCache: CoverCache,
        private val sourceManager: () -> SourceManager,
    ) : Fetcher.Factory<Manga> {
        override fun create(data: Manga, options: Options, imageLoader: ImageLoader): Fetcher =
            MangaCoverFetcher(data.asMangaCover(), options, callFactory, coverCache, sourceManager)
    }
}

private fun String.isDeviceFile(): Boolean =
    startsWith("content://") || startsWith("file://") || startsWith("/")

/** The result's stream for a decoder that closes what it reads: closing it closes the result. */
private fun ImageResult.closingSource(): BufferedSource {
    val result = this
    return object : ForwardingSource(result.source()) {
        override fun close() = result.close()
    }.buffer()
}

/**
 * Writes through a sibling temporary file and renames, so a cancelled or failed download can never
 * leave a truncated cover behind that later reads would treat as a complete one.
 */
private fun BufferedSource.writeAtomicallyTo(target: File) {
    target.parentFile?.mkdirs()
    val temporary = File(target.parentFile, "${target.name}.tmp")
    try {
        temporary.sink().buffer().use { sink -> sink.writeAll(this) }
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
    } catch (e: Throwable) {
        temporary.delete()
        throw e
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = continuation.resume(response)
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }
    })
}

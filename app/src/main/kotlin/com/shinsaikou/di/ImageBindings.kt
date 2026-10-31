package com.shinsaikou.di

import android.content.Context
import android.os.Build
import android.os.StatFs
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowRgb565
import coil3.request.crossfade
import coil3.util.DebugLogger
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.extensions.ExtensionManager
import com.shinsaikou.extensions.loader.ExtensionIcons
import com.shinsaikou.source.SourceManager
import com.shinsaikou.ui.browse.ExtensionIcon
import com.shinsaikou.ui.browse.ExtensionIconFetcher
import com.shinsaikou.ui.browse.ExtensionIconKeyer
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.coil.MangaCoverFetcher
import eu.kanade.tachiyomi.data.coil.MangaCoverKeyer
import eu.kanade.tachiyomi.data.coil.MangaKeyer
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.util.system.DeviceUtil
import kotlinx.coroutines.ExperimentalCoroutinesApi
import okio.Path.Companion.toOkioPath
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import java.io.File

/**
 * The process-wide Coil loader. `SaikouApp` is `SingletonImageLoader.Factory` and returns this one,
 * so every `AsyncImage` and the reader's own `SingletonImageLoader.get(context)` share it.
 *
 * The cover fetchers and keyers of section 3.4 are registered here, which is what lets a screen
 * hand `AsyncImage` a `Manga` or a `MangaCover` and get the cover-cache file when there is one, and
 * an `ExtensionIcon` and get an installed extension's launcher icon out of its `.ext` file. The
 * reader is unaffected: it fetches its pages through the source contract rather than through Coil.
 *
 * The reader shell's carry-over also asks for `respectCacheHeaders(false)`. That is the Coil 2
 * builder method upstream used and it does not exist in Coil 3.6.2: the replacement is a custom
 * `coil3.network.CacheStrategy` on the fetcher factory, and the default one stands until a screen
 * needs otherwise.
 */
@BindingContainer
@ContributesTo(AppScope::class)
object ImageBindings {

    /** 20 percent of the app's memory class; Coil computes the byte size from ActivityManager. */
    const val MEMORY_CACHE_PERCENT = 0.20

    /** Disk cache bound: 2 percent of the cache partition's total space, clamped to [64 MB, 512 MB]. */
    const val DISK_CACHE_MIN_BYTES = 64L * 1024 * 1024
    const val DISK_CACHE_MAX_BYTES = 512L * 1024 * 1024
    const val DISK_CACHE_FRACTION = 0.02

    private const val CACHE_DIRECTORY = "image_cache"
    private const val CROSSFADE_MILLIS = 300

    /** Coil's own limits: fetching is IO bound, decoding is CPU bound and allocates. */
    private const val FETCH_PARALLELISM = 8
    private const val DECODE_PARALLELISM = 3

    fun diskCacheSizeBytes(directory: File): Long {
        val stat = StatFs(directory.absolutePath)
        val total = stat.blockCountLong * stat.blockSizeLong
        return (total * DISK_CACHE_FRACTION).toLong().coerceIn(DISK_CACHE_MIN_BYTES, DISK_CACHE_MAX_BYTES)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Provides
    @SingleIn(AppScope::class)
    fun provideImageLoader(
        context: Context,
        networkHelper: Lazy<NetworkHelper>,
        networkPreferences: NetworkPreferences,
        dispatchers: Dispatchers,
        coverCache: CoverCache,
        sourceManager: Lazy<SourceManager>,
        extensionManager: Lazy<ExtensionManager>,
        extensionIcons: Lazy<ExtensionIcons>,
    ): ImageLoader {
        val cacheDir = File(context.cacheDir, CACHE_DIRECTORY).apply { mkdirs() }
        return ImageLoader.Builder(context)
            .components {
                // Metro injects kotlin.Lazy, so building the loader never constructs the OkHttp
                // client on the main thread; SaikouApp warms NetworkHelper on the IO dispatcher.
                val callFactory = { networkHelper.value.client }
                // Lazy for the same reason; a cover download asks the entry's source for its
                // request, so the image hosts that check the source's Referer serve it.
                val sources = { sourceManager.value }
                // Ahead of the network fetcher: a cover that is already a file must never be
                // fetched again, and ComponentRegistry answers with the first factory that accepts
                // the data type.
                add(MangaCoverFetcher.Factory(callFactory, coverCache, sources), MangaCover::class)
                add(MangaCoverFetcher.MangaFactory(callFactory, coverCache, sources), Manga::class)
                add(MangaCoverKeyer(coverCache), MangaCover::class)
                add(MangaKeyer(), Manga::class)
                val extensions = { extensionManager.value }
                add(ExtensionIconFetcher.Factory(extensions, { extensionIcons.value }), ExtensionIcon::class)
                add(ExtensionIconKeyer(extensions), ExtensionIcon::class)
                add(OkHttpNetworkFetcherFactory(callFactory = callFactory))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, MEMORY_CACHE_PERCENT).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.toOkioPath())
                    .maxSizeBytes(diskCacheSizeBytes(cacheDir))
                    .build()
            }
            .crossfade(CROSSFADE_MILLIS)
            .allowRgb565(DeviceUtil.isLowRamDevice(context))
            .fetcherCoroutineContext(dispatchers.io.limitedParallelism(FETCH_PARALLELISM))
            .decoderCoroutineContext(dispatchers.io.limitedParallelism(DECODE_PARALLELISM))
            .apply { if (networkPreferences.verboseLogging.get()) logger(DebugLogger()) }
            .build()
    }
}

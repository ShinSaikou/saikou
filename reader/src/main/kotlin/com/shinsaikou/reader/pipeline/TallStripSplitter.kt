package com.shinsaikou.reader.pipeline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import com.shinsaikou.reader.bench.ReaderBenchHooks
import com.shinsaikou.reader.cache.ChapterCache
import com.shinsaikou.reader.memory.DisplayInfoProvider
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.InputStream

/**
 * Writes the bands of a tall strip into the [ChapterCache] as their own entries, so a viewer never
 * hands a 20000 px image to the image view. Contract 07 part 1b section 4.7.2.
 *
 * Adapted from upstream v0.20.4
 * `core/common/src/main/kotlin/tachiyomi/core/common/util/system/ImageUtil.kt` (`splitTallImage`
 * and the private `getBitmapRegionDecoder`). Upstream writes `UniFile`s into a download directory and
 * deletes the original; these bands go into an LRU cache and the parent entry is kept.
 */
@SingleIn(AppScope::class)
@Inject
class TallStripSplitter(
    private val cache: ChapterCache,
    private val bounds: BoundsReader,
    private val display: DisplayInfoProvider,
) {

    /** Null when [width] by [height] is not a tall strip. Pure; safe on any thread. */
    fun plan(width: Int, height: Int): SplitPlan? =
        SplitPlanner.plan(width, height, SplitPlanner.optimalImageHeight(display))

    /**
     * Splits the image behind [open] into cache entries keyed from [key] and returns the number of
     * parts now in the cache. Returns 1 when the image is not tall, when its bounds cannot be
     * read, when no region decoder opens, or when any band fails: in every one of those cases the
     * caller serves the page whole and the parent entry is untouched. Blocking; IO only.
     *
     * One decoder instance decodes every band, which is upstream's shape. Going through
     * `ImageDecoder.decode` once per band would construct one `BitmapRegionDecoder` per band, and
     * the platform region decoder has to inflate a PNG from the start of the image to reach a
     * region, so N instantiations would turn a linear cost into a quadratic one on the longest
     * single IO operation in the reader.
     *
     * The body runs inside the `Reader.split` trace section (part 2 section 8.3), which is why it
     * is a separate function: the section has to survive the early returns below, and an inline
     * `trace { }` around a body full of non-local returns reads worse than one wrapper.
     */
    fun split(open: () -> InputStream, key: String): Int =
        ReaderBenchHooks.trace(ReaderBenchHooks.SECTION_SPLIT) { splitInto(open, key) }

    @Suppress("ReturnCount")
    private fun splitInto(open: () -> InputStream, key: String): Int {
        // Idempotent: a chapter re-entered through the chapter window finds its parts and stops.
        val existing = cache.partsInCache(key)
        if (existing > 1) return existing

        val size = bounds.read(open) ?: return 1
        val plan = plan(size.width, size.height) ?: return 1

        var written = 0
        var complete = false
        try {
            open().use { stream ->
                val decoder = newRegionDecoder(stream)
                if (decoder == null) {
                    logcat(LogPriority.WARN) { "No region decoder for a tall page of ${plan.partCount} bands" }
                    return 1
                }
                val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                try {
                    for (band in plan.bands) {
                        val region = Rect(0, band.top, band.width, band.bottom)
                        val bitmap = decoder.decodeRegion(region, options)
                            ?: error("band ${band.index} decoded to nothing")
                        try {
                            cache.putPartToCache(key, band.index) { out ->
                                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                            }
                        } finally {
                            // Exactly one band bitmap is live at a time.
                            bitmap.recycle()
                        }
                        written++
                    }
                } finally {
                    decoder.recycle()
                }
            }
            complete = true
            return plan.partCount
        } catch (e: CancellationException) {
            // A cancelled fetch is not a failed split, but it still leaves no half strip behind.
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "Tall page split failed at band $written of ${plan.partCount}" }
            return 1
        } finally {
            if (!complete) cache.removeParts(key)
        }
    }

    private companion object {
        /**
         * Upstream's value, kept. The parts are a cache artefact to be tiled, not a download, and a
         * lossless re-encode of five 800x4000 bands is seconds rather than tenths of a second on
         * the low-RAM device, all of it on the fetch path. Quality 100 keeps the artefact below
         * the visible threshold on screentone; the parent keeps its original bytes either way.
         */
        const val JPEG_QUALITY = 100
    }
}

/**
 * The one-argument overload is API 31 and up and the two-argument one is deprecated there, so both
 * are kept. Re-stated from upstream v0.20.4's private
 * `core/common/src/main/kotlin/tachiyomi/core/common/util/system/ImageUtil.kt#getBitmapRegionDecoder`.
 *
 * It lived beside the `ImageDecoder` seam until that seam was deleted; the band splitter is the
 * only region decode left in the module.
 */
internal fun newRegionDecoder(stream: InputStream): BitmapRegionDecoder? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        BitmapRegionDecoder.newInstance(stream)
    } else {
        @Suppress("DEPRECATION")
        BitmapRegionDecoder.newInstance(stream, false)
    }

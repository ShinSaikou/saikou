package com.shinsaikou.reader.pipeline

import com.shinsaikou.reader.memory.DisplayInfoProvider

/** One band of a tall strip. [bottom] is exclusive, so the bands partition `[0, imageHeight)`. */
data class Band(val index: Int, val top: Int, val height: Int, val width: Int) {
    val bottom: Int get() = top + height
}

data class SplitPlan(val partCount: Int, val bands: List<Band>)

/**
 * Pure. Decides whether a page is a tall strip and, if it is, how to cut it. Contract 07 part 1b
 * section 4.7.1.
 *
 * Adapted from upstream v0.20.4:
 * `core/common/src/main/kotlin/tachiyomi/core/common/util/system/TallImageSplitCalculator.kt`
 * (the two formulas, verbatim) and the private `Options.splitData` extension of
 * `core/common/src/main/kotlin/tachiyomi/core/common/util/system/ImageUtil.kt` (the band loop).
 * The saikou-image library carries the calculator as `com.shinsaikou.image.TallImageSplitCalculator`, but
 * not the band loop: upstream's `splitTallImage` wrote into a download directory, which is the wrong
 * shape and the wrong destination for the reader, and the library leaves it out.
 */
object SplitPlanner {

    /** Upstream's formula, unchanged. The -1 keeps `imageHeight == optimalImageHeight` at one part. */
    fun calculatePartCount(imageHeight: Int, optimalImageHeight: Int): Int {
        require(imageHeight > 0) { "imageHeight must be positive" }
        require(optimalImageHeight > 0) { "optimalImageHeight must be positive" }
        return (imageHeight - 1) / optimalImageHeight + 1
    }

    /** Upstream's formula, unchanged: taller than [MIN_TALL_ASPECT] times its width, and over one part. */
    fun shouldSplit(imageWidth: Int, imageHeight: Int, optimalImageHeight: Int): Boolean {
        require(imageWidth > 0) { "imageWidth must be positive" }
        return imageHeight > imageWidth * MIN_TALL_ASPECT &&
            calculatePartCount(imageHeight, optimalImageHeight) > 1
    }

    /**
     * Null when the image is not a tall strip. Otherwise a contiguous, gapless, non-overlapping
     * band plan: `bands[i].bottom == bands[i + 1].top` and the last band absorbs the remainder.
     *
     * Upstream's part count is raised until no band exceeds [maxBandPixels], because the band height
     * controls the transient bitmap the splitter holds while it re-encodes one band. At 4 Mpx that
     * transient is 16 MB in ARGB_8888, half the low-RAM decode budget.
     */
    fun plan(
        imageWidth: Int,
        imageHeight: Int,
        optimalImageHeight: Int,
        maxBandPixels: Int = MAX_BAND_PIXELS,
    ): SplitPlan? {
        if (!shouldSplit(imageWidth, imageHeight, optimalImageHeight)) return null

        val byHeight = calculatePartCount(imageHeight, optimalImageHeight)
        val byPixels = ceilDiv(imageWidth.toLong() * imageHeight, maxBandPixels.toLong()).toInt()
        val partCount = maxOf(byHeight, byPixels).coerceAtMost(minOf(MAX_PARTS, imageHeight))

        val bandHeight = imageHeight / partCount
        val bands = buildList<Band> {
            for (index in 0..<partCount) {
                // Only continue while there is image remaining.
                if (isNotEmpty() && imageHeight <= last().bottom) break

                val top = index * bandHeight
                var height = minOf(bandHeight, imageHeight - top)
                if (index == partCount - 1) height += imageHeight - (top + height)
                add(Band(index, top, height, imageWidth))
            }
        }
        return SplitPlan(bands.size, bands)
    }

    /**
     * Upstream's `optimalImageHeight`, twice the display's larger dimension, read off the injectable
     * provider rather than `Resources.getSystem()` so the planner stays pure.
     */
    fun optimalImageHeight(display: DisplayInfoProvider): Int = display.maxDimensionPx * 2

    private fun ceilDiv(value: Long, divisor: Long): Long = (value + divisor - 1) / divisor

    /** A page splits only when it is more than this many times as tall as it is wide. */
    const val MIN_TALL_ASPECT = 3

    /** 16 MB of ARGB_8888, the largest transient the splitter may hold for one band. */
    const val MAX_BAND_PIXELS = 4_194_304

    /** Part indices are formatted `%03d` into the cache key, so 999 is the ceiling. */
    const val MAX_PARTS = 999
}

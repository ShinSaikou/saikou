package com.shinsaikou.reader.viewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.shinsaikou.reader.pipeline.TargetSize
import com.shinsaikou.reader.pipeline.sampleSizeFor
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import tachiyomi.decoder.ImageDecoder

/**
 * Draws the two pages of a spread as one bitmap, side by side at one height, so the image view shows,
 * pans and zooms them as one picture (ruling R165 item 2).
 *
 * The pages are read by the native decoder the image view's own region decoder is built on, so a
 * page the single-page view reads, AVIF, HEIF and JXL included, reads here too, and with crop
 * borders on it crops them the same way.
 */
internal object SpreadImage {

    sealed interface Result {
        data class Drawn(val bitmap: Bitmap) : Result

        /** [page] cannot share the screen: it is wide or animated, or the decoder cannot read it. */
        data class Alone(val page: ReaderPage) : Result
    }

    /**
     * Each page is decoded at the largest sample size that still covers [target] (powers of two, as
     * everywhere in the reader), and no larger than [PAGE_MAX_PIXELS]: a whole bitmap rather than
     * tiles, three spreads of them at most around the one on screen. The taller page is scaled down
     * to the shorter one's height. Runs on IO.
     *
     * [pages] are left to right as drawn.
     */
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    fun draw(pages: List<ReaderPage>, cropBorders: Boolean, target: TargetSize): Result {
        val decoders = mutableListOf<ImageDecoder>()
        val bitmaps = mutableListOf<Bitmap>()
        try {
            for (page in pages) {
                // Its route is known once it has loaded, which may be after it was paired: one that
                // never shares the screen (an animated page has a view of its own) goes alone before
                // anything decodes it, so its first frame is never drawn as a still.
                if (spreadShape(page) == SpreadShape.WIDE) return Result.Alone(page)
                val open = page.stream ?: return Result.Alone(page)
                val decoder = try {
                    open().use { ImageDecoder.newInstance(it, cropBorders) }
                } catch (_: Exception) {
                    null
                } ?: return Result.Alone(page)
                decoders += decoder
                if (decoder.width > decoder.height) return Result.Alone(page)
            }
            decoders.forEachIndexed { index, decoder ->
                val budget = target.copy(maxPixels = minOf(target.maxPixels, PAGE_MAX_PIXELS))
                val sample = sampleSizeFor(decoder.width, decoder.height, budget)
                bitmaps += decoder.decode(Rect(0, 0, decoder.width, decoder.height), sample)
                    ?: return Result.Alone(pages[index])
            }
            return Result.Drawn(sideBySide(bitmaps))
        } finally {
            decoders.forEach { it.recycle() }
            bitmaps.forEach { it.recycle() }
        }
    }

    private fun sideBySide(bitmaps: List<Bitmap>): Bitmap {
        val height = bitmaps.minOf { it.height }
        val widths = bitmaps.map { (it.width.toLong() * height / it.height).toInt().coerceAtLeast(1) }
        val spread = Bitmap.createBitmap(widths.sum(), height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(spread)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        var left = 0
        bitmaps.forEachIndexed { index, bitmap ->
            canvas.drawBitmap(bitmap, null, Rect(left, 0, left + widths[index], height), paint)
            left += widths[index]
        }
        return spread
    }

    /** One page's pixels at most: two of them make a 24 MB spread. */
    const val PAGE_MAX_PIXELS = 3_000_000L
}

package com.shinsaikou.reader.pipeline

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.unit.IntSize
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.shinsaikou.domain.preferences.ReaderPreferences
import com.shinsaikou.reader.memory.DisplayInfoProvider
import com.shinsaikou.reader.memory.MemoryInfoProvider
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.nio.ByteBuffer
import kotlin.math.sqrt
import android.graphics.ImageDecoder as AndroidImageDecoder

/**
 * The pixel cap for an animated page, and the Coil decoder that enforces it. Contract 07 part 1b
 * section 4.8.
 *
 * Fresh; upstream has no equivalent. Its animated request sets no size, no precision and no config
 * hint, so a 2000x12000 animated page decodes at 2000x12000 and every frame buffer is 96 MB.
 *
 * An animated page cannot be tiled (the image view fork has one final decoder and no pluggable
 * decoder factory), cannot be region decoded (the platform has no animated region decoder) and
 * cannot be split. The sample size chosen before the first frame buffer exists is the only lever.
 */
@SingleIn(AppScope::class)
@Inject
class AnimatedImagePolicy(
    private val memory: MemoryInfoProvider,
    private val display: DisplayInfoProvider,
    private val readerPreferences: ReaderPreferences,
) {

    /**
     * The cap, as a size. Computed from the viewport outward, never from the source inward: a cap
     * derived from the image would let a big source ask for a big frame, which is the defect being
     * fixed. The preference is a ceiling, so raising it cannot make a low-RAM device allocate more
     * than its own budget allows.
     */
    fun target(): IntSize {
        val wantedWidth = display.widthPx.toLong() * ZOOM_HEADROOM
        val wantedHeight = display.heightPx.toLong() * ZOOM_HEADROOM
        val wanted = wantedWidth * wantedHeight

        val budget = minOf(
            memory.maxHeapBytes / HEAP_BUDGET_SHARE,
            if (memory.isLowRamDevice) LOW_RAM_BUDGET_BYTES else BUDGET_BYTES,
        )
        val deviceCap = budget / ANIMATED_BUDGET_SHARE / BYTES_PER_PIXEL
        val cap = minOf(readerPreferences.animatedImageMaxPixels.get().toLong(), deviceCap)

        if (wanted <= cap) return IntSize(wantedWidth.toInt(), wantedHeight.toInt())

        // An aspect-preserving shrink that lands exactly on the cap.
        val k = sqrt(cap.toDouble() / wanted.toDouble())
        return IntSize(
            (wantedWidth * k).toInt().coerceAtLeast(1),
            (wantedHeight * k).toInt().coerceAtLeast(1),
        )
    }

    /** RGB_565 frame buffers on a low-RAM device, the same rule the image view config follows. */
    val lowRamFrames: Boolean get() = memory.isLowRamDevice

    /**
     * Null below API 28. Coil falls back to its `Movie`-backed decoder there, and `Movie` has no
     * sample size and no target size, so on API 26 and 27 an animated page decodes at its own
     * dimensions and the cap does not apply. That hole is stated rather than stubbed around: a
     * factory that pretended to cap would be a lie in the one place a lie is expensive. Animated
     * WEBP does not route here below API 28 anyway, so only GIF reaches it.
     */
    fun decoderFactory(): Decoder.Factory? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            CappedAnimatedDecoder.Factory(target(), lowRamFrames)
        } else {
            null
        }

    companion object {
        /** Linear headroom over the viewport, so a zoomed animated page is not a smear. */
        const val ZOOM_HEADROOM = 2

        /** One animated page may claim half the decode budget, never all of it. */
        const val ANIMATED_BUDGET_SHARE = 2

        const val BYTES_PER_PIXEL = 4

        private const val HEAP_BUDGET_SHARE = 4
        private const val MIB = 1024L * 1024L
        private const val LOW_RAM_BUDGET_BYTES = 32L * MIB
        private const val BUDGET_BYTES = 96L * MIB
    }
}

/**
 * Coil 3.6.2's `AnimatedImageDecoder` calls only `setAllocator`, `setMemorySizePolicy`,
 * `setTargetColorSpace` and `setPostProcessor`, and its `GifDecoder` reads only `scale`,
 * `bitmapConfig` and `allowRgb565`. Neither consults `Options.size`, so `.size()` and
 * `.precision()` on an animated request compile, are accepted, and cap nothing. The cap is applied
 * here instead, through the platform decoder's own sample size. API 28 and up.
 */
@RequiresApi(Build.VERSION_CODES.P)
class CappedAnimatedDecoder(
    private val source: ImageSource,
    private val target: IntSize,
    private val lowRamFrames: Boolean,
) : Decoder {

    /**
     * Returns null unless the bytes sniff as GIF or animated WEBP, so a static page that somehow
     * reaches this factory falls through to Coil's own decoders.
     */
    class Factory(
        private val target: IntSize,
        private val lowRamFrames: Boolean,
        private val probe: ImageProbe = ImageProbe(),
    ) : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            val header = headerOf(result.source)
            val format = probe.sniff(header)
            val animated = format == ImageFormat.GIF ||
                (format == ImageFormat.WEBP && probe.isAnimated(format, header))
            return if (animated) CappedAnimatedDecoder(result.source, target, lowRamFrames) else null
        }

        private fun headerOf(source: ImageSource): ByteArray {
            val peek = source.source().peek()
            val wanted = ImageProbe.HEADER_BYTES.toLong()
            return if (peek.request(wanted)) peek.readByteArray(wanted) else peek.readByteArray()
        }
    }

    override suspend fun decode(): DecodeResult {
        var sampleSize = 1
        val drawable = AndroidImageDecoder.decodeDrawable(platformSource()) { decoder, info, _ ->
            sampleSize = sampleSizeFor(info.size.width, info.size.height, budget())
            decoder.setTargetSampleSize(sampleSize)
            decoder.setMemorySizePolicy(
                if (lowRamFrames) {
                    AndroidImageDecoder.MEMORY_POLICY_LOW_RAM
                } else {
                    AndroidImageDecoder.MEMORY_POLICY_DEFAULT
                },
            )
        }
        return DecodeResult(image = drawable.asImage(), isSampled = sampleSize > 1)
    }

    private fun budget() = TargetSize(target.width, target.height, target.width.toLong() * target.height)

    /**
     * Never calls `ImageSource.file()`, which would write a temp copy of a page that is already in
     * the chapter cache.
     */
    private fun platformSource(): AndroidImageDecoder.Source {
        val file = source.fileOrNull()
        return if (file != null) {
            AndroidImageDecoder.createSource(file.toFile())
        } else {
            AndroidImageDecoder.createSource(ByteBuffer.wrap(source.source().readByteArray()))
        }
    }
}

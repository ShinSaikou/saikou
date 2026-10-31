package com.shinsaikou.reader.pipeline

import android.graphics.BitmapFactory
import androidx.compose.ui.unit.IntSize
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import tachiyomi.decoder.ImageDecoder
import java.io.IOException
import java.io.InputStream

/**
 * Reads the width and height of an image without decoding a pixel. Separate from [ImageProbe] so
 * the routing table can be unit tested on the JVM. Contract 07 section 4.5.
 */
interface BoundsReader {
    /** Width by height, or null when the bytes are not boundable. */
    fun read(open: () -> InputStream): IntSize?
}

/** [BoundsReader] over `BitmapFactory` with `inJustDecodeBounds`. */
@ContributesBinding(AppScope::class)
@Inject
class PlatformBoundsReader : BoundsReader {
    override fun read(open: () -> InputStream): IntSize? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            open().use { BitmapFactory.decodeStream(it, null, options) }
        } catch (_: IOException) {
            return null
        }
        val width = options.outWidth
        val height = options.outHeight
        return if (width > 0 && height > 0) IntSize(width, height) else null
    }
}

/**
 * The width and height of an AVIF, HEIF or JXL page, read by the native decoder the image view's
 * region decoder is built on. A second interface rather than a second [BoundsReader], so the two
 * stay apart in the graph and in tests.
 */
interface NativeBoundsReader {
    /** Width by height, or null when the native decoder cannot open the bytes. */
    fun read(open: () -> InputStream): IntSize?
}

/**
 * [NativeBoundsReader] over the native decoder that arrives with the image view fork. It asks the
 * decoder for its size and never for a region. Whatever this reads, the image view reads too,
 * because it opens the same decoder the same way.
 */
@ContributesBinding(AppScope::class)
@Inject
class DecoderBoundsReader : NativeBoundsReader {
    @Suppress("TooGenericExceptionCaught")
    override fun read(open: () -> InputStream): IntSize? {
        val decoder = try {
            open().use { ImageDecoder.newInstance(it) }
        } catch (_: Exception) {
            // The image view's own decoder fails the same bytes the same way; the route says so first.
            return null
        } ?: return null
        return try {
            if (decoder.width > 0 && decoder.height > 0) IntSize(decoder.width, decoder.height) else null
        } finally {
            decoder.recycle()
        }
    }
}

/**
 * The magic-byte sniff and the route decision, in one pass over a cached page. Contract 07
 * section 4.5.
 *
 * The signature table is the same one `com.shinsaikou.image.ImageUtil` uses, so the
 * two always agree on a format; the reader has its own copy because the route needs the animation
 * flag and the bounds together and must be testable off the device. The content type a source
 * reports is never consulted.
 */
@Inject
class ImageProbe(
    private val bounds: BoundsReader = PlatformBoundsReader(),
    private val nativeBounds: NativeBoundsReader = DecoderBoundsReader(),
) {

    /** Pure. Reads at most the first [HEADER_BYTES] of a file. */
    fun sniff(header: ByteArray): ImageFormat {
        fun matches(offset: Int, vararg bytes: Int): Boolean {
            if (header.size < offset + bytes.size) return false
            return bytes.withIndex().all { (i, b) -> header[offset + i].toInt() and 0xFF == b }
        }

        return when {
            matches(0, 0xFF, 0xD8, 0xFF) -> ImageFormat.JPEG
            matches(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> ImageFormat.PNG
            matches(0, 0x47, 0x49, 0x46, 0x38, 0x37, 0x61) -> ImageFormat.GIF
            matches(0, 0x47, 0x49, 0x46, 0x38, 0x39, 0x61) -> ImageFormat.GIF
            matches(0, 0x52, 0x49, 0x46, 0x46) && matches(8, 0x57, 0x45, 0x42, 0x50) -> ImageFormat.WEBP
            matches(4, 0x66, 0x74, 0x79, 0x70) -> when (brand(header)) {
                "avif", "avis" -> ImageFormat.AVIF
                "heic", "heix", "hevc", "hevx", "mif1", "msf1" -> ImageFormat.HEIF
                else -> ImageFormat.UNKNOWN
            }
            matches(0, 0xFF, 0x0A) -> ImageFormat.JXL
            matches(0, 0x00, 0x00, 0x00, 0x0C, 0x4A, 0x58, 0x4C, 0x20, 0x0D, 0x0A, 0x87, 0x0A) -> ImageFormat.JXL
            matches(0, 0x00, 0x00, 0x00, 0x0C, 0x6A, 0x50, 0x20, 0x20, 0x0D, 0x0A, 0x87, 0x0A) -> ImageFormat.JP2
            matches(0, 0xFF, 0x4F, 0xFF, 0x51) -> ImageFormat.JP2
            else -> ImageFormat.UNKNOWN
        }
    }

    /**
     * Pure. GIF always; WEBP when the extended VP8X chunk is present and its animation flag is
     * set; AVIF and HEIF when the major brand is an image sequence's; else false. Animated PNG
     * counts as static, because the platform decoder does not animate it.
     */
    fun isAnimated(format: ImageFormat, header: ByteArray): Boolean = when (format) {
        ImageFormat.GIF -> true
        ImageFormat.WEBP -> isAnimatedWebP(header)
        ImageFormat.AVIF, ImageFormat.HEIF -> brand(header) in SEQUENCE_BRANDS
        else -> false
    }

    /**
     * Reads the header, sniffs, reads the bounds and routes. Never decodes a pixel. [sdkInt] is
     * passed in rather than read, so the API 26 and 27 rows are testable.
     *
     * AVIF, HEIF and JXL are bounded by the native decoder rather than BitmapFactory, which reads
     * them on some API levels or none. A deferred page is not bounded at all.
     */
    fun probe(open: () -> InputStream, sdkInt: Int): PageRoute {
        val header = try {
            open().use { readHeader(it) }
        } catch (_: IOException) {
            return PageRoute.Unsupported
        }
        val format = sniff(header)
        val animated = isAnimated(format, header)
        val size = when {
            isDeferred(format, animated) -> null
            format.isNativeOnly -> nativeBounds.read(open)
            else -> bounds.read(open)
        }
        return route(
            format = format,
            animated = animated,
            width = size?.width ?: 0,
            height = size?.height ?: 0,
            sdkInt = sdkInt,
        )
    }

    /**
     * Pure. The whole routing table, so a test can pin every row without a file.
     *
     * A static AVIF, HEIF or JXL page goes to the region path: the image view decodes every page
     * through its native decoder, which reads these three. An animated AVIF or HEIF stays deferred,
     * because that decoder is not known to read an image sequence. Nothing this build ships reads
     * JP2.
     */
    @Suppress("ComplexCondition")
    fun route(format: ImageFormat, animated: Boolean, width: Int, height: Int, sdkInt: Int): PageRoute = when {
        format == ImageFormat.GIF -> PageRoute.Animated(format, width, height)
        format == ImageFormat.WEBP && animated && sdkInt >= ANIMATED_WEBP_SDK ->
            PageRoute.Animated(format, width, height)
        isDeferred(format, animated) -> PageRoute.Deferred(format)
        format == ImageFormat.JPEG || format == ImageFormat.PNG || format == ImageFormat.WEBP ||
            format.isNativeOnly ->
            if (width > 0 && height > 0) PageRoute.Region(format, width, height) else PageRoute.Unsupported
        // BitmapFactory bounded it, so the image view's region decoder will read it too.
        width > 0 && height > 0 -> PageRoute.Region(ImageFormat.UNKNOWN, width, height)
        else -> PageRoute.Unsupported
    }

    private fun isDeferred(format: ImageFormat, animated: Boolean): Boolean =
        format == ImageFormat.JP2 || animated && (format == ImageFormat.AVIF || format == ImageFormat.HEIF)

    private fun readHeader(stream: InputStream): ByteArray {
        val bytes = ByteArray(HEADER_BYTES)
        var read = 0
        while (read < HEADER_BYTES) {
            val count = stream.read(bytes, read, HEADER_BYTES - read)
            if (count == -1) break
            read += count
        }
        return if (read == HEADER_BYTES) bytes else bytes.copyOf(read)
    }

    private fun brand(header: ByteArray): String =
        if (header.size < 12) "" else String(header, 8, 4, Charsets.US_ASCII)

    /** The VP8X chunk carries the animation flag in bit 1 of byte 20. */
    private fun isAnimatedWebP(header: ByteArray): Boolean {
        if (header.size < 21) return false
        val isExtended = String(header, 12, 4, Charsets.US_ASCII) == "VP8X"
        return isExtended && (header[20].toInt() and 0x02) != 0
    }

    companion object {
        const val HEADER_BYTES = 32

        /** Animated WEBP decodes only from API 28; below it the page shows its first frame. */
        const val ANIMATED_WEBP_SDK = 28

        /** The ISOBMFF major brands of an image sequence: animated AVIF, then the three HEIF ones. */
        private val SEQUENCE_BRANDS = setOf("avis", "hevc", "hevx", "msf1")
    }
}

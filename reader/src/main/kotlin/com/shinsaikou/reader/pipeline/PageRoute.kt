package com.shinsaikou.reader.pipeline

/**
 * Where the bytes of a Ready page go. Decided once by [ImageProbe], before any viewer touches the
 * page. Contract 07 section 4.4.
 */
sealed interface PageRoute {
    /**
     * Static JPEG, PNG, WEBP, AVIF, HEIF and JXL (and anything BitmapFactory can bound): image view
     * region decoding.
     */
    data class Region(val format: ImageFormat, val width: Int, val height: Int) : PageRoute {
        /** Whether the tall-strip split, which decodes through the platform, may cut this page. */
        val isSplittable: Boolean
            get() = !format.isNativeOnly
    }

    /** GIF, animated WEBP on API 28+: Coil, under the cap [AnimatedImagePolicy] computes. */
    data class Animated(val format: ImageFormat, val width: Int, val height: Int) : PageRoute

    /** JP2, animated AVIF and animated HEIF: no decoder this build ships reads them. */
    data class Deferred(val format: ImageFormat) : PageRoute

    data object Unsupported : PageRoute
}

/**
 * A [PageRoute.Deferred] or [PageRoute.Unsupported] page never reaches a holder as Ready: the
 * loader fails it with this, and the holder's error view names the format.
 */
class UnsupportedImageFormatException(val format: ImageFormat) :
    RuntimeException("unsupported image format: $format")

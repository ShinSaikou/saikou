package com.shinsaikou.reader.pipeline

import com.shinsaikou.image.ImageUtil
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * The `:core:common` carry-over requires `ImageProbe` to agree with `ImageUtil.findImageType` by
 * construction, because both sniffers read the same signature table and the reader picks the route
 * while the vendored helpers pick the background and the split. Both sniffers are pure Kotlin, so
 * the cross-check runs here rather than on a device.
 */
class ImageProbeAgreesWithImageUtilTest {

    private val probe = ImageProbe(
        bounds = object : BoundsReader {
            override fun read(open: () -> InputStream) = null
        },
    )

    @Test
    fun `both sniffers name the same format for every shared fixture`() {
        val shared = mapOf(
            ImageHeaders.JPEG to ImageUtil.ImageType.JPEG,
            ImageHeaders.PNG to ImageUtil.ImageType.PNG,
            ImageHeaders.GIF87A to ImageUtil.ImageType.GIF,
            ImageHeaders.GIF89A to ImageUtil.ImageType.GIF,
            ImageHeaders.WEBP_STATIC to ImageUtil.ImageType.WEBP,
            ImageHeaders.WEBP_ANIMATED to ImageUtil.ImageType.WEBP,
            ImageHeaders.AVIF to ImageUtil.ImageType.AVIF,
            ImageHeaders.AVIS to ImageUtil.ImageType.AVIF,
            ImageHeaders.HEIC to ImageUtil.ImageType.HEIF,
            ImageHeaders.HEIX to ImageUtil.ImageType.HEIF,
            ImageHeaders.HEVC to ImageUtil.ImageType.HEIF,
            ImageHeaders.HEVX to ImageUtil.ImageType.HEIF,
            ImageHeaders.MIF1 to ImageUtil.ImageType.HEIF,
            ImageHeaders.MSF1 to ImageUtil.ImageType.HEIF,
            ImageHeaders.JXL_CODESTREAM to ImageUtil.ImageType.JXL,
            ImageHeaders.JXL_CONTAINER to ImageUtil.ImageType.JXL,
        )

        shared.forEach { (header, expected) ->
            findImageType(header) shouldBe expected
            probe.sniff(header).name shouldBe expected.name
        }
    }

    @Test
    fun `both sniffers reject the same bytes`() {
        listOf(ImageHeaders.MP42, ImageHeaders.GARBAGE).forEach { header ->
            findImageType(header) shouldBe null
            probe.sniff(header) shouldBe ImageFormat.UNKNOWN
        }
    }

    @Test
    fun `JP2 is the one format only the reader names`() {
        // ImageUtil's enum has no JP2 case, so agreement cannot be asserted in either direction.
        // Both still route to Deferred, so nothing downstream sees the difference.
        listOf(ImageHeaders.JP2_CONTAINER, ImageHeaders.JP2_CODESTREAM).forEach { header ->
            findImageType(header) shouldBe null
            probe.sniff(header) shouldBe ImageFormat.JP2
            probe.route(ImageFormat.JP2, animated = false, width = 800, height = 1200, sdkInt = 36) shouldBe
                PageRoute.Deferred(ImageFormat.JP2)
        }
    }

    @Test
    fun `the animation verdict matches for every fixture that carries one`() {
        // isAnimatedAndSupported folds the API level in; isAnimated does not, so the route is
        // what makes them comparable. Below API 28 an animated WEBP routes to Region, which is
        // exactly what upstream's helper reports there.
        probe.isAnimated(ImageFormat.GIF, ImageHeaders.GIF89A) shouldBe true
        probe.isAnimated(ImageFormat.WEBP, ImageHeaders.WEBP_ANIMATED) shouldBe true
        probe.isAnimated(ImageFormat.WEBP, ImageHeaders.WEBP_EXTENDED_STATIC) shouldBe false
        probe.isAnimated(ImageFormat.HEIF, ImageHeaders.HEIC) shouldBe false
    }

    private fun findImageType(header: ByteArray) = ImageUtil.findImageType(ByteArrayInputStream(header))
}

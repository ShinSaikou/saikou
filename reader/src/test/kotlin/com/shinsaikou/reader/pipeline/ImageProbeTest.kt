package com.shinsaikou.reader.pipeline

import androidx.compose.ui.unit.IntSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class ImageProbeTest {

    private val probe = ImageProbe(bounds = FakeBounds(IntSize(1080, 1920)), nativeBounds = FakeNativeBounds(null))

    // ---- sniff ----

    @Test
    fun `every signature is recognised`() {
        probe.sniff(ImageHeaders.JPEG) shouldBe ImageFormat.JPEG
        probe.sniff(ImageHeaders.PNG) shouldBe ImageFormat.PNG
        probe.sniff(ImageHeaders.GIF87A) shouldBe ImageFormat.GIF
        probe.sniff(ImageHeaders.GIF89A) shouldBe ImageFormat.GIF
        probe.sniff(ImageHeaders.WEBP_STATIC) shouldBe ImageFormat.WEBP
        probe.sniff(ImageHeaders.WEBP_ANIMATED) shouldBe ImageFormat.WEBP
        probe.sniff(ImageHeaders.JXL_CODESTREAM) shouldBe ImageFormat.JXL
        probe.sniff(ImageHeaders.JXL_CONTAINER) shouldBe ImageFormat.JXL
        probe.sniff(ImageHeaders.JP2_CONTAINER) shouldBe ImageFormat.JP2
        probe.sniff(ImageHeaders.JP2_CODESTREAM) shouldBe ImageFormat.JP2
    }

    @Test
    fun `all six ISOBMFF brands map to their format`() {
        probe.sniff(ImageHeaders.AVIF) shouldBe ImageFormat.AVIF
        probe.sniff(ImageHeaders.AVIS) shouldBe ImageFormat.AVIF
        probe.sniff(ImageHeaders.HEIC) shouldBe ImageFormat.HEIF
        probe.sniff(ImageHeaders.HEIX) shouldBe ImageFormat.HEIF
        probe.sniff(ImageHeaders.HEVC) shouldBe ImageFormat.HEIF
        probe.sniff(ImageHeaders.HEVX) shouldBe ImageFormat.HEIF
        probe.sniff(ImageHeaders.MIF1) shouldBe ImageFormat.HEIF
        probe.sniff(ImageHeaders.MSF1) shouldBe ImageFormat.HEIF
    }

    @Test
    fun `an unrecognised brand and unrecognised bytes are unknown`() {
        probe.sniff(ImageHeaders.MP42) shouldBe ImageFormat.UNKNOWN
        probe.sniff(ImageHeaders.GARBAGE) shouldBe ImageFormat.UNKNOWN
        probe.sniff(ByteArray(0)) shouldBe ImageFormat.UNKNOWN
    }

    @Test
    fun `a header shorter than a signature does not match it`() {
        // Three bytes of a PNG signature are not a PNG.
        probe.sniff(byteArrayOf(0x89.toByte(), 0x50, 0x4E)) shouldBe ImageFormat.UNKNOWN
        // Three bytes of a JPEG signature are a JPEG.
        probe.sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())) shouldBe ImageFormat.JPEG
    }

    // ---- isAnimated ----

    @Test
    fun `a GIF is always animated and a PNG never is`() {
        probe.isAnimated(ImageFormat.GIF, ImageHeaders.GIF89A) shouldBe true
        probe.isAnimated(ImageFormat.GIF, ImageHeaders.GIF87A) shouldBe true
        probe.isAnimated(ImageFormat.PNG, ImageHeaders.PNG) shouldBe false
    }

    @Test
    fun `a WEBP is animated only with the VP8X animation flag set`() {
        probe.isAnimated(ImageFormat.WEBP, ImageHeaders.WEBP_ANIMATED) shouldBe true
        probe.isAnimated(ImageFormat.WEBP, ImageHeaders.WEBP_EXTENDED_STATIC) shouldBe false
        probe.isAnimated(ImageFormat.WEBP, ImageHeaders.WEBP_STATIC) shouldBe false
    }

    @Test
    fun `an AVIF or HEIF is animated only under an image sequence brand`() {
        probe.isAnimated(ImageFormat.AVIF, ImageHeaders.AVIS) shouldBe true
        probe.isAnimated(ImageFormat.HEIF, ImageHeaders.HEVC) shouldBe true
        probe.isAnimated(ImageFormat.HEIF, ImageHeaders.HEVX) shouldBe true
        probe.isAnimated(ImageFormat.HEIF, ImageHeaders.MSF1) shouldBe true

        probe.isAnimated(ImageFormat.AVIF, ImageHeaders.AVIF) shouldBe false
        probe.isAnimated(ImageFormat.HEIF, ImageHeaders.HEIC) shouldBe false
        probe.isAnimated(ImageFormat.HEIF, ImageHeaders.HEIX) shouldBe false
        probe.isAnimated(ImageFormat.HEIF, ImageHeaders.MIF1) shouldBe false
    }

    @Test
    fun `a JXL counts as static`() {
        probe.isAnimated(ImageFormat.JXL, ImageHeaders.JXL_CODESTREAM) shouldBe false
        probe.isAnimated(ImageFormat.JXL, ImageHeaders.JXL_CONTAINER) shouldBe false
    }

    // ---- route ----

    @Test
    fun `static formats with bounds go to the region path`() {
        probe.route(ImageFormat.JPEG, animated = false, width = 800, height = 1200, sdkInt = 36) shouldBe
            PageRoute.Region(ImageFormat.JPEG, 800, 1200)
        probe.route(ImageFormat.PNG, animated = false, width = 800, height = 1200, sdkInt = 36) shouldBe
            PageRoute.Region(ImageFormat.PNG, 800, 1200)
        probe.route(ImageFormat.WEBP, animated = false, width = 800, height = 1200, sdkInt = 36) shouldBe
            PageRoute.Region(ImageFormat.WEBP, 800, 1200)
    }

    @Test
    fun `a static format without bounds is unsupported`() {
        probe.route(ImageFormat.JPEG, animated = false, width = 0, height = 0, sdkInt = 36) shouldBe
            PageRoute.Unsupported
    }

    @Test
    fun `a GIF is animated on every API level`() {
        probe.route(ImageFormat.GIF, animated = true, width = 400, height = 400, sdkInt = 26) shouldBe
            PageRoute.Animated(ImageFormat.GIF, 400, 400)
        probe.route(ImageFormat.GIF, animated = true, width = 400, height = 400, sdkInt = 36) shouldBe
            PageRoute.Animated(ImageFormat.GIF, 400, 400)
    }

    @Test
    fun `an animated WEBP shows its first frame below API 28`() {
        probe.route(ImageFormat.WEBP, animated = true, width = 400, height = 400, sdkInt = 27) shouldBe
            PageRoute.Region(ImageFormat.WEBP, 400, 400)
        probe.route(ImageFormat.WEBP, animated = true, width = 400, height = 400, sdkInt = 26) shouldBe
            PageRoute.Region(ImageFormat.WEBP, 400, 400)
        probe.route(ImageFormat.WEBP, animated = true, width = 400, height = 400, sdkInt = 28) shouldBe
            PageRoute.Animated(ImageFormat.WEBP, 400, 400)
    }

    @Test
    fun `a static AVIF, HEIF or JXL with bounds goes to the region path on every API level`() {
        listOf(ImageFormat.AVIF, ImageFormat.HEIF, ImageFormat.JXL).forEach { format ->
            listOf(26, 28, 31, 36).forEach { sdk ->
                probe.route(format, animated = false, width = 800, height = 1200, sdkInt = sdk) shouldBe
                    PageRoute.Region(format, 800, 1200)
            }
        }
    }

    @Test
    fun `a static AVIF, HEIF or JXL without bounds is unsupported`() {
        listOf(ImageFormat.AVIF, ImageFormat.HEIF, ImageFormat.JXL).forEach {
            probe.route(it, animated = false, width = 0, height = 0, sdkInt = 36) shouldBe PageRoute.Unsupported
        }
    }

    @Test
    fun `an animated AVIF or HEIF and every JP2 stay deferred`() {
        listOf(ImageFormat.AVIF, ImageFormat.HEIF).forEach {
            probe.route(it, animated = true, width = 800, height = 1200, sdkInt = 36) shouldBe PageRoute.Deferred(it)
        }
        probe.route(ImageFormat.JP2, animated = false, width = 800, height = 1200, sdkInt = 36) shouldBe
            PageRoute.Deferred(ImageFormat.JP2)
    }

    @Test
    fun `only the formats the native decoder alone reads are kept from the tall-strip split`() {
        listOf(ImageFormat.AVIF, ImageFormat.HEIF, ImageFormat.JXL).forEach {
            PageRoute.Region(it, 800, 20_000).isSplittable shouldBe false
        }
        listOf(ImageFormat.JPEG, ImageFormat.PNG, ImageFormat.WEBP, ImageFormat.UNKNOWN).forEach {
            PageRoute.Region(it, 800, 20_000).isSplittable shouldBe true
        }
    }

    @Test
    fun `an unknown format that BitmapFactory can bound still gets the region path`() {
        probe.route(ImageFormat.UNKNOWN, animated = false, width = 800, height = 1200, sdkInt = 36) shouldBe
            PageRoute.Region(ImageFormat.UNKNOWN, 800, 1200)
        probe.route(ImageFormat.UNKNOWN, animated = false, width = 0, height = 0, sdkInt = 36) shouldBe
            PageRoute.Unsupported
    }

    // ---- probe ----

    @Test
    fun `probe reads the header and the bounds in one pass`() {
        val bounds = FakeBounds(IntSize(2000, 3000))
        val native = FakeNativeBounds(IntSize(1, 1))
        val route = ImageProbe(bounds, native).probe({ ByteArrayInputStream(ImageHeaders.PNG) }, sdkInt = 36)
        route shouldBe PageRoute.Region(ImageFormat.PNG, 2000, 3000)
        bounds.reads shouldBe 1
        native.reads shouldBe 0
    }

    @Test
    fun `an AVIF, HEIF or JXL is bounded by the native decoder and never by BitmapFactory`() {
        mapOf(
            ImageHeaders.AVIF to ImageFormat.AVIF,
            ImageHeaders.HEIC to ImageFormat.HEIF,
            ImageHeaders.MIF1 to ImageFormat.HEIF,
            ImageHeaders.JXL_CODESTREAM to ImageFormat.JXL,
            ImageHeaders.JXL_CONTAINER to ImageFormat.JXL,
        ).forEach { (header, format) ->
            val bounds = FakeBounds(IntSize(1, 1))
            val native = FakeNativeBounds(IntSize(1600, 2400))

            ImageProbe(bounds, native).probe({ ByteArrayInputStream(header) }, sdkInt = 36) shouldBe
                PageRoute.Region(format, 1600, 2400)
            bounds.reads shouldBe 0
            native.reads shouldBe 1
        }
    }

    @Test
    fun `an AVIF the native decoder cannot open is unsupported`() {
        ImageProbe(FakeBounds(IntSize(800, 1200)), FakeNativeBounds(null))
            .probe({ ByteArrayInputStream(ImageHeaders.AVIF) }, sdkInt = 36) shouldBe PageRoute.Unsupported
    }

    @Test
    fun `a file shorter than the header window still sniffs`() {
        val short = ImageHeaders.JPEG.copyOf(4)
        platformOnly(IntSize(10, 10)).probe({ ByteArrayInputStream(short) }, sdkInt = 36) shouldBe
            PageRoute.Region(ImageFormat.JPEG, 10, 10)
    }

    @Test
    fun `probe reads no more than the header window`() {
        val stream = CountingStream(ByteArray(4096))
        platformOnly(IntSize(10, 10)).probe({ stream }, sdkInt = 36)
        stream.read shouldBe ImageProbe.HEADER_BYTES
    }

    @Test
    fun `probe survives a stream that will not open`() {
        val failing: () -> InputStream = { throw IOException("gone") }
        platformOnly(null).probe(failing, sdkInt = 36) shouldBe PageRoute.Unsupported
    }

    @Test
    fun `a page whose bounds cannot be read is unsupported`() {
        platformOnly(null).probe({ ByteArrayInputStream(ImageHeaders.PNG) }, sdkInt = 36) shouldBe
            PageRoute.Unsupported
    }

    @Test
    fun `a deferred format is not bounded at all`() {
        val bounds = FakeBounds(IntSize(800, 1200))
        val native = FakeNativeBounds(IntSize(800, 1200))
        val probe = ImageProbe(bounds, native)

        mapOf(
            ImageHeaders.AVIS to ImageFormat.AVIF,
            ImageHeaders.MSF1 to ImageFormat.HEIF,
            ImageHeaders.JP2_CONTAINER to ImageFormat.JP2,
        ).forEach { (header, format) ->
            probe.probe({ ByteArrayInputStream(header) }, sdkInt = 36) shouldBe PageRoute.Deferred(format)
        }
        bounds.reads shouldBe 0
        native.reads shouldBe 0
    }

    /** A probe whose native bounds reader is never expected to be asked. */
    private fun platformOnly(size: IntSize?) = ImageProbe(FakeBounds(size), FakeNativeBounds(null))

    private class CountingStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var read = 0
            private set

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) read += it }
    }

    private class FakeBounds(private val size: IntSize?) : BoundsReader {
        var reads = 0
            private set

        override fun read(open: () -> InputStream): IntSize? {
            reads++
            return size
        }
    }

    private class FakeNativeBounds(private val size: IntSize?) : NativeBoundsReader {
        var reads = 0
            private set

        override fun read(open: () -> InputStream): IntSize? {
            reads++
            return size
        }
    }
}

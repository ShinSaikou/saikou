package com.shinsaikou.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shinsaikou.reader.pipeline.DecoderBoundsReader
import com.shinsaikou.reader.pipeline.ImageFormat
import com.shinsaikou.reader.pipeline.ImageProbe
import com.shinsaikou.reader.pipeline.PageRoute
import com.shinsaikou.reader.pipeline.PlatformBoundsReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.decoder.ImageDecoder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.math.abs

/**
 * Instrumentation, not a unit test: [PlatformBoundsReader] is `BitmapFactory` with
 * `inJustDecodeBounds`, and on the unit test JVM `BitmapFactory` is the stubbed `android.jar` that
 * `unitTests.isReturnDefaultValues` turns into zeroes. `ImageProbeTest` in `src/test` therefore
 * drives the routing table through a fake `BoundsReader` and never sees a real byte; this test
 * drives the real bounds reader over real encodes, which is the only place where the table and the
 * platform can disagree. Contract 07 part 2 section 9.2.
 *
 * Every fixture here is generated, because `android.graphics` can encode PNG and JPEG and because
 * a file whose dimensions the test chose is the only way to assert that the bounds are right. The
 * formats with no platform encoder are the one case that needs pushed files, and those are
 * synthetic too: `tools/fixtures/make_probe_images.py` draws them.
 *
 * AVIF, HEIF and JXL are bounded by [DecoderBoundsReader], the image view's own native decoder,
 * which is a real native library here and nowhere else.
 */
@RunWith(AndroidJUnit4::class)
class ImageProbeDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val bounds = PlatformBoundsReader()
    private val probe = ImageProbe(bounds, DecoderBoundsReader())
    private lateinit var scratch: File

    @Before
    fun setUp() {
        scratch = File(context.cacheDir, "image-probe-device-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @Test
    fun pngBoundsAreTheDimensionsItWasEncodedWith() {
        val png = write("page.png", encode(PAGE_WIDTH, PAGE_HEIGHT, Bitmap.CompressFormat.PNG))
        assertEquals(PageRoute.Region(ImageFormat.PNG, PAGE_WIDTH, PAGE_HEIGHT), routeOf(png))
    }

    @Test
    fun jpegBoundsAreTheDimensionsItWasEncodedWith() {
        val jpeg = write("page.jpg", encode(JPEG_WIDTH, JPEG_HEIGHT, Bitmap.CompressFormat.JPEG))
        assertEquals(PageRoute.Region(ImageFormat.JPEG, JPEG_WIDTH, JPEG_HEIGHT), routeOf(jpeg))
    }

    @Test
    fun theBoundsReaderReportsTheDimensionsOfARealEncode() {
        val png = write("bounds.png", encode(PAGE_WIDTH, PAGE_HEIGHT, Bitmap.CompressFormat.PNG))
        val size = bounds.read { png.inputStream() }
        assertNotNull("a PNG the platform wrote has bounds", size)
        assertEquals(PAGE_WIDTH, size!!.width)
        assertEquals(PAGE_HEIGHT, size.height)
    }

    @Test
    fun aFileTruncatedInsideItsHeaderRoutesToUnsupportedRatherThanThrowing() {
        val whole = encode(PAGE_WIDTH, PAGE_HEIGHT, Bitmap.CompressFormat.PNG)
        // The signature is eight bytes and IHDR starts at the ninth, so a sixteen byte prefix is
        // past the point where the format is recognisable and short of the width and height
        // fields: no decoder can invent a size from it, which makes the assertion exact.
        val truncated = write("truncated-header.png", whole.copyOf(HEADER_PREFIX))

        assertEquals(PageRoute.Unsupported, routeOf(truncated))
        assertNull(bounds.read { truncated.inputStream() })
    }

    @Test
    fun aFileTruncatedPastItsHeaderNeverReportsABogusSize() {
        val whole = encode(PAGE_WIDTH, PAGE_HEIGHT, Bitmap.CompressFormat.PNG)
        val half = write("truncated-body.png", whole.copyOf(whole.size / 2))

        // Half a cache entry is the realistic truncation, and the platform reads the size out of
        // IHDR, so this one usually is still boundable. The assertion is shaped as a choice
        // because both answers are correct and the invariant is what holds across them: the probe
        // returns rather than throwing, and it never reports a size a page could not be laid out
        // with.
        when (val route = routeOf(half)) {
            is PageRoute.Region -> {
                assertEquals(ImageFormat.PNG, route.format)
                assertEquals(PAGE_WIDTH, route.width)
                assertEquals(PAGE_HEIGHT, route.height)
            }
            else -> assertEquals(PageRoute.Unsupported, route)
        }
    }

    @Test
    fun anEmptyFileRoutesToUnsupported() {
        val empty = write("empty.png", ByteArray(0))

        assertEquals(PageRoute.Unsupported, routeOf(empty))
        assertNull(bounds.read { empty.inputStream() })
    }

    @Test
    fun bytesThatMatchNoSignatureRouteToUnsupported() {
        // A fixed pattern rather than a random one: two byte signatures such as the JXL
        // codestream marker are reachable by chance, and a fixture that is wrong one run in
        // sixty thousand is worse than no fixture.
        val garbage = write("garbage.bin", ByteArray(GARBAGE_BYTES) { (it * 31 + 7).toByte() })

        assertEquals(PageRoute.Unsupported, routeOf(garbage))
        assertNull(bounds.read { garbage.inputStream() })
    }

    @Test
    fun aTallStripReportsItsRealHeight() {
        val strip = write("strip.png", tallGreyscalePng(STRIP_WIDTH, STRIP_HEIGHT))

        assertEquals(PageRoute.Region(ImageFormat.PNG, STRIP_WIDTH, STRIP_HEIGHT), routeOf(strip))
    }

    /**
     * The routes that cannot be generated, because `android.graphics` has no encoder for an
     * animated WEBP or a GIF, and its WEBP encoder needs an API level above the module's
     * minimum. They come from the pushed fixture directory instead (01-build section 8.3), where
     * `tools/fixtures/make_probe_images.py` writes them, so this method is a no-op on a run
     * without them and fails instead when `saikou.strict` is set. The phase 1 gate passes it to
     * this module and writes the files when they are missing (ruling R49); before that, strict
     * reached `:app` alone and these routes passed without executing in every gate run.
     */
    @Test
    fun theFormatsWithNoPlatformEncoderKeepTheirRoute() {
        val fixtures = fixtureDirectory() ?: return

        val gif = routeOf(copyFixture(fixtures, "animated.gif") ?: return) as? PageRoute.Animated
        assertNotNull("a GIF is the animated route on every API level", gif)
        assertEquals(ImageFormat.GIF, gif!!.format)
        assertTrue("a GIF the platform can bound has a size", gif.width > 0 && gif.height > 0)

        val still = routeOf(copyFixture(fixtures, "static.webp") ?: return) as? PageRoute.Region
        assertNotNull("a static WEBP is the region route", still)
        assertEquals(ImageFormat.WEBP, still!!.format)
        assertTrue("a WEBP the platform can bound has a size", still.width > 0 && still.height > 0)

        // Below API 28 an animated WEBP shows its first frame through the region path instead.
        val moving = routeOf(copyFixture(fixtures, "animated.webp") ?: return)
        if (Build.VERSION.SDK_INT >= ImageProbe.ANIMATED_WEBP_SDK) {
            assertTrue("an animated WEBP is the animated route here, got $moving", moving is PageRoute.Animated)
        } else {
            assertTrue("an animated WEBP is the region route here, got $moving", moving is PageRoute.Region)
        }
    }

    /**
     * A still AVIF, HEIF and JXL go to the region path with the size they were drawn at, on every
     * API level, and the native decoder the image view decodes with turns each into the picture
     * that was drawn: the first frame's red bar on a near-white ground. The colours are compared
     * loosely because all three encodes are lossy. Pushed fixtures, as above.
     */
    @Test
    fun theFormatsOnlyTheNativeDecoderReadsGoToTheRegionPathAndDecode() {
        val fixtures = fixtureDirectory() ?: return
        mapOf(
            "still.avif" to ImageFormat.AVIF,
            "still.heic" to ImageFormat.HEIF,
            "still.jxl" to ImageFormat.JXL,
        ).forEach { (name, format) ->
            val file = copyFixture(fixtures, name) ?: return
            assertEquals(name, PageRoute.Region(format, FIXTURE_WIDTH, FIXTURE_HEIGHT), routeOf(file))

            val decoder = file.inputStream().use { ImageDecoder.newInstance(it) }
            assertNotNull("the image view's decoder could not open $name", decoder)
            val bitmap = try {
                decoder!!.decode()
            } finally {
                decoder!!.recycle()
            }
            assertNotNull("the image view's decoder decoded nothing from $name", bitmap)
            try {
                assertEquals(name, FIXTURE_WIDTH, bitmap!!.width)
                assertEquals(name, FIXTURE_HEIGHT, bitmap.height)
                assertTrue("$name: the bar is not red", isClose(bitmap.getPixel(BAR_X, MIDDLE_Y), BAR_COLOUR))
                assertTrue("$name: no near-white ground", isClose(bitmap.getPixel(GROUND_X, MIDDLE_Y), GROUND_COLOUR))
            } finally {
                bitmap?.recycle()
            }
        }
    }

    // ---- fixtures ----

    private fun write(name: String, bytes: ByteArray): File =
        File(scratch, name).apply { writeBytes(bytes) }

    private fun routeOf(file: File): PageRoute = probe.probe({ file.inputStream() }, Build.VERSION.SDK_INT)

    private fun isClose(actual: Int, expected: Int): Boolean =
        abs(Color.red(actual) - Color.red(expected)) <= COLOUR_TOLERANCE &&
            abs(Color.green(actual) - Color.green(expected)) <= COLOUR_TOLERANCE &&
            abs(Color.blue(actual) - Color.blue(expected)) <= COLOUR_TOLERANCE

    /** A real platform encode, so the bounds under test come from bytes a real encoder wrote. */
    private fun encode(width: Int, height: Int, format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).run {
                drawColor(Color.WHITE)
                drawRect(0f, 0f, width / 2f, height.toFloat(), Paint().apply { color = Color.BLACK })
            }
            val bytes = ByteArrayOutputStream()
            assertTrue("the platform encoder refused $format", bitmap.compress(format, QUALITY, bytes))
            return bytes.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * A [width] by [height] eight bit greyscale PNG, deflated one scanline at a time.
     *
     * Every other fixture here goes through `Bitmap.compress`, and this one cannot: a 2000 by
     * 20000 ARGB_8888 bitmap is 160 MB of pixels, which is more than the 2 GB device the reader
     * has to survive on will hand out, and allocating it to test a path that never reads a pixel
     * would be the test failing for the wrong reason. Writing the chunks keeps the peak cost at
     * one scanline and still produces a file the platform decoder accepts.
     */
    private fun tallGreyscalePng(width: Int, height: Int): ByteArray {
        val png = ByteArrayOutputStream()
        png.write(PNG_SIGNATURE, 0, PNG_SIGNATURE.size)

        val header = ByteArrayOutputStream()
        header.writeBigEndian(width)
        header.writeBigEndian(height)
        header.write(BIT_DEPTH)
        header.write(COLOUR_TYPE_GREYSCALE)
        header.write(0) // deflate, the only compression method PNG defines
        header.write(0) // adaptive filtering, the only filter method PNG defines
        header.write(0) // not interlaced
        png.writeChunk("IHDR", header.toByteArray())

        val pixels = ByteArrayOutputStream()
        DeflaterOutputStream(pixels).use { deflated ->
            // A raw scanline is one filter byte, here 0 for no filter, then one byte per pixel.
            val scanline = ByteArray(width + 1)
            for (y in 0 until height) {
                scanline.fill((y % GREY_LEVELS).toByte(), 1, scanline.size)
                deflated.write(scanline)
            }
        }
        png.writeChunk("IDAT", pixels.toByteArray())
        png.writeChunk("IEND", ByteArray(0))
        return png.toByteArray()
    }

    private fun ByteArrayOutputStream.writeChunk(type: String, data: ByteArray) {
        val name = type.toByteArray(Charsets.US_ASCII)
        writeBigEndian(data.size)
        write(name, 0, name.size)
        write(data, 0, data.size)
        // A chunk's CRC covers the type and the data and not the length field.
        val crc = CRC32().apply {
            update(name, 0, name.size)
            update(data, 0, data.size)
        }
        writeBigEndian(crc.value.toInt())
    }

    private fun ByteArrayOutputStream.writeBigEndian(value: Int) {
        write(value ushr 24 and 0xFF)
        write(value ushr 16 and 0xFF)
        write(value ushr 8 and 0xFF)
        write(value and 0xFF)
    }

    private fun fixtureDirectory(): File? {
        val root = InstrumentationRegistry.getArguments().getString(FIXTURES_ARGUMENT)
        if (!requireFixture(!root.isNullOrBlank(), "instrumentation argument $FIXTURES_ARGUMENT")) return null
        val directory = File(root!!, FIXTURE_SUBDIRECTORY)
        if (!requireFixture(directory.isDirectory, directory.path)) return null
        return directory
    }

    private fun copyFixture(directory: File, name: String): File? {
        val pushed = File(directory, name)
        if (!requireFixture(pushed.isFile, pushed.path)) return null
        // The pushed files sit outside the app's own storage, so they are copied into the cache
        // before anything opens them (01-build section 8.3).
        return File(scratch, name).also { pushed.copyTo(it, overwrite = true) }
    }

    /**
     * The gate runs with `saikou.strict` set and must not go green on a machine that is missing
     * these files; a developer without them gets a no-op instead of a red module. This uses
     * an early return rather than `Assume.assumeTrue`: `AndroidJUnitRunner` reports an assumption
     * failure through `testAssumptionFailure`, and AGP's connected test XML writer records that as
     * a `<failure>`, so every machine without the owner's fixture directory would go red anyway.
     *
     * @return whether the fixture is present, so callers can `?: return` when it is not.
     */
    private fun requireFixture(present: Boolean, what: String): Boolean {
        val strict = InstrumentationRegistry.getArguments().getString(STRICT_ARGUMENT) == "true"
        if (!present && strict) throw AssertionError("missing fixture: $what")
        return present
    }

    private companion object {
        const val PAGE_WIDTH = 720
        const val PAGE_HEIGHT = 1280
        const val JPEG_WIDTH = 800
        const val JPEG_HEIGHT = 1200
        const val STRIP_WIDTH = 2000
        const val STRIP_HEIGHT = 20000
        const val QUALITY = 90
        const val HEADER_PREFIX = 16
        const val GARBAGE_BYTES = 4096

        /** What `make_probe_images.py` draws: a 96 by 64 still, its first bar at x 8 to 32. */
        const val FIXTURE_WIDTH = 96
        const val FIXTURE_HEIGHT = 64
        const val BAR_X = 20
        const val GROUND_X = 80
        const val MIDDLE_Y = 32
        val BAR_COLOUR = Color.rgb(200, 40, 40)
        val GROUND_COLOUR = Color.rgb(250, 250, 250)
        const val COLOUR_TOLERANCE = 40

        const val BIT_DEPTH = 8
        const val COLOUR_TYPE_GREYSCALE = 0
        const val GREY_LEVELS = 0xFF
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        const val FIXTURES_ARGUMENT = "saikou.fixtures.dir"
        const val STRICT_ARGUMENT = "saikou.strict"

        /** One file per format, under the pushed fixture root. No fixture name says where it came from. */
        const val FIXTURE_SUBDIRECTORY = "reader/probe"
    }
}

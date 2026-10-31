package com.shinsaikou.reader

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.reader.cache.ChapterCache
import com.shinsaikou.reader.memory.DisplayInfoProvider
import com.shinsaikou.reader.pipeline.PlatformBoundsReader
import com.shinsaikou.reader.pipeline.TallStripSplitter
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.math.abs

/**
 * Instrumentation, not a unit test: every step of a split is platform code. `BitmapFactory` reads
 * the bounds, `BitmapRegionDecoder` cuts each band and `Bitmap.compress` writes it, and `:reader`
 * sets `unitTests.isReturnDefaultValues`, so on the JVM all three answer null and the splitter
 * would report one part without ever having tried. The bands also have to land in a real
 * `DiskLruCache` on a real filesystem for [ChapterCache.partsInCache] to mean anything.
 *
 * Contract 07 part 2 section 9.2. The fixture is generated here, so this class never skips.
 */
@RunWith(AndroidJUnit4::class)
class TallStripSplitterTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Fixed rather than read off the device, so the plan under test is the same on every AVD. At
     * 2000 px the optimal height is 4000, which asks for five bands; the 4 Mpx transient ceiling
     * raises that to ten, and ten is what the device then has to produce.
     */
    private val display = object : DisplayInfoProvider {
        override val widthPx = 1080
        override val heightPx = 2000
        override val maxDimensionPx = 2000
    }

    private lateinit var directory: File
    private lateinit var cache: ChapterCache
    private lateinit var splitter: TallStripSplitter

    @Before
    fun setUp() {
        directory = File(context.cacheDir, "tall-strip-splitter-test").apply {
            deleteRecursively()
            mkdirs()
        }
        cache = ChapterCache(
            context = context,
            json = Json,
            dispatchers = Dispatchers(
                io = kotlinx.coroutines.Dispatchers.IO,
                default = kotlinx.coroutines.Dispatchers.Default,
                main = kotlinx.coroutines.Dispatchers.Main,
            ),
        )
        splitter = TallStripSplitter(cache, PlatformBoundsReader(), display)
        cache.removeParts(KEY)
    }

    @After
    fun tearDown() {
        cache.removeParts(KEY)
        directory.deleteRecursively()
    }

    @Test
    fun splitsAGeneratedTallStripIntoThePlannedBands() {
        val strip = File(directory, "tall-strip.png")
        writePng(strip, deflatedGradientScanlines())

        val plan = checkNotNull(splitter.plan(WIDTH, HEIGHT)) { "a ${WIDTH}x$HEIGHT page has to be a tall strip" }
        assertEquals(plan.partCount, plan.bands.size)
        assertEquals(HEIGHT, plan.bands.sumOf { it.height })

        assertEquals(plan.partCount, splitter.split({ strip.inputStream() }, KEY))
        assertEquals(plan.partCount, cache.partsInCache(KEY))

        var decodedHeight = 0
        for (band in plan.bands) {
            val file = cache.getPartFile(KEY, band.index)
            assertTrue("band ${band.index} is not in the cache", file.exists())
            val bitmap = checkNotNull(BitmapFactory.decodeFile(file.absolutePath)) {
                "band ${band.index} did not open as a bitmap"
            }
            try {
                assertEquals(band.width, bitmap.width)
                assertEquals(band.height, bitmap.height)
                // The strip is a top-to-bottom gradient, so the first and last rows of a band name
                // the rows of the original it was cut from: this is what makes the band heights
                // summing to HEIGHT mean "the bands concatenate back" and not just "they add up".
                assertGray(band.index, grayAt(band.top), bitmap.getPixel(SAMPLE_X, 0))
                assertGray(band.index, grayAt(band.bottom - 1), bitmap.getPixel(SAMPLE_X, bitmap.height - 1))
                decodedHeight += bitmap.height
            } finally {
                // Exactly one band bitmap is live at a time, the same rule the splitter follows:
                // four of these at 2000x2000 would be the whole decode budget of the 2 GB device.
                bitmap.recycle()
            }
        }
        assertEquals(HEIGHT, decodedHeight)

        // Idempotence is only observable against a real cache: the second pass finds its parts.
        assertEquals(plan.partCount, splitter.split({ strip.inputStream() }, KEY))
    }

    @Test
    fun aFileThatIsNotAnImageIsLeftAloneAndCountsAsOnePart() {
        val notAnImage = File(directory, "not-an-image.bin")
        notAnImage.writeBytes(noise())
        val before = notAnImage.readBytes()

        assertEquals(1, splitter.split({ notAnImage.inputStream() }, KEY))

        assertArrayEquals(before, notAnImage.readBytes())
        assertEquals(0, cache.partsInCache(KEY))
    }

    @Test
    fun aStripWithGarbagePixelsIsStillSplitFromItsValidHeaderAndLeavesTheCallersFileIntact() {
        val strip = File(directory, "unreadable-strip.png")
        // A well formed IHDR over an IDAT that is not a zlib stream. On API 36 the platform
        // region decoder never reports a failure for a file whose header parses: it opens from
        // the header alone and cuts every band without complaint, so the splitter's plan runs to
        // completion and the full part count comes back, garbage pixels and all. The "a decoder
        // failure returns 1" half of contract 07 part 2 section 9.2 is instead covered by the
        // sibling test aFileThatIsNotAnImageIsLeftAloneAndCountsAsOnePart, where the header itself
        // does not parse and BitmapFactory genuinely refuses the file.
        writePng(strip, noise())
        val before = strip.readBytes()

        val plan = checkNotNull(splitter.plan(WIDTH, HEIGHT)) { "a ${WIDTH}x$HEIGHT page has to be a tall strip" }
        assertEquals(plan.partCount, splitter.split({ strip.inputStream() }, KEY))
        assertEquals(plan.partCount, cache.partsInCache(KEY))

        // Splitting reads from the caller's stream and never touches the caller's file, whether
        // the decode behind it succeeded or not.
        assertArrayEquals(before, strip.readBytes())
    }

    private fun assertGray(index: Int, expected: Int, pixel: Int) {
        val actual = Color.red(pixel)
        // The bands are re-encoded as JPEG, and quality 100 is still lossy.
        assertTrue(
            "band $index: expected a gray within $GRAY_TOLERANCE of $expected, got $actual",
            abs(expected - actual) <= GRAY_TOLERANCE,
        )
    }

    /**
     * The PNG is assembled chunk by chunk instead of through `Bitmap.compress`, because a
     * 2000 x 20000 ARGB_8888 bitmap is 160 MB in one allocation and the 2 GB AVD this suite has to
     * pass on cannot make it. Deflating one scanline at a time holds [WIDTH] + 1 bytes instead, and
     * the splitter only ever sees the finished file.
     */
    private fun deflatedGradientScanlines(): ByteArray {
        val idat = ByteArrayOutputStream()
        val deflater = Deflater(Deflater.BEST_SPEED)
        try {
            DeflaterOutputStream(idat, deflater).use { out ->
                val scanline = ByteArray(1 + WIDTH)
                for (y in 0..<HEIGHT) {
                    // Filter type 0, so a row's bytes are its pixels and grayAt stays readable.
                    scanline[0] = 0
                    scanline.fill(grayAt(y).toByte(), 1, scanline.size)
                    out.write(scanline)
                }
            }
        } finally {
            deflater.end()
        }
        return idat.toByteArray()
    }

    private fun writePng(target: File, idat: ByteArray) {
        val header = ByteArrayOutputStream(IHDR_BYTES)
        DataOutputStream(header).use {
            it.writeInt(WIDTH)
            it.writeInt(HEIGHT)
            it.writeByte(BIT_DEPTH)
            it.writeByte(COLOR_TYPE_GRAYSCALE)
            it.writeByte(0) // deflate, the only compression method PNG defines
            it.writeByte(0) // adaptive filtering, the only filter method PNG defines
            it.writeByte(0) // not interlaced
        }
        DataOutputStream(BufferedOutputStream(target.outputStream())).use { out ->
            out.write(PNG_SIGNATURE)
            writeChunk(out, "IHDR", header.toByteArray())
            writeChunk(out, "IDAT", idat)
            writeChunk(out, "IEND", ByteArray(0))
        }
    }

    private fun writeChunk(out: DataOutputStream, type: String, data: ByteArray) {
        val name = type.toByteArray(Charsets.US_ASCII)
        out.writeInt(data.size)
        out.write(name)
        out.write(data)
        val crc = CRC32()
        crc.update(name)
        crc.update(data)
        out.writeInt(crc.value.toInt())
    }

    /** Deterministic bytes that are not a zlib stream: the first byte names compression method 7. */
    private fun noise(): ByteArray = ByteArray(NOISE_BYTES) { (it * 31 + 7).toByte() }

    private fun grayAt(y: Int): Int = y * MAX_GRAY / (HEIGHT - 1)

    private companion object {
        const val WIDTH = 2000
        const val HEIGHT = 20000

        /** Any string works: `DiskKeys` hashes it before `DiskLruCache` ever sees it. */
        const val KEY = "tall-strip-splitter-test"

        const val SAMPLE_X = WIDTH / 2
        const val GRAY_TOLERANCE = 4
        const val MAX_GRAY = 255
        const val NOISE_BYTES = 1024

        const val IHDR_BYTES = 13
        const val BIT_DEPTH = 8
        const val COLOR_TYPE_GRAYSCALE = 0

        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}

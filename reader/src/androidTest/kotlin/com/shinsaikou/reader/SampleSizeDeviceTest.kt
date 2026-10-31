package com.shinsaikou.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shinsaikou.reader.pipeline.TargetSize
import com.shinsaikou.reader.pipeline.sampleSizeFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Instrumentation, not a unit test: every assertion below runs through [BitmapFactory], a thin
 * binding over Skia that has no implementation on the unit test JVM, and `:reader` sets
 * `unitTests.isReturnDefaultValues = true`, so on the JVM a decode would quietly hand back a null
 * bitmap and a zero width and the test would pass for the wrong reason.
 *
 * Contract 07 part 2 section 9.2 names this test `PlatformImageDecoderTest`. Ruling R25 deleted the
 * subject of that name: `ImageDecoder`, its one implementation `PlatformImageDecoder` and
 * `DecoderRegistry` went because nothing wired them. What survived is the pure calculator
 * [sampleSizeFor] with [TargetSize], and so did the assertion the contract was really making about
 * it, which is the one this file covers: a decode that applies the calculator's answer allocates no
 * more than [TargetSize.maxPixels], and the sample size is settled before the first pixel buffer
 * exists. `SampleSizeTest` in `reader/src/test` already covers the arithmetic on its own, so
 * nothing here asserts a number that a real decode did not produce.
 */
@RunWith(AndroidJUnit4::class)
class SampleSizeDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File
    private lateinit var image: File

    @Before
    fun setUp() {
        directory = File(context.cacheDir, "sample-size-device-test").apply {
            deleteRecursively()
            mkdirs()
        }
        image = writeFixture(File(directory, "page.jpg"))
    }

    @Test
    fun boundsDecodeAllocatesNoPixels() {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val decoded = BitmapFactory.decodeFile(image.path, options)

        // This is the "before the first pixel buffer exists" half of the contract: the numbers
        // sampleSizeFor works from come out of a pass that returns no bitmap to allocate.
        assertNull("a bounds pass must not return a bitmap", decoded)
        assertEquals(IMAGE_WIDTH, options.outWidth)
        assertEquals(IMAGE_HEIGHT, options.outHeight)
    }

    @Test
    fun sampledDecodeStaysInsideTheTargetBudget() {
        val bounds = decodeBounds()
        val target = TargetSize(width = VIEWPORT_WIDTH, height = VIEWPORT_HEIGHT, maxPixels = VIEWPORT_PIXELS)
        val sample = sampleSizeFor(bounds.outWidth, bounds.outHeight, target)

        // The assertions below only mean anything because the unsampled decode really would
        // overflow: the fixture is twelve megapixels against a two megapixel budget.
        assertTrue(
            "the fixture is too small to exercise the budget",
            bounds.outWidth.toLong() * bounds.outHeight > target.maxPixels,
        )

        val decoded = decodeSampled(sample)
        try {
            assertWithinOnePixel(bounds.outWidth / sample, decoded.width, "width")
            assertWithinOnePixel(bounds.outHeight / sample, decoded.height, "height")
            assertTrue(
                "decoded ${decoded.width}x${decoded.height} is over the ${target.maxPixels} pixel budget",
                decoded.width.toLong() * decoded.height <= target.maxPixels,
            )
            // maxPixels is a pixel count, so the byte ceiling it implies depends on the config,
            // which is why decodeSampled pins ARGB_8888 rather than taking what the file suggests.
            val budgetBytes = target.maxPixels * ARGB_8888_BYTES_PER_PIXEL
            assertTrue(
                "decode allocated ${decoded.allocationByteCount} bytes against a ceiling of $budgetBytes",
                decoded.allocationByteCount <= budgetBytes,
            )
        } finally {
            decoded.recycle()
        }
    }

    @Test
    fun largestPowerOfTwoBoundaryHoldsOnRealBounds() {
        val bounds = decodeBounds()
        // A budget of zero switches the pixel cap off, which leaves the power of two rule as the
        // only thing deciding these two sample sizes.
        val covered = TargetSize(bounds.outWidth / 4, bounds.outHeight / 4, 0)
        val onePixelLarger = TargetSize(covered.width + 1, covered.height + 1, 0)

        val atBoundary = decodeSampled(sampleSizeFor(bounds.outWidth, bounds.outHeight, covered))
        val pastBoundary = decodeSampled(sampleSizeFor(bounds.outWidth, bounds.outHeight, onePixelLarger))
        try {
            assertTrue(
                "decoded ${atBoundary.width}x${atBoundary.height} does not cover ${covered.width}x${covered.height}",
                atBoundary.width >= covered.width && atBoundary.height >= covered.height,
            )
            // One pixel of target past what the smaller decode delivers is the whole boundary: the
            // rule may only halve while the result still covers, and this decode proves it would
            // not have covered.
            assertTrue(
                "decoded ${atBoundary.width} wide should fall short of ${onePixelLarger.width}",
                atBoundary.width < onePixelLarger.width,
            )
            assertTrue(
                "decoded ${pastBoundary.width}x${pastBoundary.height} does not cover " +
                    "${onePixelLarger.width}x${onePixelLarger.height}",
                pastBoundary.width >= onePixelLarger.width && pastBoundary.height >= onePixelLarger.height,
            )
        } finally {
            atBoundary.recycle()
            pastBoundary.recycle()
        }
    }

    @Test
    fun targetLargerThanTheImageDecodesItWhole() {
        val bounds = decodeBounds()
        val target = TargetSize(bounds.outWidth * 2, bounds.outHeight * 2, 0)

        // RGB_565 here alone: this is the only unsampled decode in the file and the claim is about
        // dimensions, so there is no reason to ask a device for the 48 MB that ARGB_8888 would want.
        val decoded = decodeSampled(sampleSizeFor(bounds.outWidth, bounds.outHeight, target), Bitmap.Config.RGB_565)
        try {
            assertEquals(bounds.outWidth, decoded.width)
            assertEquals(bounds.outHeight, decoded.height)
        } finally {
            decoded.recycle()
        }
    }

    private fun decodeBounds(): BitmapFactory.Options =
        BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            BitmapFactory.decodeFile(image.path, this)
        }

    private fun decodeSampled(sample: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = config
        }
        return checkNotNull(BitmapFactory.decodeFile(image.path, options)) {
            "the fixture failed to decode at sample size $sample"
        }
    }

    /**
     * Skia derives a sampled dimension by integer division, but a codec is allowed to round, so one
     * pixel of slack keeps this from asserting an implementation detail. Anything wider than that is
     * the sample size not having been applied.
     */
    private fun assertWithinOnePixel(expected: Int, actual: Int, what: String) {
        assertTrue("$what was $actual, expected about $expected", abs(actual - expected) <= 1)
    }

    /**
     * Drawn at RGB_565 and recycled straight away: only the encoded dimensions matter, and holding
     * the source at ARGB_8888 would put 48 MB on the same heap the decodes under test are measured
     * against. The bars are there so the encoder cannot collapse a flat fill into a file no page
     * would ever be.
     */
    private fun writeFixture(file: File): File {
        val source = Bitmap.createBitmap(IMAGE_WIDTH, IMAGE_HEIGHT, Bitmap.Config.RGB_565)
        try {
            val canvas = Canvas(source)
            canvas.drawColor(Color.WHITE)
            val paint = Paint().apply { color = Color.BLACK }
            var left = 0
            while (left < IMAGE_WIDTH) {
                canvas.drawRect(left.toFloat(), 0f, (left + 40).toFloat(), IMAGE_HEIGHT.toFloat(), paint)
                left += 160
            }
            file.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        } finally {
            source.recycle()
        }
        return file
    }

    private companion object {
        const val IMAGE_WIDTH = 4000
        const val IMAGE_HEIGHT = 3000
        const val VIEWPORT_WIDTH = 1080
        const val VIEWPORT_HEIGHT = 1920
        const val VIEWPORT_PIXELS = 2_073_600L
        const val ARGB_8888_BYTES_PER_PIXEL = 4
    }
}

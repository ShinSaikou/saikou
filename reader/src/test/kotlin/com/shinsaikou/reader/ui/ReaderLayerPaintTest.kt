package com.shinsaikou.reader.ui

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * A pure function of two booleans, which is the whole point of extracting it out of the
 * `AndroidView` update block.
 *
 * `android.graphics.ColorMatrix` is a stub on the unit-test JVM: under
 * `unitTests.isReturnDefaultValues` `setSaturation` is a no-op and `getArray` returns null, so the
 * coefficients cannot be read back off a live matrix. That is why the inversion matrix is
 * [INVERSION_MATRIX] in production code and is asserted here directly, and why the paint itself is
 * only asserted null or not null. The rendered result is a device concern (part 2 section 9.2).
 */
class ReaderLayerPaintTest {

    @Test
    fun `neither filter means no layer at all`() {
        readerLayerPaint(grayscale = false, invertedColors = false) shouldBe null
    }

    @Test
    fun `either filter produces a paint`() {
        (readerLayerPaint(grayscale = true, invertedColors = false) != null) shouldBe true
        (readerLayerPaint(grayscale = false, invertedColors = true) != null) shouldBe true
        (readerLayerPaint(grayscale = true, invertedColors = true) != null) shouldBe true
    }

    @Test
    fun `the inversion matrix negates each colour channel and offsets by full scale`() {
        INVERSION_MATRIX.size shouldBe 20
        INVERSION_MATRIX[0] shouldBe -1f
        INVERSION_MATRIX[4] shouldBe 255f
        INVERSION_MATRIX[6] shouldBe -1f
        INVERSION_MATRIX[9] shouldBe 255f
        INVERSION_MATRIX[12] shouldBe -1f
        INVERSION_MATRIX[14] shouldBe 255f
    }

    @Test
    fun `the inversion matrix leaves alpha alone`() {
        // Alpha passes through untouched, or the page would fade as well as invert.
        INVERSION_MATRIX[18] shouldBe 1f
        INVERSION_MATRIX[19] shouldBe 0f
        // And no colour channel bleeds into another.
        listOf(1, 2, 3, 5, 7, 8, 10, 11, 13, 15, 16, 17).forEach { INVERSION_MATRIX[it] shouldBe 0f }
    }
}

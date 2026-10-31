package com.shinsaikou.reader.settings

import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The `imageScaleType` preference integer goes straight into `setMinimumScaleType(int)`, so the
 * order of [ReaderSettingsOptions.ImageScaleType] *is* the image view fork's constant order. This
 * is the test that stops a reordered label list from silently changing what every stored preference
 * means.
 *
 * The fork's scale types are Java `static final int`, which Kotlin inlines at compile time, so
 * nothing here loads an Android class.
 */
class ScaleTypeConstantsTest {

    @Test
    fun `index i labels the image view constant i plus one`() {
        val expected = listOf(
            SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE,
            SubsamplingScaleImageView.SCALE_TYPE_CENTER_CROP,
            SubsamplingScaleImageView.SCALE_TYPE_FIT_WIDTH,
            SubsamplingScaleImageView.SCALE_TYPE_FIT_HEIGHT,
            SubsamplingScaleImageView.SCALE_TYPE_ORIGINAL_SIZE,
            SubsamplingScaleImageView.SCALE_TYPE_SMART_FIT,
        )
        expected shouldBe listOf(1, 2, 3, 4, 5, 6)
        ReaderSettingsOptions.ImageScaleType.indices.map { it + 1 } shouldBe expected
    }

    @Test
    fun `there are exactly six scale types and every one has a label`() {
        ReaderSettingsOptions.ImageScaleType.size shouldBe 6
        ReaderSettingsOptions.ImageScaleType.forEach { (it != 0) shouldBe true }
    }

    @Test
    fun `zoom start has four entries and tap zones six`() {
        ReaderSettingsOptions.ZoomStart.size shouldBe 4
        ReaderSettingsOptions.TapZones.size shouldBe 6
    }
}

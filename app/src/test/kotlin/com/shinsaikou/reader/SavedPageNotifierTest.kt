package com.shinsaikou.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SavedPageNotifierTest {

    @Test
    fun `a page inside the preview's box is decoded whole`() {
        previewSampleSize(720, 1280) shouldBe 1
        previewSampleSize(64, 96) shouldBe 1
    }

    @Test
    fun `a larger page is halved until it fits on both sides`() {
        previewSampleSize(1080, 1920) shouldBe 2
        // A long strip page: the height decides.
        previewSampleSize(800, 10_000) shouldBe 8
        previewSampleSize(4000, 1000) shouldBe 8
    }
}

package com.shinsaikou.reader.pipeline

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The sample-size rule, which is what is left of contract 07 section 4.6 after the `ImageDecoder`
 * seam was deleted: the image view fork decodes inside itself and has no pluggable decoder, so the
 * interface, its one implementation and the registry had no production caller. `sampleSizeFor`
 * itself is live, through `AnimatedImagePolicy` and `CappedAnimatedDecoder` (ruling R23).
 */
class SampleSizeTest {

    private fun target(width: Int, height: Int, maxPixels: Long) = TargetSize(width, height, maxPixels)

    @Test
    fun `an image already at the target is not sampled`() {
        sampleSizeFor(1000, 1000, target(1000, 1000, 10_000_000)) shouldBe 1
    }

    @Test
    fun `an image under the target is never upsampled`() {
        sampleSizeFor(500, 700, target(2160, 4800, 10_000_000)) shouldBe 1
    }

    @Test
    fun `the sample size is the largest power of two that still covers the target`() {
        sampleSizeFor(4000, 4000, target(1000, 1000, 64_000_000)) shouldBe 4
        sampleSizeFor(3999, 3999, target(1000, 1000, 64_000_000)) shouldBe 2
        sampleSizeFor(8000, 8000, target(1000, 1000, 64_000_000)) shouldBe 8
    }

    @Test
    fun `one dimension covering the target is enough`() {
        // 2000x12000 against 1374x3053: halving keeps the height over the target, so it halves.
        sampleSizeFor(2000, 12000, target(1374, 3053, 64_000_000)) shouldBe 2
    }

    @Test
    fun `the pixel budget shrinks the result past the target when it has to`() {
        // Two would leave 1000x6000, six million pixels, over the cap.
        sampleSizeFor(2000, 12000, target(1374, 3053, 4_194_304)) shouldBe 4
    }

    @Test
    fun `the pixel budget is a hard cap on a square image too`() {
        // 8000x8000 at the target alone would be 8; the budget alone demands at least 8 as well.
        sampleSizeFor(8000, 8000, target(4000, 4000, 1_000_000)) shouldBe 8
    }

    @Test
    fun `a budget of zero disables the cap`() {
        sampleSizeFor(4000, 4000, target(1000, 1000, 0)) shouldBe 4
    }

    @Test
    fun `a degenerate size is never sampled`() {
        sampleSizeFor(0, 1000, target(100, 100, 1000)) shouldBe 1
        sampleSizeFor(1000, 0, target(100, 100, 1000)) shouldBe 1
        sampleSizeFor(-1, -1, target(100, 100, 1000)) shouldBe 1
    }

    @Test
    fun `a degenerate target terminates on a power of two rather than looping`() {
        val sample = sampleSizeFor(1000, 1000, target(0, 0, 1_000_000))
        (sample and (sample - 1)) shouldBe 0
        (1000 / sample) shouldBe 1
    }

    @Test
    fun `the result is always a power of two`() {
        listOf(
            sampleSizeFor(20000, 20000, target(100, 100, 4_194_304)),
            sampleSizeFor(4001, 9007, target(333, 777, 1_000_000)),
            sampleSizeFor(1, 100_000, target(1, 1, 1_000)),
        ).forEach { (it and (it - 1)) shouldBe 0 }
    }
}

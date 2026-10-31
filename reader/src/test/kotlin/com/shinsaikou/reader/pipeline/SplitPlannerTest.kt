package com.shinsaikou.reader.pipeline

import com.shinsaikou.reader.memory.DisplayInfoProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SplitPlannerTest {

    // ---- upstream's two formulas, the same cases :core:common's calculator test uses ----

    @Test
    fun `part count is one while the image fits the optimal height`() {
        SplitPlanner.calculatePartCount(imageHeight = 1, optimalImageHeight = 4800) shouldBe 1
        SplitPlanner.calculatePartCount(imageHeight = 4799, optimalImageHeight = 4800) shouldBe 1
        SplitPlanner.calculatePartCount(imageHeight = 4800, optimalImageHeight = 4800) shouldBe 1
    }

    @Test
    fun `part count grows one band at a time past the optimal height`() {
        SplitPlanner.calculatePartCount(imageHeight = 4801, optimalImageHeight = 4800) shouldBe 2
        SplitPlanner.calculatePartCount(imageHeight = 9600, optimalImageHeight = 4800) shouldBe 2
        SplitPlanner.calculatePartCount(imageHeight = 9601, optimalImageHeight = 4800) shouldBe 3
        SplitPlanner.calculatePartCount(imageHeight = 20000, optimalImageHeight = 4800) shouldBe 5
    }

    @Test
    fun `part count refuses a non positive input`() {
        shouldThrow<IllegalArgumentException> { SplitPlanner.calculatePartCount(0, 4800) }
        shouldThrow<IllegalArgumentException> { SplitPlanner.calculatePartCount(100, 0) }
    }

    @Test
    fun `splitting needs both the aspect guard and more than one part`() {
        // Tall enough and more than one part.
        SplitPlanner.shouldSplit(800, 20000, 4800) shouldBe true
        // More than one part, but only 2_5 times as tall as it is wide.
        SplitPlanner.shouldSplit(2000, 5000, 4800) shouldBe false
        // Tall enough, but one part.
        SplitPlanner.shouldSplit(800, 4000, 4800) shouldBe false
        // Exactly three times as tall is not more than three times as tall.
        SplitPlanner.shouldSplit(2000, 6000, 1000) shouldBe false
        SplitPlanner.shouldSplit(2000, 6001, 1000) shouldBe true
    }

    @Test
    fun `shouldSplit refuses a non positive width`() {
        shouldThrow<IllegalArgumentException> { SplitPlanner.shouldSplit(0, 20000, 4800) }
    }

    // ---- The worked table of part 1b section 4_7_1 ----

    @Test
    fun `the bench fixture plans as five bands, which is upstream's plan unchanged`() {
        val plan = SplitPlanner.plan(800, 20000, optimalImageHeight = 4800)!!
        plan.partCount shouldBe 5
        plan.bands.map { it.height }.distinct() shouldBe listOf(4000)
        plan.bands.map { it.width }.distinct() shouldBe listOf(800)
    }

    @Test
    fun `a wide strip is cut by the pixel cap rather than by the height`() {
        // Upstream's height rule alone would give 5 bands of 2000x4000, 32 MB of transient each.
        val plan = SplitPlanner.plan(2000, 20000, optimalImageHeight = 4800)!!
        plan.partCount shouldBe 10
        plan.bands.map { it.height }.distinct() shouldBe listOf(2000)
        plan.bands.all { it.width.toLong() * it.height <= SplitPlanner.MAX_BAND_PIXELS } shouldBe true
    }

    @Test
    fun `the pixel cap raises a three band plan to four`() {
        val plan = SplitPlanner.plan(1200, 12000, optimalImageHeight = 4800)!!
        plan.partCount shouldBe 4
        plan.bands.map { it.height }.distinct() shouldBe listOf(3000)
    }

    @Test
    fun `an ordinary page and a double page spread are not strips`() {
        SplitPlanner.plan(1080, 2400, optimalImageHeight = 4800) shouldBe null
        SplitPlanner.plan(2000, 5000, optimalImageHeight = 4800) shouldBe null
    }

    @Test
    fun `a taller display asks for fewer, taller bands`() {
        val plan = SplitPlanner.plan(800, 20000, optimalImageHeight = 5120)!!
        plan.partCount shouldBe 4
        plan.bands.map { it.height }.distinct() shouldBe listOf(5000)
    }

    // ---- The invariants the splitter and both viewers depend on ----

    @Test
    fun `bands partition the image with no gap and no overlap`() {
        val plan = SplitPlanner.plan(900, 21_007, optimalImageHeight = 4800)!!
        plan.bands.first().top shouldBe 0
        plan.bands.zipWithNext().forEach { (a, b) -> b.top shouldBe a.bottom }
        plan.bands.last().bottom shouldBe 21_007
        plan.bands.sumOf { it.height } shouldBe 21_007
    }

    @Test
    fun `the last band absorbs the integer division remainder`() {
        val plan = SplitPlanner.plan(900, 21_007, optimalImageHeight = 4800)!!
        val heights = plan.bands.map { it.height }
        heights.dropLast(1).distinct().size shouldBe 1
        heights.last() shouldBe heights.first() + 21_007 % plan.partCount
    }

    @Test
    fun `band indices are dense and start at zero`() {
        val plan = SplitPlanner.plan(800, 20000, optimalImageHeight = 4800)!!
        plan.bands.map { it.index } shouldBe plan.bands.indices.toList()
        plan.partCount shouldBe plan.bands.size
    }

    @Test
    fun `the part count never exceeds the part key budget`() {
        // A very small optimal height and a very large image would otherwise ask for thousands.
        val plan = SplitPlanner.plan(10, 500_000, optimalImageHeight = 1, maxBandPixels = 1)!!
        plan.partCount shouldBe SplitPlanner.MAX_PARTS
        plan.bands.size shouldBe SplitPlanner.MAX_PARTS
    }

    @Test
    fun `the part count never exceeds one band per row`() {
        val plan = SplitPlanner.plan(1, 7, optimalImageHeight = 1, maxBandPixels = 1)!!
        plan.partCount shouldBe 7
        plan.bands.map { it.height }.distinct() shouldBe listOf(1)
    }

    @Test
    fun `the optimal height is twice the display's larger dimension`() {
        SplitPlanner.optimalImageHeight(FakeDisplay(1080, 2400, 2400)) shouldBe 4800
        SplitPlanner.optimalImageHeight(FakeDisplay(2560, 1600, 2560)) shouldBe 5120
    }

    private class FakeDisplay(
        override val widthPx: Int,
        override val heightPx: Int,
        override val maxDimensionPx: Int,
    ) : DisplayInfoProvider
}

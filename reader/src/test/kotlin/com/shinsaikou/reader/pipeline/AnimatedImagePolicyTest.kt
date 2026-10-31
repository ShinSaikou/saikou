package com.shinsaikou.reader.pipeline

import androidx.compose.ui.unit.IntSize
import com.shinsaikou.domain.preferences.ReaderPreferences
import com.shinsaikou.reader.memory.DisplayInfoProvider
import com.shinsaikou.reader.memory.MemoryInfoProvider
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class AnimatedImagePolicyTest {

    private val mib = 1024L * 1024L

    // ---- The three worked rows of part 1b section 4_8 ----

    @Test
    fun `the low RAM device caps an animated frame at a quarter of the wanted size`() {
        // Viewport 1080x2400, wanted 2160x4800 = 10_37 Mpx, budget 32 MiB, device cap 4_19 Mpx.
        val target = policy(width = 1080, height = 2400, maxHeap = 512 * mib, lowRam = true).target()
        target shouldBe IntSize(1373, 3052)
        (target.pixels() <= 4_194_304L) shouldBe true
    }

    @Test
    fun `the four gigabyte device gets the full zoom headroom`() {
        // The device cap of 12_58 Mpx is above the wanted 10_37 Mpx, so nothing shrinks.
        policy(width = 1080, height = 2400, maxHeap = 1024 * mib, lowRam = false).target() shouldBe
            IntSize(2160, 4800)
    }

    @Test
    fun `the tablet in landscape is shrunk onto its device cap`() {
        // Viewport 2560x1600, wanted 5120x3200 = 16_38 Mpx, device cap 12_58 Mpx.
        val target = policy(width = 2560, height = 1600, maxHeap = 1024 * mib, lowRam = false).target()
        target shouldBe IntSize(4486, 2804)
        (target.pixels() <= 12_582_912L) shouldBe true
    }

    // ---- The clamp order ----

    @Test
    fun `a preference below the device cap wins`() {
        val target = policy(
            width = 1080,
            height = 2400,
            maxHeap = 512 * mib,
            lowRam = true,
            maxPixels = 2_000_000,
        ).target()
        target shouldBe IntSize(948, 2108)
        (target.pixels() <= 2_000_000L) shouldBe true
    }

    @Test
    fun `a preference above the device cap does not raise it`() {
        val raised = policy(
            width = 1080,
            height = 2400,
            maxHeap = 512 * mib,
            lowRam = true,
            maxPixels = 64_000_000,
        ).target()
        val default = policy(width = 1080, height = 2400, maxHeap = 512 * mib, lowRam = true).target()
        raised shouldBe default
    }

    @Test
    fun `the heap quarter binds before the low RAM constant on a very small heap`() {
        // 64 MiB heap: a quarter is 16 MiB, which is below the 32 MiB low-RAM constant.
        val target = policy(width = 1080, height = 2400, maxHeap = 64 * mib, lowRam = true).target()
        (target.pixels() <= 16 * mib / 2 / 4) shouldBe true
    }

    // ---- The boundary ----

    @Test
    fun `a wanted size exactly on the cap is returned unshrunk`() {
        // Device cap is 4_194_304 px; a 1024x1024 viewport wants 2048x2048 = 4_194_304 px.
        policy(width = 1024, height = 1024, maxHeap = 512 * mib, lowRam = true).target() shouldBe
            IntSize(2048, 2048)
    }

    @Test
    fun `one pixel past the cap shrinks`() {
        val target = policy(width = 1025, height = 1024, maxHeap = 512 * mib, lowRam = true).target()
        (target.width < 2050) shouldBe true
        (target.pixels() <= 4_194_304L) shouldBe true
    }

    @Test
    fun `a tiny viewport never returns a zero dimension`() {
        val target = policy(width = 1, height = 1, maxHeap = 512 * mib, lowRam = true).target()
        target shouldBe IntSize(2, 2)
    }

    // ---- The RGB_565 rule ----

    @Test
    fun `low RAM frame buffers follow the device, not the preference`() {
        policy(1080, 2400, 512 * mib, lowRam = true).lowRamFrames shouldBe true
        policy(1080, 2400, 1024 * mib, lowRam = false).lowRamFrames shouldBe false
    }

    private fun IntSize.pixels(): Long = width.toLong() * height

    private fun policy(
        width: Int,
        height: Int,
        maxHeap: Long,
        lowRam: Boolean,
        maxPixels: Int? = null,
    ): AnimatedImagePolicy {
        val store = InMemoryPreferenceStore(
            maxPixels?.let {
                sequenceOf(InMemoryPreferenceStore.InMemoryPreference(ANIMATED_MAX_PIXELS_KEY, it, it))
            } ?: sequenceOf(),
        )
        return AnimatedImagePolicy(
            memory = FakeMemory(maxHeap, lowRam),
            display = FakeDisplay(width, height, maxOf(width, height)),
            readerPreferences = ReaderPreferences(store),
        )
    }

    private class FakeMemory(
        override val maxHeapBytes: Long,
        override val isLowRamDevice: Boolean,
    ) : MemoryInfoProvider {
        override val usedHeapBytes: Long get() = 0
    }

    private class FakeDisplay(
        override val widthPx: Int,
        override val heightPx: Int,
        override val maxDimensionPx: Int,
    ) : DisplayInfoProvider

    private companion object {
        const val ANIMATED_MAX_PIXELS_KEY = "reader_animated_max_pixels"
    }
}

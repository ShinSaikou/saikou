package com.shinsaikou.reader.state

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PreloadPolicyTest {

    // ---- The three worked rows of contract 07 section 3_5 ----

    @Test
    fun `the two gigabyte device gets three pages ahead and six fetched`() {
        // 1080x2400 costs 10_37 MB a page; the low RAM cap of 32 MiB binds before the heap share.
        val window = policy(width = 1080, height = 2400, maxHeap = 512 * MIB, lowRam = true).compute()
        window shouldBe PreloadPolicy.Window(decodeAhead = 3, decodeBehind = 1, fetchAhead = 6, chapterPreloadAt = 6)
    }

    @Test
    fun `the four gigabyte device is clamped at four pages ahead and eight fetched`() {
        val window = policy(width = 1080, height = 2400, maxHeap = 512 * MIB, lowRam = false).compute()
        window shouldBe PreloadPolicy.Window(decodeAhead = 4, decodeBehind = 1, fetchAhead = 8, chapterPreloadAt = 8)
    }

    @Test
    fun `the tablet in landscape still gets four pages ahead`() {
        // 2560x1600 costs 16_38 MB a page, and 96 MiB still buys six, so the clamp is what binds.
        val window = policy(width = 2560, height = 1600, maxHeap = 512 * MIB, lowRam = false).compute()
        window.decodeAhead shouldBe 4
        window.fetchAhead shouldBe 8
    }

    // ---- The clamps at both ends ----

    @Test
    fun `a page larger than the whole budget still gets one ahead and two fetched`() {
        val window = policy(width = 4000, height = 4000, maxHeap = 64 * MIB, lowRam = true).compute()
        window shouldBe PreloadPolicy.Window(decodeAhead = 1, decodeBehind = 1, fetchAhead = 2, chapterPreloadAt = 2)
    }

    @Test
    fun `a tiny display cannot raise the window past the clamp`() {
        val window = policy(width = 320, height = 480, maxHeap = 4096 * MIB, lowRam = false).compute()
        window.decodeAhead shouldBe 4
        window.fetchAhead shouldBe 8
    }

    @Test
    fun `a display that reports nothing falls back to the default window`() {
        policy(width = 0, height = 0, maxHeap = 512 * MIB, lowRam = false).compute() shouldBe PreloadPolicy.DEFAULT
    }

    // ---- The two constants the reducer uses ----

    @Test
    fun `the degraded window is strictly smaller than the default in every dimension`() {
        val default = PreloadPolicy.DEFAULT
        val degraded = PreloadPolicy.DEGRADED
        (degraded.decodeAhead < default.decodeAhead) shouldBe true
        (degraded.decodeBehind < default.decodeBehind) shouldBe true
        (degraded.fetchAhead < default.fetchAhead) shouldBe true
        (degraded.chapterPreloadAt < default.chapterPreloadAt) shouldBe true
    }

    private fun policy(width: Int, height: Int, maxHeap: Long, lowRam: Boolean) = PreloadPolicy(
        memory = FakeMemory(maxHeap, lowRam),
        display = FakeDisplay(width, height),
    )
}

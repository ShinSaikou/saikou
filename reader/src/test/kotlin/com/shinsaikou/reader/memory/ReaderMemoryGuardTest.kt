package com.shinsaikou.reader.memory

import android.content.ComponentCallbacks2
import android.content.Context
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

@Suppress("DEPRECATION") // The five foreground trim levels are exactly what this class exists for.
class ReaderMemoryGuardTest {

    private val levels = mutableListOf<Int>()
    private var cacheDrops = 0
    private var now = 10_000L

    // ---- The level table ----

    @Test
    fun `every level is reported, whatever it is`() {
        val guard = guard()
        val table = listOf(
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
        )
        table.forEach { guard.onTrimMemory(it) }
        levels shouldBe table
    }

    @Test
    fun `the image memory cache is dropped from running critical upward and not below it`() {
        val guard = guard()

        guard.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)
        guard.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        cacheDrops shouldBe 0

        guard.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        cacheDrops shouldBe 1

        guard.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        guard.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        guard.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        cacheDrops shouldBe 4
    }

    @Test
    fun `onLowMemory maps to complete`() {
        guard().onLowMemory()
        levels shouldBe listOf(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        cacheDrops shouldBe 1
    }

    // ---- The synthesized foreground watermark ----

    @Test
    fun `a quiet heap synthesizes nothing`() {
        guard(used = 0.74).sample()
        levels shouldBe emptyList()
    }

    @Test
    fun `seventy five percent of the heap synthesizes running low`() {
        guard(used = 0.75).sample()
        levels shouldBe listOf(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        cacheDrops shouldBe 0
    }

    @Test
    fun `eighty four percent is still only running low`() {
        guard(used = 0.84).sample()
        levels shouldBe listOf(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
    }

    @Test
    fun `eighty five percent of the heap synthesizes running critical`() {
        guard(used = 0.85).sample()
        levels shouldBe listOf(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        cacheDrops shouldBe 1
    }

    // ---- The rate limit ----

    @Test
    fun `a burst of samples inside the interval fires once`() {
        val guard = guard(used = 0.90)
        repeat(20) { guard.sample() }
        levels.size shouldBe 1
    }

    @Test
    fun `a sample past the interval fires again`() {
        val guard = guard(used = 0.90)
        guard.sample()
        now += ReaderMemoryGuard.SAMPLE_INTERVAL_MS - 1
        guard.sample()
        levels.size shouldBe 1

        now += 1
        guard.sample()
        levels.size shouldBe 2
    }

    @Test
    fun `the first sample of a session is never rate limited away`() {
        now = 0
        guard(used = 0.90).sample()
        levels.size shouldBe 1
    }

    private fun guard(used: Double = 0.0) = ReaderMemoryGuard(
        context = mockk<Context>(relaxed = true),
        memory = FakeMemory(used),
        onTrimLevel = { levels += it },
        clock = { now },
        dropImageMemoryCache = { cacheDrops++ },
    )

    private class FakeMemory(private val usedFraction: Double) : MemoryInfoProvider {
        override val maxHeapBytes: Long = 1_000_000_000L
        override val isLowRamDevice: Boolean = true
        override val usedHeapBytes: Long get() = (maxHeapBytes * usedFraction).toLong()
    }
}

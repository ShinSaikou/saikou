package com.shinsaikou.di

import com.shinsaikou.BuildConfig
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * The graph's wall clock (phase 3 unit H, Q12): the debug build adds the test offset, read on every
 * call; `:app` has unit tests for the debug variant only, so the release build's path is pinned
 * through `PlatformBindings.clockFor(debug = false, ...)`, the call the provider makes in a release
 * build, on `GoogleBindingsTest`'s pattern: it is `Clock.System` and never reads the offset.
 */
class ClockBindingsTest {

    private val offset = ClockOffset()

    @Test
    fun `the debug build's clock moves with the offset, read on every call`() {
        BuildConfig.DEBUG shouldBe true
        val clock = PlatformBindings.provideClock(lazyOf(offset))

        (clock.now() - Clock.System.now()).absoluteValue shouldBeLessThan 1.minutes

        // Set after the clock was handed out, as a device test sets it after the graph built its users.
        offset.days = 14
        val ahead = clock.now() - Clock.System.now()
        ahead shouldBeGreaterThan 14.days - 1.minutes
        ahead shouldBeLessThan 14.days + 1.minutes

        offset.days = 0
        (clock.now() - Clock.System.now()).absoluteValue shouldBeLessThan 1.minutes
    }

    @Test
    fun `the release build's clock is the system clock without reading the offset`() {
        var read = 0
        val lazyOffset = lazy {
            read++
            offset.apply { days = 14 }
        }

        PlatformBindings.clockFor(debug = false) { lazyOffset.value.days } shouldBeSameInstanceAs Clock.System
        val release = PlatformBindings.clockFor(debug = false) { throw AssertionError("a release build read the clock offset") }
        release shouldBeSameInstanceAs Clock.System
        release.now()
        read shouldBe 0
        lazyOffset.isInitialized() shouldBe false

        val debug = PlatformBindings.clockFor(debug = true) { lazyOffset.value.days }
        read shouldBe 0
        (debug.now() - Clock.System.now()) shouldBeGreaterThan 13.days
        read shouldBe 1
    }
}

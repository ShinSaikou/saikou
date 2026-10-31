package com.shinsaikou.core.common.crash

import com.shinsaikou.core.common.crash.CrashScreenLauncher.Companion.WINDOW_MILLIS
import com.shinsaikou.core.common.crash.CrashScreenLauncher.Companion.shouldStart
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The two rules that keep the crash screen out of a loop, at their edges. */
class CrashScreenLauncherTest {

    private val now = 50_000_000L

    @Test
    fun `the first crash starts the screen`() {
        shouldStart(inCrashProcess = false, lastStart = null, now = now) shouldBe true
    }

    @Test
    fun `the crash screen's own process never starts it`() {
        shouldStart(inCrashProcess = true, lastStart = null, now = now) shouldBe false
        shouldStart(inCrashProcess = true, lastStart = now - WINDOW_MILLIS * 10, now = now) shouldBe false
    }

    @Test
    fun `a start inside the window holds the next one back, up to its last millisecond`() {
        shouldStart(inCrashProcess = false, lastStart = now, now = now) shouldBe false
        shouldStart(inCrashProcess = false, lastStart = now - (WINDOW_MILLIS - 1), now = now) shouldBe false
        shouldStart(inCrashProcess = false, lastStart = now - WINDOW_MILLIS, now = now) shouldBe true
    }

    @Test
    fun `a start stamped in the future, after the clock was set back, holds nothing back`() {
        shouldStart(inCrashProcess = false, lastStart = now + 1, now = now) shouldBe true
    }
}

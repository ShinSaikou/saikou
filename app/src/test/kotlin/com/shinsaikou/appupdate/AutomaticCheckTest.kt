package com.shinsaikou.appupdate

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** The foreground arrival's check: a release build, the setting on, and a day since the last one. */
class AutomaticCheckTest {

    private val now = 1_800_000_000_000L
    private fun ago(millis: Long) = now - millis

    @Test
    fun `a first check is due`() {
        AutomaticCheck.due(buildChecks = true, enabled = true, lastCheckAt = 0L, now = now) shouldBe true
    }

    @Test
    fun `a check less than a day after the last one is not due, a day or more after is`() {
        AutomaticCheck.due(true, true, lastCheckAt = ago(23.hours.inWholeMilliseconds), now = now) shouldBe false
        AutomaticCheck.due(true, true, lastCheckAt = ago((24.hours - 1.minutes).inWholeMilliseconds), now = now) shouldBe false
        AutomaticCheck.due(true, true, lastCheckAt = ago(24.hours.inWholeMilliseconds), now = now) shouldBe true
        AutomaticCheck.due(true, true, lastCheckAt = ago(72.hours.inWholeMilliseconds), now = now) shouldBe true
    }

    @Test
    fun `the setting off stops it whatever the time`() {
        AutomaticCheck.due(buildChecks = true, enabled = false, lastCheckAt = 0L, now = now) shouldBe false
        AutomaticCheck.due(buildChecks = true, enabled = false, lastCheckAt = ago(72.hours.inWholeMilliseconds), now = now) shouldBe false
    }

    @Test
    fun `a build that does not check by itself never does`() {
        AutomaticCheck.due(buildChecks = false, enabled = true, lastCheckAt = 0L, now = now) shouldBe false
    }

    @Test
    fun `a last check in the future means the clock went back, and checks`() {
        AutomaticCheck.due(true, true, lastCheckAt = now + 5.hours.inWholeMilliseconds, now = now) shouldBe true
    }
}

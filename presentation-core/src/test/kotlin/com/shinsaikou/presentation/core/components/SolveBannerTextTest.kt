package com.shinsaikou.presentation.core.components

import com.shinsaikou.core.common.network.CloudflareSolveCoordinator.InFlightSolve
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The banner's wording rules, off the device. The composable only formats what this returns, so
 * which host is named, which second is shown and which budget it is shown against are all decided
 * here.
 */
class SolveBannerTextTest {

    private fun solve(host: String, startedAt: Long, waiters: Int = 0, budgetMs: Long = 30_000L) =
        InFlightSolve(host = host, startedAtElapsedMs = startedAt, waiters = waiters, budgetMs = budgetMs)

    @Test
    fun `nothing in flight says nothing`() {
        solveBannerText(emptyMap(), nowElapsedMs = 50_000L).shouldBeNull()
    }

    @Test
    fun `one host is named with its elapsed seconds and its own budget`() {
        val inFlight = mapOf("one.invalid" to solve("one.invalid", startedAt = 10_000L, budgetMs = 20_000L))

        solveBannerText(inFlight, nowElapsedMs = 17_900L) shouldBe SolveBannerText(
            host = "one.invalid",
            elapsedSeconds = 7,
            budgetSeconds = 20,
            waiters = 0,
            moreHosts = 0,
        )
    }

    @Test
    fun `several hosts name the oldest and count the rest`() {
        val inFlight = mapOf(
            "late.invalid" to solve("late.invalid", startedAt = 12_000L),
            "early.invalid" to solve("early.invalid", startedAt = 4_000L),
            "middle.invalid" to solve("middle.invalid", startedAt = 8_000L),
        )

        val text = solveBannerText(inFlight, nowElapsedMs = 14_000L)!!

        text.host shouldBe "early.invalid"
        text.elapsedSeconds shouldBe 10
        text.moreHosts shouldBe 2
    }

    @Test
    fun `two hosts that started together always name the same one`() {
        val inFlight = mapOf(
            "b.invalid" to solve("b.invalid", startedAt = 5_000L),
            "a.invalid" to solve("a.invalid", startedAt = 5_000L),
        )

        solveBannerText(inFlight, nowElapsedMs = 6_000L)!!.host shouldBe "a.invalid"
    }

    @Test
    fun `the waiters counted are the ones queued behind the named solve`() {
        val inFlight = mapOf(
            "early.invalid" to solve("early.invalid", startedAt = 1_000L, waiters = 1),
            "late.invalid" to solve("late.invalid", startedAt = 2_000L, waiters = 5),
        )

        solveBannerText(inFlight, nowElapsedMs = 3_000L)!!.waiters shouldBe 1
        solveBannerText(
            mapOf("one.invalid" to solve("one.invalid", startedAt = 1_000L, waiters = 3)),
            nowElapsedMs = 3_000L,
        )!!.waiters shouldBe 3
    }

    @Test
    fun `a second solve on the same host restarts the count from its own start`() {
        val first = mapOf("one.invalid" to solve("one.invalid", startedAt = 1_000L))
        solveBannerText(first, nowElapsedMs = 29_000L)!!.elapsedSeconds shouldBe 28

        // The first solve failed and a waiter took the lock and published its own attempt.
        val second = mapOf("one.invalid" to solve("one.invalid", startedAt = 31_500L))
        solveBannerText(second, nowElapsedMs = 33_000L)!!.elapsedSeconds shouldBe 1
    }

    @Test
    fun `the elapsed figure never passes the budget or goes below zero`() {
        val inFlight = mapOf("one.invalid" to solve("one.invalid", startedAt = 10_000L, budgetMs = 30_000L))

        solveBannerText(inFlight, nowElapsedMs = 45_000L)!!.elapsedSeconds shouldBe 30
        solveBannerText(inFlight, nowElapsedMs = 9_000L)!!.elapsedSeconds shouldBe 0
    }
}

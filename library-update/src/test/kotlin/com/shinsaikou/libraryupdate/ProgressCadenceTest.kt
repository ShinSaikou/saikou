package com.shinsaikou.libraryupdate

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * The library update's progress cadence (phase 5 unit SC5), driven as the pass drives it: one
 * report per entry started, `done` the entries finished by then, and the clock a [TestTimeSource].
 */
class ProgressCadenceTest {

    private val clock = TestTimeSource()

    /**
     * Runs [total] entries through a fresh cadence, the clock moving [gap] before each start after
     * the first, and returns the 1-based starts whose report went out.
     */
    private fun sentStarts(total: Int, gap: Duration, done: (started: Int) -> Int): List<Int> {
        val cadence = ProgressCadence(clock)
        return (1..total).filter { started ->
            if (started > 1) clock += gap
            cadence.shouldSend(FlatLibraryUpdate.Progress(done(started), total, current = null))
        }
    }

    @Test
    fun `the first and the last report go out whatever the clock and the count say`() {
        // Every entry starts at the same instant and none has finished by the last start.
        sentStarts(total = 10, gap = Duration.ZERO) { 0 } shouldContainExactly listOf(1, 10)
    }

    @Test
    fun `40,000 entries started in one second send about one report per percent`() {
        // 32 in flight, as GLOBAL_CAP allows: an entry finishes for each one started after the first 32.
        val sent = sentStarts(total = 40_000, gap = 25.microseconds) { started -> (started - 1 - 32).coerceAtLeast(0) }

        // The first, one for every 400 finished, and the last. A second passes in all, so at most two
        // of the time-driven kind could join them.
        sent.size shouldBeLessThanOrEqual 1 + ProgressCadence.STEPS + 2 + 1
        sent.size shouldBeGreaterThanOrEqual ProgressCadence.STEPS
        // Exactly: the first, done at 400, 800 and so on to 39,600 (99), and the last, done 39,967.
        // 400 starts take 10 ms here, so the clock never sends one.
        sent.size shouldBe 101
        sent.first() shouldBe 1
        sent.last() shouldBe 40_000
    }

    @Test
    fun `a slow run of one entry a second sends every report`() {
        val total = 600
        sentStarts(total = total, gap = 1.seconds) { started -> (started - 1 - 32).coerceAtLeast(0) } shouldBe
            (1..total).toList()
    }

    @Test
    fun `a run of one entry sends its one report`() {
        sentStarts(total = 1, gap = Duration.ZERO) { 0 } shouldContainExactly listOf(1)
    }

    @Test
    fun `while nothing finishes the clock alone sends a report every half second`() {
        // An entry starts every 100 ms and none finishes: the first, then every fifth start, then the last.
        sentStarts(total = 23, gap = 100.milliseconds) { 0 } shouldContainExactly listOf(1, 6, 11, 16, 21, 23)
    }

    @Test
    fun `under a hundred entries each one finished sends`() {
        // The step is at least one entry: a 50-entry run reports each time done moves.
        sentStarts(total = 50, gap = Duration.ZERO) { started -> started - 1 } shouldBe (1..50).toList()
    }
}

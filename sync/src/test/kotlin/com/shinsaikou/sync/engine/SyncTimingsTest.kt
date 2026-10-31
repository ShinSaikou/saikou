package com.shinsaikou.sync.engine

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The run's timing line (phase 5 unit SC3), which `ScaleRowsTest` parses into a row's split: every
 * step once as `key=<ms>`, the export's parts last, and a step's time added from every call, a
 * failing one included.
 */
class SyncTimingsTest {

    @Test
    fun `the line names every step once, in ms, and the export's parts last`() {
        val timings = SyncTimings()
        timings.add(SyncTimings.Step.CREATE, 2_500_000)
        timings.add(SyncTimings.Step.CREATE, 1_600_000)
        timings.exportParts = 3

        val pairs = timings.line().split(' ').map { it.substringBefore('=') to it.substringAfter('=').toLong() }

        pairs.map { it.first } shouldBe SyncTimings.Step.entries.map { it.key } + "exportParts"
        pairs.toMap()["create"] shouldBe 4
        pairs.toMap()["exportParts"] shouldBe 3
        pairs.toMap()["readBack"] shouldBe 0
    }

    @Test
    fun `a timed block that throws still adds its time`() {
        val timings = SyncTimings()

        runCatching { timings.time(SyncTimings.Step.PULL_APPLY) { Thread.sleep(5); error("failed") } }

        val applied = timings.line().split(' ').single { it.startsWith("pullApply=") }.substringAfter('=').toLong()
        (applied >= 5) shouldBe true
    }
}

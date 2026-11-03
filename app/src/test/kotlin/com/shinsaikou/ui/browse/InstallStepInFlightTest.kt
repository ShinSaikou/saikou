package com.shinsaikou.ui.browse

import eu.kanade.tachiyomi.extension.model.InstallStep
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * When an Extensions row takes no tap or long press: while its install is in flight, so a second
 * tap cannot start a second install of the same package. A failed one has ended, and its row retries.
 */
class InstallStepInFlightTest {

    @Test
    fun `pending, downloading and installing are in flight`() {
        listOf(InstallStep.Pending, InstallStep.Downloading, InstallStep.Installing)
            .map { it.isInFlight() } shouldBe listOf(true, true, true)
    }

    @Test
    fun `no step, idle, installed and error are not`() {
        listOf(null, InstallStep.Idle, InstallStep.Installed, InstallStep.Error)
            .map { it.isInFlight() } shouldBe listOf(false, false, false, false)
    }
}

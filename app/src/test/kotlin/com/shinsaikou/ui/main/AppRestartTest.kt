package com.shinsaikou.ui.main

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The intent the Restart action starts before the process exits (F13). It has to be the platform's
 * restart task for MainActivity, whose `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK` drop the
 * old task, and it has to keep the action of the intent [MainIntents.newIntent] built, or the new
 * process opens on the library instead of the section the owner was in.
 *
 * `Intent` is mocked, as in [MainIntentsTest]: the JVM's android.jar has no body for it, so the
 * static [Intent.makeRestartActivityTask] is stubbed and what is checked is what is asked of it.
 */
class AppRestartTest {

    private val component = mockk<ComponentName>()
    private val restartTask = mockk<Intent>(relaxed = true)

    @BeforeEach
    fun stubRestartTask() {
        mockkStatic(Intent::class)
        every { Intent.makeRestartActivityTask(component) } returns restartTask
    }

    @AfterEach
    fun unstub() = unmockkStatic(Intent::class)

    @Test
    fun `the restart intent is the platform's restart task for the target's activity, with its flags untouched`() {
        AppRestart.restartIntentOf(target(extras = null)) shouldBeSameInstanceAs restartTask

        verify(exactly = 1) { Intent.makeRestartActivityTask(component) }
        verify(exactly = 0) { restartTask.addFlags(any()) }
        verify(exactly = 0) { restartTask.setFlags(any()) }
    }

    @Test
    fun `the restart intent carries the target's action, so the new process opens on the same destination`() {
        AppRestart.restartIntentOf(target(extras = null))

        verify(exactly = 1) { restartTask.setAction(MainIntents.ACTION_OPEN_ADVANCED_SETTINGS) }
    }

    @Test
    fun `the restart intent for the experimental settings carries that action, so the new process opens there`() {
        AppRestart.restartIntentOf(target(extras = null, action = MainIntents.ACTION_OPEN_EXPERIMENTAL_SETTINGS))

        verify(exactly = 1) { restartTask.setAction(MainIntents.ACTION_OPEN_EXPERIMENTAL_SETTINGS) }
        verify(exactly = 0) { restartTask.setAction(MainIntents.ACTION_OPEN_ADVANCED_SETTINGS) }
    }

    @Test
    fun `the restart intent carries the target's extras`() {
        val extras = mockk<Bundle>()

        AppRestart.restartIntentOf(target(extras = extras))

        verify(exactly = 1) { restartTask.putExtras(extras) }
    }

    @Test
    fun `a target without extras puts none on the restart intent`() {
        AppRestart.restartIntentOf(target(extras = null))

        verify(exactly = 0) { restartTask.putExtras(any<Bundle>()) }
        verify(exactly = 0) { restartTask.putExtras(any<Intent>()) }
    }

    private fun target(extras: Bundle?, action: String = MainIntents.ACTION_OPEN_ADVANCED_SETTINGS): Intent = mockk {
        every { this@mockk.component } returns this@AppRestartTest.component
        every { this@mockk.action } returns action
        every { this@mockk.extras } returns extras
    }
}

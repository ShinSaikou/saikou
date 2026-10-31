package com.shinsaikou.util

import androidx.preference.PreferenceManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue

/**
 * The end of a stage a host runs in an instrumentation of its own, when a later stage, in a new
 * process, reads a preference this one set (R140 item 9's rule; phase 5 unit TG4's audit).
 *
 * The app's preference store writes with `apply()` (`AndroidPreference`), which reaches the file up
 * to 100 ms later on API 26 and up, and the platform kills the instrumentation's process as soon as
 * the stage returns, so a write made just before the return can be lost and the next stage reads the
 * value from before it. A `commit()` of the default preferences, even an empty one, waits behind
 * every `apply()` queued before it and returns once the file holds them all. `SyncPairTest`'s steps
 * were the first to end this way (phase 5 unit TG3).
 *
 * Infrastructure rather than a test, like [GateFragments].
 */
object StagePreferences {

    /** Every preference set so far is on disk when this returns; fails if the file could not be written. */
    fun commit() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val written = PreferenceManager.getDefaultSharedPreferences(context).edit().commit()
        assertTrue("the preferences could not be written", written)
    }
}

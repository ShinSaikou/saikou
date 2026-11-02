package com.shinsaikou.util

import android.app.Instrumentation
import android.os.Bundle
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.test.runner.AndroidJUnitRunner

/**
 * The app's instrumentation: [AndroidJUnitRunner], and one `commit()` of the default preferences as
 * the run ends, before the process goes.
 *
 * Tests put back the preferences they pinned through the app's store, which writes with `apply()`
 * (`AndroidPreference`), and the platform kills the instrumentation's process as soon as the run
 * returns, so the last test's restores could be lost: a device kept the reader's tap zones off, a
 * white page and a dark app theme after a run, and the next run, or the owner, started from those.
 * [StagePreferences] is the same commit at the end of a stage. The tools find this class through
 * `pm list instrumentation`.
 *
 * A commit that fails is reported rather than failing the run: every test has reported its result
 * by now, and what is at stake is the next run's starting point, so the failure goes to the log and
 * to the run's printed result, where whoever reads it sees the device may be left as the tests had it.
 */
class SaikouTestRunner : AndroidJUnitRunner() {
    override fun finish(resultCode: Int, results: Bundle?) {
        val written = runCatching { PreferenceManager.getDefaultSharedPreferences(targetContext).edit().commit() }
        if (written.getOrDefault(false).not()) {
            val message = "$TAG: the preferences the tests put back were not written " +
                "(${written.exceptionOrNull() ?: "commit() returned false"}); this device may keep what the tests set."
            Log.e(TAG, message)
            results?.putString(
                Instrumentation.REPORT_KEY_STREAMRESULT,
                results.getString(Instrumentation.REPORT_KEY_STREAMRESULT).orEmpty() + "\n" + message + "\n",
            )
        }
        super.finish(resultCode, results)
    }

    private companion object {
        const val TAG = "SaikouTestRunner"
    }
}

package com.shinsaikou.security

import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.shinsaikou.SaikouApp
import com.shinsaikou.core.common.preferences.SecurityPreferences.SecureScreenMode
import com.shinsaikou.util.StagePreferences
import eu.kanade.tachiyomi.ui.security.UnlockActivity
import eu.kanade.tachiyomi.util.system.AuthenticatorUtil.isAuthenticationSupported
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The window half of the app lock on a real window (the 2026-10-30 audit): an activity that resumes
 * while the lock is owed is covered and secure before its first frame, even under Secure screen
 * Never, and the cover goes once the prompt is passed. Before, the activity drew the library under
 * the prompt and the recents thumbnail kept it.
 *
 * The lock only engages on a device that can authenticate, so on one with no screen lock the test
 * returns with a `Skipped:` line. The prompt it raises is never answered: the test unlocks through
 * [AppLock.unlock], as `UnlockActivity` does, and closes the prompt's activity afterwards.
 */
@RunWith(AndroidJUnit4::class)
class AppLockCoverTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val graph get() = (context.applicationContext as SaikouApp).graph

    @Test
    fun anActivityResumedWhileTheLockIsOwedIsCoveredAndSecureUntilUnlocked() {
        if (!context.isAuthenticationSupported()) {
            Log.i(LOG_TAG, "Skipped: the device has no screen lock, so the app lock never engages.")
            return
        }
        val security = graph.securityPreferences
        val incognito = graph.basePreferences.incognitoMode
        val lockWas = security.useAuthenticator.get()
        val afterWas = security.lockAppAfter.get()
        val secureWas = security.secureScreen.get()
        val incognitoWas = incognito.get()
        try {
            security.useAuthenticator.set(true)
            security.lockAppAfter.set(0)
            security.secureScreen.set(SecureScreenMode.NEVER)
            incognito.set(false)
            // What the process lifecycle runs as the app comes to the front; Lock when idle Always
            // owes the lock at every start.
            instrumentation.runOnMainSync { graph.appLock.onApplicationStart() }

            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertTrue("the window under the prompt is open to screenshots", activity.isSecure())
                    val cover = checkNotNull(activity.cover()) { "the window under the prompt is not covered" }
                    // Above the content's own branch of the decor. The decor may add its bar
                    // background views after it; they are the bars' colours and show nothing.
                    val decor = activity.window.decorView as ViewGroup
                    val content = activity.findViewById<View>(android.R.id.content)
                    val contentBranch = (0 until decor.childCount).map(decor::getChildAt)
                        .single { branch -> generateSequence(content) { it.parent as? View }.any { it === branch } }
                    assertTrue(
                        "the cover is drawn under the content",
                        decor.indexOfChild(cover) > decor.indexOfChild(contentBranch),
                    )
                    assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, cover.layoutParams.width)
                    assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, cover.layoutParams.height)
                }

                instrumentation.runOnMainSync { graph.appLock.unlock() }

                // Below Android 13 a window that is not in front stays secure while the lock is
                // on, for the recents thumbnail; this one is behind the prompt.
                val secureBehindThePrompt = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                awaitTrue("the cover outlived the unlock, or the window kept the wrong secure flag") {
                    var settled = false
                    scenario.onActivity { settled = it.cover() == null && it.isSecure() == secureBehindThePrompt }
                    settled
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                graph.appLock.unlock()
                ActivityLifecycleMonitorRegistry.getInstance().let { monitor ->
                    Stage.values().flatMap { monitor.getActivitiesInStage(it) }
                        .filterIsInstance<UnlockActivity>()
                        .forEach { it.finish() }
                }
            }
            security.useAuthenticator.set(lockWas)
            security.lockAppAfter.set(afterWas)
            security.secureScreen.set(secureWas)
            incognito.set(incognitoWas)
            // On disk before the process ends: a lock left on would cover every later test's
            // activity behind a prompt nobody answers.
            StagePreferences.commit()
        }
    }

    private fun ComponentActivity.isSecure() =
        window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    private fun ComponentActivity.cover(): View? = window.decorView.findViewWithTag(APP_LOCK_COVER_TAG)

    private fun awaitTrue(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MILLIS
        while (!condition()) {
            if (SystemClock.uptimeMillis() > deadline) throw AssertionError(message)
            SystemClock.sleep(50)
        }
    }

    private companion object {
        const val LOG_TAG = "AppLockCoverTest"
        const val TIMEOUT_MILLIS = 5_000L
    }
}

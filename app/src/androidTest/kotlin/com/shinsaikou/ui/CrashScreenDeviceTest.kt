package com.shinsaikou.ui

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shinsaikou.SaikouApp
import com.shinsaikou.core.common.crash.CrashScreenLauncher
import com.shinsaikou.i18n.R
import com.shinsaikou.ui.crash.CrashActivity
import com.shinsaikou.ui.crash.CrashLogProvider
import com.shinsaikou.ui.crash.CrashScreenLook
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The crash screen without a crash: one crash file written by the app's own `CrashLog.write`, the
 * uncaught exception handler's call, and the screen started with the intent the handler starts it
 * with (`CrashActivity.intent`). Nothing in the product exists for the test (ruling R39).
 *
 * The screen runs in the `:crash` process, which the Compose rule cannot reach from this one, so it
 * is read and driven through the instrumentation's `UiAutomation`, as `CrashLogShareTest` reads the
 * chooser and `InPlaceUpgradeCheckTest` walks the accessibility tree.
 */
@RunWith(AndroidJUnit4::class)
class CrashScreenDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val graph get() = (context.applicationContext as SaikouApp).graph
    private val crashProcess = "${context.packageName}:crash"

    private var seeded: File? = null

    @Before
    fun seedACrash() {
        seeded = graph.crashLog.write(Thread.currentThread(), IllegalStateException(FIXTURE_MESSAGE))
    }

    /** Backs the screen away first, so no class after this one opens under it. */
    @After
    fun cleanUp() {
        if (activeWindowShows(FIXTURE_MESSAGE)) {
            instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            awaitTrue(SCREEN_TIMEOUT_MILLIS, "Back left the crash screen up") { !activeWindowShows(FIXTURE_MESSAGE) }
        }
        seeded?.delete()
        CrashScreenLauncher.clear(context)
        val activityManager = context.getSystemService(ActivityManager::class.java)
        activityManager.runningAppProcesses
            ?.firstOrNull { it.processName == crashProcess }
            ?.let { Process.killProcess(it.pid) }
    }

    /**
     * The screen shows the log from its own process, settles the launch banner as reading a log on
     * the crash log screen does (R80), and Close takes it away and clears the relaunch guard.
     */
    @Test
    fun theScreenShowsTheLogFromItsOwnProcessAndCloseLeavesIt() {
        val file = checkNotNull(seeded) { "CrashLog.write left no file" }
        assertTrue("the seeded crash raises no banner to begin with", graph.crashLog.hasUnexportedCrash())

        startTheScreen(file)
        awaitNode(SCREEN_TIMEOUT_MILLIS, "the crash screen never showed the seeded log") {
            it.text?.toString()?.contains(FIXTURE_MESSAGE) == true
        }
        val processes = context.getSystemService(ActivityManager::class.java).runningAppProcesses.orEmpty()
        assertTrue(
            "no $crashProcess process; running: ${processes.map { it.processName }}",
            processes.any { it.processName == crashProcess },
        )
        awaitTrue(SCREEN_TIMEOUT_MILLIS, "showing the log did not settle the banner") {
            !graph.crashLog.hasUnexportedCrash()
        }

        clickable(awaitNode(SCREEN_TIMEOUT_MILLIS, "no Close on the crash screen") { it.hasText(R.string.action_close) })
            .performAction(AccessibilityNodeInfo.ACTION_CLICK)
        awaitTrue(SCREEN_TIMEOUT_MILLIS, "Close left the crash screen up") {
            !activeWindowShows(FIXTURE_MESSAGE)
        }
        assertFalse("Close left the relaunch guard set", CrashScreenLauncher.stampOf(context).exists())
    }

    /** Share starts the system chooser from the crash screen's process, as the crash log screen's does. */
    @Test
    fun shareOnTheCrashScreenOpensTheSystemChooser() {
        val file = checkNotNull(seeded) { "CrashLog.write left no file" }
        val chooser = context.packageManager
            .resolveActivity(Intent(Intent.ACTION_CHOOSER), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo
        assertNotNull("nothing on this device answers ACTION_CHOOSER", chooser)
        val chooserPackage = chooser!!.packageName

        startTheScreen(file)
        val share = clickable(
            awaitNode(SCREEN_TIMEOUT_MILLIS, "no Share on the crash screen") { it.hasText(R.string.action_share) },
        )

        val windows = CopyOnWriteArrayList<String>()
        val automation = instrumentation.uiAutomation
        automation.setOnAccessibilityEventListener { event ->
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                windows += "${event.packageName}/${event.className}"
            }
        }
        try {
            share.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            awaitTrue(CHOOSER_TIMEOUT_MILLIS, "no chooser window from $chooserPackage came up; windows seen: $windows") {
                windows.any { it.startsWith("$chooserPackage/") }
            }
            awaitTrue(CHOOSER_TIMEOUT_MILLIS, "the chooser came up but never took the screen") {
                activePackage() == chooserPackage
            }
            Log.i(LOG_TAG, "chooser for ${file.name}: ${windows.first { it.startsWith("$chooserPackage/") }}")
        } finally {
            automation.setOnAccessibilityEventListener(null)
            backOutOf(chooserPackage)
        }
    }

    /**
     * The URI Share hands on is served from the crash screen's process, so reading it never starts
     * the main process, which may be the one that dies on every start.
     */
    @Test
    fun theCrashScreensProviderServesTheLogFromTheCrashProcess() {
        val file = checkNotNull(seeded) { "CrashLog.write left no file" }
        val authority = CrashLogProvider.authority(context)
        val provider = context.packageManager.resolveContentProvider(authority, 0)
        assertNotNull("no provider for $authority", provider)
        assertEquals("the crash screen's provider runs in the wrong process", crashProcess, provider!!.processName)

        val uri = FileProvider.getUriForFile(context, authority, file)
        val text = context.contentResolver.openInputStream(uri)!!.use { it.reader().readText() }
        assertTrue("the provider served something other than the crash file", FIXTURE_MESSAGE in text)
    }

    private fun startTheScreen(file: File) {
        context.startActivity(CrashActivity.intent(context, file, CrashScreenLook()))
    }

    private fun AccessibilityNodeInfo.hasText(resourceId: Int): Boolean =
        text?.toString() == context.getString(resourceId)

    /** A Compose button carries its label on the node that clicks, or on a child of it. */
    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current: AccessibilityNodeInfo? = node
        while (current != null && !current.isClickable) current = current.parent
        return checkNotNull(current) { "nothing clickable holds \"${node.text}\"" }
    }

    private fun activeWindowShows(text: String): Boolean {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        if (root.packageName?.toString() != context.packageName) return false
        return find(root) { it.text?.toString()?.contains(text) == true } != null
    }

    /** Polls the active window, when it is this app's, for a node [matches] accepts. */
    private fun awaitNode(
        timeoutMillis: Long,
        message: String,
        matches: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (root != null && root.packageName?.toString() == context.packageName) {
                find(root, matches)?.let { return it }
            }
            SystemClock.sleep(POLL_MILLIS)
        }
        throw AssertionError("$message (after $timeoutMillis ms)")
    }

    private fun find(node: AccessibilityNodeInfo, matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (matches(node)) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            find(child, matches)?.let { return it }
        }
        return null
    }

    /** As `CrashLogShareTest` closes it: back until the chooser no longer holds the screen. */
    private fun backOutOf(chooserPackage: String) {
        val deadline = SystemClock.uptimeMillis() + CHOOSER_TIMEOUT_MILLIS
        while (activePackage() == chooserPackage) {
            if (SystemClock.uptimeMillis() > deadline) throw AssertionError("the chooser did not close")
            instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            Thread.sleep(CLOSE_SETTLE_MILLIS)
        }
    }

    private fun activePackage(): String? = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()

    private fun awaitTrue(timeoutMillis: Long, message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (!condition()) {
            if (SystemClock.uptimeMillis() > deadline) throw AssertionError("$message (after $timeoutMillis ms)")
            Thread.sleep(POLL_MILLIS)
        }
    }

    private companion object {
        const val LOG_TAG = "CrashScreenDeviceTest"
        const val FIXTURE_MESSAGE = "CrashScreenDeviceTest fixture crash"
        const val SCREEN_TIMEOUT_MILLIS = 30_000L
        const val CHOOSER_TIMEOUT_MILLIS = 10_000L
        const val POLL_MILLIS = 50L
        const val CLOSE_SETTLE_MILLIS = 500L
    }
}

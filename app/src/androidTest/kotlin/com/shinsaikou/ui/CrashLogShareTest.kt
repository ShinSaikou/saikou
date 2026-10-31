package com.shinsaikou.ui

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.shinsaikou.SaikouApp
import com.shinsaikou.i18n.R
import com.shinsaikou.navigation.SettingsSection
import com.shinsaikou.ui.crash.crashTimeText
import com.shinsaikou.ui.main.MainActivity
import com.shinsaikou.ui.settings.labelRes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The crash log screen's Share action starts the system chooser (phase 3 unit A, `release.final.md`
 * section 4 defect 1: once the launch banner is gone, nothing else in the app gets a crash file off
 * the device).
 *
 * The crash file is written by the app's own `CrashLog.write`, the uncaught exception handler's
 * call, and the screen is reached the way the owner reaches it: the More tab, Settings, the Advanced
 * section and its Crash logs row. The seeded file is the newest, so its row is the first and its
 * Share button the first with that description; the test checks that before it taps.
 *
 * The chooser is another app's window, which the Compose rule cannot see, so it is read from the
 * accessibility event the platform sends when that window comes up, through the instrumentation's
 * `UiAutomation`, as `DownloadedChapterReaderTest` reads a toast (ruling R77). The package that owns
 * the chooser differs by release (the framework on API 26, a separate resolver app on later ones),
 * so it is resolved from `ACTION_CHOOSER` on the device rather than named. Nothing in the product
 * exists for the test (ruling R39).
 *
 * The same walk checks the row itself: the crash time, formatted from the file's timestamp as the
 * screen formats it, beside the size (Q17).
 *
 * The crash log is marked exported before the activity starts, so the seeded file raises no banner
 * over the screens the test walks (ruling R59, as `MainActivitySmokeTest` does).
 */
@RunWith(AndroidJUnit4::class)
class CrashLogShareTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val graph get() = (context.applicationContext as SaikouApp).graph

    private var seeded: File? = null

    @get:Rule(order = 0)
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(*NOTIFICATION_PERMISSIONS)

    /** The battery prompt answered and one crash file written, both before the activity starts. */
    @get:Rule(order = 1)
    val seedACrash = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                graph.basePreferences.batteryExemptionRequested.set(true)
                graph.crashLog.write(Thread.currentThread(), IllegalStateException(FIXTURE_MESSAGE))
                seeded = graph.crashLog.latest()
                graph.crashLog.markExported()
                base.evaluate()
            }
        }
    }

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @After
    fun removeTheSeededCrash() {
        seeded?.delete()
    }

    /**
     * Q17: the row carries the crash time, from the file's timestamp, beside its size. Both are read
     * from one node, so a time shown anywhere else on the screen does not pass.
     */
    @Test
    fun aCrashLogRowShowsTheCrashTimeBesideItsSize() {
        val file = checkNotNull(seeded) { "CrashLog.write left no file" }
        val time = crashTimeText(context, file.lastModified())
        val size = context.getString(R.string.crash_size_format, (file.length() / BYTES_PER_KIB).toInt())

        awaitLibraryScreen()
        openCrashLogs()
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(file.name).fetchSemanticsNodes().isNotEmpty()
        }

        val rows = composeRule.onAllNodes(hasText(time, substring = true) and hasText(size, substring = true))
            .fetchSemanticsNodes()
        assertEquals("no row shows \"$time\" beside \"$size\" for ${file.name}", 1, rows.size)
        Log.i(LOG_TAG, "row for ${file.name}: $time, $size")
    }

    @Test
    fun shareOnACrashLogRowOpensTheSystemChooser() {
        val file = checkNotNull(seeded) { "CrashLog.write left no file" }
        assertEquals("the seeded crash file is not the newest", file, graph.crashLog.latest())
        val chooser = context.packageManager
            .resolveActivity(Intent(Intent.ACTION_CHOOSER), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo
        assertNotNull("nothing on this device answers ACTION_CHOOSER", chooser)
        val chooserPackage = chooser!!.packageName

        awaitLibraryScreen()
        openCrashLogs()
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(file.name).fetchSemanticsNodes().isNotEmpty()
        }

        val windows = CopyOnWriteArrayList<String>()
        val automation = instrumentation.uiAutomation
        automation.setOnAccessibilityEventListener { event ->
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                windows += "${event.packageName}/${event.className}"
            }
        }
        try {
            composeRule.onAllNodes(hasContentDescription(string(R.string.action_share)) and hasClickAction())[0]
                .performClick()

            awaitTrue(CHOOSER_TIMEOUT_MILLIS, "no chooser window from $chooserPackage came up; windows seen: $windows") {
                windows.any { it.startsWith("$chooserPackage/") }
            }
            awaitTrue(CHOOSER_TIMEOUT_MILLIS, "the chooser came up but never took the screen") {
                activePackage() == chooserPackage
            }
            Log.i(LOG_TAG, "chooser for ${file.name}: ${windows.first { it.startsWith("$chooserPackage/") }}")
        } finally {
            automation.setOnAccessibilityEventListener(null)
            closeChooser(chooserPackage)
        }
    }

    /**
     * Backs out of the chooser until it no longer holds the screen. A back sent while it is still
     * coming up is dropped, and a chooser left open sat over the next class's activity, which then
     * found no Compose hierarchy (`MainActivitySmokeTest` in the 4 GB AVD's full suite).
     */
    private fun closeChooser(chooserPackage: String) {
        val deadline = SystemClock.uptimeMillis() + CHOOSER_TIMEOUT_MILLIS
        while (activePackage() == chooserPackage) {
            if (SystemClock.uptimeMillis() > deadline) throw AssertionError("the chooser did not close")
            instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            Thread.sleep(CLOSE_SETTLE_MILLIS)
        }
    }

    private fun activePackage(): String? = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()

    /** As `MainActivitySmokeTest` waits: the library's title up, and its loading spinner gone. */
    private fun awaitLibraryScreen() {
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(string(R.string.library_title)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) {
            composeRule.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
                .fetchSemanticsNodes()
                .isEmpty()
        }
    }

    /**
     * More, Settings, Advanced, Crash logs. The Advanced body scrolls in both layouts, a pushed
     * screen on a phone and the end pane on a tablet, so its row is scrolled to.
     */
    private fun openCrashLogs() {
        composeRule.onNode(hasText(string(R.string.tab_more)) and isSelectable()).performClick()
        val settings = hasText(string(R.string.label_settings)) and hasClickAction()
        composeRule.onNode(hasScrollAction()).performScrollToNode(settings)
        composeRule.onNode(settings).performClick()
        composeRule.onNode(
            hasText(string(SettingsSection.ADVANCED.labelRes)) and hasClickAction() and isSelectable().not(),
        ).performClick()
        val crashLogs = composeRule.onNode(hasText(string(R.string.crash_title)) and hasClickAction())
        crashLogs.performScrollTo()
        crashLogs.performClick()
    }

    private fun awaitTrue(timeoutMillis: Long, message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (!condition()) {
            if (SystemClock.uptimeMillis() > deadline) throw AssertionError("$message (after $timeoutMillis ms)")
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun string(resourceId: Int): String = context.getString(resourceId)

    private companion object {
        /** Empty below API 33, where `POST_NOTIFICATIONS` is not a runtime permission. */
        val NOTIFICATION_PERMISSIONS: Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                emptyArray()
            }

        const val LOG_TAG = "CrashLogShareTest"
        const val FIXTURE_MESSAGE = "CrashLogShareTest fixture crash"
        const val SCREEN_TIMEOUT_MILLIS = 30_000L
        const val CHOOSER_TIMEOUT_MILLIS = 10_000L
        const val POLL_MILLIS = 50L
        const val BYTES_PER_KIB = 1024
        const val CLOSE_SETTLE_MILLIS = 500L
    }
}

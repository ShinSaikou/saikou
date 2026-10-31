package com.shinsaikou.bench

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shinsaikou.SaikouApp
import com.shinsaikou.di.AppGraph
import com.shinsaikou.libraryupdate.LibraryUpdateWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The installed build's half of unit R1's in-place upgrade check (phase 3 decision Q15). The host
 * runs it after each `adb install -r` over the state [InPlaceUpgradeSeedTest] left: the owner-signed
 * release over the debug build, a release with a higher versionCode over that, and the debug build
 * type over both. Against the release it runs minified, which is the point: it is how the check
 * learns that what R8 did leaves the owner's data readable.
 *
 * It fails on the first of these that does not hold, in this order:
 *
 * - the application launches and draws its library screen;
 * - the snapshot this build takes equals the one the last build recorded ([InPlaceUpgrade.snapshot]):
 *   the library, categories, chapters with their read marks and bookmarks, history, every download
 *   the manifest lists and every file under the download root, the preferences the seed set, the
 *   secret, the crash file and the installed extension;
 * - the periodic library update is still scheduled;
 * - "Update library", tapped in the library screen's overflow menu, runs to success, and the
 *   fixture extension's source finds the chapters the seed left out;
 * - the download the hold stage left in flight is still queued, and finishes once the fixture
 *   server lets its pages through;
 * - the crash file is listed on the crash log screen, reached as the owner reaches it (More, then
 *   Crash logs).
 *
 * The screens are walked through the accessibility tree rather than a Compose test rule, and the
 * strings are looked up by name, so nothing of the application's UI has to survive shrinking for
 * the walk to work.
 *
 * Then it records the snapshot afresh, with this leg's result, for the next install. Without
 * `saikou.upgrade.stage=check` it returns at once with a `Skipped:` line.
 */
@RunWith(AndroidJUnit4::class)
class InPlaceUpgradeCheckTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val graph: AppGraph get() = (context.applicationContext as SaikouApp).graph

    @Test
    fun theInstalledBuildReadsEverythingTheLastOneLeft() {
        if (InPlaceUpgrade.stage() != STAGE) return InPlaceUpgrade.skip("theInstalledBuildReadsEverythingTheLastOneLeft", STAGE)
        val build = InPlaceUpgrade.build()
        val outcome = InPlaceUpgrade.readOutcome()
        val expected = outcome.getJSONObject("snapshot")
        val preferenceKeys = outcome.getJSONArray("preferenceKeys").let { keys -> (0 until keys.length()).map { keys.getString(it) } }
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val versionCode = info.versionCode
        Log.i(InPlaceUpgrade.LOG_TAG, "check $build: versionCode $versionCode, versionName ${info.versionName}")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        launchAndAwaitTheLibrary()

        runBlocking(Dispatchers.IO) {
            withTimeout(CHECK_TIMEOUT_MILLIS) {
                graph.extensionManager.isInitialized.first { it }
                val actual = InPlaceUpgrade.snapshot(graph, context, preferenceKeys)
                val changed = InPlaceUpgrade.differences(expected, actual)
                assertTrue("after the install, $build reads other state:\n  ${changed.joinToString("\n  ")}", changed.isEmpty())
                Log.i(InPlaceUpgrade.LOG_TAG, "check $build: snapshot equal: ${InPlaceUpgrade.summary(actual)}")
            }
        }
        val periodic = InPlaceUpgrade.uniqueWork(context, LibraryUpdateWorker.WORK_NAME_AUTO).map { it.second }
        assertTrue(
            "the periodic library update is not scheduled: states $periodic",
            periodic.any { it == InPlaceUpgrade.WORK_ENQUEUED || it == InPlaceUpgrade.WORK_RUNNING },
        )
        Log.i(InPlaceUpgrade.LOG_TAG, "check $build: the periodic library update is scheduled (states $periodic)")

        val earlierRuns = InPlaceUpgrade.uniqueWork(context, LibraryUpdateWorker.WORK_NAME_MANUAL).map { it.first }.toSet()
        val overflow = string("action_menu_overflow_description")
        click(awaitNode(UI_TIMEOUT_MILLIS, "no \"$overflow\" button on the library screen") { it.contentDescription?.toString() == overflow })
        val update = string("action_update_library")
        click(awaitNodeAnyWindow(UI_TIMEOUT_MILLIS, "no \"$update\" item in the library screen's overflow menu") { it.text?.toString() == update })
        Log.i(InPlaceUpgrade.LOG_TAG, "check $build: opened the overflow menu and tapped \"$update\"")

        runBlocking(Dispatchers.IO) {
            withTimeout(CHECK_TIMEOUT_MILLIS) {
                if (!outcome.isNull("heldChapterId")) finishTheHeldDownload(outcome.getLong("heldChapterId"))
                awaitTheLibraryUpdate(earlierRuns, outcome.getLong("mangaId"))
            }
        }

        val crashFile = expected.getJSONArray("crashFiles").getString(0)
        openTheCrashLogsAndFind(crashFile)
        Log.i(InPlaceUpgrade.LOG_TAG, "check $build: the crash log screen lists $crashFile")

        val after = InPlaceUpgrade.snapshot(graph, context, preferenceKeys)
        val legs = outcome.getJSONArray("legs").put(JSONObject().put("build", build).put("versionCode", versionCode).put("result", "passed"))
        InPlaceUpgrade.writeOutcome(outcome.put("heldChapterId", JSONObject.NULL).put("legs", legs).put("snapshot", after))
        Log.i(InPlaceUpgrade.LOG_TAG, "check $build: passed; recorded for the next install: ${InPlaceUpgrade.summary(after)}")
    }

    /** Starts the launcher activity and waits for the library tab's title in the window. */
    private fun launchAndAwaitTheLibrary() {
        val launch = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName)) { "no launcher activity" }
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val title = string("library_title")
        awaitNode(LAUNCH_TIMEOUT_MILLIS, "the library screen never drew \"$title\"") { it.text?.toString() == title }
        Log.i(InPlaceUpgrade.LOG_TAG, "check ${InPlaceUpgrade.build()}: launched, the library screen is up")
    }

    /** More, then Crash logs, then the row with [name]. */
    private fun openTheCrashLogsAndFind(name: String) {
        val more = string("tab_more")
        click(awaitNode(UI_TIMEOUT_MILLIS, "no \"$more\" tab") { it.text?.toString() == more })
        val crashTitle = string("crash_title")
        val row = awaitNode(UI_TIMEOUT_MILLIS, "no \"$crashTitle\" row on More", scroll = true) { it.text?.toString() == crashTitle }
        click(row)
        awaitNode(UI_TIMEOUT_MILLIS, "the crash log screen does not list $name") { it.text?.toString() == name }
    }

    /**
     * The chapter the hold stage left in flight: still queued and not failed after the install, then
     * downloaded once the fixture server lets its pages through. Read from the database's queue and
     * manifest tables.
     */
    private suspend fun finishTheHeldDownload(chapterId: Long) {
        val id = chapterId.toString()
        val queued = InPlaceUpgrade.query(
            context,
            InPlaceUpgrade.DATABASE,
            "SELECT state, downloaded_pages, total_pages, error_message FROM download_queue WHERE chapter_id = ?",
            id,
        )
        assertEquals("the download in flight before the install is no longer queued", 1, queued.size)
        assertTrue("the download in flight before the install failed: ${queued[0]}", queued[0][0]?.toLong() != ERROR_STATE)
        Log.i(InPlaceUpgrade.LOG_TAG, "check ${InPlaceUpgrade.build()}: the chapter in flight is still queued: ${queued[0]}")
        val released = InPlaceUpgrade.fixtureServer(HOLD_RELEASE_PATH)
        assertTrue("the fixture server is still holding: $released", !released.getBoolean("holding"))
        var seen: List<List<String?>> = queued
        InPlaceUpgrade.await(DOWNLOAD_TIMEOUT_MILLIS, { "the held chapter did not finish: $seen" }) {
            seen = InPlaceUpgrade.query(
                context,
                InPlaceUpgrade.DATABASE,
                "SELECT state, downloaded_pages, total_pages, error_message FROM download_queue WHERE chapter_id = ?",
                id,
            )
            if (seen.firstOrNull()?.get(0)?.toLong() == ERROR_STATE) throw AssertionError("the held chapter failed: $seen")
            seen.isEmpty() && InPlaceUpgrade.count(context, "SELECT count(*) FROM downloads_manifest WHERE chapter_id = ?", id) == 1L
        }
        Log.i(InPlaceUpgrade.LOG_TAG, "check ${InPlaceUpgrade.build()}: the chapter in flight across the install finished")
    }

    /** The run the tap started, other than [earlierRuns], to success, and the chapters it found. */
    private suspend fun awaitTheLibraryUpdate(earlierRuns: Set<String>, mangaId: Long) {
        var runs: List<Pair<String, Int>> = emptyList()
        InPlaceUpgrade.await(UPDATE_TIMEOUT_MILLIS, { "the library update did not finish: states ${runs.map { it.second }}" }) {
            runs = InPlaceUpgrade.uniqueWork(context, LibraryUpdateWorker.WORK_NAME_MANUAL).filter { it.first !in earlierRuns }
            runs.isNotEmpty() && runs.all { it.second >= InPlaceUpgrade.WORK_SUCCEEDED }
        }
        assertEquals("the library update's state", listOf(InPlaceUpgrade.WORK_SUCCEEDED), runs.map { it.second })
        val chapters = InPlaceUpgrade.count(
            context,
            "SELECT count(*) FROM chapters WHERE manga_id = ? AND deleted_at IS NULL",
            mangaId.toString(),
        )
        assertEquals("the fixture entry's chapters after the update", InPlaceUpgrade.FIXTURE_LISTED.toLong(), chapters)
        Log.i(InPlaceUpgrade.LOG_TAG, "check ${InPlaceUpgrade.build()}: a library update succeeded; the fixture entry has $chapters chapters")
    }

    /**
     * Polls the active window's accessibility tree for a node [matches] accepts. With [scroll], each
     * miss scrolls the first scrollable node forward once, so a row below the fold is reached.
     */
    private fun awaitNode(
        timeoutMillis: Long,
        message: String,
        scroll: Boolean = false,
        matches: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (root != null && root.packageName?.toString() == context.packageName) {
                find(root, matches)?.let { return it }
                if (scroll) find(root) { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            SystemClock.sleep(UI_POLL_MILLIS)
        }
        throw AssertionError("$message (after $timeoutMillis ms)")
    }

    /**
     * Like [awaitNode], but also scans every window [UiAutomation.getWindows] reports, not only
     * [UiAutomation.getRootInActiveWindow]. A Compose `DropdownMenu` opens its own window, and
     * whether that becomes the active window's root is not guaranteed, so an item inside it is
     * searched for across all windows the application owns.
     */
    private fun awaitNodeAnyWindow(
        timeoutMillis: Long,
        message: String,
        matches: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            val roots = buildList {
                instrumentation.uiAutomation.rootInActiveWindow?.let { add(it) }
                instrumentation.uiAutomation.windows.forEach { window -> window.root?.let { add(it) } }
            }
            for (root in roots) {
                if (root.packageName?.toString() != context.packageName) continue
                find(root, matches)?.let { return it }
            }
            SystemClock.sleep(UI_POLL_MILLIS)
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

    /** Clicks [node], or the nearest ancestor that takes a click: Compose puts the action on the row. */
    private fun click(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        assertTrue("nothing around \"${node.text}\" takes a click", target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }

    /** A string of the application's by name, so no resource class has to survive shrinking. */
    private fun string(name: String): String {
        val id = context.resources.getIdentifier(name, "string", context.packageName)
        check(id != 0) { "the application has no string $name" }
        return context.getString(id)
    }

    private companion object {
        const val STAGE = "check"
        const val HOLD_RELEASE_PATH = "/hold/release"

        /** `DownloadState.ERROR`'s stored code; the enum is the application's, and R8 may rewrite it. */
        const val ERROR_STATE = 4L

        const val LAUNCH_TIMEOUT_MILLIS = 90_000L
        const val UI_TIMEOUT_MILLIS = 30_000L
        const val UI_POLL_MILLIS = 250L
        const val CHECK_TIMEOUT_MILLIS = 300_000L
        const val DOWNLOAD_TIMEOUT_MILLIS = 120_000L
        const val UPDATE_TIMEOUT_MILLIS = 180_000L
    }
}

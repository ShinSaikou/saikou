package com.shinsaikou.util

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.shinsaikou.SaikouApp
import com.shinsaikou.domain.preferences.ThemeMode
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import java.io.File

/**
 * Screenshots and accessibility dumps a unit's report shows, taken by the device tests themselves
 * on the screens they have just asserted (unit ID3). Nothing here asserts anything, and nothing runs
 * unless the run asks: `saikou.evidence.screenshots=true` turns the captures on, and
 * `saikou.evidence.theme=light` or `dark` pins the app's theme mode for the run, put back as found
 * and on disk before each test ends. The gate passes neither, so a gate run captures nothing and
 * changes no preference.
 *
 * Files go under the app's external files directory, `<unit>-evidence/<theme>/`, which AGP's
 * uninstall at the end of a connected run deletes: a run that wants them installs and instruments
 * by hand and pulls the directory afterwards. Shots taken by hand around such a run start the app
 * with `am start -n com.shinsaikou/.ui.main.MainActivity`, never `monkey`: a debug build has a second
 * launcher entry (`app/src/debug/AndroidManifest.xml` says why).
 */
object EvidenceCapture {

    private val arguments get() = InstrumentationRegistry.getArguments()

    private const val SETTLE_ATTEMPTS = 20
    private const val SETTLE_INTERVAL_MILLIS = 100L

    val enabled: Boolean get() = arguments.getString("saikou.evidence.screenshots") == "true"

    private val theme: ThemeMode?
        get() = when (arguments.getString("saikou.evidence.theme")) {
            "light" -> ThemeMode.LIGHT
            "dark" -> ThemeMode.DARK
            else -> null
        }

    /**
     * Pins the theme mode the run named before the activity launches, so order it before the
     * compose rule, and puts the preference back afterwards, set or unset. A run that names no theme
     * gets the test as it is.
     */
    fun themeRule(): TestRule = TestRule { base, description ->
        val wanted = theme ?: return@TestRule base
        themeRule(wanted).apply(base, description)
    }

    /**
     * [themeRule] with [wanted] pinned whatever the run's arguments say. The preference is put back
     * on disk before the rule returns, through [StagePreferences.commit]: the store's own `set` and
     * `delete` are `apply()`, the last thing that flushed them was the activity stopping before the
     * restore, and the runner kills the process as soon as the last test returns, so a restore by
     * `apply()` alone was lost and the pinned mode was what the next launch read (LK2's report).
     */
    fun themeRule(wanted: ThemeMode): TestRule = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val preference = graphThemeMode()
                val wasSet = preference.isSet()
                val before = preference.get()
                preference.set(wanted)
                try {
                    base.evaluate()
                } finally {
                    if (wasSet) preference.set(before) else preference.delete()
                    StagePreferences.commit()
                }
            }
        }
    }

    /**
     * The whole screen as the device shows it, popups and system bars included, once it has settled:
     * a screenshot can still show the frame before the one the app has just laid out, so shots are
     * taken [SETTLE_INTERVAL_MILLIS] apart until two in a row are the same, at most
     * [SETTLE_ATTEMPTS] times, and the last is kept.
     */
    fun screenshot(unit: String, name: String) {
        if (!enabled) return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        var shot = readableScreenshot()
        for (attempt in 1 until SETTLE_ATTEMPTS) {
            SystemClock.sleep(SETTLE_INTERVAL_MILLIS)
            val next = readableScreenshot()
            val settled = next.sameAs(shot)
            shot.recycle()
            shot = next
            if (settled) break
        }
        try {
            File(directory(unit), "$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            shot.recycle()
        }
    }

    private fun readableScreenshot(): Bitmap {
        val shot = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()) {
            "UiAutomation took no screenshot"
        }
        if (shot.config != Bitmap.Config.HARDWARE) return shot
        return shot.copy(Bitmap.Config.ARGB_8888, false).also { shot.recycle() }
    }

    /**
     * The active window's accessibility tree, one node a line with its class, text, description and
     * whether it is clickable: what `uiautomator dump` reads, taken through the instrumentation's own
     * connection, since a second `uiautomator` process cannot connect while it holds one.
     */
    fun accessibilityDump(unit: String, name: String) {
        if (!enabled) return
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return
        val lines = mutableListOf<String>()
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            lines += "  ".repeat(depth) + listOf(
                node.className,
                "text=\"${node.text ?: ""}\"",
                "content-desc=\"${node.contentDescription ?: ""}\"",
                "clickable=${node.isClickable}",
            ).joinToString(" ")
            for (index in 0 until node.childCount) node.getChild(index)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        File(directory(unit), "$name.txt").writeText(lines.joinToString("\n", postfix = "\n"))
    }

    private fun directory(unit: String): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val external = checkNotNull(context.getExternalFilesDir(null)) { "no external files directory" }
        val mode = arguments.getString("saikou.evidence.theme") ?: "system"
        return File(external, "$unit-evidence/$mode").apply { mkdirs() }
    }

    private fun graphThemeMode() =
        (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SaikouApp)
            .graph.uiPreferences.themeMode
}

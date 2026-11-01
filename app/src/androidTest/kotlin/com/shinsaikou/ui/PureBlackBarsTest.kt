package com.shinsaikou.ui

import android.Manifest
import android.graphics.Color
import android.os.Build
import android.view.Window
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.shinsaikou.SaikouApp
import com.shinsaikou.domain.preferences.ThemeMode
import com.shinsaikou.domain.preferences.UiPreferences
import com.shinsaikou.nightModeOf
import com.shinsaikou.ui.main.MainActivity
import com.shinsaikou.util.StagePreferences
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import tachiyomi.core.common.preference.Preference

/**
 * Pure black clears the window's bar colours below Android 15 and turns the navigation bar's
 * contrast enforcement off from Android 10; turning it off again has to give the window back the
 * bars `enableEdgeToEdge` drew, with the activity still the same one. From Android 10 on that is
 * the enforcement coming back, which it always did. On Android 8 and 9 (the API 26 AVD) the scrim
 * is the navigation bar's colour itself, and it stayed transparent until something re-created the
 * activity.
 *
 * The theme is pinned to dark before the activity starts, with AppCompat's night mode set to match
 * on the main thread, so `SaikouApp`'s own follow-up of the preference finds nothing to change and
 * never re-creates the activity under the test.
 */
@RunWith(AndroidJUnit4::class)
class PureBlackBarsTest {

    /** As in [MainActivitySmokeTest]: the permission dialog would sit over the activity. */
    @get:Rule(order = 0)
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(
        *if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyArray()
        },
    )

    /** Around the activity, so the theme is in place before it starts and put back once it is gone. */
    @get:Rule(order = 1)
    val darkWithoutPureBlack = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val restore = listOf(restorer(ui.themeMode), restorer(ui.themeDarkAmoled))
                ui.themeMode.set(ThemeMode.DARK)
                ui.themeDarkAmoled.set(false)
                followThemeMode()
                try {
                    base.evaluate()
                } finally {
                    restore.forEach { it() }
                    followThemeMode()
                    // The run ends right after this, and a write still queued would be lost with it.
                    StagePreferences.commit()
                }
            }
        }
    }

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ui: UiPreferences
        get() = (instrumentation.targetContext.applicationContext as SaikouApp).graph.uiPreferences

    @Test
    fun turningPureBlackOffGivesBackTheBarsEdgeToEdgeDrew() {
        val window = composeRule.activity.window
        composeRule.waitForIdle()
        val edgeToEdge = barsOf(window)

        ui.themeDarkAmoled.set(true)
        awaitBars(window, PURE_BLACK, "pure black clears the bars")

        ui.themeDarkAmoled.set(false)
        awaitBars(window, edgeToEdge, "turning pure black off gives back the bars edge to edge drew")
    }

    /** What `SaikouApp` does on a change of the theme mode, done now rather than when it gets to it. */
    private fun followThemeMode() {
        val mode = ui.themeMode.get()
        instrumentation.runOnMainSync { AppCompatDelegate.setDefaultNightMode(nightModeOf(mode)) }
    }

    /** Puts [preference] back as it is now, set or unset. */
    private fun <T> restorer(preference: Preference<T>): () -> Unit {
        val wasSet = preference.isSet()
        val before = preference.get()
        return { if (wasSet) preference.set(before) else preference.delete() }
    }

    @Suppress("DEPRECATION")
    private fun barsOf(window: Window): Bars = composeRule.runOnUiThread {
        Bars(
            status = window.statusBarColor.takeIf { COLOURS_APPLY },
            navigation = window.navigationBarColor.takeIf { COLOURS_APPLY },
            contrastEnforced = if (ENFORCEMENT_APPLIES) window.isNavigationBarContrastEnforced else null,
        )
    }

    private fun awaitBars(window: Window, expected: Bars, message: String) {
        try {
            composeRule.waitUntil(TIMEOUT_MILLIS) { barsOf(window) == expected }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("$message: expected $expected, was ${barsOf(window)}", e)
        }
    }

    /**
     * What the theme can change on the window, each null where the platform ignores it: the two
     * colours from Android 15 on, the enforcement before Android 10.
     */
    private data class Bars(val status: Int?, val navigation: Int?, val contrastEnforced: Boolean?) {
        override fun toString() =
            "status ${status?.let(::hex)}, navigation ${navigation?.let(::hex)}, contrast enforced $contrastEnforced"
    }

    private companion object {
        val COLOURS_APPLY = Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM
        val ENFORCEMENT_APPLIES = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        val PURE_BLACK = Bars(
            status = Color.TRANSPARENT.takeIf { COLOURS_APPLY },
            navigation = Color.TRANSPARENT.takeIf { COLOURS_APPLY },
            contrastEnforced = false.takeIf { ENFORCEMENT_APPLIES },
        )

        /** A preference write reaches the window through the view model's flow and a recomposition. */
        const val TIMEOUT_MILLIS = 5_000L

        fun hex(color: Int) = "#%08X".format(color)
    }
}

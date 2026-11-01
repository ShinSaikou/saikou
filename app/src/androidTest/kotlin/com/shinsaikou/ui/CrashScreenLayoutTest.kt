package com.shinsaikou.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shinsaikou.i18n.R
import com.shinsaikou.ui.crash.CrashScreen
import com.shinsaikou.ui.crash.CrashScreenContent
import eu.kanade.presentation.theme.SaikouTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The crash screen's layout, from its composable drawn in this process. The activity that shows it
 * runs in `:crash`, where [CrashScreenDeviceTest] can drive it only through accessibility, and the
 * nodes that reports carry no bounds to compare.
 */
@RunWith(AndroidJUnit4::class)
class CrashScreenLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * The row had the small padding on every side while the text above it had the medium one. Left
     * to right whatever the device's locale, since the bounds compared are the physical edges.
     */
    @Test
    fun theButtonRowEndsAsFarFromTheRightEdgeAsTheTextStartsFromTheLeft() {
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                SaikouTheme {
                    CrashScreen(
                        content = CrashScreenContent.Unreadable,
                        onShare = { false },
                        onRestart = {},
                        onStartWithoutExtensions = {},
                        onClose = {},
                    )
                }
            }
        }

        val screen = compose.onRoot().getBoundsInRoot()
        val text = compose.onNodeWithText(context.getString(R.string.crash_screen_text)).getBoundsInRoot()
        val restart = compose.onNode(hasText(context.getString(R.string.action_restart)) and hasClickAction())
            .getBoundsInRoot()

        assertEquals(
            "the text's left margin and the last button's right margin, in dp",
            (text.left - screen.left).value,
            (screen.right - restart.right).value,
            MARGIN_TOLERANCE_DP,
        )
    }

    private companion object {
        /** Bounds arrive in whole pixels; half a dp covers the rounding at any density. */
        const val MARGIN_TOLERANCE_DP = 0.5f
    }
}

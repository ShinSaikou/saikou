package com.shinsaikou.ui.components

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shinsaikou.domain.preferences.AppTheme
import com.shinsaikou.presentation.core.util.isTabletUi
import com.shinsaikou.util.Args
import eu.kanade.presentation.theme.BaseSaikouTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.presentation.core.components.AdaptiveSheet
import tachiyomi.presentation.core.components.AdaptiveSheetImpl

/**
 * The colour a `Text` inside the shared sheet is drawn in, read from the text's own layout in light
 * and in dark.
 *
 * Both forms of the sheet are upstream's: a `Surface` on `surfaceContainerHigh` that names no content
 * colour, so a text that sets no colour of its own (every `ListRowTitle`) takes Material's
 * `contentColorFor(surfaceContainerHigh)`. The phone and centred forms are each drawn on every device
 * through [AdaptiveSheetImpl]; the last two open [AdaptiveSheet] as an app would, which takes the
 * centred form where tablet UI is composed, and under `saikou.expect.tablet=true` they fail if it did
 * not.
 */
@RunWith(AndroidJUnit4::class)
class AdaptiveSheetContentColorTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun aTextInThePhoneSheetTakesTheContainersContentColorInLight() {
        assertForm(isTabletUi = false, isDark = false)
    }

    @Test
    fun aTextInThePhoneSheetTakesTheContainersContentColorInDark() {
        assertForm(isTabletUi = false, isDark = true)
    }

    @Test
    fun aTextInTheCentredSheetTakesTheContainersContentColorInLight() {
        assertForm(isTabletUi = true, isDark = false)
    }

    @Test
    fun aTextInTheCentredSheetTakesTheContainersContentColorInDark() {
        assertForm(isTabletUi = true, isDark = true)
    }

    @Test
    fun theSheetThisDeviceOpensTakesTheContainersContentColorInLight() {
        assertDeviceSheet(isDark = false)
    }

    @Test
    fun theSheetThisDeviceOpensTakesTheContainersContentColorInDark() {
        assertDeviceSheet(isDark = true)
    }

    private fun assertDeviceSheet(isDark: Boolean) {
        val seen = Seen()
        var tablet = false
        composeRule.setContent {
            BaseSaikouTheme(appTheme = AppTheme.DEFAULT, isDark = isDark, isAmoled = false) {
                seen.read()
                tablet = isTabletUi()
                AdaptiveSheet(onDismissRequest = {}) { SampleText(seen) }
            }
        }
        composeRule.waitForIdle()
        val form = if (tablet) "centred" else "phone"
        Log.i(LOG_TAG, "the device's sheet: the $form form")
        if (Args.expectTablet) {
            assertTrue("saikou.expect.tablet is true, but the sheet took the phone form", tablet)
        }
        seen.assertContentColor("$form, ${if (isDark) "dark" else "light"}")
    }

    private fun assertForm(isTabletUi: Boolean, isDark: Boolean) {
        val seen = Seen()
        composeRule.setContent {
            BaseSaikouTheme(appTheme = AppTheme.DEFAULT, isDark = isDark, isAmoled = false) {
                seen.read()
                AdaptiveSheetImpl(isTabletUi = isTabletUi, enableImplicitDismiss = true, onDismissRequest = {}) {
                    SampleText(seen)
                }
            }
        }
        composeRule.waitForIdle()
        val form = if (isTabletUi) "centred" else "phone"
        seen.assertContentColor("$form, ${if (isDark) "dark" else "light"}")
    }

    @Composable
    private fun SampleText(seen: Seen) {
        Text(text = "Sheet text", onTextLayout = { seen.text = it.layoutInput.style.color })
    }

    /** Material's content colour for the sheet's container and the colour the sample text was laid out in. */
    private class Seen {
        var expected = Color.Unspecified
        var text = Color.Unspecified

        @Composable
        fun read() {
            expected = contentColorFor(MaterialTheme.colorScheme.surfaceContainerHigh)
        }

        fun assertContentColor(case: String) {
            assertNotEquals("the expected colour was read ($case)", Color.Unspecified, expected)
            assertEquals("a Text in the sheet takes contentColorFor(surfaceContainerHigh) ($case)", expected, text)
        }
    }

    private companion object {
        const val LOG_TAG = "AdaptiveSheetColour"
    }
}

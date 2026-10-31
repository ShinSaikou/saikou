package com.shinsaikou.ui.crash

import androidx.appcompat.app.AppCompatDelegate
import com.shinsaikou.domain.preferences.AppTheme
import com.shinsaikou.domain.preferences.ThemeMode
import com.shinsaikou.nightModeOf
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The crash screen's process takes its look and its process check from strings alone: the intent's
 * extras, and the process name, since it may read nothing of the app's own.
 */
class CrashScreenLookTest {

    @Test
    fun `the look the crashed process sent is the look the screen takes`() {
        CrashScreenLook.of(themeMode = "DARK", appTheme = "NORD", amoled = true, secure = true) shouldBe
            CrashScreenLook(ThemeMode.DARK, AppTheme.NORD, amoled = true, secure = true)
    }

    @Test
    fun `missing or unknown names fall back to the app's defaults`() {
        CrashScreenLook.of(themeMode = null, appTheme = null, amoled = false, secure = false) shouldBe CrashScreenLook()
        CrashScreenLook.of(themeMode = "SEPIA", appTheme = "NOT_A_THEME", amoled = false, secure = false) shouldBe
            CrashScreenLook()
    }

    @Test
    fun `the screen's day or night follows the app's theme mode`() {
        nightModeOf(ThemeMode.SYSTEM) shouldBe AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        nightModeOf(ThemeMode.LIGHT) shouldBe AppCompatDelegate.MODE_NIGHT_NO
        nightModeOf(ThemeMode.DARK) shouldBe AppCompatDelegate.MODE_NIGHT_YES
    }

    @Test
    fun `only the process the manifest names for the screen counts as the crash process`() {
        CrashProcess.isCrashProcess("com.shinsaikou:crash", "com.shinsaikou") shouldBe true
        CrashProcess.isCrashProcess("com.shinsaikou", "com.shinsaikou") shouldBe false
        CrashProcess.isCrashProcess("com.shinsaikou.debug:crash", "com.shinsaikou") shouldBe false
        CrashProcess.isCrashProcess(null, "com.shinsaikou") shouldBe false
    }
}

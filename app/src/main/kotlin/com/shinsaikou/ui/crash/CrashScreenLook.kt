package com.shinsaikou.ui.crash

import com.shinsaikou.domain.preferences.AppTheme
import com.shinsaikou.domain.preferences.ThemeMode

/**
 * The look the crash screen takes, so it does not come up in another theme than the app it
 * replaces. The process that crashed reads it and puts it in the intent, since the screen's own
 * process may not open the preferences; [secure] is the app lock's secure screen, already resolved
 * against incognito mode.
 */
internal data class CrashScreenLook(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val appTheme: AppTheme = AppTheme.DEFAULT,
    val amoled: Boolean = false,
    val secure: Boolean = false,
) {

    companion object {
        /** From the intent's extras; a missing or unknown name falls back to the app's default. */
        fun of(themeMode: String?, appTheme: String?, amoled: Boolean, secure: Boolean) = CrashScreenLook(
            themeMode = ThemeMode.entries.firstOrNull { it.name == themeMode } ?: ThemeMode.SYSTEM,
            appTheme = AppTheme.entries.firstOrNull { it.name == appTheme } ?: AppTheme.DEFAULT,
            amoled = amoled,
            secure = secure,
        )
    }
}

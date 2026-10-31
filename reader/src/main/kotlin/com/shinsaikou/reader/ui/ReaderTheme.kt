package com.shinsaikou.reader.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import com.shinsaikou.domain.preferences.AppTheme
import com.shinsaikou.domain.preferences.ThemeMode
import com.shinsaikou.domain.preferences.UiPreferences
import eu.kanade.presentation.theme.BaseSaikouTheme
import tachiyomi.presentation.core.util.collectAsState

/**
 * The app palette with no window side effects. Contract 07 part 2 section 5.5, as ruling R21
 * settles it.
 *
 * `SaikouTheme` owns the system bars for the rest of the app through a `SideEffect`; inside the
 * reader `ReaderSystemBars` owns them, and two writers fight (decision D21). `SaikouPreviewTheme`,
 * which part 2 names, is for `@Preview` only. `BaseSaikouTheme` is the palette alone, and is public
 * in `:presentation-core` for exactly this.
 */
@Composable
fun ReaderTheme(uiPreferences: UiPreferences, content: @Composable () -> Unit) {
    val values = rememberReaderThemeValues(uiPreferences)
    BaseSaikouTheme(
        appTheme = values.appTheme,
        isDark = values.isDark,
        isAmoled = values.isAmoled,
        content = content,
    )
}

/**
 * The same three values as plain data, so `ReaderViewerHost` can carry them into the vendored
 * `AbstractComposeView`s, which open their own composition roots (ruling R21).
 */
@Immutable
data class ReaderThemeValues(
    val appTheme: AppTheme,
    val isDark: Boolean,
    val isAmoled: Boolean,
)

@Composable
fun rememberReaderThemeValues(uiPreferences: UiPreferences): ReaderThemeValues {
    val appTheme by uiPreferences.appTheme.collectAsState()
    val themeMode by uiPreferences.themeMode.collectAsState()
    val isAmoled by uiPreferences.themeDarkAmoled.collectAsState()
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    return ReaderThemeValues(appTheme = appTheme, isDark = isDark, isAmoled = isAmoled)
}

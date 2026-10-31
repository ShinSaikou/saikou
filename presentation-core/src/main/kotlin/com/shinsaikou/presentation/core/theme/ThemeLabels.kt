package com.shinsaikou.presentation.core.theme

import androidx.annotation.StringRes
import com.shinsaikou.domain.preferences.AppTheme
import com.shinsaikou.domain.preferences.TabletUiMode
import com.shinsaikou.domain.preferences.ThemeMode
import com.shinsaikou.i18n.R

/**
 * The enums themselves are stored preference values, so `:domain` owns them. What a user reads on
 * the Appearance screen is a presentation concern, and `:domain` cannot reach `:i18n`, so the
 * labels live here. Every value maps to exactly one string: adding a case to an enum without a
 * label here is a compile error, not a blank row.
 */
@get:StringRes
val AppTheme.titleRes: Int
    get() = when (this) {
        AppTheme.DEFAULT -> R.string.theme_default
        AppTheme.MONET -> R.string.theme_dynamic
        AppTheme.CATPPUCCIN -> R.string.theme_catppuccin
        AppTheme.GREEN_APPLE -> R.string.theme_greenapple
        AppTheme.LAVENDER -> R.string.theme_lavender
        AppTheme.MIDNIGHT_DUSK -> R.string.theme_midnightdusk
        AppTheme.NORD -> R.string.theme_nord
        AppTheme.STRAWBERRY_DAIQUIRI -> R.string.theme_strawberrydaiquiri
        AppTheme.TAKO -> R.string.theme_tako
        AppTheme.TEALTURQUOISE -> R.string.theme_tealturquoise
        AppTheme.TIDAL_WAVE -> R.string.theme_tidalwave
        AppTheme.YINYANG -> R.string.theme_yinyang
        AppTheme.YOTSUBA -> R.string.theme_yotsuba
        AppTheme.TOKYONIGHT -> R.string.theme_tokyonight
        AppTheme.MONOCHROME -> R.string.theme_monochrome
    }

@get:StringRes
val ThemeMode.titleRes: Int
    get() = when (this) {
        ThemeMode.SYSTEM -> R.string.theme_mode_system
        ThemeMode.LIGHT -> R.string.theme_mode_light
        ThemeMode.DARK -> R.string.theme_mode_dark
    }

@get:StringRes
val TabletUiMode.titleRes: Int
    get() = when (this) {
        TabletUiMode.AUTOMATIC -> R.string.tablet_ui_automatic
        TabletUiMode.ALWAYS -> R.string.tablet_ui_always
        TabletUiMode.LANDSCAPE -> R.string.tablet_ui_landscape
        TabletUiMode.NEVER -> R.string.tablet_ui_never
    }

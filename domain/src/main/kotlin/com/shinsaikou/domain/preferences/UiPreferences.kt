package com.shinsaikou.domain.preferences

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

/**
 * Upstream's theme names, in upstream's order, so an upstream backup's `pref_app_theme` restores to the same
 * theme. [DEFAULT] is Saikou's own scheme, labelled "Saikou", where upstream's is its blue one. Upstream's
 * deprecated `DARK_BLUE`, `HOT_PINK` and `BLUE` are left out: an unknown restored name falls back to
 * [DEFAULT] through getEnum, which is what upstream shows for them.
 */
enum class AppTheme {
    DEFAULT,
    MONET,
    CATPPUCCIN,
    GREEN_APPLE,
    LAVENDER,
    MIDNIGHT_DUSK,
    NORD,
    STRAWBERRY_DAIQUIRI,
    TAKO,
    TEALTURQUOISE,
    TIDAL_WAVE,
    YINYANG,
    YOTSUBA,
    TOKYONIGHT,
    MONOCHROME,
    ;

    companion object {
        /**
         * A name from outside this enum, restored from a backup written by another reader, maps to
         * [DEFAULT]. The preference path already does this through getEnum; this is for callers
         * that read a raw stored name, such as the backup importer.
         */
        fun fromName(name: String?): AppTheme = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

enum class TabletUiMode {
    AUTOMATIC,
    ALWAYS,
    LANDSCAPE,
    NEVER,
}

/** Theme and formatting settings, and History's NSFW switch. Every key but [historyHideNsfw]'s is upstream's. */
@SingleIn(AppScope::class)
@Inject
class UiPreferences(preferenceStore: PreferenceStore) {

    val themeMode: Preference<ThemeMode> = preferenceStore.getEnum("pref_theme_mode_key", ThemeMode.SYSTEM)

    /**
     * Saikou's own scheme on every API level (phase 5, `phase5-start.md` section 3.1.3, kept by the
     * owner on 2026-10-17 in the look rethink). Upstream defaults to the dynamic scheme on API 31 and
     * up; here Dynamic stays a choice, not the default.
     */
    val appTheme: Preference<AppTheme> = preferenceStore.getEnum("pref_app_theme", AppTheme.DEFAULT)

    val themeDarkAmoled: Preference<Boolean> = preferenceStore.getBoolean("pref_theme_dark_amoled_key", false)

    val relativeTime: Preference<Boolean> = preferenceStore.getBoolean("relative_time_v2", true)

    /** Empty = the device's own date format; read through [UiPreferences.dateFormat]. */
    val dateFormat: Preference<String> = preferenceStore.getString("app_date_format", "")

    val tabletUiMode: Preference<TabletUiMode> = preferenceStore.getEnum("tablet_ui_mode", TabletUiMode.AUTOMATIC)

    val imagesInDescription: Preference<Boolean> = preferenceStore.getBoolean("pref_render_images_description", true)

    /**
     * History leaves out the entries whose source an installed NSFW extension provides. Saikou's own
     * key: upstream's History has no such switch.
     */
    val historyHideNsfw: Preference<Boolean> = preferenceStore.getBoolean("pref_history_hide_nsfw", false)

    companion object {
        /**
         * Upstream's formatter for a [dateFormat] value (phase 4 unit SR): the locale's short date for
         * the empty default, otherwise the stored pattern in the device's locale.
         */
        fun dateFormat(format: String): DateTimeFormatter = when (format) {
            "" -> DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
            else -> DateTimeFormatter.ofPattern(format, Locale.getDefault())
        }
    }
}

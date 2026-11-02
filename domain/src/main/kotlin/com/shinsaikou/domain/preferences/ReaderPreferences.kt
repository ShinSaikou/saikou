package com.shinsaikou.domain.preferences

import com.shinsaikou.domain.manga.model.ReaderOrientation
import com.shinsaikou.domain.manga.model.ReadingMode
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

enum class FlashColor {
    BLACK,
    WHITE,
    WHITE_BLACK,
}

enum class ReaderHideThreshold {
    HIGHEST,
    HIGH,
    LOW,
    LOWEST,
}

enum class TappingInvertMode {
    NONE,
    HORIZONTAL,
    VERTICAL,
    BOTH,
}

/**
 * Reader settings. Every key but the two marked fresh is upstream's, so an upstream backup restores them
 * generically by name. The reader resolves the effective mode as the manga's own reading mode
 * unless it is DEFAULT, else [defaultReadingMode]; orientation likewise.
 */
@Suppress("TooManyFunctions")
@SingleIn(AppScope::class)
@Inject
class ReaderPreferences(preferenceStore: PreferenceStore) {

    // region General

    val pageTransitions: Preference<Boolean> = preferenceStore.getBoolean("pref_enable_transitions_key", true)

    val flashOnPageChange: Preference<Boolean> = preferenceStore.getBoolean("pref_reader_flash", false)

    val flashDurationMillis: Preference<Int> = preferenceStore.getInt("pref_reader_flash_duration", 100)

    val flashPageInterval: Preference<Int> = preferenceStore.getInt("pref_reader_flash_interval", 1)

    val flashColor: Preference<FlashColor> = preferenceStore.getEnum("pref_reader_flash_mode", FlashColor.BLACK)

    val doubleTapAnimSpeed: Preference<Int> = preferenceStore.getInt("pref_double_tap_anim_speed", 500)

    val showPageNumber: Preference<Boolean> = preferenceStore.getBoolean("pref_show_page_number_key", true)

    /** Names of the [ReadingMode] entries that use the vertical navigator. */
    val verticalNavigator: Preference<Set<String>> = preferenceStore.getStringSet("pref_vertical_navigator", emptySet())

    val verticalNavigatorOnLeft: Preference<Boolean> =
        preferenceStore.getBoolean("pref_vertical_navigator_on_left", false)

    val verticalNavigatorHeight: Preference<Int> = preferenceStore.getInt("pref_vertical_navigator_height", 65)

    val showReadingMode: Preference<Boolean> = preferenceStore.getBoolean("pref_show_reading_mode", true)

    val fullscreen: Preference<Boolean> = preferenceStore.getBoolean("fullscreen", true)

    val drawUnderCutout: Preference<Boolean> = preferenceStore.getBoolean("cutout_short", true)

    val keepScreenOn: Preference<Boolean> = preferenceStore.getBoolean("pref_keep_screen_on_key", false)

    val defaultReadingMode: Preference<Int> =
        preferenceStore.getInt("pref_default_reading_mode_key", ReadingMode.RIGHT_TO_LEFT.flagValue)

    val defaultOrientationType: Preference<Int> =
        preferenceStore.getInt("pref_default_orientation_type_key", ReaderOrientation.FREE.flagValue)

    val webtoonDoubleTapZoomEnabled: Preference<Boolean> =
        preferenceStore.getBoolean("pref_enable_double_tap_zoom_webtoon", true)

    val imageScaleType: Preference<Int> = preferenceStore.getInt("pref_image_scale_type_key", 1)

    val zoomStart: Preference<Int> = preferenceStore.getInt("pref_zoom_start_key", 1)

    /** 0 white, 1 black, 2 gray, 3 automatic. */
    val readerTheme: Preference<Int> = preferenceStore.getInt("pref_reader_theme_key", 1)

    val alwaysShowChapterTransition: Preference<Boolean> =
        preferenceStore.getBoolean("always_show_chapter_transition", true)

    val cropBorders: Preference<Boolean> = preferenceStore.getBoolean("crop_borders", false)

    val navigateToPan: Preference<Boolean> = preferenceStore.getBoolean("navigate_pan", true)

    val landscapeZoom: Preference<Boolean> = preferenceStore.getBoolean("landscape_zoom", true)

    val cropBordersWebtoon: Preference<Boolean> = preferenceStore.getBoolean("crop_borders_webtoon", false)

    /** Percent of the screen width, capped at 25 by the settings screen. */
    val webtoonSidePadding: Preference<Int> = preferenceStore.getInt("webtoon_side_padding", 0)

    val readerHideThreshold: Preference<ReaderHideThreshold> =
        preferenceStore.getEnum("reader_hide_threshold", ReaderHideThreshold.LOW)

    val folderPerManga: Preference<Boolean> = preferenceStore.getBoolean("create_folder_per_manga", false)

    val skipRead: Preference<Boolean> = preferenceStore.getBoolean("skip_read", false)

    val skipFiltered: Preference<Boolean> = preferenceStore.getBoolean("skip_filtered", true)

    val skipDupe: Preference<Boolean> = preferenceStore.getBoolean("skip_dupe", false)

    val webtoonDisableZoomOut: Preference<Boolean> = preferenceStore.getBoolean("webtoon_disable_zoom_out", false)

    // endregion

    // region Dual page. Restored from a backup, read by nobody in Phase 1.

    val dualPageSplitPaged: Preference<Boolean> = preferenceStore.getBoolean("pref_dual_page_split", false)

    val dualPageInvertPaged: Preference<Boolean> = preferenceStore.getBoolean("pref_dual_page_invert", false)

    val dualPageSplitWebtoon: Preference<Boolean> = preferenceStore.getBoolean("pref_dual_page_split_webtoon", false)

    val dualPageInvertWebtoon: Preference<Boolean> = preferenceStore.getBoolean("pref_dual_page_invert_webtoon", false)

    val dualPageRotateToFit: Preference<Boolean> = preferenceStore.getBoolean("pref_dual_page_rotate", false)

    val dualPageRotateToFitInvert: Preference<Boolean> =
        preferenceStore.getBoolean("pref_dual_page_rotate_invert", false)

    val dualPageRotateToFitWebtoon: Preference<Boolean> =
        preferenceStore.getBoolean("pref_dual_page_rotate_webtoon", false)

    val dualPageRotateToFitInvertWebtoon: Preference<Boolean> =
        preferenceStore.getBoolean("pref_dual_page_rotate_invert_webtoon", false)

    // endregion

    // region Color filter

    val customBrightness: Preference<Boolean> = preferenceStore.getBoolean("pref_custom_brightness_key", false)

    val customBrightnessValue: Preference<Int> = preferenceStore.getInt("custom_brightness_value", 0)

    val colorFilter: Preference<Boolean> = preferenceStore.getBoolean("pref_color_filter_key", false)

    val colorFilterValue: Preference<Int> = preferenceStore.getInt("color_filter_value", 0)

    val colorFilterMode: Preference<Int> = preferenceStore.getInt("color_filter_mode", 0)

    val grayscale: Preference<Boolean> = preferenceStore.getBoolean("pref_grayscale", false)

    val invertedColors: Preference<Boolean> = preferenceStore.getBoolean("pref_inverted_colors", false)

    // endregion

    // region Controls

    val readWithLongTap: Preference<Boolean> = preferenceStore.getBoolean("reader_long_tap", true)

    val readWithVolumeKeys: Preference<Boolean> = preferenceStore.getBoolean("reader_volume_keys", false)

    val readWithVolumeKeysInverted: Preference<Boolean> =
        preferenceStore.getBoolean("reader_volume_keys_inverted", false)

    val navigationModePager: Preference<Int> = preferenceStore.getInt("reader_navigation_mode_pager", 0)

    val navigationModeWebtoon: Preference<Int> = preferenceStore.getInt("reader_navigation_mode_webtoon", 0)

    val pagerNavInverted: Preference<TappingInvertMode> =
        preferenceStore.getEnum("reader_tapping_inverted", TappingInvertMode.NONE)

    val webtoonNavInverted: Preference<TappingInvertMode> =
        preferenceStore.getEnum("reader_tapping_inverted_webtoon", TappingInvertMode.NONE)

    val showNavigationOverlayNewUser: Preference<Boolean> =
        preferenceStore.getBoolean("reader_navigation_overlay_new_user", true)

    val showNavigationOverlayOnStart: Preference<Boolean> =
        preferenceStore.getBoolean("reader_navigation_overlay_on_start", false)

    // endregion

    /** Fresh: pixel cap above which an animated image is decoded as a still frame. */
    val animatedImageMaxPixels: Preference<Int> = preferenceStore.getInt("reader_animated_max_pixels", 16_000_000)

    /** Fresh: split a tall page while reading online, not only while downloading. */
    val tallImageSplitEnabled: Preference<Boolean> = preferenceStore.getBoolean("reader_split_tall_images_online", true)

    /**
     * Fresh, Experimental: an online chapter fetches several pages at once rather than one at a
     * time. Off by default, because a source may refuse requests that come together.
     */
    val parallelPageLoading: Preference<Boolean> = preferenceStore.getBoolean("reader_parallel_page_loading", false)
}

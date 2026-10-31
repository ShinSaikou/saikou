package com.shinsaikou.reader.settings

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.BlendMode
import com.shinsaikou.i18n.R

/**
 * The four option lists upstream keeps in its `ReaderPreferences` companion. Contract 07 part 2
 * section 6.5.
 *
 * They are presentation, `:domain` may not hold a `@StringRes`, and the settings pages index into
 * them by position, so they move here unchanged in order. Changing an order changes what a stored
 * preference integer means.
 *
 * **There is no scale-type enum, and there must not be one.** The `imageScaleType` integer is
 * passed straight into `ReaderPageImageView.Config.minimumScaleType` and from there into the image
 * view fork's `setMinimumScaleType(int)`, whose constants run `SCALE_TYPE_CENTER_INSIDE = 1`
 * through `SCALE_TYPE_SMART_FIT = 6` in exactly the order of [ImageScaleType]. An enum would add a
 * third place where that alignment has to hold; `ScaleTypeConstantsTest` pins it instead.
 */
object ReaderSettingsOptions {

    const val WEBTOON_PADDING_MIN = 0
    const val WEBTOON_PADDING_MAX = 25

    /** The flash-duration preference is stored in milliseconds and shown in tenths of a second. */
    const val MILLI_CONVERSION = 100

    /** Index is the `navigationMode*` preference value, 0 to 5. */
    @get:StringRes
    val TapZones: List<Int> = listOf(
        R.string.label_default,
        R.string.l_nav,
        R.string.kindlish_nav,
        R.string.edge_nav,
        R.string.right_and_left_nav,
        R.string.disabled_nav,
    )

    /** Index `i` labels the `imageScaleType` preference value `i + 1`, which is also the view's constant. */
    @get:StringRes
    val ImageScaleType: List<Int> = listOf(
        R.string.scale_type_fit_screen,
        R.string.scale_type_stretch,
        R.string.scale_type_fit_width,
        R.string.scale_type_fit_height,
        R.string.scale_type_original_size,
        R.string.scale_type_smart_fit,
    )

    /** Index `i` labels the `zoomStart` preference value `i + 1`, 1 to 4. */
    @get:StringRes
    val ZoomStart: List<Int> = listOf(
        R.string.zoom_start_automatic,
        R.string.zoom_start_left,
        R.string.zoom_start_right,
        R.string.zoom_start_center,
    )

    /** Label and blend mode. The last three entries exist only on API 28 and up, as in upstream. */
    val ColorFilterMode: List<Pair<Int, BlendMode>> = buildList {
        add(R.string.label_default to BlendMode.SrcOver)
        add(R.string.filter_mode_multiply to BlendMode.Modulate)
        add(R.string.filter_mode_screen to BlendMode.Screen)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            add(R.string.filter_mode_overlay to BlendMode.Overlay)
            add(R.string.filter_mode_lighten to BlendMode.Lighten)
            add(R.string.filter_mode_darken to BlendMode.Darken)
        }
    }

    fun colorFilterModeOrNull(index: Int): Pair<Int, BlendMode>? = ColorFilterMode.getOrNull(index)
}

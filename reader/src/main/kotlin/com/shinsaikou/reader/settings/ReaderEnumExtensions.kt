package com.shinsaikou.reader.settings

import androidx.annotation.StringRes
import com.shinsaikou.domain.preferences.FlashColor
import com.shinsaikou.domain.preferences.ReaderHideThreshold
import com.shinsaikou.domain.preferences.TappingInvertMode
import com.shinsaikou.domain.preferences.TwoPageLayout
import com.shinsaikou.i18n.R

/**
 * The payloads `:domain`'s bare reader enums cannot carry. Contract 07 part 2 section 6.4.
 *
 * `:domain` may not depend on `:i18n` and holds no `@StringRes`, and upstream's versions of these
 * enums are nested inside its `ReaderPreferences` with their payloads attached. This file is the
 * pattern ruling R17 established for `AppTheme.titleRes`: the value is domain state, the label is a
 * presentation concern, and the two live in different modules.
 *
 * Every `when` is exhaustive with no `else`, so adding an enum case without a payload is a compile
 * error rather than a blank row in the settings sheet.
 */

/** Percent of the scroll delta below which the webtoon viewer does not hide the menu. Upstream's values. */
val ReaderHideThreshold.threshold: Int
    get() = when (this) {
        ReaderHideThreshold.HIGHEST -> 5
        ReaderHideThreshold.HIGH -> 13
        ReaderHideThreshold.LOW -> 31
        ReaderHideThreshold.LOWEST -> 47
    }

val TappingInvertMode.shouldInvertHorizontal: Boolean
    get() = this == TappingInvertMode.HORIZONTAL || this == TappingInvertMode.BOTH

val TappingInvertMode.shouldInvertVertical: Boolean
    get() = this == TappingInvertMode.VERTICAL || this == TappingInvertMode.BOTH

@get:StringRes
val TappingInvertMode.titleRes: Int
    get() = when (this) {
        TappingInvertMode.NONE -> R.string.tapping_inverted_none
        TappingInvertMode.HORIZONTAL -> R.string.tapping_inverted_horizontal
        TappingInvertMode.VERTICAL -> R.string.tapping_inverted_vertical
        TappingInvertMode.BOTH -> R.string.tapping_inverted_both
    }

@get:StringRes
val ReaderHideThreshold.titleRes: Int
    get() = when (this) {
        ReaderHideThreshold.HIGHEST -> R.string.pref_highest
        ReaderHideThreshold.HIGH -> R.string.pref_high
        ReaderHideThreshold.LOW -> R.string.pref_low
        ReaderHideThreshold.LOWEST -> R.string.pref_lowest
    }

@get:StringRes
val FlashColor.titleRes: Int
    get() = when (this) {
        FlashColor.BLACK -> R.string.pref_flash_style_black
        FlashColor.WHITE -> R.string.pref_flash_style_white
        FlashColor.WHITE_BLACK -> R.string.pref_flash_style_white_black
    }

@get:StringRes
val TwoPageLayout.titleRes: Int
    get() = when (this) {
        TwoPageLayout.NEVER -> R.string.two_pages_never
        TwoPageLayout.IN_LANDSCAPE -> R.string.two_pages_in_landscape
        TwoPageLayout.ALWAYS -> R.string.two_pages_always
    }

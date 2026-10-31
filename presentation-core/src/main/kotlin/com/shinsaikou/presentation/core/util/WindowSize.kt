package com.shinsaikou.presentation.core.util

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import com.shinsaikou.domain.preferences.TabletUiMode

/**
 * Upstream's AUTOMATIC thresholds, on the smallest width, from upstream v0.20.4
 * `app/src/main/java/eu/kanade/tachiyomi/util/system/DisplayExtensions.kt`, where the portrait one
 * carries the note "some tablets have screen width like 711dp = 1600px / 2.25" and the landscape
 * one "make sure icons on the nav rail fit". The 720 in that file is not AUTOMATIC's rule: it is
 * the value upstream coerces the configuration to once the mode has decided, and applying it here in
 * both orientations left a tablet between 600 and 719 dp with no split at all (ruling R52).
 */
const val TABLET_UI_MIN_SCREEN_WIDTH_PORTRAIT_DP = 700

/** See [TABLET_UI_MIN_SCREEN_WIDTH_PORTRAIT_DP]. Applies to every orientation other than portrait, as in upstream. */
const val TABLET_UI_MIN_SCREEN_WIDTH_LANDSCAPE_DP = 600

/** `MainActivity` provides this from `UiPreferences.tabletUiMode`; screens call [isTabletUi] with no argument. */
val LocalTabletUiMode = compositionLocalOf { TabletUiMode.AUTOMATIC }

/**
 * Whether a screen should lay itself out in two panes. The one tablet boolean in the app: there is
 * no second window-size helper.
 */
@Composable
@ReadOnlyComposable
fun isTabletUi(mode: TabletUiMode = LocalTabletUiMode.current): Boolean {
    val configuration = LocalConfiguration.current
    return mode.isTabletUi(configuration.smallestScreenWidthDp, configuration.orientation)
}

/**
 * The breakpoint rule on its own, outside a composition, so it can be tested.
 *
 * AUTOMATIC is upstream's rule exactly: 700 dp of smallest width upright, 600 dp otherwise.
 *
 * Only AUTOMATIC consults the width, and that is deliberate rather than an omission. LANDSCAPE was
 * flagged as returning true from the orientation alone, on the reading that a phone in that mode,
 * rotated, would get two panes at phone width. It was measured instead of argued, on
 * `saikou_pixel8a_2g`: rotated, that phone reports `sw411dp w914dp h411dp`, so `TwoPanelBox` gives
 * each pane 417 dp, which is wider than the whole window the same phone hands a portrait screen.
 * Landscape on a phone is the long edge by definition, so there is no device this app runs on
 * where that branch produces a narrow pane.
 *
 * ALWAYS is the branch with no width behind it. The same phone held upright reports `w411dp`, and
 * two panes of that window come out near 166 dp each once the navigation rail has taken its 80,
 * which is a library down to a single column of covers beside a detail pane narrower than a cover.
 * It is left alone by ruling R52: a floor this function could apply would compare the smallest
 * width, which the rotated phone shares with the upright one, and so would take away the one split
 * ALWAYS gives a phone that works; a correct floor would make ALWAYS the same as LANDSCAPE on every
 * phone. What pushed a mid-size tablet into ALWAYS was a stricter AUTOMATIC than upstream's, and that
 * is what the ruling changed instead.
 */
fun TabletUiMode.isTabletUi(smallestScreenWidthDp: Int, orientation: Int): Boolean = when (this) {
    TabletUiMode.AUTOMATIC -> smallestScreenWidthDp >= when (orientation) {
        Configuration.ORIENTATION_PORTRAIT -> TABLET_UI_MIN_SCREEN_WIDTH_PORTRAIT_DP
        else -> TABLET_UI_MIN_SCREEN_WIDTH_LANDSCAPE_DP
    }
    TabletUiMode.ALWAYS -> true
    TabletUiMode.LANDSCAPE -> orientation == Configuration.ORIENTATION_LANDSCAPE
    TabletUiMode.NEVER -> false
}

@Composable
@ReadOnlyComposable
fun isLandscape(): Boolean = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

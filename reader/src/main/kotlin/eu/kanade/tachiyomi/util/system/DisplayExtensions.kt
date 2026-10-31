// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/util/system/DisplayExtensions.kt (modified: yes)
package eu.kanade.tachiyomi.util.system

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.View

/**
 * Modified: trimmed to [isNightMode] and the two [hasDisplayCutout] receivers. Dropped, because
 * nothing in `:reader` calls them and each needs something the module does not have:
 * `Configuration.isTabletUi` and `prepareTabletUiContext` (`:presentation-core` owns the one tablet
 * rule, `com.shinsaikou.presentation.core.util.isTabletUi`, and upstream's version reads `UiPreferences` out
 * of Injekt), and `isNavigationBarNeedsScrim`, which needs upstream's `InternalResourceHelper`.
 */
fun Context.isNightMode(): Boolean =
    resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

/**
 * Checks whether if the device has a display cutout (i.e. notch, camera cutout, etc.).
 *
 * Only works on Android 9+.
 */
fun Activity.hasDisplayCutout(): Boolean {
    return window.decorView.hasDisplayCutout()
}

/**
 * Checks whether if the device has a display cutout (i.e. notch, camera cutout, etc.).
 *
 * Only works on Android 9+.
 */
fun View.hasDisplayCutout(): Boolean {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && rootWindowInsets?.displayCutout != null
}

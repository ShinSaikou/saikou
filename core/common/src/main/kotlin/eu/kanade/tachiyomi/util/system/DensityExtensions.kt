// Vendored from upstream v0.20.4: core/common/src/main/kotlin/eu/kanade/tachiyomi/util/system/DensityExtensions.kt (modified: no)
package eu.kanade.tachiyomi.util.system

import android.content.res.Resources

/**
 * Converts to px.
 */
val Int.dpToPx: Int
    get() = (this * Resources.getSystem().displayMetrics.density).toInt()

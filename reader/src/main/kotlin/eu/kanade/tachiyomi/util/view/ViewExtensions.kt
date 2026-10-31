// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/util/view/ViewExtensions.kt (modified: yes)
package eu.kanade.tachiyomi.util.view

import android.content.res.Resources
import android.graphics.Rect
import android.view.View

/**
 * Modified: trimmed to [isVisibleOnScreen], which `ReaderPageImageView` needs. Dropped: both
 * `setComposeContent` helpers (they call upstream's `TachiyomiTheme`, and this shell composes its own
 * roots) and `View.popupMenu` (nothing in the reader opens one).
 */
fun View?.isVisibleOnScreen(): Boolean {
    if (this == null) return false
    if (!this.isShown) return false
    val actualPosition = Rect()
    this.getGlobalVisibleRect(actualPosition)
    val screen = Rect(
        0,
        0,
        Resources.getSystem().displayMetrics.widthPixels,
        Resources.getSystem().displayMetrics.heightPixels,
    )
    return actualPosition.intersect(screen)
}

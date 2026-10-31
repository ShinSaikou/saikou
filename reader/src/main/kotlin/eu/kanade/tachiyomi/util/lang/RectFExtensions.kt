// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/util/lang/RectFExtensions.kt (modified: yes)
package eu.kanade.tachiyomi.util.lang

import android.graphics.RectF
import com.shinsaikou.domain.preferences.TappingInvertMode
import com.shinsaikou.reader.settings.shouldInvertHorizontal
import com.shinsaikou.reader.settings.shouldInvertVertical

/**
 * Modified: `TappingInvertMode` is `:domain`'s bare enum rather than upstream's nested one, so the two
 * booleans arrive as extensions from `com.shinsaikou.reader.settings` (contract 07 part 2 section 6.4).
 */
fun RectF.invert(invertMode: TappingInvertMode): RectF {
    val horizontal = invertMode.shouldInvertHorizontal
    val vertical = invertMode.shouldInvertVertical
    return when {
        horizontal && vertical -> RectF(1f - this.right, 1f - this.bottom, 1f - this.left, 1f - this.top)
        vertical -> RectF(this.left, 1f - this.bottom, this.right, 1f - this.top)
        horizontal -> RectF(1f - this.right, this.top, 1f - this.left, this.bottom)
        else -> this
    }
}

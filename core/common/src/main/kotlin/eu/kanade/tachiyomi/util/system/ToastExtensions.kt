// Vendored from upstream v0.20.4: core/common/src/main/kotlin/eu/kanade/tachiyomi/util/system/ToastExtensions.kt (modified: yes) the string-resource overload is dropped; this module has no resources
package eu.kanade.tachiyomi.util.system

import android.content.Context
import android.widget.Toast

/**
 * Display a toast in this context.
 *
 * @param text the text to display.
 * @param duration the duration of the toast. Defaults to short.
 */
fun Context.toast(
    text: String?,
    duration: Int = Toast.LENGTH_SHORT,
    block: (Toast) -> Unit = {},
): Toast {
    return Toast.makeText(applicationContext, text.orEmpty(), duration).also {
        block(it)
        it.show()
    }
}

// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderButton.kt (modified: yes)
package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import com.google.android.material.R
import com.google.android.material.button.MaterialButton
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerViewer

/**
 * A button class to be used by child views of the pager viewer. All tap gestures are handled by
 * the pager, but this class disables that behavior to allow clickable buttons.
 *
 * Modified: `R.attr.materialButtonStyle` is read from the Material library rather than from
 * upstream's own `R`, which is the same attribute; `:reader` declares no attrs of its own. Also
 * upstream's later fix (v0.21.0): a cancelled touch gives taps back to the pager too ([endsTouch]).
 */
class ReaderButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = R.attr.materialButtonStyle,
) : MaterialButton(context, attrs, defStyleAttr) {

    var viewer: PagerViewer? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        viewer?.pager?.setGestureDetectorEnabled(false)
        if (endsTouch(event.actionMasked)) {
            viewer?.pager?.setGestureDetectorEnabled(true)
        }
        return super.onTouchEvent(event)
    }

    internal companion object {
        /**
         * Lifted or cancelled: a press that turns into a scroll ends in a cancel, and tap navigation
         * stayed off after one until the next press.
         */
        fun endsTouch(action: Int): Boolean = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
    }
}

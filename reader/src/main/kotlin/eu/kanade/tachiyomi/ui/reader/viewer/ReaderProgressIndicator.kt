// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderProgressIndicator.kt (modified: yes)
package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import androidx.annotation.IntRange
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.isVisible
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.shinsaikou.reader.viewer.ViewerHost
import eu.kanade.presentation.theme.BaseSaikouTheme
import tachiyomi.presentation.core.components.CombinedCircularProgressIndicator

/**
 * A wrapper for [CircularProgressIndicator] that always rotates.
 *
 * By always rotating we give the feedback to the user that the application isn't 'stuck'.
 *
 * Modified (ruling R21): upstream's no-argument `TachiyomiTheme { }` works there only because it reads
 * `UiPreferences` out of Injekt inside the composable. This is an Android View with its own
 * composition root and cannot inherit a `CompositionLocal` from `ReaderScreen`, so the three
 * resolved theme values arrive on [ViewerHost] and the call becomes `BaseSaikouTheme(...)`. Its
 * `isDark` is the night mode of upstream's `createReaderThemeContext()`, which the page holders built
 * this View with: dark on a black or gray reader background, light on white.
 * `:reader` never calls `SaikouTheme`, whose `SideEffect` writes the system bars and would fight
 * `ReaderSystemBars`.
 */
class ReaderProgressIndicator @JvmOverloads constructor(
    context: Context,
    private val host: ViewerHost,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AbstractComposeView(context, attrs, defStyleAttr) {

    init {
        layoutParams = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
    }

    private var progress by mutableFloatStateOf(0f)

    @Composable
    override fun Content() {
        BaseSaikouTheme(host.appTheme, host.isDark, host.isAmoled) {
            CombinedCircularProgressIndicator(progress = { progress })
        }
    }

    fun show() {
        isVisible = true
    }

    fun hide() {
        isVisible = false
    }

    fun setProgress(@IntRange(from = 0, to = 100) progress: Int) {
        this.progress = progress / 100f
    }
}

// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderTransitionView.kt (modified: yes)
package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import android.util.AttributeSet
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.AbstractComposeView
import com.shinsaikou.reader.viewer.ViewerHost
import com.shinsaikou.source.SourceIds
import eu.kanade.presentation.theme.BaseSaikouTheme
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import tachiyomi.domain.manga.model.Manga
import eu.kanade.presentation.reader.ChapterTransition as ChapterTransitionContent

/**
 * The chapter transition, composed inside the viewer's own View tree so it scrolls with the pages.
 *
 * Two modifications:
 *
 *  - upstream's no-argument `TachiyomiTheme { }` becomes
 *    `BaseSaikouTheme(host.appTheme, host.isDark, host.isAmoled)` (ruling R21): this View owns its
 *    own composition root and cannot inherit a `CompositionLocal` from `ReaderScreen`.
 *    `host.isDark` carries the night mode of upstream's `createReaderThemeContext()`, which this
 *    View's context had there: dark on a black or gray reader background, light on white.
 *  - `bind` loses its `DownloadManager` parameter: the two "downloaded" flags are read at compose
 *    time instead, the chapter left from its page loader (a downloaded or Local chapter's loader
 *    is local), the chapter ahead from [ViewerHost.downloadedChapterIds] or a Local entry, as
 *    upstream decides them. Decision D24 had both hard false while `:downloads` was a Phase 1
 *    skeleton. The retry part 2 section 5.5 mentions is not here and is not a parameter: both
 *    transition holders already draw their own retry button off `ReaderChapter.stateFlow`, exactly
 *    as in upstream.
 */
class ReaderTransitionView @JvmOverloads constructor(
    context: Context,
    private val host: ViewerHost,
    attrs: AttributeSet? = null,
) : AbstractComposeView(context, attrs) {

    private var data: Data? by mutableStateOf(null)

    init {
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    }

    fun bind(transition: ChapterTransition, manga: Manga?) {
        data = manga?.let { Data(transition = transition, isLocal = it.source == SourceIds.LOCAL) }
    }

    @Composable
    override fun Content() {
        data?.let {
            BaseSaikouTheme(host.appTheme, host.isDark, host.isAmoled) {
                CompositionLocalProvider(
                    LocalTextStyle provides MaterialTheme.typography.bodySmall,
                    LocalContentColor provides MaterialTheme.colorScheme.onBackground,
                ) {
                    val goingTo = it.transition.to?.chapter?.id
                    ChapterTransitionContent(
                        transition = it.transition,
                        currChapterDownloaded = it.transition.from.pageLoader?.isLocal == true,
                        goingToChapterDownloaded = it.isLocal || goingTo in host.downloadedChapterIds,
                    )
                }
            }
        }
    }

    private data class Data(val transition: ChapterTransition, val isLocal: Boolean)
}

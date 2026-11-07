package com.shinsaikou.reader.viewer

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import coil3.decode.Decoder
import com.shinsaikou.domain.preferences.AppTheme
import com.shinsaikou.domain.preferences.ReaderPreferences
import com.shinsaikou.reader.pipeline.AnimatedImagePolicy
import com.shinsaikou.reader.state.PreloadPolicy
import com.shinsaikou.reader.state.ReaderEvent
import com.shinsaikou.reader.state.ReaderState
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation
import kotlinx.coroutines.CoroutineScope
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * The shell's [ViewerHost]. Contract 07 part 2 section 5.3.
 *
 * Every member reads through [state] rather than holding a copy, so a viewer built once sees every
 * later state without being rebuilt. The three theme values are the resolved ones ruling R21
 * requires, with the night mode taken from the reader background as upstream's reader context takes
 * it (see [ViewerHost.isDark]); they are written by `ReaderScreen` on every recomposition, because
 * a vendored `AbstractComposeView` opens its own composition root and cannot inherit them.
 *
 * Part 2 section 5.3 also gives this class a `readerThemeContext()` member. Ruling R21 and defect 1
 * supersede it: `context` is already themed with `Theme.Saikou.Reader`, so both vendored adapters
 * take it directly and `ReaderThemeContext.kt` is not written.
 */
class ReaderViewerHost(
    override val context: Context,
    override val scope: CoroutineScope,
    override val readerPreferences: ReaderPreferences,
    private val state: () -> ReaderState,
    private val send: (ReaderEvent) -> Unit,
    private val animatedPolicy: AnimatedImagePolicy,
) : ViewerHost {

    /** Read by `ReaderScreen`; written by the viewers' config listeners. */
    val navigationOverlay: MutableState<NavigationOverlayRequest?> = mutableStateOf(null)

    override var appTheme: AppTheme = AppTheme.DEFAULT
    override var isDark: Boolean = true
    override var isAmoled: Boolean = false
    override var downloadedChapterIds: Set<Long> by mutableStateOf(emptySet())

    override val menuVisible: Boolean get() = state().menuVisible
    override val isScrollingThroughPages: Boolean get() = state().scrubbing || state().movingBySlider
    override val manga: Manga? get() = state().manga

    override fun ownerOf(chapter: Chapter): Manga? = state().session?.ownerOf(chapter) ?: manga

    override fun preloadWindow(): PreloadPolicy.Window = state().preload

    override fun onPageSelected(page: ReaderPage) = send(ReaderEvent.PageSelected(page))

    override fun onTransitionSelected(transition: ChapterTransition) =
        send(ReaderEvent.TransitionSelected(transition))

    override fun requestPreloadChapter(chapter: ReaderChapter) = send(ReaderEvent.PreloadRequested(chapter))

    override fun onPageLongTap(page: ReaderPage) = send(ReaderEvent.PageLongTapped(page))

    override fun toggleMenu() = send(ReaderEvent.ToggleMenu)

    override fun showMenu() = send(ReaderEvent.ShowMenu)

    override fun hideMenu() = send(ReaderEvent.HideMenu)

    override fun setNavigationOverlay(navigation: ViewerNavigation, showOnStart: Boolean) {
        navigationOverlay.value = NavigationOverlayRequest(navigation, showOnStart)
    }

    /** The shell's background is a collected preference, so there is nothing to do here (D18). */
    override fun onReaderThemeChanged() = Unit

    override fun animatedDecoderFactory(): Decoder.Factory? = animatedPolicy.decoderFactory()
}

data class NavigationOverlayRequest(val navigation: ViewerNavigation, val showOnStart: Boolean)

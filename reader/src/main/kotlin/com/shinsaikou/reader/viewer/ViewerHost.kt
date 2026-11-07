package com.shinsaikou.reader.viewer

import android.content.Context
import coil3.decode.Decoder
import com.shinsaikou.domain.preferences.AppTheme
import com.shinsaikou.domain.preferences.ReaderPreferences
import com.shinsaikou.reader.state.PreloadPolicy
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation
import kotlinx.coroutines.CoroutineScope
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * Everything a vendored viewer may ask of the shell. Contract 07 section 2.4.
 *
 * Upstream's viewers hold a `ReaderActivity` and reach through it for the view model, the binding, the
 * theme and Injekt. None of that exists here, so every one of those reaches becomes a member of
 * this interface (modification H). A vendored viewer never receives an `Activity`.
 *
 * `ReaderViewerHost` is the shell's implementation (part 2 section 5.3); unit tests supply fakes.
 */
interface ViewerHost {

    /** Activity context themed with `Theme.Saikou.Reader`; every View is built with it. */
    val context: Context

    /** The composition's scope. A holder's load is cancelled when the composition leaves. */
    val scope: CoroutineScope

    val readerPreferences: ReaderPreferences

    /** Current effective menu visibility; viewers read it, never store it. */
    val menuVisible: Boolean

    /**
     * True while the slider is being dragged, and from its release until the viewer has moved to
     * the page it landed on. Added under 2.4's escape clause: `PagerViewer` reads upstream's
     * `activity.isScrollingThroughPages` to decide whether a page selection should hide the menu,
     * and a selection caused by a slider release must not.
     */
    val isScrollingThroughPages: Boolean

    /**
     * The three resolved theme values, so a vendored `AbstractComposeView` can open its own
     * composition root with `BaseSaikouTheme(host.appTheme, host.isDark, host.isAmoled) { }`.
     *
     * Upstream's `ReaderProgressIndicator` and `ReaderTransitionView` call its no-argument
     * `TachiyomiTheme { }`, which works there only because it reads `UiPreferences` out of Injekt
     * inside the composable. These are Android Views with their own composition roots and cannot
     * inherit a `CompositionLocal` from `ReaderScreen`, so the values travel here instead
     * (ruling R21).
     *
     * [appTheme] and [isAmoled] are the app's. [isDark] is the night mode upstream's
     * `createReaderThemeContext()` gives the holders' context: dark on a black or gray reader
     * background, light on white, the app's resolved mode on automatic. So the transition text and
     * the progress ring read against the reader background whatever the app theme.
     */
    val appTheme: AppTheme
    val isDark: Boolean
    val isAmoled: Boolean

    /**
     * The manga being read, null until the session loads. Added under 2.4's escape clause: both
     * transition holders pass `activity.viewModel.manga` to `ReaderTransitionView.bind`.
     */
    val manga: Manga?

    /** The entry [chapter] belongs to, which is [manga] unless the title is merged (R163). */
    fun ownerOf(chapter: Chapter): Manga? = manga

    /**
     * The downloaded chapters of the session's entries, which the chapter transition marks as upstream's does with
     * its `DownloadManager`. Snapshot state in the reader, so the transition redraws when it changes.
     */
    val downloadedChapterIds: Set<Long> get() = emptySet()

    /** Current preload window (section 3.5); re-read on every use, it changes on trim. */
    fun preloadWindow(): PreloadPolicy.Window

    fun onPageSelected(page: ReaderPage)
    fun onTransitionSelected(transition: ChapterTransition)
    fun requestPreloadChapter(chapter: ReaderChapter)
    fun onPageLongTap(page: ReaderPage)
    fun toggleMenu()
    fun showMenu()
    fun hideMenu()
    fun setNavigationOverlay(navigation: ViewerNavigation, showOnStart: Boolean)

    /** Webtoon theme change: the shell recolours the background; no activity recreation (D10). */
    fun onReaderThemeChanged()

    /**
     * The decoder that enforces the animated pixel cap, or null below API 28 where the platform
     * cannot sample an animated decode (part 1b section 4.8). Re-read on every bind, so a change
     * to the cap preference reaches the next page rather than the next process.
     */
    fun animatedDecoderFactory(): Decoder.Factory?
}

// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonViewer.kt (modified: yes)
package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.graphics.PointF
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.WebtoonLayoutManager
import com.shinsaikou.reader.bench.ReaderBenchHooks
import com.shinsaikou.reader.settings.threshold
import com.shinsaikou.reader.state.firstPartOf
import com.shinsaikou.reader.state.pageCount
import com.shinsaikou.reader.viewer.StripKey
import com.shinsaikou.reader.viewer.ViewerHost
import com.shinsaikou.reader.viewer.stripKey
import com.shinsaikou.reader.viewer.wheelScroll
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import tachiyomi.core.common.util.system.logcat
import kotlin.math.max
import kotlin.math.min

/**
 * Implementation of a [Viewer] to display pages with a [RecyclerView].
 *
 * Modified (H) (I) throughout: `activity` is a [ViewerHost], every View is built with
 * `host.context`, and upstream's dead `downloadManager by injectLazy()` is dropped along with the
 * Injekt read of `readerHideThreshold`. Six behavioural changes:
 *
 *  - The hard-coded five-page chapter preload trigger becomes `host.preloadWindow().chapterPreloadAt`
 *    (section 3.5).
 *  - A theme change re-binds the visible holders instead of recreating the activity (decision D10);
 *    the reader's background is a collected preference and the shell recolours itself.
 *  - [handleGenericMotionEvent] gains the pager's `AXIS_VSCROLL` branch, so a mouse wheel scrolls
 *    the webtoon (decision D9). Upstream returns false here and the wheel does nothing. It scrolls
 *    by the event's delta, as a web page does, so a touchpad's stream of small events is usable.
 *  - [handleKeyEvent] reads `stripKey`: keys act on key down and repeat while held, and Page Up,
 *    Page Down and Space move one viewport (the strip's height on screen, not the display's).
 *  - [trimMemory] and [onSplitInserted] are the two additions modification (V) requires.
 *  - [setChapters] keeps a transition only while "always show chapter transition" is on, not also
 *    while one is the item last selected, so one-page chapters read without a transition between
 *    pages. The adapter's `DiffUtil` update puts the chapter that loaded where its transition was.
 *
 * The first layout and the chapter preload trigger count pages, not tall-strip parts, which sit in
 * the page list after their page with its index. The recycler is told the side padding, as in
 * upstream v0.21.0, so its zoom bounds leave the padding out, and a change in its width re-applies
 * the padding to the pages already bound.
 */
class WebtoonViewer(val host: ViewerHost, val isContinuous: Boolean = true) : Viewer {

    private val scope = MainScope()

    /**
     * Recycler view used by this viewer.
     */
    val recycler = WebtoonRecyclerView(host.context)

    /**
     * Frame containing the recycler view.
     */
    private val frame = WebtoonFrame(host.context)

    /**
     * Distance to scroll when the user taps on one side of the recycler view.
     */
    private val scrollDistance = host.context.resources.displayMetrics.heightPixels * 3 / 4

    /**
     * Layout manager of the recycler view.
     */
    private val layoutManager = WebtoonLayoutManager(host.context, scrollDistance)

    /**
     * Configuration used by this viewer, like allow taps, or crop image borders.
     */
    val config = WebtoonConfig(scope, host.readerPreferences)

    /**
     * Adapter of the recycler view.
     */
    private val adapter = WebtoonAdapter(this)

    /**
     * Currently active item. It can be a chapter page or a chapter transition.
     */
    private var currentPage: Any? = null

    private val threshold: Int = host.readerPreferences.readerHideThreshold.get().threshold

    /** Pixels per unit of a wheel or touchpad scroll, the platform's own. */
    private val wheelScrollFactor = ViewConfiguration.get(host.context).scaledVerticalScrollFactor

    init {
        recycler.sidePaddingPercent = config.sidePadding
        recycler.setItemViewCacheSize(RECYCLER_VIEW_CACHE_SIZE)
        recycler.isVisible = false // Don't let the recycler layout yet
        recycler.layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        recycler.isFocusable = false
        recycler.itemAnimator = null
        recycler.layoutManager = layoutManager
        recycler.adapter = adapter
        recycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    onScrolled()

                    if ((dy > threshold || dy < -threshold) && host.menuVisible) {
                        host.hideMenu()
                    }

                    if (dy < 0) {
                        val firstIndex = layoutManager.findFirstVisibleItemPosition()
                        val firstItem = adapter.items.getOrNull(firstIndex)
                        if (firstItem is ChapterTransition.Prev && firstItem.to != null) {
                            host.requestPreloadChapter(firstItem.to)
                        }
                    }

                    val lastIndex = layoutManager.findLastEndVisibleItemPosition()
                    val lastItem = adapter.items.getOrNull(lastIndex)
                    if (lastItem is ChapterTransition.Next && lastItem.to == null) {
                        host.showMenu()
                    }
                }
            },
        )
        recycler.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            // The side padding is a share of the width, and the pages already bound took theirs
            // from the old one: a rotation or a split-screen resize keeps this viewer.
            val width = right - left
            val oldWidth = oldRight - oldLeft
            if (width != oldWidth && oldWidth != 0 && recycler.sidePaddingPercent != 0) {
                recycler.post { adapter.notifyItemRangeChanged(0, adapter.itemCount, WebtoonAdapter.SIDE_PADDING) }
            }
        }
        recycler.tapListener = { event ->
            val viewPosition = IntArray(2)
            recycler.getLocationOnScreen(viewPosition)
            val viewPositionRelativeToWindow = IntArray(2)
            recycler.getLocationInWindow(viewPositionRelativeToWindow)
            val pos = PointF(
                (event.rawX - viewPosition[0] + viewPositionRelativeToWindow[0]) / recycler.width,
                (event.rawY - viewPosition[1] + viewPositionRelativeToWindow[1]) / recycler.originalHeight,
            )
            when (config.navigator.getAction(pos)) {
                NavigationRegion.MENU -> host.toggleMenu()
                NavigationRegion.NEXT, NavigationRegion.RIGHT -> scrollDown()
                NavigationRegion.PREV, NavigationRegion.LEFT -> scrollUp()
            }
        }
        recycler.longTapListener = f@{ event ->
            if (host.menuVisible || config.longTapEnabled) {
                val child = recycler.findChildViewUnder(event.x, event.y)
                if (child != null) {
                    val position = recycler.getChildAdapterPosition(child)
                    val item = adapter.items.getOrNull(position)
                    if (item is ReaderPage) {
                        host.onPageLongTap(item)
                        return@f true
                    }
                }
            }
            false
        }

        config.imagePropertyChangedListener = {
            recycler.sidePaddingPercent = config.sidePadding
            refreshAdapter()
        }

        config.themeChangedListener = {
            host.onReaderThemeChanged()
            refreshAdapter()
        }

        config.doubleTapZoomChangedListener = {
            frame.doubleTapZoom = it
        }

        config.zoomPropertyChangedListener = {
            frame.zoomOutDisabled = it
        }

        config.navigationModeChangedListener = {
            val showOnStart = config.navigationOverlayOnStart || config.forceNavigationOverlay
            host.setNavigationOverlay(config.navigator, showOnStart)
        }

        frame.layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        frame.addView(recycler)
    }

    private fun checkAllowPreload(page: ReaderPage?): Boolean {
        // Page is transition page - preload allowed
        page ?: return true

        // Initial opening - preload allowed
        currentPage ?: return true

        val nextItem = adapter.items.getOrNull(adapter.items.size - 1)
        val nextChapter = (nextItem as? ChapterTransition.Next)?.to ?: (nextItem as? ReaderPage)?.chapter

        // Allow preload for
        // 1. Going between pages of same chapter
        // 2. Next chapter page
        return when (page.chapter) {
            (currentPage as? ReaderPage)?.chapter -> true
            nextChapter -> true
            else -> false
        }
    }

    /**
     * Returns the view this viewer uses.
     */
    override fun getView(): View {
        return frame
    }

    /**
     * Destroys this viewer. Called when leaving the reader or swapping viewers.
     */
    override fun destroy() {
        super.destroy()
        scope.cancel()
    }

    /**
     * Called from the RecyclerView listener when a [page] is marked as active. It notifies the
     * activity of the change and requests the preload of the next chapter if this is the last page.
     */
    private fun onPageSelected(page: ReaderPage, allowPreload: Boolean) {
        val pages = page.chapter.pages ?: return
        logcat { "onPageSelected: ${page.number}/${pages.size}" }
        host.onPageSelected(page)

        // Preload the next chapter once we are inside the session's chapter-preload window.
        val inPreloadRange = pages.pageCount() - page.number < host.preloadWindow().chapterPreloadAt
        if (inPreloadRange && allowPreload && page.chapter == adapter.currentChapter) {
            logcat { "Request preload next chapter because we're at page ${page.number} of ${pages.size}" }
            val nextItem = adapter.items.getOrNull(adapter.items.size - 1)
            val transitionChapter = (nextItem as? ChapterTransition.Next)?.to ?: (nextItem as?ReaderPage)?.chapter
            if (transitionChapter != null) {
                logcat { "Requesting to preload chapter ${transitionChapter.chapter.chapterNumber}" }
                host.requestPreloadChapter(transitionChapter)
            }
        }
    }

    /**
     * Called from the RecyclerView listener when a [transition] is marked as active. It request the
     * preload of the destination chapter of the transition.
     */
    private fun onTransitionSelected(transition: ChapterTransition) {
        logcat { "onTransitionSelected: $transition" }
        val toChapter = transition.to
        if (toChapter != null) {
            logcat { "Request preload destination chapter because we're on the transition" }
            host.requestPreloadChapter(toChapter)
        }
    }

    /**
     * Tells this viewer to set the given [chapters] as active.
     */
    override fun setChapters(chapters: ViewerChapters) {
        ReaderBenchHooks.trace(ReaderBenchHooks.SECTION_SET_CHAPTERS) {
            adapter.setChapters(chapters, config.alwaysShowChapterTransition)

            if (recycler.isGone) {
                logcat { "Recycler first layout" }
                val pages = chapters.currChapter.pages ?: return
                moveToPage(pages.firstPartOf(chapters.currChapter.requestedPage))
                recycler.isVisible = true
            }
        }
    }

    /**
     * Tells this viewer to move to the given [page].
     */
    override fun moveToPage(page: ReaderPage) {
        ReaderBenchHooks.trace(ReaderBenchHooks.SECTION_MOVE_TO_PAGE) {
            val position = adapter.items.indexOf(page)
            if (position != -1) {
                layoutManager.scrollToPositionWithOffset(position, 0)
                if (layoutManager.findLastEndVisibleItemPosition() == -1) {
                    onScrolled(pos = position)
                }
            } else {
                logcat { "Page ${page.number} not found in adapter" }
            }
        }
    }

    /**
     * Fresh (V). Drops the view pool and recycles every holder outside the preload window, which is
     * what actually returns the decoded tiles of a long strip.
     */
    override fun trimMemory() {
        ReaderBenchHooks.trace(ReaderBenchHooks.SECTION_TRIM) {
            val window = host.preloadWindow()
            val first = layoutManager.findFirstVisibleItemPosition()
            val last = layoutManager.findLastEndVisibleItemPosition()
            recycler.recycledViewPool.clear()
            if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) return@trace
            val keep = (first - window.decodeBehind)..(last + window.decodeAhead)
            for (position in 0 until adapter.itemCount) {
                if (position in keep) continue
                val holder = recycler.findViewHolderForAdapterPosition(position) as? WebtoonBaseHolder
                holder?.recycle()
            }
        }
    }

    /**
     * Fresh (V). Tall-strip parts were inserted into [page]'s chapter; re-setting the chapters lets
     * the adapter's `DiffUtil` put them in place without disturbing the scroll position.
     */
    override fun onSplitInserted(page: ReaderPage) {
        val chapters = adapter.lastChapters ?: return
        adapter.setChapters(chapters, forceTransition = false)
    }

    fun onScrolled(pos: Int? = null) {
        val position = pos ?: layoutManager.findLastEndVisibleItemPosition()
        val item = adapter.items.getOrNull(position)
        val allowPreload = checkAllowPreload(item as? ReaderPage)
        if (item != null && currentPage != item) {
            currentPage = item
            when (item) {
                is ReaderPage -> onPageSelected(item, allowPreload)
                is ChapterTransition -> onTransitionSelected(item)
            }
        }
    }

    /**
     * Scrolls up by [scrollDistance].
     */
    private fun scrollUp() = scrollBy(-scrollDistance)

    /**
     * Scrolls down by [scrollDistance].
     */
    private fun scrollDown() = scrollBy(scrollDistance)

    private fun scrollBy(dy: Int) {
        if (config.usePageTransitions) {
            recycler.smoothScrollBy(0, dy)
        } else {
            recycler.scrollBy(0, dy)
        }
    }

    /**
     * Called from the containing activity when a key [event] is received. It should return true
     * if the event was handled, false otherwise.
     */
    override fun handleKeyEvent(event: KeyEvent): Boolean {
        val key = stripKey(
            keyCode = event.keyCode,
            action = event.action,
            shift = event.isShiftPressed,
            volumeKeys = config.volumeKeysEnabled,
            volumeKeysInverted = config.volumeKeysInverted,
            menuVisible = host.menuVisible,
        ) ?: return false
        when (key) {
            StripKey.STEP_BACK -> scrollUp()
            StripKey.STEP_FORWARD -> scrollDown()
            StripKey.PAGE_BACK -> scrollBy(-recycler.visibleHeight)
            StripKey.PAGE_FORWARD -> scrollBy(recycler.visibleHeight)
            StripKey.MENU -> host.toggleMenu()
            StripKey.NOTHING -> Unit
        }
        return true
    }

    /**
     * Called from the containing activity when a generic motion [event] is received. It should
     * return true if the event was handled, false otherwise.
     */
    override fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_CLASS_POINTER != 0 &&
            event.action == MotionEvent.ACTION_SCROLL
        ) {
            val dy = wheelScroll(event.getAxisValue(MotionEvent.AXIS_VSCROLL), wheelScrollFactor)
            if (dy != 0) recycler.scrollBy(0, dy)
            return true
        }
        return false
    }

    /**
     * Notifies adapter of changes around the current page to trigger a relayout in the recycler.
     * Used when an image configuration is changed.
     */
    private fun refreshAdapter() {
        val position = layoutManager.findLastEndVisibleItemPosition()
        adapter.notifyItemRangeChanged(
            max(0, position - 3),
            min(position + 3, adapter.itemCount - 1),
        )
    }
}

// Double the cache size to reduce rebinds/recycles incurred by the extra layout space on scroll direction changes
private const val RECYCLER_VIEW_CACHE_SIZE = 4

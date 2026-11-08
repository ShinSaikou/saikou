// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerViewer.kt (modified: yes) departures in the comment, the latest the Experimental two-page view
package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.content.res.Configuration
import android.graphics.PointF
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams
import androidx.core.view.children
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.viewpager.widget.ViewPager
import com.shinsaikou.domain.manga.model.ReadingMode
import com.shinsaikou.reader.R
import com.shinsaikou.reader.bench.ReaderBenchHooks
import com.shinsaikou.reader.state.firstPartOf
import com.shinsaikou.reader.state.pageCount
import com.shinsaikou.reader.viewer.PageSpread
import com.shinsaikou.reader.viewer.SpreadShape
import com.shinsaikou.reader.viewer.ViewerHost
import com.shinsaikou.reader.viewer.holdsPage
import com.shinsaikou.reader.viewer.leadPage
import com.shinsaikou.reader.viewer.pageAfterRemovedTransition
import com.shinsaikou.reader.viewer.positionShowing
import com.shinsaikou.reader.viewer.twoPagesShown
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion
import eu.kanade.tachiyomi.widget.ViewPagerAdapter.PositionableView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.system.logcat
import java.util.WeakHashMap

/**
 * Implementation of a [Viewer] to display pages with a [ViewPager].
 *
 * Modified (H) throughout: `activity` is a [ViewerHost]. Also (I): upstream's
 * `downloadManager by injectLazy()` was already dead in v0.20.4 and is dropped. The hard-coded
 * five-page chapter preload trigger becomes `host.preloadWindow().chapterPreloadAt` (section 3.5),
 * and [trimMemory] and [onSplitInserted] are the two additions modification (V) requires.
 * Also (M): [setChaptersInternal] keeps the pager on the item it showed when the adapter's update
 * moves that item and the pager does not follow ([keepShownItem], ruling R79).
 * Also: the first layout and the chapter preload trigger count pages, not tall-strip parts, which
 * sit in the page list after their page with its index.
 * Also (T): a transition is kept only while "always show chapter transition" is on, not also while
 * it is the item shown, and the pager lands past one the update removed
 * ([pageAfterRemovedTransition]), so one-page chapters read without a transition between pages.
 * Also the Experimental two-page view (ruling R165 item 2): while [showsTwoPages], the left to right
 * and right to left pagers show the pages of a chapter in pairs, each pair one [PageSpread] item that
 * [com.shinsaikou.reader.viewer.PagerSpreadHolder] draws as one picture. Every turn, tap, key and
 * wheel step moves a whole item, so a whole spread; a spread reports both its pages
 * ([ViewerHost.onSpreadSelected]); [moveToPage] goes to the spread holding the page; and a change of
 * layout, of shift or of a page found wide keeps the page on screen in view ([keepShownPage]).
 */
@Suppress("LeakingThis")
abstract class PagerViewer(val host: ViewerHost) : Viewer {

    private val scope = MainScope()

    /**
     * View pager used by this viewer. It's abstract to implement L2R, R2L and vertical pagers on
     * top of this class.
     */
    val pager = createPager()

    /**
     * Configuration used by the pager, like allow taps, scale mode on images, page transitions...
     */
    val config = PagerConfig(this, scope, host.readerPreferences)

    /**
     * Fresh. Whether this viewer shows two pages side by side now: the Experimental switch, the
     * layout setting and the screen's orientation, read again on every change of the three.
     */
    var showsTwoPages = computeShowsTwoPages()
        private set

    /** Fresh. Whether the adapter's items were built for two pages, so a change rebuilds every page view. */
    private var itemsShowTwoPages = showsTwoPages

    /**
     * Fresh. Pages a spread found wide when it opened their images, before anything else knew their
     * size (a downloaded page has no route until a holder asks for it). Weak, so a chapter that
     * leaves the window takes its pages with it.
     */
    private val measuredShapes = WeakHashMap<ReaderPage, SpreadShape>()

    /**
     * Adapter of the pager.
     */
    private val adapter = PagerViewerAdapter(this)

    /**
     * Currently active item. It can be a chapter page or a chapter transition.
     */
    private var currentPage: Any? = null

    /**
     * Viewer chapters to set when the pager enters idle mode. Otherwise, if the view was settling
     * or dragging, there'd be a noticeable and annoying jump.
     */
    private var awaitingIdleViewerChapters: ViewerChapters? = null

    /**
     * Whether the view pager is currently in idle mode. It sets the awaiting chapters if setting
     * this field to true.
     */
    private var isIdle = true
        set(value) {
            field = value
            if (value) {
                awaitingIdleViewerChapters?.let { viewerChapters ->
                    setChaptersInternal(viewerChapters)
                    awaitingIdleViewerChapters = null
                    if (viewerChapters.currChapter.pages?.size == 1) {
                        adapter.nextTransition?.to?.let(host::requestPreloadChapter)
                    }
                }
            }
        }

    private val pagerListener = object : ViewPager.SimpleOnPageChangeListener() {
        override fun onPageSelected(position: Int) {
            if (!host.isScrollingThroughPages) {
                host.hideMenu()
            }
            onPageChange(position)
        }

        override fun onPageScrollStateChanged(state: Int) {
            isIdle = state == ViewPager.SCROLL_STATE_IDLE
        }
    }

    init {
        pager.isVisible = false // Don't layout the pager yet
        pager.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        pager.isFocusable = false
        pager.offscreenPageLimit = 1
        pager.id = R.id.reader_pager
        pager.adapter = adapter
        pager.addOnPageChangeListener(pagerListener)
        pager.tapListener = { event ->
            val viewPosition = IntArray(2)
            pager.getLocationOnScreen(viewPosition)
            val viewPositionRelativeToWindow = IntArray(2)
            pager.getLocationInWindow(viewPositionRelativeToWindow)
            val pos = PointF(
                (event.rawX - viewPosition[0] + viewPositionRelativeToWindow[0]) / pager.width,
                (event.rawY - viewPosition[1] + viewPositionRelativeToWindow[1]) / pager.height,
            )
            when (config.navigator.getAction(pos)) {
                NavigationRegion.MENU -> host.toggleMenu()
                NavigationRegion.NEXT -> moveToNext()
                NavigationRegion.PREV -> moveToPrevious()
                NavigationRegion.RIGHT -> moveRight()
                NavigationRegion.LEFT -> moveLeft()
            }
        }
        pager.longTapListener = f@{ event ->
            if (host.menuVisible || config.longTapEnabled) {
                // A spread's sheet is for the page under the finger, the half of the screen it shows.
                val item = when (val shown = adapter.items.getOrNull(pager.currentItem)) {
                    is PageSpread -> shown.onScreen(this is R2LPagerViewer)[if (event.x < pager.width / 2f) 0 else 1]
                    else -> shown
                }
                if (item is ReaderPage) {
                    host.onPageLongTap(item)
                    return@f true
                }
            }
            false
        }

        config.dualPageSplitChangedListener = { enabled ->
            if (!enabled) {
                cleanupPageSplit()
            }
        }

        config.imagePropertyChangedListener = {
            refreshAdapter()
        }

        config.navigationModeChangedListener = {
            val showOnStart = config.navigationOverlayOnStart || config.forceNavigationOverlay
            host.setNavigationOverlay(config.navigator, showOnStart)
        }

        config.twoPagesChangedListener = { updateTwoPages() }

        // The reader keeps its viewer through a rotation, so "In landscape" follows the new size.
        pager.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                pager.post { updateTwoPages() }
            }
        }
    }

    override fun destroy() {
        super.destroy()
        scope.cancel()
    }

    /**
     * Creates a new ViewPager.
     */
    abstract fun createPager(): Pager

    /**
     * Returns the view this viewer uses.
     */
    override fun getView(): View {
        return pager
    }

    /**
     * Returns the holder of the provided item: a [PagerPageHolder] for a page, a
     * [PagerSpreadHolder] for a spread.
     */
    private fun getPageHolder(item: Any): ReaderPageImageView? =
        pager.children
            .filterIsInstance(ReaderPageImageView::class.java)
            .firstOrNull { (it as? PositionableView)?.item == item }

    /**
     * Called when a new page (either a [ReaderPage] or [ChapterTransition]) is marked as active
     */
    private fun onPageChange(position: Int) {
        val item = adapter.items.getOrNull(position)
        if (item != null && currentPage != item) {
            // A spread stands for its earlier page here, which is the one its progress is saved as.
            val page = (item as? PageSpread)?.first ?: item
            val shownPage = (currentPage as? PageSpread)?.first ?: currentPage
            val allowPreload = checkAllowPreload(page as? ReaderPage)
            val forward = when {
                shownPage is ReaderPage && page is ReaderPage -> {
                    // if both pages have the same number, it's a split page with an InsertPage
                    if (page.number == shownPage.number) {
                        // the InsertPage is always the second in the reading direction
                        page is InsertPage
                    } else {
                        page.number > shownPage.number
                    }
                }
                shownPage is ChapterTransition.Prev && page is ReaderPage ->
                    false
                else -> true
            }
            currentPage = item
            when (page) {
                is ReaderPage -> onReaderPageSelected(item, page, allowPreload, forward)
                is ChapterTransition -> onTransitionSelected(page)
            }
        }
    }

    private fun checkAllowPreload(page: ReaderPage?): Boolean {
        // Page is transition page - preload allowed
        page ?: return true

        // Initial opening - preload allowed
        val shown = currentPage ?: return true

        // Allow preload for
        // 1. Going to next chapter from chapter transition
        // 2. Going between pages of same chapter
        // 3. Next chapter page
        return when (page.chapter) {
            (shown as? ChapterTransition.Next)?.to -> true
            leadPage(shown)?.chapter -> true
            adapter.nextTransition?.to -> true
            else -> false
        }
    }

    /**
     * Called when a [ReaderPage] is marked as active. It notifies the
     * activity of the change and requests the preload of the next chapter if this is the last page.
     * [item] is [page] itself, or the spread whose earlier page it is.
     */
    private fun onReaderPageSelected(item: Any, page: ReaderPage, allowPreload: Boolean, forward: Boolean) {
        val pages = page.chapter.pages ?: return
        logcat { "onReaderPageSelected: ${page.number}/${pages.size}" }
        val last = (item as? PageSpread)?.second
        if (last != null) host.onSpreadSelected(page, last) else host.onPageSelected(page)

        // Notify holder of page change
        getPageHolder(item)?.onPageSelected(forward)

        // Skip preload on inserts it causes unwanted page jumping
        if (page is InsertPage) {
            return
        }

        // Preload the next chapter once we are inside the session's chapter-preload window.
        val inPreloadRange = pages.pageCount() - (last ?: page).number < host.preloadWindow().chapterPreloadAt
        if (inPreloadRange && allowPreload && page.chapter == adapter.currentChapter) {
            logcat { "Request preload next chapter because we're at page ${page.number} of ${pages.size}" }
            adapter.nextTransition?.to?.let(host::requestPreloadChapter)
        }
    }

    /**
     * Called when a [ChapterTransition] is marked as active. It request the
     * preload of the destination chapter of the transition.
     */
    private fun onTransitionSelected(transition: ChapterTransition) {
        logcat { "onTransitionSelected: $transition" }
        val toChapter = transition.to
        if (toChapter != null) {
            logcat { "Request preload destination chapter because we're on the transition" }
            host.requestPreloadChapter(toChapter)
        } else if (transition is ChapterTransition.Next) {
            // No more chapters, show menu because the user is probably going to close the reader
            host.showMenu()
        }
    }

    /**
     * Tells this viewer to set the given [chapters] as active. If the pager is currently idle,
     * it sets the chapters immediately, otherwise they are saved and set when it becomes idle.
     */
    override fun setChapters(chapters: ViewerChapters) {
        if (isIdle) {
            setChaptersInternal(chapters)
        } else {
            awaitingIdleViewerChapters = chapters
        }
    }

    /**
     * Sets the active [chapters] on this pager.
     */
    private fun setChaptersInternal(chapters: ViewerChapters) {
        ReaderBenchHooks.trace(ReaderBenchHooks.SECTION_SET_CHAPTERS) {
            // Remove listener so the change in item doesn't trigger it
            pager.removeOnPageChangeListener(pagerListener)

            val shownIndex = pager.currentItem
            val shown = adapter.items.getOrNull(shownIndex)
            adapter.setChapters(chapters, config.alwaysShowChapterTransition)
            // The two-page view turned on or off: every page view is built anew, so split and rotate
            // apply to a page alone again, or stop applying while it shares the screen.
            val layoutChanged = itemsShowTwoPages != showsTwoPages
            if (layoutChanged) {
                itemsShowTwoPages = showsTwoPages
                pager.adapter = adapter
            }
            val landing = pageAfterRemovedTransition(shown, adapter.items, chapters)
            if (landing != null) {
                pager.setCurrentItem(adapter.items.positionShowing(landing), false)
            } else if (showsTwoPages || layoutChanged) {
                keepShownPage(shown)
            } else {
                keepShownItem(shownIndex, shown)
            }

            // Layout the pager once a chapter is being set
            if (pager.isGone) {
                logcat { "Pager first layout" }
                val pages = chapters.currChapter.pages ?: return
                moveToPage(pages.firstPartOf(chapters.currChapter.requestedPage))
                pager.isVisible = true
            }

            pager.addOnPageChangeListener(pagerListener)
            // Manually call onPageChange to update the UI
            onPageChange(pager.currentItem)
        }
    }

    /**
     * Puts the pager back on [shown] when the adapter's update left the pager at [shownIndex] but
     * put another item there. Once the pager has built its page views it follows the item itself,
     * through `getItemPosition`, and its index changes; before its first layout pass it has none to
     * follow, so it keeps the index. The right to left pager reverses its list, so a next chapter
     * loaded in that window goes in front of the page shown and the kept index lands on the next
     * chapter's first page: a short chapter whose next chapter loads at once (both downloaded) opened
     * on the next chapter. Upstream v0.20.4 has the same window. A move made inside the update (the
     * split page's `moveToPage`) changes the index, so it is left alone.
     */
    private fun keepShownItem(shownIndex: Int, shown: Any?) {
        if (shown == null || pager.currentItem != shownIndex) return
        if (adapter.items.getOrNull(shownIndex) === shown) return
        val index = adapter.items.indexOf(shown)
        if (index != -1) pager.setCurrentItem(index, false)
    }

    /**
     * Fresh. Keeps the page that was on screen in view when the items are built again with spreads
     * in them: the pairing changed (the two-page view turned on or off, the screen turned, Shift, a
     * page found wide), so the item that showed it may be gone. The pager goes to the item that
     * shows the same page now, a spread's earlier page standing for the spread. A transition is
     * found again by itself.
     */
    private fun keepShownPage(shown: Any?) {
        shown ?: return
        val current = adapter.items.getOrNull(pager.currentItem)
        val lead = leadPage(shown)
        val index = if (lead != null) {
            if (holdsPage(current, lead)) return
            adapter.items.positionShowing(lead)
        } else {
            if (current == shown) return
            adapter.items.indexOf(shown)
        }
        if (index != -1) pager.setCurrentItem(index, false)
    }

    /**
     * Tells this viewer to move to the given [page]: the spread that holds it, while two pages
     * show.
     */
    override fun moveToPage(page: ReaderPage) {
        ReaderBenchHooks.trace(ReaderBenchHooks.SECTION_MOVE_TO_PAGE) {
            val position = adapter.items.positionShowing(page)
            if (position != -1) {
                val currentPosition = pager.currentItem
                pager.setCurrentItem(position, true)
                // manually call onPageChange since ViewPager listener is not triggered in this case
                if (currentPosition == position) {
                    onPageChange(position)
                }
            } else {
                logcat { "Page ${page.number} not found in adapter" }
            }
        }
    }

    /**
     * Moves to the next page.
     */
    open fun moveToNext() {
        moveRight()
    }

    /**
     * Moves to the previous page.
     */
    open fun moveToPrevious() {
        moveLeft()
    }

    /**
     * Moves to the page at the right.
     */
    protected open fun moveRight() {
        if (pager.currentItem != adapter.count - 1) {
            val holder = currentPage?.takeIf { it is ReaderPage || it is PageSpread }?.let(::getPageHolder)
            if (holder != null && config.navigateToPan && holder.canPanRight()) {
                holder.panRight()
            } else {
                pager.setCurrentItem(pager.currentItem + 1, config.usePageTransitions)
            }
        }
    }

    /**
     * Moves to the page at the left.
     */
    protected open fun moveLeft() {
        if (pager.currentItem != 0) {
            val holder = currentPage?.takeIf { it is ReaderPage || it is PageSpread }?.let(::getPageHolder)
            if (holder != null && config.navigateToPan && holder.canPanLeft()) {
                holder.panLeft()
            } else {
                pager.setCurrentItem(pager.currentItem - 1, config.usePageTransitions)
            }
        }
    }

    /**
     * Moves to the page at the top (or previous).
     */
    protected open fun moveUp() {
        moveToPrevious()
    }

    /**
     * Moves to the page at the bottom (or next).
     */
    protected open fun moveDown() {
        moveToNext()
    }

    /**
     * Resets the adapter in order to recreate all the views. Used when a image configuration is
     * changed.
     */
    private fun refreshAdapter() {
        val currentItem = pager.currentItem
        pager.adapter = adapter
        pager.setCurrentItem(currentItem, false)
    }

    /**
     * Called from the containing activity when a key [event] is received. It should return true
     * if the event was handled, false otherwise.
     */
    override fun handleKeyEvent(event: KeyEvent): Boolean {
        val isUp = event.action == KeyEvent.ACTION_UP
        val ctrlPressed = event.metaState.and(KeyEvent.META_CTRL_ON) > 0

        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (!config.volumeKeysEnabled || host.menuVisible) {
                    return false
                } else if (isUp) {
                    if (!config.volumeKeysInverted) moveDown() else moveUp()
                }
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (!config.volumeKeysEnabled || host.menuVisible) {
                    return false
                } else if (isUp) {
                    if (!config.volumeKeysInverted) moveUp() else moveDown()
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (isUp) {
                    if (ctrlPressed) moveToNext() else moveRight()
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (isUp) {
                    if (ctrlPressed) moveToPrevious() else moveLeft()
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (isUp) moveDown()
            KeyEvent.KEYCODE_DPAD_UP -> if (isUp) moveUp()
            KeyEvent.KEYCODE_PAGE_DOWN -> if (isUp) moveDown()
            KeyEvent.KEYCODE_PAGE_UP -> if (isUp) moveUp()
            KeyEvent.KEYCODE_MENU -> if (isUp) host.toggleMenu()
            else -> return false
        }
        return true
    }

    /**
     * Called from the containing activity when a generic motion [event] is received. It should
     * return true if the event was handled, false otherwise.
     */
    override fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_CLASS_POINTER != 0) {
            when (event.action) {
                MotionEvent.ACTION_SCROLL -> {
                    if (event.getAxisValue(MotionEvent.AXIS_VSCROLL) < 0.0f) {
                        moveDown()
                    } else {
                        moveUp()
                    }
                    return true
                }
            }
        }
        return false
    }

    fun onPageSplit(currentPage: ReaderPage, newPage: InsertPage) {
        // Still has to run on the main thread or the inserted images go blank; `host.scope` is the
        // composition's scope, which is main-dispatched.
        scope.launch {
            // A split that finished after split was turned off would put a half back, and so would
            // one that finished as two pages came to share the screen.
            if (config.dualPageSplit && !showsTwoPages) adapter.onPageSplit(currentPage, newPage)
        }
    }

    /**
     * Fresh (V). Recycles every bound holder but the current item. `offscreenPageLimit = 1` already
     * bounds residency; this is what gives the tiles back when the window degrades.
     */
    override fun trimMemory() {
        ReaderBenchHooks.trace(ReaderBenchHooks.SECTION_TRIM) {
            pager.children
                .filterIsInstance(ReaderPageImageView::class.java)
                .filter { (it as? PositionableView)?.item != currentPage }
                .toList()
                .forEach { it.recycle() }
        }
    }

    /**
     * Fresh (V). Tall-strip parts were inserted into [page]'s chapter. Re-setting the chapters is
     * what makes the adapter see them, and it may only happen while the pager is idle: doing it
     * mid-settle is the jump `awaitingIdleViewerChapters` exists to avoid.
     *
     * It goes through [setChapters] like any other update, so the "always show chapter transition"
     * setting holds and the pager stays on the item it showed. A window already waiting for idle
     * is the newer one and is kept: the parts are on the chapter itself, so it carries them too.
     * The idle path used to set the adapter directly with the transitions off, and a split that
     * finished while the pager settled into the next chapter replaced that chapter's window with
     * the one before it, so the viewer stayed a chapter behind the reader.
     */
    override fun onSplitInserted(page: ReaderPage) {
        val chapters = awaitingIdleViewerChapters ?: adapter.lastChapters ?: return
        setChapters(chapters)
    }

    private fun cleanupPageSplit() {
        adapter.cleanupPageSplit()
    }

    /** Fresh. Whether the entry's pages pair one page later, as Shift pages sets it. */
    val shiftsPages: Boolean
        get() = host.manga?.id?.toString() in config.twoPagesShifted

    /** Fresh. What a spread found [page] to be when it opened its image, if it opened one. */
    fun measuredShape(page: ReaderPage): SpreadShape? = measuredShapes[page]

    /**
     * Fresh. A spread opened [page]'s image and found it wide, or found no decoder for it, before
     * its route said so. The page stands alone from now on and the pairing after it is laid out
     * again, keeping the page on screen in view.
     */
    fun onSpreadPageWide(page: ReaderPage) {
        if (measuredShapes[page] == SpreadShape.WIDE) return
        measuredShapes[page] = SpreadShape.WIDE
        val chapters = awaitingIdleViewerChapters ?: adapter.lastChapters ?: return
        setChapters(chapters)
    }

    /**
     * Fresh. The switch, the layout, the shifted entries or the screen's size changed. While two
     * pages show, or when they start or stop showing, the items are built again (the page on screen
     * stays in view); otherwise nothing changes.
     */
    private fun updateTwoPages() {
        val showed = showsTwoPages
        showsTwoPages = computeShowsTwoPages()
        if (!showed && !showsTwoPages) return
        val chapters = awaitingIdleViewerChapters ?: adapter.lastChapters ?: return
        setChapters(chapters)
    }

    private fun computeShowsTwoPages(): Boolean = twoPagesShown(
        enabled = config.twoPagesEnabled,
        layout = config.twoPageLayout,
        mode = when (this) {
            is L2RPagerViewer -> ReadingMode.LEFT_TO_RIGHT
            is R2LPagerViewer -> ReadingMode.RIGHT_TO_LEFT
            else -> ReadingMode.VERTICAL
        },
        landscape = host.context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
    )
}

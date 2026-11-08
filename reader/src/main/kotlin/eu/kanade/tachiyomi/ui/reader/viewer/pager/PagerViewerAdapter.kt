// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerViewerAdapter.kt (modified: yes) departures in the comment, the latest the Experimental two-page view
package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.view.View
import android.view.ViewGroup
import com.shinsaikou.reader.viewer.PageSpread
import com.shinsaikou.reader.viewer.PagerSpreadHolder
import com.shinsaikou.reader.viewer.spreadItems
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.viewer.calculateChapterGap
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import tachiyomi.core.common.util.system.logcat

/**
 * Pager adapter used by this [viewer] to where [ViewerChapters] updates are posted.
 *
 * Modified (H). Upstream builds its holders with `viewer.activity.createReaderThemeContext()`, an
 * extension in its `app/` module that wraps the activity with an overridden `Configuration.uiMode`
 * and reads `UiPreferences` out of Injekt. `ViewerHost.context` is already the activity themed with
 * `Theme.Saikou.Reader`, so both call sites collapse to it and the extension is never vendored
 * (ruling R21 and `REMAINING.md` section 3 defect 1). The night mode that wrapper set from the
 * reader background travels as `ViewerHost.isDark` instead, which the holders' Compose views read.
 * `refresh()` goes with it: it existed only to rebuild that wrapped context, and
 * `PagerViewer.refreshAdapter` recreates every view anyway.
 *
 * Also modified: [lastChapters] is remembered so `PagerViewer.onSplitInserted` can re-run
 * `setChapters` with the same window when tall-strip parts are inserted.
 *
 * Also: upstream v0.21.1's fix for wide pages split in a chapter next to the current one
 * (3156b4a1c, "Insert split pages only into the chapter they belong to"). [preprocessed] held
 * those halves by page index alone, so one could be inserted into the current chapter at that
 * index, or a second chapter's half replaced the first's, and the pager crashed. They are kept with
 * their chapter now, inserted only into their own, and kept only while that chapter is adjacent.
 * Turning split off drops them with the halves shown ([cleanupPageSplit]).
 *
 * Also the Experimental two-page view (ruling R165 item 2): while `viewer.showsTwoPages`, each
 * chapter's pages go in as [spreadItems] lays them out, a pair as one [PageSpread] that a
 * [PagerSpreadHolder] draws, and no split half goes in.
 */
class PagerViewerAdapter(private val viewer: PagerViewer) : ViewPagerAdapter() {

    /**
     * List of currently set items.
     */
    var items: MutableList<Any> = mutableListOf()
        private set

    /**
     * Holds preprocessed items so they don't get removed when changing chapter
     */
    private var preprocessed: List<InsertPage> = emptyList()

    var nextTransition: ChapterTransition.Next? = null
        private set

    var currentChapter: ReaderChapter? = null

    /** The last window set on this adapter, so a split insertion can re-issue it. */
    var lastChapters: ViewerChapters? = null
        private set

    private val readerThemedContext = viewer.host.context

    /**
     * Updates this adapter with the given [chapters]. It handles setting a few pages of the
     * next/previous chapter to allow seamless transitions and inverting the pages if the viewer
     * has R2L direction.
     */
    fun setChapters(chapters: ViewerChapters, forceTransition: Boolean) {
        lastChapters = chapters
        val newItems = mutableListOf<Any>()

        // Forces chapter transition if there is missing chapters
        val prevHasMissingChapters = calculateChapterGap(chapters.currChapter, chapters.prevChapter) > 0
        val nextHasMissingChapters = calculateChapterGap(chapters.nextChapter, chapters.currChapter) > 0

        // Add previous chapter pages and transition
        chapters.prevChapter?.pages?.let { newItems.addAll(itemsOf(it)) }

        // Skip transition page if the chapter is loaded & current page is not a transition page
        if (prevHasMissingChapters || forceTransition || chapters.prevChapter?.state !is ReaderChapter.State.Loaded) {
            newItems.add(ChapterTransition.Prev(chapters.currChapter, chapters.prevChapter))
        }

        var insertPageLastPage: InsertPage? = null

        // Add current chapter.
        val currPages = chapters.currChapter.pages
        if (currPages != null && viewer.showsTwoPages) {
            newItems.addAll(itemsOf(currPages))
        } else if (currPages != null) {
            val pages = currPages.toMutableList()

            val lastPage = pages.last()

            // Insert preprocessed pages into current page list, only this chapter's (upstream
            // 3156b4a1c). Each goes after the last item of its page, which with tall-strip parts in
            // the list is not the page's index, as upstream assumes.
            preprocessed.filter { it.chapter.chapter.id == chapters.currChapter.chapter.id }
                .sortedByDescending { it.index }
                .forEach { page ->
                    if (lastPage.index == page.index) {
                        insertPageLastPage = page
                    }
                    val after = pages.indexOfLast { it.index == page.index }
                    if (after >= 0) pages.add(after + 1, page)
                }

            newItems.addAll(pages)
        }

        currentChapter = chapters.currChapter

        // Add next chapter transition and pages.
        nextTransition = ChapterTransition.Next(chapters.currChapter, chapters.nextChapter)
            .also {
                if (
                    nextHasMissingChapters ||
                    forceTransition ||
                    chapters.nextChapter?.state !is ReaderChapter.State.Loaded
                ) {
                    newItems.add(it)
                }
            }

        chapters.nextChapter?.pages?.let { newItems.addAll(itemsOf(it)) }

        // Resets double-page splits, else insert pages get misplaced
        items.filterIsInstance<InsertPage>().also { items.removeAll(it) }

        if (viewer is R2LPagerViewer) {
            newItems.reverse()
        }

        // Halves of an adjacent chapter wait for it to become current; any other chapter's go.
        val adjacentChapterIds = setOfNotNull(chapters.prevChapter?.chapter?.id, chapters.nextChapter?.chapter?.id)
        preprocessed = preprocessed.filter { it.chapter.chapter.id in adjacentChapterIds }
        items = newItems
        notifyDataSetChanged()

        // Will skip insert page otherwise
        if (insertPageLastPage != null) {
            viewer.moveToPage(insertPageLastPage)
        }
    }

    /** One chapter's items: its pages, or its spreads while two pages show side by side. */
    private fun itemsOf(pages: List<ReaderPage>): List<Any> =
        if (viewer.showsTwoPages) spreadItems(pages, viewer.shiftsPages, viewer::measuredShape) else pages

    /**
     * Returns the amount of items of the adapter.
     */
    override fun getCount(): Int {
        return items.size
    }

    /**
     * Creates a new view for the item at the given [position].
     */
    override fun createView(container: ViewGroup, position: Int): View {
        return when (val item = items[position]) {
            is PageSpread -> PagerSpreadHolder(readerThemedContext, viewer, item)
            is ReaderPage -> PagerPageHolder(readerThemedContext, viewer, item)
            is ChapterTransition -> PagerTransitionHolder(readerThemedContext, viewer, item)
            else -> throw NotImplementedError("Holder for ${item.javaClass} not implemented")
        }
    }

    /**
     * Returns the current position of the given [view] on the adapter.
     */
    override fun getItemPosition(view: Any): Int {
        if (view is PositionableView) {
            val position = items.indexOf(view.item)
            if (position != -1) {
                return position
            } else {
                logcat { "Position for ${view.item} not found" }
            }
        }
        return POSITION_NONE
    }

    fun onPageSplit(currentPage: Any?, newPage: InsertPage) {
        if (currentPage !is ReaderPage) return

        val currentIndex = items.indexOf(currentPage)

        // Put aside pages of an adjacent chapter until it becomes the current one, so they are not lost on the change
        if (currentPage.chapter.chapter.id != currentChapter?.chapter?.id) {
            val chapterId = newPage.chapter.chapter.id
            if (preprocessed.none { it.chapter.chapter.id == chapterId && it.index == newPage.index }) {
                preprocessed = preprocessed + newPage
            }
            return
        }

        val placeAtIndex = when (viewer) {
            is L2RPagerViewer,
            is VerticalPagerViewer,
            -> currentIndex + 1
            else -> currentIndex
        }

        // It will enter a endless cycle of insert pages
        if (viewer is R2LPagerViewer && placeAtIndex - 1 >= 0 && items[placeAtIndex - 1] is InsertPage) {
            return
        }

        // Same here it will enter a endless cycle of insert pages
        if (items[placeAtIndex] is InsertPage) {
            return
        }

        items.add(placeAtIndex, newPage)

        notifyDataSetChanged()
    }

    /** Split turned off: the halves shown go, and so do the ones held for an adjacent chapter. */
    fun cleanupPageSplit() {
        val insertPages = items.filterIsInstance<InsertPage>()
        items.removeAll(insertPages)
        preprocessed = emptyList()
        notifyDataSetChanged()
    }
}

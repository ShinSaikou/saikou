// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonAdapter.kt (modified: yes)
package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.calculateChapterGap

/**
 * RecyclerView Adapter used by this [viewer] to where [ViewerChapters] updates are posted.
 *
 * Modified (H). Upstream builds its holders with `viewer.activity.createReaderThemeContext()`, which
 * lives in its `app/` module and reads `UiPreferences` out of Injekt. `ViewerHost.context` is
 * already the activity themed with `Theme.Saikou.Reader`, so the two call sites collapse to it and
 * the extension is never vendored (ruling R21 and `REMAINING.md` section 3 defect 1). The night
 * mode that wrapper set from the reader background travels as `ViewerHost.isDark` instead, which
 * the holders' Compose views read. `refresh()` existed only to rebuild that wrapped context and
 * goes with it; `WebtoonViewer.refreshAdapter`
 * already re-binds the range around the current position. 2.1 lists this file as unmodified, which
 * was wrong.
 *
 * Also modified: [lastChapters] is remembered so `WebtoonViewer.onSplitInserted` can re-issue the
 * same window and let `DiffUtil` insert the tall-strip parts in place; and a [SIDE_PADDING] change
 * re-applies a page's margins without loading its image again.
 */
class WebtoonAdapter(val viewer: WebtoonViewer) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /**
     * List of currently set items.
     */
    var items: List<Any> = emptyList()
        private set

    var currentChapter: ReaderChapter? = null

    /** The last window set on this adapter, so a split insertion can re-issue it. */
    var lastChapters: ViewerChapters? = null
        private set

    private val readerThemedContext = viewer.host.context

    /**
     * Updates this adapter with the given [chapters]. It handles setting a few pages of the
     * next/previous chapter to allow seamless transitions.
     */
    fun setChapters(chapters: ViewerChapters, forceTransition: Boolean) {
        lastChapters = chapters
        val newItems = mutableListOf<Any>()

        // Forces chapter transition if there is missing chapters
        val prevHasMissingChapters = calculateChapterGap(chapters.currChapter, chapters.prevChapter) > 0
        val nextHasMissingChapters = calculateChapterGap(chapters.nextChapter, chapters.currChapter) > 0

        // Add previous chapter pages and transition.
        chapters.prevChapter?.pages?.let(newItems::addAll)

        // Skip transition page if the chapter is loaded & current page is not a transition page
        if (prevHasMissingChapters || forceTransition || chapters.prevChapter?.state !is ReaderChapter.State.Loaded) {
            newItems.add(ChapterTransition.Prev(chapters.currChapter, chapters.prevChapter))
        }

        // Add current chapter.
        val currPages = chapters.currChapter.pages
        if (currPages != null) {
            newItems.addAll(currPages)
        }

        currentChapter = chapters.currChapter

        // Add next chapter transition and pages.
        if (nextHasMissingChapters || forceTransition || chapters.nextChapter?.state !is ReaderChapter.State.Loaded) {
            newItems.add(ChapterTransition.Next(chapters.currChapter, chapters.nextChapter))
        }

        chapters.nextChapter?.pages?.let(newItems::addAll)

        updateItems(newItems)
    }

    private fun updateItems(newItems: List<Any>) {
        val result = DiffUtil.calculateDiff(Callback(items, newItems))
        items = newItems
        result.dispatchUpdatesTo(this)
    }

    /**
     * Returns the amount of items of the adapter.
     */
    override fun getItemCount(): Int {
        return items.size
    }

    /**
     * Returns the view type for the item at the given [position].
     */
    override fun getItemViewType(position: Int): Int {
        return when (val item = items[position]) {
            is ReaderPage -> PAGE_VIEW
            is ChapterTransition -> TRANSITION_VIEW
            else -> error("Unknown view type for ${item.javaClass}")
        }
    }

    /**
     * Creates a new view holder for an item with the given [viewType].
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            PAGE_VIEW -> {
                val view = ReaderPageImageView(readerThemedContext, isWebtoon = true)
                WebtoonPageHolder(view, viewer)
            }
            TRANSITION_VIEW -> {
                val view = LinearLayout(readerThemedContext)
                WebtoonTransitionHolder(view, viewer)
            }
            else -> error("Unknown view type")
        }
    }

    /**
     * Binds an existing view [holder] with the item at the given [position].
     */
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        when (holder) {
            is WebtoonPageHolder -> holder.bind(item as ReaderPage)
            is WebtoonTransitionHolder -> holder.bind(item as ChapterTransition)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: List<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == SIDE_PADDING }) {
            (holder as? WebtoonPageHolder)?.refreshLayoutParams()
        } else {
            onBindViewHolder(holder, position)
        }
    }

    /**
     * Recycles an existing view [holder] before adding it to the view pool.
     */
    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        when (holder) {
            is WebtoonPageHolder -> holder.recycle()
            is WebtoonTransitionHolder -> holder.recycle()
        }
    }

    /**
     * Diff util callback used to dispatch delta updates instead of full dataset changes.
     */
    private class Callback(
        private val oldItems: List<Any>,
        private val newItems: List<Any>,
    ) : DiffUtil.Callback() {

        /**
         * Returns true if these two items are the same.
         */
        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val oldItem = oldItems[oldItemPosition]
            val newItem = newItems[newItemPosition]

            return oldItem == newItem
        }

        /**
         * Returns true if the contents of the items are the same.
         */
        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return true
        }

        /**
         * Returns the size of the old list.
         */
        override fun getOldListSize(): Int {
            return oldItems.size
        }

        /**
         * Returns the size of the new list.
         */
        override fun getNewListSize(): Int {
            return newItems.size
        }
    }

    companion object {
        /** Change payload: only the side padding's share of the width moved. */
        const val SIDE_PADDING = "side-padding"
    }
}

/**
 * View holder type of a chapter page view.
 */
private const val PAGE_VIEW = 0

/**
 * View holder type of a chapter transition view.
 */
private const val TRANSITION_VIEW = 1

// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerViewers.kt (modified: yes)
package eu.kanade.tachiyomi.ui.reader.viewer.pager

import com.shinsaikou.reader.viewer.ViewerHost

/**
 * Implementation of a left to right PagerViewer.
 *
 * Modified (H): the three viewers take a `ViewerHost` and build their pager with `host.context`.
 */
class L2RPagerViewer(host: ViewerHost) : PagerViewer(host) {
    /**
     * Creates a new left to right pager.
     */
    override fun createPager(): Pager {
        return Pager(host.context)
    }
}

/**
 * Implementation of a right to left PagerViewer.
 */
class R2LPagerViewer(host: ViewerHost) : PagerViewer(host) {
    /**
     * Creates a new right to left pager.
     */
    override fun createPager(): Pager {
        return Pager(host.context)
    }

    /**
     * Moves to the next page. On a R2L pager the next page is the one at the left.
     */
    override fun moveToNext() {
        moveLeft()
    }

    /**
     * Moves to the previous page. On a R2L pager the previous page is the one at the right.
     */
    override fun moveToPrevious() {
        moveRight()
    }
}

/**
 * Implementation of a vertical (top to bottom) PagerViewer.
 */
class VerticalPagerViewer(host: ViewerHost) : PagerViewer(host) {
    /**
     * Creates a new vertical pager.
     */
    override fun createPager(): Pager {
        return Pager(host.context, isHorizontal = false)
    }
}

package com.shinsaikou.ui.browse.source

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.unit.dp
import com.shinsaikou.i18n.R
import com.shinsaikou.ui.components.mangaGridCells
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.model.LibraryDisplayMode

/**
 * The browse listing's layout decisions, taken without composing: which of the three layouts each
 * stored mode draws, which choices the toolbar offers, how many columns the grid gets, and when the
 * grid's scroll position asks for the next page.
 */
class SourceBrowseLayoutTest {

    @Test
    fun `four stored modes give three layouts, the cover-only grid as the compact grid without titles`() {
        browseLayoutOf(LibraryDisplayMode.List) shouldBe BrowseLayout.List
        browseLayoutOf(LibraryDisplayMode.CompactGrid) shouldBe BrowseLayout.CompactGrid(showTitles = true)
        browseLayoutOf(LibraryDisplayMode.CoverOnlyGrid) shouldBe BrowseLayout.CompactGrid(showTitles = false)
        browseLayoutOf(LibraryDisplayMode.ComfortableGrid) shouldBe BrowseLayout.ComfortableGrid

        LibraryDisplayMode.values.map(::browseLayoutOf).map { it::class }.toSet().size shouldBe 3
    }

    @Test
    fun `the menu offers upstream's three choices in upstream's order and no cover-only entry`() {
        DISPLAY_MODE_CHOICES shouldContainExactly listOf(
            LibraryDisplayMode.ComfortableGrid to R.string.library_display_comfortable_grid,
            LibraryDisplayMode.CompactGrid to R.string.library_display_compact_grid,
            LibraryDisplayMode.List to R.string.library_display_list,
        )
    }

    /**
     * Phase 4 unit MP1, R108 carry-over 1: every layout, the list included, marks a library entry with
     * upstream's badge and its cover at upstream's `BrowseFavoriteCoverAlpha`, and leaves any other entry as
     * it is.
     */
    @Test
    fun `a library entry gets the badge and the faded cover, any other entry neither`() {
        inLibraryMark(inLibrary = true) shouldBe InLibraryMark(badge = true, coverAlpha = 0.34f)
        inLibraryMark(inLibrary = false) shouldBe InLibraryMark(badge = false, coverAlpha = 1f)
    }

    @Test
    fun `a stored 0 is adaptive at 128 dp and any other count is that many columns`() {
        mangaGridCells(0) shouldBe GridCells.Adaptive(128.dp)
        mangaGridCells(4) shouldBe GridCells.Fixed(4)
    }

    @Test
    fun `the grid's threshold is six rows of cells, so more columns ask earlier by item`() {
        // 63 items: six rows of three cells before the end is item 45.
        isNearEnd(lastVisibleIndex = 44, totalItemsCount = 63, cellsPerRow = 3) shouldBe false
        isNearEnd(lastVisibleIndex = 45, totalItemsCount = 63, cellsPerRow = 3) shouldBe true
        // The same position in a one-column grid, or the list, is still twelve rows away.
        isNearEnd(lastVisibleIndex = 45, totalItemsCount = 63, cellsPerRow = 1) shouldBe false
        isNearEnd(lastVisibleIndex = 57, totalItemsCount = 63, cellsPerRow = 1) shouldBe true
        // Nothing laid out yet asks for nothing.
        isNearEnd(lastVisibleIndex = null, totalItemsCount = 0, cellsPerRow = 3) shouldBe false
    }

    @Test
    fun `the next page is asked for once each time the grid scrolls near its end`() = runTest {
        val grid = Listing(this, GridPosition(lastVisible = 11, total = 63, columns = 3), page = 1)
        var requests = 0
        val trigger = launch { requestPagesNearEnd(grid::position) { requests++ } }
        runCurrent()
        requests shouldBe 0

        grid.lay(GridPosition(lastVisible = 44, total = 63, columns = 3))
        requests shouldBe 0
        grid.lay(GridPosition(lastVisible = 45, total = 63, columns = 3))
        requests shouldBe 1
        // Still near the end on the next frame: one request, not one per frame.
        grid.lay(GridPosition(lastVisible = 50, total = 63, columns = 3))
        requests shouldBe 1
        // Page two lands and pushes the end away; scrolling down to it again asks for page three.
        grid.lay(GridPosition(lastVisible = 50, total = 123, columns = 3), page = 2)
        requests shouldBe 1
        grid.lay(GridPosition(lastVisible = 105, total = 123, columns = 3))
        requests shouldBe 2

        trigger.cancel()
    }

    @Test
    fun `pages too short to fill the screen keep being asked for, one request per page`() = runTest {
        // Four entries under the header in a three-column grid, all on screen: the listing is near
        // its end before anyone scrolls, and stays there as each short page lands.
        val grid = Listing(this, GridPosition(lastVisible = 4, total = 5, columns = 3), page = 1)
        var requests = 0
        val trigger = launch { requestPagesNearEnd(grid::position) { requests++ } }
        runCurrent()
        requests shouldBe 1

        for (page in 2..4) {
            // The spinner goes in below the entries while the page is on its way.
            grid.lay(GridPosition(lastVisible = grid.laidOut.total, total = grid.laidOut.total + 1, columns = 3))
            requests shouldBe page - 1
            // The page lands with three entries, and the spinner goes.
            val total = grid.laidOut.total - 1 + 3
            grid.lay(GridPosition(lastVisible = total - 1, total = total, columns = 3), page = page)
            requests shouldBe page
        }

        trigger.cancel()
    }

    @Test
    fun `a page that failed is not asked for again until the listing is scrolled back to its end`() = runTest {
        // Twenty-one entries under the header, each step as the screen sees it: the state first,
        // the layout a frame later.
        val list = Listing(this, GridPosition(lastVisible = 20, total = 22, columns = 1), page = 1)
        list.list(items = 22)
        var requests = 0
        val trigger = launch { requestPagesNearEnd(list::position) { requests++ } }
        runCurrent()
        requests shouldBe 1

        // The spinner comes and goes, and no page lands: no retry by itself.
        list.list(items = 23)
        list.lay(GridPosition(lastVisible = 22, total = 23, columns = 1))
        list.list(items = 22)
        list.lay(GridPosition(lastVisible = 20, total = 22, columns = 1))
        requests shouldBe 1
        // Away from the end and back is the owner asking again.
        list.lay(GridPosition(lastVisible = 10, total = 22, columns = 1))
        list.lay(GridPosition(lastVisible = 20, total = 22, columns = 1))
        requests shouldBe 2

        trigger.cancel()
    }

    @Test
    fun `a layout a frame behind the listing asks for nothing`() = runTest {
        val grid = Listing(this, GridPosition(lastVisible = 50, total = 63, columns = 3), page = 1)
        grid.list(items = 63)
        var requests = 0
        val trigger = launch { requestPagesNearEnd(grid::position) { requests++ } }
        runCurrent()
        requests shouldBe 1

        // Page two is in state, sixty more entries, while the grid still shows page one's end.
        grid.list(items = 123, page = 2)
        requests shouldBe 1
        // Laid out, page two leaves the end far away.
        grid.lay(GridPosition(lastVisible = 50, total = 123, columns = 3))
        requests shouldBe 1

        trigger.cancel()
    }

    /**
     * What the screen hands the trigger: the grid state's last visible item, item count and cells
     * per row, the items the screen put in the listing, and the page in state. [list] is the state
     * changing and [lay] a layout pass, each followed by a frame. Until a test calls [list], the
     * layout is never behind.
     */
    private class Listing(private val scope: TestScope, laidOut: GridPosition, page: Int) {
        private val laidOutState = mutableStateOf(laidOut)
        private val pageState = mutableStateOf(page)
        private val listedState = mutableStateOf<Int?>(null)

        val laidOut: GridPosition get() = laidOutState.value

        fun position(): ListingPosition? = laidOutState.value.let {
            listingPosition(it.total, listedState.value ?: it.total, pageState.value) {
                isNearEnd(it.lastVisible, it.total, it.columns)
            }
        }

        fun list(items: Int, page: Int = pageState.value) {
            listedState.value = items
            pageState.value = page
            frame()
        }

        fun lay(position: GridPosition, page: Int = pageState.value) {
            laidOutState.value = position
            pageState.value = page
            frame()
        }

        private fun frame() {
            Snapshot.sendApplyNotifications()
            scope.runCurrent()
        }
    }

    private data class GridPosition(val lastVisible: Int, val total: Int, val columns: Int)
}

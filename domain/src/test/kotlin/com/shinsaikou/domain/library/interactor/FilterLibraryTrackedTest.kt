package com.shinsaikou.domain.library.interactor

import com.shinsaikou.domain.library.model.LibraryFilters
import com.shinsaikou.domain.library.search.LibrarySearchMatcher
import io.kotest.matchers.shouldBe
import tachiyomi.domain.library.model.search.QueryNode
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.TriState

/**
 * The library's tracker filter (phase 3 unit T3), upstream's predicate over the signed-in trackers'
 * filter states: a manga bound to an excluded tracker is dropped, and while any tracker is included
 * only a manga bound to one of them stays; with no tracker in the filter, or every one DISABLED,
 * every manga passes. And the active-filter check counts a tracker's filter only when it is set.
 */
class FilterLibraryTrackedTest {

    private val filterLibrary = FilterLibrary(LibrarySearchMatcher())

    // Manga 1 on trackers 1 and 2, manga 2 on tracker 1, manga 3 on tracker 2, manga 4 on none.
    private val items = (1L..4L).map { libraryItem(it, "Title $it") }
    private val tracks = mapOf(
        1L to listOf(trackRow(1, 1), trackRow(1, 2)),
        2L to listOf(trackRow(2, 1)),
        3L to listOf(trackRow(3, 2)),
    )

    private fun shown(vararg tracked: Pair<Long, TriState>): List<Long> =
        filterLibrary.apply(items, LibraryFilters(tracked = tracked.toMap()), QueryNode.from(""), emptyMap(), tracks)
            .map { it.id }

    @Test
    fun `with no tracker signed in, or every tracker DISABLED, every manga passes`() {
        shown() shouldBe listOf(1L, 2L, 3L, 4L)
        shown(1L to TriState.DISABLED, 2L to TriState.DISABLED) shouldBe listOf(1L, 2L, 3L, 4L)
    }

    @Test
    fun `an included tracker keeps only the manga bound to it`() {
        shown(1L to TriState.ENABLED_IS, 2L to TriState.DISABLED) shouldBe listOf(1L, 2L)
    }

    @Test
    fun `an excluded tracker drops every manga bound to it`() {
        shown(1L to TriState.ENABLED_NOT) shouldBe listOf(3L, 4L)
    }

    @Test
    fun `two included trackers keep a manga bound to either, and an exclusion wins over an inclusion`() {
        shown(1L to TriState.ENABLED_IS, 2L to TriState.ENABLED_IS) shouldBe listOf(1L, 2L, 3L)
        shown(1L to TriState.ENABLED_IS, 2L to TriState.ENABLED_NOT) shouldBe listOf(2L)
    }

    @Test
    fun `a tracker's DISABLED filter is not an active filter, a set one is`() {
        LibraryFilters(tracked = mapOf(1L to TriState.DISABLED)).isActive shouldBe false
        LibraryFilters(tracked = mapOf(1L to TriState.ENABLED_IS)).isActive shouldBe true
        LibraryFilters(tracked = mapOf(1L to TriState.ENABLED_NOT)).isActive shouldBe true
        LibraryFilters(unread = TriState.ENABLED_IS, tracked = mapOf(1L to TriState.DISABLED)).isActive shouldBe true
        LibraryFilters().isActive shouldBe false
    }
}

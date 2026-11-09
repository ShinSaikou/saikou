package com.shinsaikou.domain.library.interactor

import com.shinsaikou.domain.library.model.LibraryFilters
import com.shinsaikou.domain.library.model.LibraryItem
import com.shinsaikou.domain.library.search.LibrarySearchMatcher
import com.shinsaikou.source.model.MangaStatus
import dev.zacsweers.metro.Inject
import tachiyomi.domain.library.model.search.QueryNode
import tachiyomi.domain.manga.model.applyFilter
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.source.model.Source
import tachiyomi.domain.track.model.Track

/**
 * Applies the library filter toggles, the tracker filters and the parsed search query to a list of
 * rows.
 */
@Inject
class FilterLibrary(
    private val librarySearchMatcher: LibrarySearchMatcher,
) {

    /** [tracks] holds each manga's track rows by manga id, for [LibraryFilters.tracked]. */
    fun apply(
        items: List<LibraryItem>,
        filters: LibraryFilters,
        query: QueryNode,
        sources: Map<Long, Source>,
        tracks: Map<Long, List<Track>> = emptyMap(),
    ): List<LibraryItem> {
        val included = filters.tracked.filterValues { it == TriState.ENABLED_IS }.keys
        val excluded = filters.tracked.filterValues { it == TriState.ENABLED_NOT }.keys
        return items.filter { item ->
            matchesFilters(item, filters) &&
                matchesTracked(tracks[item.id].orEmpty(), included, excluded) &&
                librarySearchMatcher.matches(query, item, sources[item.manga.source])
        }
    }

    /**
     * Upstream's tracker filter: a manga bound to an excluded tracker is dropped, and while any tracker
     * is included only a manga bound to one of them stays. The filter holds signed-in trackers only,
     * so with none signed in, or every one DISABLED, it passes everything.
     */
    private fun matchesTracked(tracks: List<Track>, included: Set<Long>, excluded: Set<Long>): Boolean {
        if (included.isEmpty() && excluded.isEmpty()) return true
        val isExcluded = excluded.isNotEmpty() && tracks.any { it.trackerId in excluded }
        val isIncluded = included.isEmpty() || tracks.any { it.trackerId in included }
        return !isExcluded && isIncluded
    }

    private fun matchesFilters(item: LibraryItem, filters: LibraryFilters): Boolean {
        if (!applyFilter(filters.downloaded) { item.downloadCount > 0 || item.isLocal }) return false
        if (!applyFilter(filters.unread) { item.unreadCount > 0 }) return false
        if (!applyFilter(filters.started) { item.libraryManga.hasStarted }) return false
        if (!applyFilter(filters.bookmarked) { item.libraryManga.hasBookmarks }) return false
        // The status the entry screen shows: the owner's edit over the source's (R165 item 4).
        val completed = item.manga.displayStatus == MangaStatus.COMPLETED.code.toLong()
        if (!applyFilter(filters.completed) { completed }) return false
        return applyFilter(filters.intervalCustom) { item.manga.fetchInterval < 0 }
    }
}

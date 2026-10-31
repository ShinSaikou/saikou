package com.shinsaikou.domain.library.model

import tachiyomi.core.common.preference.TriState

/**
 * The filter state the library screen applies to its rows, built from LibraryPreferences by the
 * view model and handed to FilterLibrary.
 */
data class LibraryFilters(
    val downloaded: TriState = TriState.DISABLED,
    val unread: TriState = TriState.DISABLED,
    val started: TriState = TriState.DISABLED,
    val bookmarked: TriState = TriState.DISABLED,
    val completed: TriState = TriState.DISABLED,
    val intervalCustom: TriState = TriState.DISABLED,
    /**
     * Tracker id to filter state (`LibraryPreferences.filterTracking`), for each signed-in tracker
     * only, DISABLED included (phase 3 unit T3); empty while no tracker is signed in.
     */
    val tracked: Map<Long, TriState> = emptyMap(),
) {
    /**
     * Whether any filter narrows the rows: a tracker's DISABLED entry is present whenever that
     * tracker is signed in, and is no more a filter than a DISABLED toggle is (phase 3 unit T3).
     */
    val isActive: Boolean
        get() = copy(tracked = emptyMap()) != LibraryFilters() || tracked.values.any { it != TriState.DISABLED }
}

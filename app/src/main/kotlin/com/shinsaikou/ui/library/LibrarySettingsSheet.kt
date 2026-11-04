package com.shinsaikou.ui.library

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.i18n.R
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.presentation.core.components.AdaptiveSheet
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.RadioItem
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.components.SortItem
import tachiyomi.presentation.core.components.TriStateItem
import tachiyomi.presentation.core.components.material.TabText
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Filter, sort and display, in one sheet over the library.
 *
 * The three tabs are the three questions the library asks of a preference: which entries to show,
 * in what order, and in what shape. Everything a row writes goes through the view model or straight
 * at a `Preference`, and the projection re-runs on the write, so the sheet holds no state of its
 * own beyond which tab is open.
 *
 * The badge and tab checkboxes are the vendored `CheckboxItem(label, pref)`, which subscribes to
 * the preference itself. That is why this takes [preferences] as well as [state]: a boolean copied
 * into the state would be a second source of truth for a value nothing else reads.
 */
@Composable
internal fun LibrarySettingsSheet(
    state: LibraryViewModel.State,
    preferences: LibraryPreferences,
    onDismissRequest: () -> Unit,
    onFilter: (LibraryViewModel.LibraryFilter, TriState) -> Unit,
    onTrackerFilter: (Long, TriState) -> Unit,
    onSort: (LibrarySort.Type, LibrarySort.Direction) -> Unit,
    onDisplayMode: (LibraryDisplayMode) -> Unit,
    onColumns: (Int) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(SettingsTab.FILTER) }

    // The dialog window the sheet sits in comes with the shared AdaptiveSheet in :presentation-core.
    AdaptiveSheet(
        onDismissRequest = onDismissRequest,
        enableImplicitDismiss = true,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                SettingsTab.entries.forEach { entry ->
                    Tab(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        unselectedContentColor = MaterialTheme.colorScheme.onSurface,
                        text = { TabText(stringResource(entry.labelRes)) },
                    )
                }
            }
            Column(
                modifier = Modifier
                    .heightIn(max = MAX_CONTENT_HEIGHT)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
            ) {
                when (tab) {
                    SettingsTab.FILTER -> FilterTab(state, onFilter, onTrackerFilter)
                    SettingsTab.SORT -> SortTab(state, onSort)
                    SettingsTab.DISPLAY -> DisplayTab(state, preferences, onDisplayMode, onColumns)
                }
            }
        }
    }
}

/**
 * The toggles, each cycling ignore, include, exclude, then upstream's tracker rows for the
 * signed-in trackers (phase 3 unit T3): one row, "Tracked", for one tracker, else a heading and a
 * row per tracker. The custom interval row shows only while its experimental switch is on, the one
 * way an entry can have a custom interval.
 *
 * The downloaded row is disabled while the app-wide downloaded-only switch is on, and shows as
 * included, because that switch forces the filter. Leaving the row live would let the owner turn
 * off a filter that comes straight back on the next projection.
 */
@Composable
private fun FilterTab(
    state: LibraryViewModel.State,
    onFilter: (LibraryViewModel.LibraryFilter, TriState) -> Unit,
    onTrackerFilter: (Long, TriState) -> Unit,
) {
    TriStateItem(
        label = stringResource(R.string.library_filter_downloaded),
        state = state.filters.downloaded,
        enabled = !state.downloadedOnly,
        onClick = { onFilter(LibraryViewModel.LibraryFilter.DOWNLOADED, it) },
    )
    TriStateItem(
        label = stringResource(R.string.library_filter_unread),
        state = state.filters.unread,
        onClick = { onFilter(LibraryViewModel.LibraryFilter.UNREAD, it) },
    )
    TriStateItem(
        label = stringResource(R.string.library_filter_started),
        state = state.filters.started,
        onClick = { onFilter(LibraryViewModel.LibraryFilter.STARTED, it) },
    )
    TriStateItem(
        label = stringResource(R.string.library_filter_bookmarked),
        state = state.filters.bookmarked,
        onClick = { onFilter(LibraryViewModel.LibraryFilter.BOOKMARKED, it) },
    )
    TriStateItem(
        label = stringResource(R.string.library_filter_completed),
        state = state.filters.completed,
        onClick = { onFilter(LibraryViewModel.LibraryFilter.COMPLETED, it) },
    )
    if (state.customUpdateInterval) {
        TriStateItem(
            label = stringResource(R.string.library_filter_interval_custom),
            state = state.filters.intervalCustom,
            onClick = { onFilter(LibraryViewModel.LibraryFilter.INTERVAL_CUSTOM, it) },
        )
    }
    val trackers = state.signedInTrackers
    if (trackers.size > 1) HeadingItem(R.string.action_filter_tracked)
    trackers.forEach { tracker ->
        TriStateItem(
            label = if (trackers.size == 1) stringResource(R.string.action_filter_tracked) else tracker.name,
            state = state.filters.tracked[tracker.id] ?: TriState.DISABLED,
            onClick = { onTrackerFilter(tracker.id, it) },
        )
    }
}

/**
 * One row per sort key. Tapping the active key flips its direction; tapping another keeps the
 * direction already chosen, so changing what the library is sorted by does not silently change
 * which end of it the owner is looking at. The tracker score is offered only while a tracker is
 * signed in, as in upstream (phase 3 unit T3).
 */
@Composable
private fun SortTab(state: LibraryViewModel.State, onSort: (LibrarySort.Type, LibrarySort.Direction) -> Unit) {
    val sort = state.sort
    LibrarySort.types.forEach { type ->
        if (type == LibrarySort.Type.TrackerMean && state.signedInTrackers.isEmpty()) return@forEach
        val active = sort.type == type
        SortItem(
            label = stringResource(type.labelRes),
            sortDescending = if (active) !sort.isAscending else null,
            onClick = {
                val direction = when {
                    !active -> sort.direction
                    sort.isAscending -> LibrarySort.Direction.Descending
                    else -> LibrarySort.Direction.Ascending
                }
                onSort(type, direction)
            },
        )
    }
}

@Composable
private fun DisplayTab(
    state: LibraryViewModel.State,
    preferences: LibraryPreferences,
    onDisplayMode: (LibraryDisplayMode) -> Unit,
    onColumns: (Int) -> Unit,
) {
    LibraryDisplayMode.values.forEach { mode ->
        RadioItem(
            label = stringResource(mode.labelRes),
            selected = state.displayMode == mode,
            onClick = { onDisplayMode(mode) },
        )
    }
    if (state.displayMode != LibraryDisplayMode.List) {
        SliderItem(
            value = state.columns,
            valueRange = 0..MAX_COLUMNS,
            label = stringResource(R.string.library_display_columns),
            // 0 is not "no columns", it is "as many as fit", which is the default a fresh install
            // and a restored backup both carry.
            valueString = if (state.columns == 0) {
                stringResource(R.string.library_display_columns_auto)
            } else {
                state.columns.toString()
            },
            onChange = onColumns,
        )
    }
    CheckboxItem(label = stringResource(R.string.library_display_badge_unread), pref = preferences.unreadBadge)
    CheckboxItem(label = stringResource(R.string.library_display_badge_downloaded), pref = preferences.downloadBadge)
    CheckboxItem(label = stringResource(R.string.library_display_badge_local), pref = preferences.localBadge)
    CheckboxItem(label = stringResource(R.string.library_display_badge_language), pref = preferences.languageBadge)
    CheckboxItem(label = stringResource(R.string.library_show_category_tabs), pref = preferences.categoryTabs)
    CheckboxItem(label = stringResource(R.string.library_show_item_count), pref = preferences.categoryNumberOfItems)
    CheckboxItem(
        label = stringResource(R.string.library_show_continue_button),
        pref = preferences.showContinueReadingButton,
    )
}

private enum class SettingsTab(@StringRes val labelRes: Int) {
    FILTER(R.string.action_filter),
    SORT(R.string.action_sort),
    DISPLAY(R.string.action_display),
}

@get:StringRes
private val LibrarySort.Type.labelRes: Int
    get() = when (this) {
        LibrarySort.Type.Alphabetical -> R.string.library_sort_alphabetical
        LibrarySort.Type.LastRead -> R.string.library_sort_last_read
        LibrarySort.Type.LastUpdate -> R.string.library_sort_last_update
        LibrarySort.Type.UnreadCount -> R.string.library_sort_unread_count
        LibrarySort.Type.TotalChapters -> R.string.library_sort_total_chapters
        LibrarySort.Type.LatestChapter -> R.string.library_sort_latest_chapter
        LibrarySort.Type.ChapterFetchDate -> R.string.library_sort_chapter_fetch_date
        LibrarySort.Type.DateAdded -> R.string.library_sort_date_added
        LibrarySort.Type.TrackerMean -> R.string.library_sort_tracker_mean
        LibrarySort.Type.Random -> R.string.library_sort_random
    }

@get:StringRes
private val LibraryDisplayMode.labelRes: Int
    get() = when (this) {
        LibraryDisplayMode.CompactGrid -> R.string.library_display_compact_grid
        LibraryDisplayMode.ComfortableGrid -> R.string.library_display_comfortable_grid
        LibraryDisplayMode.CoverOnlyGrid -> R.string.library_display_cover_only_grid
        LibraryDisplayMode.List -> R.string.library_display_list
    }

/** Upstream's ceiling. Past ten columns a cover is a smudge on any phone. */
private const val MAX_COLUMNS = 10
private val MAX_CONTENT_HEIGHT = 420.dp

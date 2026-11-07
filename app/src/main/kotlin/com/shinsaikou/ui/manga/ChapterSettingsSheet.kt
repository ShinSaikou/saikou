package com.shinsaikou.ui.manga

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shinsaikou.i18n.R
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.manga.model.Manga
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.IconItem
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.RadioItem
import tachiyomi.presentation.core.components.SortItem
import tachiyomi.presentation.core.components.TabbedDialog
import tachiyomi.presentation.core.components.TabbedDialogPaddings
import tachiyomi.presentation.core.components.TriStateItem
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Filter, sort and display, in one sheet over the chapter list.
 *
 * Everything a row writes is a bit on `Manga.chapterFlags`, so unlike the library's sheet no row
 * here owns a `Preference`: the tap goes to the view model, the write re-emits the chapter list,
 * and the next projection is what moves the control. The sheet holds no state of its own beyond
 * which tab is open, which the pager keeps.
 *
 * "Set as default" and "Reset" sit in the tab bar's overflow rather than as rows, which is what
 * [TabbedDialog]'s overflow slot exists for: they act on all three tabs at once and do not belong
 * to any of them. Both are upstream's (phase 4 unit CS): "Set as default" asks first, with "Also
 * apply to all entries in my library" unticked, and "Reset" applies the defaults to this entry at
 * once. The question sits over the sheet, as upstream's does, so the sheet is there after it.
 */
@Composable
internal fun ChapterSettingsSheet(
    state: MangaDetailViewModel.State.Success,
    onDismissRequest: () -> Unit,
    onFilter: (MangaDetailViewModel.ChapterFilter, TriState) -> Unit,
    onSort: (Long) -> Unit,
    onDisplay: (Long) -> Unit,
    onGroupByVolume: (Boolean) -> Unit,
    onSetAsDefault: (applyToLibrary: Boolean) -> Unit,
    onResetToDefault: () -> Unit,
    onOpenScanlatorFilter: () -> Unit,
) {
    var showSetAsDefaultDialog by rememberSaveable { mutableStateOf(false) }
    if (showSetAsDefaultDialog) {
        SetAsDefaultDialog(
            onDismissRequest = { showSetAsDefaultDialog = false },
            onConfirmed = onSetAsDefault,
        )
    }

    val tabTitles = listOf(
        stringResource(R.string.action_filter),
        stringResource(R.string.action_sort),
        stringResource(R.string.action_display),
    )

    BoxWithConstraints {
        TabbedDialog(
            onDismissRequest = onDismissRequest,
            tabTitles = tabTitles,
            modifier = Modifier.heightIn(max = maxHeight * SHEET_HEIGHT_FRACTION),
            tabOverflowMenuContent = { closeMenu ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chapter_settings_set_as_default)) },
                    onClick = {
                        showSetAsDefaultDialog = true
                        closeMenu()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chapter_settings_reset)) },
                    onClick = {
                        onResetToDefault()
                        closeMenu()
                    },
                )
            },
        ) { page ->
            Column(
                modifier = Modifier
                    .padding(vertical = TabbedDialogPaddings.Vertical)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (page) {
                    0 -> FilterPage(state, onFilter, onOpenScanlatorFilter)
                    1 -> SortPage(state, onSort)
                    2 -> DisplayPage(state, onDisplay, onGroupByVolume)
                }
            }
        }
    }
}

/**
 * Three toggles, each cycling ignore, include, exclude, and a way into the scanlator list.
 *
 * The downloaded row is upstream's: first, and dimmed while the More tab's "Downloaded only" switch is
 * forcing it on, because the switch is then the control that clears it and the row says what it is
 * doing. The scanlator row appears only when there is more than one group to choose between,
 * because excluding the only group there is would empty the list with nothing left on screen to
 * undo it.
 */
@Composable
private fun ColumnScope.FilterPage(
    state: MangaDetailViewModel.State.Success,
    onFilter: (MangaDetailViewModel.ChapterFilter, TriState) -> Unit,
    onOpenScanlatorFilter: () -> Unit,
) {
    TriStateItem(
        label = stringResource(R.string.chapter_filter_downloaded),
        state = state.downloadedFilter,
        enabled = !state.downloadedOnly,
        onClick = { onFilter(MangaDetailViewModel.ChapterFilter.DOWNLOADED, it) },
    )
    TriStateItem(
        label = stringResource(R.string.chapter_filter_unread),
        state = state.manga.unreadFilter,
        onClick = { onFilter(MangaDetailViewModel.ChapterFilter.UNREAD, it) },
    )
    TriStateItem(
        label = stringResource(R.string.chapter_filter_bookmarked),
        state = state.manga.bookmarkedFilter,
        onClick = { onFilter(MangaDetailViewModel.ChapterFilter.BOOKMARKED, it) },
    )
    if (state.availableScanlators.size > 1) {
        IconItem(
            label = stringResource(R.string.chapter_filter_scanlators),
            icon = Icons.Outlined.ChevronRight,
            onClick = onOpenScanlatorFilter,
        )
    }
}

/**
 * The four fields `getChapterSort` knows how to compare, and nothing else: it throws on a sorting
 * value it does not recognise. A row carries an arrow only while its own field is the active one,
 * and tapping the active row is what flips the direction. A merged title has no one source's order
 * to offer: its list sorts by number instead, so By source is left out and Number shows as active.
 */
@Composable
private fun ColumnScope.SortPage(
    state: MangaDetailViewModel.State.Success,
    onSort: (Long) -> Unit,
) {
    val manga = state.sortedManga
    val rows = listOfNotNull(
        (Manga.CHAPTER_SORTING_SOURCE to R.string.chapter_sort_source).takeIf { state.merged == null },
        Manga.CHAPTER_SORTING_NUMBER to R.string.chapter_sort_number,
        Manga.CHAPTER_SORTING_UPLOAD_DATE to R.string.chapter_sort_upload_date,
        Manga.CHAPTER_SORTING_ALPHABET to R.string.chapter_sort_alphabet,
    )
    rows.forEach { (flag, labelRes) ->
        val active = manga.sorting == flag
        SortItem(
            label = stringResource(labelRes),
            sortDescending = if (active) manga.sortDescending() else null,
            onClick = { onSort(flag) },
        )
    }
}

/**
 * Upstream's two display rows, then Saikou's volume headers (phase 4 unit VG): a bit of this entry's
 * own, outside the six the defaults carry, so "Set as default" neither copies it nor clears it.
 */
@Composable
private fun ColumnScope.DisplayPage(
    state: MangaDetailViewModel.State.Success,
    onDisplay: (Long) -> Unit,
    onGroupByVolume: (Boolean) -> Unit,
) {
    RadioItem(
        label = stringResource(R.string.chapter_display_name),
        selected = state.manga.displayMode == Manga.CHAPTER_DISPLAY_NAME,
        onClick = { onDisplay(Manga.CHAPTER_DISPLAY_NAME) },
    )
    RadioItem(
        label = stringResource(R.string.chapter_display_number),
        selected = state.manga.displayMode == Manga.CHAPTER_DISPLAY_NUMBER,
        onClick = { onDisplay(Manga.CHAPTER_DISPLAY_NUMBER) },
    )
    CheckboxItem(
        label = stringResource(R.string.chapter_display_group_by_volume),
        checked = state.manga.groupByVolume,
        onClick = { onGroupByVolume(!state.manga.groupByVolume) },
    )
}

/** Upstream's `SetAsDefaultDialog`: the question, and the library box unticked each time it opens. */
@Composable
private fun SetAsDefaultDialog(
    onDismissRequest: () -> Unit,
    onConfirmed: (applyToLibrary: Boolean) -> Unit,
) {
    var applyToLibrary by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(R.string.chapter_settings_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = stringResource(R.string.chapter_settings_confirm_default))
                LabeledCheckbox(
                    label = stringResource(R.string.chapter_settings_apply_to_library),
                    checked = applyToLibrary,
                    onCheckedChange = { applyToLibrary = it },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(R.string.action_cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirmed(applyToLibrary)
                    onDismissRequest()
                },
            ) {
                Text(text = stringResource(R.string.action_ok))
            }
        },
    )
}

/** The sheet never grows past three quarters of the window, as the reader's own settings sheet. */
private const val SHEET_HEIGHT_FRACTION = 0.75f

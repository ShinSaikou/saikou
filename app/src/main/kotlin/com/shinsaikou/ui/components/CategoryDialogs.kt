package com.shinsaikou.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.DisabledByDefault
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shinsaikou.domain.category.CategoryTree
import com.shinsaikou.i18n.R
import com.shinsaikou.ui.library.visualName
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.manga.model.Manga
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The two dialogs that stand between a tap on "add to library" and a row in the database, shared
 * by the manga detail screen and by the library's selection mode.
 *
 * They live here rather than in either screen's package because the library moves several entries
 * at once and the detail screen moves one, and the only difference between those two cases is
 * whether a row starts [ToggleableState.Indeterminate]. One dialog with a tri-state row covers
 * both; two dialogs would drift.
 *
 * Neither dialog reaches the database. Each takes the rows it should show and hands back what the
 * owner chose, so the view model stays the only thing that writes. [CategoryFilterDialog], the
 * settings' include and exclude picker, follows the same rule.
 */

/**
 * One row of [ChangeCategoryDialog]: a category and how it applies across the chosen entries, and
 * its [depth] in [CategoryTree]'s tree (1 under a parent), which only indents the row.
 */
data class CategoryCheck(val category: Category, val state: ToggleableState, val depth: Int = 0) {
    val id: Long get() = category.id
}

/**
 * Builds the rows for a set of entries. [membership] is category id to the number of chosen
 * entries that are in it, and [total] is how many entries were chosen, so a category that holds
 * some of them but not all starts indeterminate and stays that way unless it is tapped.
 *
 * The rows are [CategoryTree]'s tree (phase 5 unit CT): each child under its parent, with a box of
 * its own. Membership stays flat (D8), so a parent's box says nothing of its children's.
 */
fun categoryChecks(
    categories: List<Category>,
    membership: Map<Long, Int>,
    total: Int,
): List<CategoryCheck> = CategoryTree.of(categories).map { node ->
    val category = node.category
    val count = membership[category.id] ?: 0
    CategoryCheck(
        category = category,
        state = when {
            count == 0 -> ToggleableState.Off
            count >= total -> ToggleableState.On
            else -> ToggleableState.Indeterminate
        },
        depth = node.depth,
    )
}

/**
 * The category picker. A tap cycles a row forward only: off becomes on, on becomes off, and a
 * partially applied category becomes fully applied before it can be cleared. Going straight from
 * indeterminate to off would silently remove a category from entries the owner never looked at.
 */
@Composable
fun ChangeCategoryDialog(
    rows: List<CategoryCheck>,
    onDismissRequest: () -> Unit,
    onConfirm: (List<CategoryCheck>) -> Unit,
) {
    var current by remember(rows) { mutableStateOf(rows) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.manga_categories_title)) },
        text = {
            if (current.isEmpty()) {
                Text(stringResource(R.string.manga_categories_none))
            } else {
                Column(
                    modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    current.forEach { row ->
                        CategoryRow(
                            row = row,
                            onClick = {
                                current = current.map {
                                    if (it.id == row.id) it.copy(state = it.state.next()) else it
                                }
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(current) },
                enabled = current.isNotEmpty(),
            ) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * A category's place in an include and exclude pair of preferences, the two a category row of the
 * settings writes (phase 3 unit AD): upstream's `TriStateListDialog` cycles a tap from unchecked to
 * included, to excluded, and back.
 */
enum class CategoryFilterState {
    UNCHECKED,
    INCLUDED,
    EXCLUDED,
    ;

    fun next(): CategoryFilterState = when (this) {
        UNCHECKED -> INCLUDED
        INCLUDED -> EXCLUDED
        EXCLUDED -> UNCHECKED
    }

    /**
     * What the row's box is read out as. An excluded category is upstream's one "Disabled" (phase 5
     * unit FX, R126 carry-over 2), as upstream draws it, where it had a copy of its own.
     */
    @StringRes
    fun description(): Int = when (this) {
        UNCHECKED -> R.string.category_filter_not_selected
        INCLUDED -> R.string.category_filter_selected
        EXCLUDED -> R.string.disabled
    }
}

/** One row of [CategoryFilterDialog]. */
data class CategoryFilterRow(val category: Category, val state: CategoryFilterState)

/**
 * The dialog's rows, every category in the order given, from the stored id sets. A category in both
 * sets reads as included, as upstream's dialog checks the included list first.
 */
fun categoryFilterRows(categories: List<Category>, included: Set<String>, excluded: Set<String>): List<CategoryFilterRow> =
    categories.map { category ->
        val id = category.id.toString()
        CategoryFilterRow(
            category = category,
            state = when (id) {
                in included -> CategoryFilterState.INCLUDED
                in excluded -> CategoryFilterState.EXCLUDED
                else -> CategoryFilterState.UNCHECKED
            },
        )
    }

/** The ids to store for [state], as strings, the form upstream's category preferences keep. */
fun List<CategoryFilterRow>.idsIn(state: CategoryFilterState): Set<String> =
    filter { it.state == state }.mapTo(mutableSetOf()) { it.category.id.toString() }

/** What one line of a category row's summary names: every category, none, or these. */
sealed interface CategoryFilterPart {
    data object All : CategoryFilterPart
    data object None : CategoryFilterPart
    data class Some(val categories: List<Category>) : CategoryFilterPart
}

/**
 * Upstream's `getCategoriesLabel` over the stored id sets, as the two parts its "Include: " and
 * "Exclude: " lines name. An id no category has any more is left out; the named ones go in category
 * order.
 */
fun categoryFilterSummary(
    categories: List<Category>,
    included: Set<String>,
    excluded: Set<String>,
): Pair<CategoryFilterPart, CategoryFilterPart> {
    fun named(ids: Set<String>) = ids.mapNotNull { id -> categories.find { it.id.toString() == id } }.sortedBy { it.order }
    val includedCategories = named(included)
    val excludedCategories = named(excluded)
    val allExcluded = excludedCategories.size == categories.size
    val include = when {
        includedCategories.isNotEmpty() && includedCategories.size != categories.size ->
            CategoryFilterPart.Some(includedCategories)
        includedCategories.size == categories.size -> CategoryFilterPart.All
        allExcluded -> CategoryFilterPart.None
        else -> CategoryFilterPart.All
    }
    val exclude = when {
        excludedCategories.isEmpty() -> CategoryFilterPart.None
        allExcluded -> CategoryFilterPart.All
        else -> CategoryFilterPart.Some(excludedCategories)
    }
    return include to exclude
}

/** [categoryFilterSummary] in words, the two lines under a category row's title. */
@Composable
fun categoryFilterLabel(categories: List<Category>, included: Set<String>, excluded: Set<String>): String {
    val (include, exclude) = categoryFilterSummary(categories, included, excluded)
    return stringResource(R.string.category_filter_include_format, include.text()) + "\n" +
        stringResource(R.string.category_filter_exclude_format, exclude.text())
}

@Composable
private fun CategoryFilterPart.text(): String = when (this) {
    CategoryFilterPart.All -> stringResource(R.string.category_filter_all)
    CategoryFilterPart.None -> stringResource(R.string.category_filter_none)
    is CategoryFilterPart.Some -> categories.map { it.visualName }.joinToString()
}

/**
 * The one tri-state category dialog, for the Library section's update categories and the Downloads
 * section's new chapter categories (phase 3 unit AD), after upstream's `TriStateListDialog`: [message]
 * says how the two sets combine, a tap moves a row to its [CategoryFilterState.next] state, and OK
 * hands back the included and excluded ids. Nothing is written until then.
 */
@Composable
fun CategoryFilterDialog(
    message: String,
    categories: List<Category>,
    included: Set<String>,
    excluded: Set<String>,
    onDismissRequest: () -> Unit,
    onConfirm: (included: Set<String>, excluded: Set<String>) -> Unit,
) {
    var rows by remember(categories) { mutableStateOf(categoryFilterRows(categories, included, excluded)) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.categories_title)) },
        text = {
            Column {
                Text(message, modifier = Modifier.padding(bottom = 8.dp))
                Column(modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    rows.forEach { row ->
                        CategoryFilterRowItem(
                            row = row,
                            onClick = {
                                rows = rows.map {
                                    if (it.category.id == row.category.id) it.copy(state = it.state.next()) else it
                                }
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(rows.idsIn(CategoryFilterState.INCLUDED), rows.idsIn(CategoryFilterState.EXCLUDED))
                },
            ) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun CategoryFilterRowItem(row: CategoryFilterRow, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when (row.state) {
                CategoryFilterState.UNCHECKED -> Icons.Rounded.CheckBoxOutlineBlank
                CategoryFilterState.INCLUDED -> Icons.Rounded.CheckBox
                CategoryFilterState.EXCLUDED -> Icons.Rounded.DisabledByDefault
            },
            contentDescription = stringResource(row.state.description()),
            modifier = Modifier.padding(end = 20.dp),
            tint = if (row.state == CategoryFilterState.UNCHECKED) {
                LocalContentColor.current
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
        Text(row.category.visualName, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * One library entry [DuplicateMangaDialog] names: the entry, its live chapter count, and its source's
 * name, the remembered one while the source is not installed ([sourceInstalled] false).
 */
data class DuplicateEntry(
    val manga: Manga,
    val chapterCount: Long,
    val sourceName: String,
    val sourceInstalled: Boolean,
)

/**
 * Shown when the entry being added is already in the library under another source. Adding both is
 * legitimate (one source may carry chapters the other does not), so this is a question and not a
 * refusal; the other ways out are to open the copy that is already there, or to migrate it onto
 * this one (phase 4 unit MA, the tap on upstream's duplicate card).
 *
 * Every match is listed, as upstream lists them, each with its source and chapter count. With more
 * than one, View chapters and Migrate act on the one picked in the list, the first until another is
 * picked; upstream's cards act on a tap each, which a dialog of text rows would make easy to hit by
 * accident.
 */
@Composable
fun DuplicateMangaDialog(
    duplicates: List<DuplicateEntry>,
    onDismissRequest: () -> Unit,
    onAddAnyway: () -> Unit,
    onOpenExisting: (mangaId: Long) -> Unit,
    onMigrate: (mangaId: Long) -> Unit,
) {
    val first = duplicates.firstOrNull() ?: return
    var picked by rememberSaveable(duplicates) { mutableLongStateOf(first.manga.id) }
    val several = duplicates.size > 1
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.manga_duplicate_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (several) {
                        stringResource(R.string.manga_duplicates_message)
                    } else {
                        stringResource(R.string.manga_duplicate_message_format, first.manga.displayTitle)
                    },
                )
                Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    duplicates.forEach { duplicate ->
                        DuplicateRow(
                            duplicate = duplicate,
                            selectable = several,
                            selected = duplicate.manga.id == picked,
                            onClick = { picked = duplicate.manga.id },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAddAnyway) { Text(stringResource(R.string.action_add_anyway)) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onDismissRequest) {
                    Text(stringResource(R.string.action_cancel))
                }
                TextButton(onClick = { onOpenExisting(picked) }) {
                    Text(stringResource(R.string.action_view_chapters))
                }
                TextButton(onClick = { onMigrate(picked) }) {
                    Text(stringResource(R.string.migrate))
                }
            }
        },
    )
}

/**
 * One match: its title, then its source and chapter count, the line a chapter row's details take.
 * With [selectable] the row is a radio choice; a single match has nothing to choose between.
 */
@Composable
private fun DuplicateRow(duplicate: DuplicateEntry, selectable: Boolean, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selectable) {
                    Modifier.selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
                } else {
                    Modifier
                },
            )
            .defaultMinSize(minHeight = 48.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (selectable) RadioButton(selected = selected, onClick = null)
        Column {
            Text(
                duplicate.manga.displayTitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val chapters = duplicate.chapterCount.toInt()
            val source = if (duplicate.sourceInstalled) {
                duplicate.sourceName
            } else {
                stringResource(R.string.manga_duplicate_source_not_installed_format, duplicate.sourceName)
            }
            Text(
                listOf(source, pluralStringResource(R.plurals.manga_num_chapters, chapters, chapters.toString()))
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A row under a parent sits this far in, as on the Categories screen (phase 5 unit CT). */
private val CHILD_INDENT = 24.dp

@Composable
private fun CategoryRow(row: CategoryCheck, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = CHILD_INDENT * row.depth, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TriStateCheckbox(state = row.state, onClick = onClick)
        Text(row.category.name, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Off to on, indeterminate to on, on to off. See [ChangeCategoryDialog] for why. */
private fun ToggleableState.next(): ToggleableState =
    if (this == ToggleableState.On) ToggleableState.Off else ToggleableState.On

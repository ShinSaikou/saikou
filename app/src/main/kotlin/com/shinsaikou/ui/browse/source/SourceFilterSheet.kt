package com.shinsaikou.ui.browse.source

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.shinsaikou.i18n.R
import com.shinsaikou.source.model.Filter
import com.shinsaikou.source.model.FilterList
import com.shinsaikou.ui.components.InlineNotice
import tachiyomi.presentation.core.components.AdaptiveSheet
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The source's own filter tree, drawn as a sheet over the listing.
 *
 * **The tree is edited in place, and that is deliberate.** A [Filter] is a mutable object and, for
 * an extension source, the instance the extension itself built. The adapter maps state back onto
 * those instances positionally, so a rebuilt tree would either arrive with the wrong shape or lose
 * the subclass the extension reads its request parameters off, and the search would go out carrying
 * nothing. Every row therefore assigns to `filter.state` and [onApply] hands back the very same
 * [FilterList] this sheet was given.
 *
 * **Which is why there is a revision counter.** An in-place assignment writes to a plain field with
 * no snapshot behind it, so Compose has nothing to invalidate and the row would go on drawing the
 * old value. [revision] is ordinary snapshot state that every edit bumps, and the rule it buys only
 * holds where the counter actually arrives: **every composable that reads a `filter.state` takes it
 * as a parameter, down to the leaf.** Strong skipping skips a call whose parameters all compare
 * equal, a `Filter` is an unstable type and so compares by instance, and the instance is the same
 * one it was before the edit; `indent` and the callback are equal too. A leaf missing the counter is
 * therefore skipped at exactly the moment it has something new to draw. Nor does a row earn a pass
 * by reading state of its own: a dropdown that invalidates its own scope because it also writes an
 * `expanded` flag is an accident of how it happens to be written, not a mechanism to lean on. The
 * same trick keeps `SourceSettingsScreen` honest about `androidx.preference`, for the same reason.
 *
 * **Nothing here may be remembered against the filter list.** `FilterList.equals` returns false
 * unconditionally (by design, so a state holder never mistakes an edited tree for the previous one),
 * so `remember(filters)` would re-initialise on every recomposition and throw away the counter and
 * every expanded group along with it. Reset instead bumps the counter by hand.
 *
 * The sheet is the app's [AdaptiveSheet], as upstream's source filter sheet is: a bottom sheet on a
 * phone and a centred dialog on a tablet, where Material's own bottom sheet stretched the tree
 * across the whole width of a landscape tablet.
 */
@Composable
fun SourceFilterSheet(
    filters: FilterList,
    onDismissRequest: () -> Unit,
    onReset: () -> Unit,
    onApply: (FilterList) -> Unit,
) {
    // Plain remember, not rememberSaveable: the counter is a nudge to recompose, never a value. The
    // edits themselves live on the filter objects, which the view model holds, so a rotation rebuilds
    // every row from the states the owner actually set and a counter back at zero is correct.
    var revision by remember { mutableIntStateOf(0) }

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column {
            Text(
                stringResource(R.string.source_filters_title),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleLarge,
            )
            if (filters.list.isEmpty()) {
                InlineNotice(stringResource(R.string.source_filters_none))
            } else {
                // The rows scroll inside a bounded box rather than taking a weight in the sheet's own
                // column: the sheet wraps its content, and a weight measured against an unbounded height
                // collapses to nothing instead of failing loudly.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = MAX_CONTENT_HEIGHT)
                        .verticalScroll(rememberScrollState()),
                ) {
                    filters.list.forEachIndexed { index, filter ->
                        key(index) {
                            FilterRow(
                                filter = filter,
                                revision = revision,
                                depth = 0,
                                onEdited = { revision++ },
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        onReset()
                        // The owner of the tree swaps in a fresh one, which the rows below cannot see by
                        // themselves for the reason given above.
                        revision++
                    },
                ) {
                    Text(stringResource(R.string.source_filters_reset))
                }
                // It closes the sheet straight after handing the tree back; the sheet has no hide
                // animation of its own to wait for.
                Button(
                    onClick = {
                        onApply(filters)
                        onDismissRequest()
                    },
                ) {
                    Text(stringResource(R.string.action_apply))
                }
            }
        }
    }
}

/**
 * One node of the tree. [revision] is unused inside except as the reason this composable cannot be
 * skipped, and it is handed on to every branch that draws a `filter.state` for that same reason; see
 * the class comment. The two that do not take it, [FilterHeading] and the divider, draw a caption
 * and a line: there is no state under either to fall behind. [depth] indents a group's children so
 * the nesting the source declared is still visible after it is flattened into one column.
 */
@Composable
private fun FilterRow(filter: Filter<*>, revision: Int, depth: Int, onEdited: () -> Unit) {
    val indent = INDENT_STEP * depth
    when (filter) {
        is Filter.Header -> FilterHeading(filter.name, indent)
        is Filter.Separator -> HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        is Filter.Select -> SelectRow(filter, revision, indent, onEdited)
        is Filter.Text -> OutlinedTextField(
            value = filter.state,
            onValueChange = {
                filter.state = it
                onEdited()
            },
            label = { Text(filter.name) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp + indent, end = 16.dp, top = 4.dp, bottom = 4.dp),
            singleLine = true,
            // A filter term is a title or a tag, and autocorrect only damages those.
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        )
        is Filter.CheckBox -> {
            val toggle = {
                filter.state = !filter.state
                onEdited()
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = toggle)
                    .padding(start = 8.dp + indent, end = 16.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = filter.state, onCheckedChange = { toggle() })
                Text(filter.name, style = MaterialTheme.typography.bodyLarge)
            }
        }
        is Filter.TriState -> TriStateRow(filter, revision, indent, onEdited)
        is Filter.Group -> GroupRow(filter, revision, depth, indent, onEdited)
        is Filter.Sort -> SortRows(filter, revision, indent, onEdited)
    }
}

/**
 * A group, collapsed until asked for. Genre groups routinely carry dozens of entries, and a sheet
 * that opens on all of them buries every other filter the source offers.
 */
@Composable
private fun GroupRow(
    filter: Filter.Group,
    revision: Int,
    depth: Int,
    indent: Dp,
    onEdited: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(start = 16.dp + indent, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            filter.name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
        )
        // The row's own label says what this opens, so the chevron adds nothing a reader has to hear
        // twice.
        Icon(
            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = null,
        )
    }
    if (expanded) {
        filter.state.forEachIndexed { index, child ->
            key(index) {
                FilterRow(filter = child, revision = revision, depth = depth + 1, onEdited = onEdited)
            }
        }
    }
}

/**
 * One choice out of a list, behind a dropdown.
 *
 * [revision] is here for the reason the class comment gives, even though writing `expanded` on the
 * way out of the menu already invalidates this scope. That is a side effect of the menu, not a
 * guarantee about the value: a variant that closed the menu some other way would go back to drawing
 * the old label with nothing to say it had.
 *
 * The suppression below goes with it. The counter is read by the caller's skipping check and never
 * by the body, so the compiler calls it unused, and deleting it as dead weight is precisely the
 * regression the leaf rows here had.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
private fun SelectRow(filter: Filter.Select, revision: Int, indent: Dp, onEdited: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp + indent, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            filter.name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
        )
        TextButton(onClick = { expanded = true }) { Text(filter.selected) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            filter.values.forEachIndexed { index, value ->
                DropdownMenuItem(
                    text = { Text(value) },
                    onClick = {
                        expanded = false
                        filter.state = index
                        onEdited()
                    },
                )
            }
        }
    }
}

/**
 * Ignore, include, exclude, in that cycle.
 *
 * The three states are told apart twice over. The box itself carries them as a tri state, which is
 * what a screen reader announces, and the label is struck through in the error colour when the term
 * is excluded, because a tick and a dash are a thin difference to hang an inverted meaning on.
 *
 * Both readings come off `filter.state` and nothing else here is snapshot state, so [revision] is
 * the only thing that lets either of them move; see the class comment.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
private fun TriStateRow(filter: Filter.TriState, revision: Int, indent: Dp, onEdited: () -> Unit) {
    val cycle = {
        filter.state = (filter.state + 1) % TRI_STATE_COUNT
        onEdited()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = cycle)
            .padding(start = 8.dp + indent, end = 16.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TriStateCheckbox(
            state = when (filter.state) {
                Filter.TriState.STATE_INCLUDE -> ToggleableState.On
                Filter.TriState.STATE_EXCLUDE -> ToggleableState.Indeterminate
                else -> ToggleableState.Off
            },
            onClick = cycle,
        )
        Text(
            filter.name,
            style = MaterialTheme.typography.bodyLarge,
            color = if (filter.isExcluded()) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            textDecoration = if (filter.isExcluded()) TextDecoration.LineThrough else TextDecoration.None,
        )
    }
}

/**
 * The sort keys, one row each, with the direction on the chosen one.
 *
 * Tapping the chosen key flips its direction; tapping another key keeps the direction the owner had
 * already picked, so changing what to sort by does not silently change which end of the list they
 * are looking at.
 *
 * [revision] is what moves the radio button and the direction arrow: both are read off
 * `filter.state`, which is a plain field; see the class comment.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
private fun SortRows(filter: Filter.Sort, revision: Int, indent: Dp, onEdited: () -> Unit) {
    FilterHeading(filter.name, indent)
    filter.values.forEachIndexed { index, value ->
        val selection = filter.state
        val active = selection != null && selection.index == index
        val ascending = selection?.ascending ?: true
        val choose = {
            filter.state = Filter.Sort.Selection(index, if (active) !ascending else ascending)
            onEdited()
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = choose)
                .padding(start = 8.dp + indent, end = 16.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = active, onClick = choose)
            Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            if (active) {
                Icon(
                    if (ascending) Icons.Outlined.ArrowUpward else Icons.Outlined.ArrowDownward,
                    contentDescription = stringResource(R.string.action_sort),
                )
            }
        }
    }
}

/**
 * A caption inside the tree. `SectionLabel` is the same thing everywhere else in the app, but it
 * hard codes its own horizontal padding, which would flatten a nested group's indentation.
 */
@Composable
private fun FilterHeading(text: String, indent: Dp) {
    Text(
        text,
        modifier = Modifier.padding(start = 16.dp + indent, end = 16.dp, top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

private val INDENT_STEP = 16.dp
private val MAX_CONTENT_HEIGHT = 420.dp
private const val TRI_STATE_COUNT = 3

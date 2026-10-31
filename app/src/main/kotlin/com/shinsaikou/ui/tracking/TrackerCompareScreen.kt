package com.shinsaikou.ui.tracking

import android.content.Context
import android.content.res.Resources
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.i18n.R
import com.shinsaikou.navigation.LocalNavigator
import com.shinsaikou.tracking.CantCompareReason
import com.shinsaikou.tracking.CompareChoice
import com.shinsaikou.tracking.CompareSection
import com.shinsaikou.tracking.StopKind
import com.shinsaikou.ui.components.ListRowDetail
import com.shinsaikou.ui.components.ListRowLayout
import com.shinsaikou.ui.components.ListRowTitle
import com.shinsaikou.ui.components.MangaCover
import com.shinsaikou.ui.components.ScreenTopBar
import com.shinsaikou.ui.components.SaikouSnackbarHost
import com.shinsaikou.ui.tracking.TrackerCompareViewModel.ConfirmSummary
import com.shinsaikou.ui.tracking.TrackerCompareViewModel.Event
import com.shinsaikou.ui.tracking.TrackerCompareViewModel.Row
import com.shinsaikou.ui.tracking.TrackerCompareViewModel.RowResult
import com.shinsaikou.ui.tracking.TrackerCompareViewModel.Screen
import com.shinsaikou.ui.tracking.TrackerCompareViewModel.Stop
import com.shinsaikou.ui.util.ErrorText
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import tachiyomi.presentation.core.components.LazyColumnWithAction
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction

/**
 * "Compare with MyAnimeList" (phase 4 unit TC), upstream's clear database shape with a choice per row:
 * a spinner with the phase while reading, then the rows in their sections under the summary, a
 * choice dialog per row, Apply under the list behind a confirmation that lists every change, and a
 * dialog back does not dismiss while applying. Every text is the design's section 7, with the
 * tracker's name through a format argument.
 *
 * Leaving the screen is the cancel of a comparison: the view model's scope ends and the calls with
 * it. While applying, back does nothing; the dialog's Cancel stops after the row in progress.
 */
@Composable
fun TrackerCompareScreen(trackerId: Long) {
    val viewModel: TrackerCompareViewModel =
        assistedMetroViewModel<TrackerCompareViewModel, TrackerCompareViewModel.Factory> { create(trackerId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }
    val name = state.trackerName
    var choosing by rememberSaveable { mutableStateOf<Long?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                Event.Back -> navigator.back()
                is Event.Open -> navigator.open(event.route)
                is Event.ChoseHigher -> {
                    val chose = resources.getQuantityString(R.plurals.compare_chose_higher_format, event.chose, event.chose)
                    val left = if (event.left == 0) {
                        ""
                    } else {
                        " " + resources.getQuantityString(R.plurals.compare_left_warned_format, event.left, event.left)
                    }
                    snackbarHostState.showSnackbar(chose + left)
                }
                is Event.Applied -> snackbarHostState.showSnackbar(
                    resources.getString(R.string.compare_applied_format, event.done, event.changed, event.failed),
                )
            }
        }
    }

    BackHandler(enabled = state.applying != null) {}

    val compared = state.screen as? Screen.Compared
    Scaffold(
        topBar = { scrollBehavior ->
            ScreenTopBar(
                title = stringResource(R.string.tracking_compare_title_format, name),
                onBack = navigator::back,
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(
                        onClick = viewModel::compare,
                        enabled = state.screen !is Screen.Reading && state.applying == null,
                        modifier = Modifier.testTag(COMPARE_AGAIN_TAG),
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.compare_again))
                    }
                    if (compared != null && compared.rows.isNotEmpty()) {
                        Box {
                            IconButton(onClick = { menuExpanded = true }, enabled = state.applying == null) {
                                Icon(
                                    Icons.Outlined.MoreVert,
                                    contentDescription = stringResource(R.string.action_menu_overflow_description),
                                )
                            }
                            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.compare_keep_higher)) },
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.keepHigher()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.compare_dont_change_any)) },
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.dontChangeAny()
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SaikouSnackbarHost(snackbarHostState) },
    ) { contentPadding ->
        val modifier = Modifier.padding(contentPadding)
        when (val screen = state.screen) {
            is Screen.Reading -> ReadingState(screen, name, modifier)
            Screen.NothingTracked -> EmptyScreen(
                message = stringResource(R.string.compare_nothing_tracked_format, name),
                modifier = modifier,
            )
            is Screen.Stopped -> EmptyScreen(
                message = stopText(resources, screen.stop, name),
                modifier = modifier,
                actions = listOf(
                    EmptyScreenAction(stopActionRes(screen.stop.kind), Icons.Outlined.Refresh, viewModel::stopAction),
                ),
            )
            is Screen.Compared -> if (screen.rows.isEmpty() && screen.runStop == null) {
                EmptyScreen(
                    message = pluralStringResource(R.plurals.compare_all_agree_format, screen.compared, screen.compared, name),
                    modifier = modifier,
                )
            } else {
                ComparedList(
                    screen = screen,
                    name = name,
                    applyShown = state.applyShown,
                    applyEnabled = state.applyEnabled,
                    applying = state.applying != null,
                    contentPadding = contentPadding,
                    onRow = { row ->
                        when {
                            row.entry.section == CompareSection.CANT_COMPARE -> viewModel.openManga(row.mangaId)
                            row.choice != null -> choosing = row.mangaId
                        }
                    },
                    onCover = viewModel::openManga,
                    onStopAction = viewModel::stopAction,
                    onApply = viewModel::requestApply,
                )
            }
        }
    }

    compared?.rows?.firstOrNull { it.mangaId == choosing && it.choice != null }?.let { row ->
        ChoiceDialog(
            row = row,
            name = name,
            onConfirm = { choice ->
                choosing = null
                viewModel.choose(row.mangaId, choice)
            },
            onDismiss = { choosing = null },
        )
    }
    state.confirming?.let { summary ->
        ConfirmDialog(summary = summary, name = name, onConfirm = viewModel::apply, onDismiss = viewModel::dismissConfirm)
    }
    state.applying?.let { applying ->
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = {
                Text(
                    if (applying.stopping) {
                        stringResource(R.string.compare_stopping)
                    } else {
                        stringResource(R.string.compare_applying_format, applying.current, applying.total)
                    },
                )
            },
            text = { CircularProgressIndicator() },
            confirmButton = {
                TextButton(
                    onClick = viewModel::cancelApply,
                    enabled = !applying.stopping,
                    modifier = Modifier.testTag(COMPARE_CANCEL_APPLY_TAG),
                ) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun ReadingState(screen: Screen.Reading, name: String, modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            text = when {
                screen.checking != null ->
                    pluralStringResource(R.plurals.compare_checking_format, screen.checking, screen.checking, name)
                screen.syncing -> stringResource(R.string.compare_syncing)
                else -> stringResource(R.string.compare_reading_list_format, name)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ComparedList(
    screen: Screen.Compared,
    name: String,
    applyShown: Boolean,
    applyEnabled: Boolean,
    applying: Boolean,
    contentPadding: PaddingValues,
    onRow: (Row) -> Unit,
    onCover: (Long) -> Unit,
    onStopAction: () -> Unit,
    onApply: () -> Unit,
) {
    val resources = LocalResources.current
    val context = LocalContext.current
    val sections = screen.rows.groupBy { it.entry.section }
    var cantOpened by rememberSaveable { mutableStateOf(false) }
    val cantOpen = cantOpened || sections.keys.singleOrNull() == CompareSection.CANT_COMPARE
    val content: LazyListScope.() -> Unit = {
        item(key = "header") {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = pluralStringResource(
                        R.plurals.compare_summary_format,
                        screen.compared,
                        screen.compared,
                        pluralStringResource(R.plurals.compare_summary_agree, screen.agreeing, screen.agreeing),
                        pluralStringResource(R.plurals.compare_summary_differ, screen.differing, screen.differing),
                        pluralStringResource(R.plurals.compare_summary_cant, screen.cantCompare, screen.cantCompare),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(COMPARE_SUMMARY_TAG),
                )
                screen.sync?.let { sync ->
                    Text(
                        text = if (sync.lastSuccessAt > 0) {
                            stringResource(R.string.compare_last_synced_format, syncTimeText(context, sync.lastSuccessAt))
                        } else {
                            stringResource(R.string.compare_never_synced)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                screen.runStop?.let { runStop ->
                    Text(
                        text = pluralStringResource(R.plurals.compare_stopped_format, runStop.total, runStop.reached, runStop.total) +
                            " " + stopText(resources, runStop.stop, name),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onStopAction, enabled = !applying) {
                        Text(stringResource(stopActionRes(runStop.stop.kind)))
                    }
                }
            }
            HorizontalDivider()
        }
        CompareSection.entries.forEach { section ->
            val rows = sections[section].orEmpty()
            if (rows.isEmpty()) return@forEach
            val open = section != CompareSection.CANT_COMPARE || cantOpen
            item(key = "section_$section") {
                val title = when (section) {
                    CompareSection.REMOTE_AHEAD -> stringResource(R.string.compare_section_remote_ahead_format, name)
                    CompareSection.APP_AHEAD -> stringResource(R.string.compare_section_app_ahead)
                    CompareSection.NOT_ON_LIST -> stringResource(R.string.compare_section_not_on_list_format, name)
                    CompareSection.CANT_COMPARE -> stringResource(R.string.compare_section_cant_compare_format, rows.size)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .let { if (section == CompareSection.CANT_COMPARE) it.clickable { cantOpened = !cantOpen } else it }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    if (section == CompareSection.CANT_COMPARE) {
                        Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
                    }
                }
            }
            if (open) {
                items(rows, key = { it.mangaId }) { row ->
                    CompareRow(row = row, name = name, applying = applying, onClick = { onRow(row) }, onCover = { onCover(row.mangaId) })
                }
            }
        }
    }
    if (applyShown) {
        LazyColumnWithAction(
            contentPadding = contentPadding,
            actionLabel = stringResource(R.string.action_apply),
            actionEnabled = applyEnabled,
            onClickAction = onApply,
            modifier = Modifier.testTag(COMPARE_LIST_TAG),
            content = content,
        )
    } else {
        LazyColumn(modifier = Modifier.padding(contentPadding).testTag(COMPARE_LIST_TAG), content = content)
    }
}

@Composable
private fun CompareRow(row: Row, name: String, applying: Boolean, onClick: () -> Unit, onCover: () -> Unit) {
    val entry = row.entry
    val resources = LocalResources.current
    // `ListRowLayout`'s frame, cover slot and divider; the cover keeps its own tap.
    ListRowLayout(
        modifier = Modifier.testTag(compareRowTag(row.mangaId)),
        onClick = onClick.takeIf { !applying },
        leading = {
            MangaCover(
                entry.manga,
                modifier = Modifier.width(48.dp).clickable(enabled = !applying, onClick = onCover),
                contentDescription = entry.manga.displayTitle,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ListRowTitle(entry.manga.displayTitle)
            if (entry.cantCompare != CantCompareReason.COULD_NOT_CHECK) {
                ListRowDetail(inAppText(resources, entry), maxLines = Int.MAX_VALUE)
            }
            entry.listed?.let { listed ->
                ListRowDetail(
                    if (entry.count > 0) {
                        stringResource(R.string.compare_on_remote_of_format, name, listed.chaptersRead.toString(), entry.count.toInt())
                    } else {
                        stringResource(R.string.compare_on_remote_format, name, listed.chaptersRead.toString())
                    },
                    maxLines = Int.MAX_VALUE,
                )
            }
            notes(resources, row, name, inDialog = false).forEach { note ->
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
            val result = row.result
            val line = when {
                result != null -> resultText(resources, result, name)
                row.choice != null -> sentence(resources, row, row.choice, name)
                else -> null
            }
            line?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (result is RowResult.Failed || result is RowResult.Unanswered) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

/** The choice dialog: a radio row per choice with its sentence, then the row's warnings and notes. */
@Composable
private fun ChoiceDialog(row: Row, name: String, onConfirm: (CompareChoice) -> Unit, onDismiss: () -> Unit) {
    val resources = LocalResources.current
    var selection by rememberSaveable(row.mangaId) { mutableStateOf(row.choice ?: CompareChoice.DONT_CHANGE) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(row.entry.manga.displayTitle) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.entry.choices.forEach { choice ->
                    val selected = selection == choice
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = selected, onClick = { selection = choice })
                            .testTag(compareChoiceTag(choice)),
                        verticalAlignment = Alignment.Top,
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Column(modifier = Modifier.padding(start = 12.dp)) {
                            Text(choiceLabel(resources, row, choice, name), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                sentence(resources, row, choice, name),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                notes(resources, row, name, inDialog = true).forEach { note ->
                    Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selection) }, modifier = Modifier.testTag(COMPARE_CHOICE_OK_TAG)) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Apply's confirmation: the count in the title, then a line per kind of change present. */
@Composable
private fun ConfirmDialog(summary: ConfirmSummary, name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val resources = LocalResources.current
    val lines = buildList {
        if (summary.markEntries > 0) {
            add(
                resources.getString(
                    R.string.compare_confirm_mark_format,
                    resources.getQuantityString(R.plurals.compare_count_chapters, summary.markChapters, summary.markChapters),
                    resources.getQuantityString(R.plurals.compare_count_entries, summary.markEntries, summary.markEntries),
                ),
            )
        }
        if (summary.updates > 0) {
            add(
                resources.getString(
                    R.string.compare_confirm_update_format,
                    name,
                    resources.getQuantityString(R.plurals.compare_count_entries, summary.updates, summary.updates),
                ),
            )
        }
        summary.setBacks.forEach { add(resources.getString(R.string.compare_confirm_set_back_format, it.title, it.from.toString(), it.to.toString())) }
        if (summary.completed > 0) {
            add(resources.getQuantityString(R.plurals.compare_confirm_completed_format, summary.completed, summary.completed))
        }
        if (summary.addBacks > 0) {
            add(
                resources.getString(
                    R.string.compare_confirm_add_back_format,
                    resources.getQuantityString(R.plurals.compare_count_entries, summary.addBacks, summary.addBacks),
                    name,
                ),
            )
        }
        if (summary.removals > 0) {
            add(
                resources.getString(
                    R.string.compare_confirm_remove_format,
                    name,
                    resources.getQuantityString(R.plurals.compare_count_entries, summary.removals, summary.removals),
                ),
            )
        }
        if (summary.keeps > 0) {
            add(resources.getQuantityString(R.plurals.compare_confirm_keep_format, summary.keeps, summary.keeps, name))
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.compare_confirm_title_format, summary.changes, summary.changes)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                lines.forEach { Text(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(COMPARE_CONFIRM_APPLY_TAG)) {
                Text(stringResource(R.string.action_apply))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun stopText(resources: Resources, stop: Stop, name: String): String = when (stop.kind) {
    StopKind.SIGN_IN_REFUSED -> ErrorText.of(resources, ErrorKey.TRACKER_AUTH_EXPIRED, null)
    StopKind.UNAUTHORIZED -> resources.getString(R.string.compare_stop_unauthorized_format, name)
    StopKind.FORBIDDEN -> resources.getString(R.string.compare_stop_forbidden_format, name)
    StopKind.THROTTLED -> resources.getString(R.string.compare_stop_throttled_format, name)
    StopKind.OTHER -> ErrorText.of(resources, stop.problem.key, stop.problem.detail)
}

private fun stopActionRes(kind: StopKind): Int = when (kind) {
    StopKind.SIGN_IN_REFUSED -> R.string.tracking_sign_in_again
    StopKind.UNAUTHORIZED -> R.string.compare_back_to_settings
    else -> R.string.action_retry
}

/** A run's time the way Sync settings writes one: the device's locale and its 12 or 24 hour setting. */
private fun syncTimeText(context: Context, millis: Long): String = DateUtils.formatDateTime(
    context,
    millis,
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME,
)

internal const val COMPARE_AGAIN_TAG = "compare_again"
internal const val COMPARE_LIST_TAG = "compare_list"
internal const val COMPARE_SUMMARY_TAG = "compare_summary"
internal const val COMPARE_CHOICE_OK_TAG = "compare_choice_ok"
internal const val COMPARE_CONFIRM_APPLY_TAG = "compare_confirm_apply"
internal const val COMPARE_CANCEL_APPLY_TAG = "compare_cancel_apply"

internal fun compareRowTag(mangaId: Long) = "compare_row_$mangaId"
internal fun compareChoiceTag(choice: CompareChoice) = "compare_choice_$choice"

package com.shinsaikou.ui.settings

import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.downloads.adoption.AdoptionFailure
import com.shinsaikou.downloads.adoption.AdoptionFailureReason
import com.shinsaikou.downloads.adoption.AdoptionScheduler
import com.shinsaikou.downloads.adoption.AdoptionSummary
import com.shinsaikou.downloads.adoption.AdoptionUnmatchedReason
import com.shinsaikou.extensions.model.Extension
import com.shinsaikou.i18n.R
import com.shinsaikou.navigation.BrowsePage
import com.shinsaikou.navigation.LocalNavigator
import com.shinsaikou.navigation.Route
import com.shinsaikou.ui.browse.labelRes
import com.shinsaikou.ui.components.ListRow
import com.shinsaikou.ui.components.ListRowDetail
import com.shinsaikou.ui.components.ListRowLayout
import com.shinsaikou.ui.components.ListRowTitle
import com.shinsaikou.ui.components.ScreenTopBar
import com.shinsaikou.ui.settings.AdoptionSummaryViewModel.UnmatchedRow
import com.shinsaikou.ui.util.ErrorText
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.tachiyomi.extension.model.InstallStep
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import java.text.DateFormat
import java.util.Date

/**
 * The last copy from a folder (unit adoption-unmatched-reasons), from the Downloads section's Last
 * copy from a folder row, in place of the dialog that listed bare paths. In order: when the run
 * ended and its counts; what matched nothing, by reason, each with what would match it next time
 * (Install or Open Extensions for a source not installed, Search for an entry not in the library,
 * Open entry for chapters its chapter list does not name); what was matched and not copied; and
 * Copy again at the bottom, over the same folder, which skips every chapter already here.
 */
@Composable
fun AdoptionSummaryScreen() {
    val viewModel: AdoptionSummaryViewModel = metroViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val context = LocalContext.current

    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            // As the Downloads section's copy row takes it: a read grant only, persisted so a run
            // WorkManager finishes in a later process can still read the folder.
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.copyFrom(uri.toString())
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is AdoptionSummaryViewModel.Event.PickFolder -> pickTree.launch(pickerHint(event.hint))
            }
        }
    }

    Scaffold(
        topBar = { scrollBehavior ->
            ScreenTopBar(
                title = stringResource(R.string.pref_adopt_summary),
                onBack = navigator::back,
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            if (state.summary != null) CopyAgainBar(state.adoption, onCopyAgain = viewModel::copyAgain)
        },
    ) { padding ->
        val summary = state.summary
        when {
            state.loading -> LoadingScreen(Modifier.padding(padding))
            summary == null -> EmptyScreen(stringRes = R.string.adopt_summary_none, modifier = Modifier.padding(padding))
            else -> LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                item { SummaryCounts(summary) }
                if (state.rows.isNotEmpty()) {
                    item { HeadingItem(R.string.pref_adopt_summary_unmatched) }
                    items(state.rows, key = { "unmatched-${it.path}" }) { row ->
                        when (row) {
                            is UnmatchedRow.SourceFolder -> SourceFolderRow(
                                row = row,
                                step = row.available?.let { state.installSteps[it.packageName] },
                                onInstall = viewModel::install,
                                onCancelInstall = viewModel::cancelInstall,
                                onOpenExtensions = {
                                    navigator.open(Route.Browse(BrowsePage.EXTENSIONS, extensionsQuery = row.name))
                                },
                            )
                            is UnmatchedRow.EntryFolder -> EntryFolderRow(
                                row = row,
                                onSearch = { navigator.open(Route.GlobalSearch(query = row.title)) },
                            )
                            is UnmatchedRow.EntryChapters -> EntryChaptersRow(
                                row = row,
                                onOpenEntry = row.mangaId?.let { id -> { navigator.open(Route.MangaDetail(id)) } },
                            )
                            is UnmatchedRow.Other -> OtherRow(row)
                        }
                    }
                }
                if (summary.failures.isNotEmpty()) {
                    item { HeadingItem(R.string.pref_adopt_summary_failures) }
                    items(summary.failures) { failure ->
                        ListRow(
                            title = failure.path.ifEmpty { stringResource(R.string.pref_adopt_summary_the_folder) },
                            detail = adoptionFailureText(LocalResources.current, failure),
                        )
                    }
                }
            }
        }
    }
}

/** When the run ended and what it did with the chapters it matched. */
@Composable
private fun SummaryCounts(summary: AdoptionSummary) {
    ListRowLayout {
        ListRowTitle(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(summary.finishedAt)))
        ListRowDetail(
            stringResource(R.string.pref_adopt_summary_counts_format, summary.matched, summary.copied, summary.skipped),
            maxLines = 3,
        )
    }
}

/**
 * Copy again, disabled with the run's progress above it while a copy runs, as the Downloads
 * section's copy row shows it.
 */
@Composable
private fun CopyAgainBar(adoption: AdoptionScheduler.State, onCopyAgain: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(MaterialTheme.padding.small)) {
        if (adoption.running) {
            Text(
                text = if (adoption.total == 0) {
                    stringResource(R.string.pref_adopt_downloads_walking)
                } else {
                    stringResource(R.string.pref_adopt_downloads_progress_format, adoption.done, adoption.total)
                },
                modifier = Modifier.padding(bottom = MaterialTheme.padding.small),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(
            onClick = onCopyAgain,
            enabled = !adoption.running,
            modifier = Modifier.fillMaxWidth(),
            elevation = ButtonDefaults.elevatedButtonElevation(),
        ) {
            Text(stringResource(R.string.adopt_copy_again))
        }
    }
}

/**
 * A source folder no installed source answers: **Install** while a repository offers its
 * extension, with the Extensions tab's ring and step while it installs (ruling R54), else **Open
 * Extensions** searched for the source's bare name; nothing once the source is live, where Copy
 * again is the action.
 */
@Composable
private fun SourceFolderRow(
    row: UnmatchedRow.SourceFolder,
    step: InstallStep?,
    onInstall: (Extension.Available) -> Unit,
    onCancelInstall: (String) -> Unit,
    onOpenExtensions: () -> Unit,
) {
    val available = row.available
    val stepRes = step?.labelRes()
    ListRowLayout(
        trailing = {
            when {
                row.installed -> Unit
                available != null && stepRes != null -> IconButton(onClick = { onCancelInstall(available.packageName) }) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.ext_action_cancel_install),
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                available != null -> TextButton(onClick = { onInstall(available) }) {
                    Text(stringResource(R.string.action_install))
                }
                else -> TextButton(onClick = onOpenExtensions) {
                    Text(stringResource(R.string.import_report_open_extensions))
                }
            }
        },
    ) {
        ListRowTitle(row.lang?.let { "${row.name} ($it)" } ?: row.name)
        when {
            row.installed -> ListRowDetail(stringResource(R.string.adopt_unmatched_source_installed))
            stepRes != null -> Text(
                stringResource(stepRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
            else -> ListRowDetail(stringResource(R.string.manga_source_not_installed))
        }
    }
}

/** An entry folder no library entry answers, with **Search** across the sources for its title. */
@Composable
private fun EntryFolderRow(row: UnmatchedRow.EntryFolder, onSearch: () -> Unit) {
    ListRowLayout(
        trailing = { TextButton(onClick = onSearch) { Text(stringResource(R.string.action_search)) } },
    ) {
        ListRowTitle(row.title)
        row.source?.let { ListRowDetail(it, maxLines = 1) }
        ListRowDetail(stringResource(R.string.adopt_unmatched_not_in_library))
    }
}

/** An entry's chapters its chapter list does not name, with **Open entry**, where the list refreshes. */
@Composable
private fun EntryChaptersRow(row: UnmatchedRow.EntryChapters, onOpenEntry: (() -> Unit)?) {
    ListRowLayout(
        trailing = {
            if (onOpenEntry != null) {
                TextButton(onClick = onOpenEntry) { Text(stringResource(R.string.adopt_open_entry)) }
            }
        },
    ) {
        ListRowTitle(row.title)
        row.source?.let { ListRowDetail(it, maxLines = 1) }
        ListRowDetail(pluralStringResource(R.plurals.adopt_unmatched_no_chapter_count, row.count, row.count))
    }
}

/** A path with no action: an unfinished download, a second copy, or one from a summary with no reasons. */
@Composable
private fun OtherRow(row: UnmatchedRow.Other) {
    ListRowLayout {
        ListRowTitle(row.path, maxLines = 3)
        ListRowDetail(
            stringResource(
                when (row.reason) {
                    AdoptionUnmatchedReason.UNFINISHED -> R.string.adopt_unmatched_unfinished
                    AdoptionUnmatchedReason.DUPLICATE -> R.string.adopt_unmatched_duplicate
                    else -> R.string.adopt_unmatched_unknown
                },
            ),
        )
    }
}

/**
 * Where the picker opens for Copy again: the stored tree as a document, since the picker's hint
 * takes a document; none for a summary that kept no tree, or a tree that is not a document tree.
 */
private fun pickerHint(tree: String?): Uri? = tree?.let {
    runCatching {
        val uri = it.toUri()
        DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
    }.getOrNull()
}

/** Why a chapter was not adopted, in words; a failure with an error key reads as the screens name it. */
private fun adoptionFailureText(resources: Resources, failure: AdoptionFailure): String = when (failure.reason) {
    AdoptionFailureReason.NO_PAGES -> resources.getString(R.string.adopt_failure_no_pages)
    AdoptionFailureReason.PAGE_COUNT_MISMATCH ->
        resources.getString(R.string.adopt_failure_page_count_format, failure.detail.orEmpty())
    AdoptionFailureReason.SIZE_MISMATCH -> resources.getString(R.string.adopt_failure_size)
    AdoptionFailureReason.NOT_AN_ARCHIVE -> resources.getString(R.string.adopt_failure_not_an_archive)
    AdoptionFailureReason.DESTINATION_IN_USE -> resources.getString(R.string.adopt_failure_destination_in_use)
    AdoptionFailureReason.QUEUED -> resources.getString(R.string.adopt_failure_queued)
    AdoptionFailureReason.STORAGE_FULL -> resources.getString(R.string.adopt_failure_storage_full)
    AdoptionFailureReason.ERROR -> ErrorText.of(resources, failure.key ?: ErrorKey.GENERIC, failure.detail)
}

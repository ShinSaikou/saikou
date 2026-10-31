package com.shinsaikou.ui.backup

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinsaikou.backup.importer.ImportError
import com.shinsaikou.backup.importer.ImportReport
import com.shinsaikou.downloads.adoption.AdoptionScheduler
import com.shinsaikou.i18n.R
import com.shinsaikou.navigation.BrowsePage
import com.shinsaikou.navigation.LocalNavigator
import com.shinsaikou.navigation.Route
import com.shinsaikou.navigation.SettingsSection
import com.shinsaikou.ui.browse.extension.MissingExtensionsList
import com.shinsaikou.ui.components.InlineNotice
import com.shinsaikou.ui.components.ListRow
import com.shinsaikou.ui.components.ListRowDetail
import com.shinsaikou.ui.components.ListRowLayout
import com.shinsaikou.ui.components.ListRowTitle
import com.shinsaikou.ui.components.ScreenTopBar
import com.shinsaikou.ui.components.SectionLabel
import com.shinsaikou.ui.components.SaikouSnackbarHost
import com.shinsaikou.ui.settings.STORAGE_ROOT_HINT
import dev.zacsweers.metrox.viewmodel.metroViewModel
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import java.text.DateFormat
import java.util.Date

private const val ERROR_LOG_MIME = "text/plain"

/**
 * The restore report (unit restore-report), from the completion notification and the Backup
 * screen's Restore report row. In order: when the run ended and its counts; the sources the file
 * named that have no extension here, each with Install or Open Extensions; the trackers to sign in
 * to, each with Sign in; the trackers this build lacks or does not know; the copy of downloads from
 * a folder, which no backup carries; and the errors by kind, with Export error log. A section with
 * nothing in it is left out.
 *
 * Upstream shows the first two lists before a restore and opens its log after one; Saikou shows them
 * before too (the Backup screen's check) and keeps them here, with the action beside each line.
 */
@Composable
fun ImportReportScreen() {
    val viewModel: ImportReportViewModel = metroViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ImportReportViewModel.Event.Snackbar ->
                    snackbarHostState.showSnackbar(resources.getString(event.messageRes))
            }
        }
    }

    val exportLog = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ERROR_LOG_MIME)) { uri ->
        if (uri != null) viewModel.exportErrors(uri)
    }
    val context = LocalContext.current
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            // As the Downloads section's copy row takes it: a read grant only, persisted so a run
            // WorkManager finishes in a later process can still read the folder.
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.copyDownloadsFrom(uri.toString())
        }
    }

    Scaffold(
        topBar = { scrollBehavior ->
            ScreenTopBar(
                title = stringResource(R.string.import_report_title),
                onBack = navigator::back,
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SaikouSnackbarHost(snackbarHostState) },
    ) { padding ->
        val report = state.report
        when {
            state.loading -> LoadingScreen(Modifier.padding(padding))
            report == null -> EmptyScreen(stringRes = R.string.import_report_none, modifier = Modifier.padding(padding))
            else -> LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                item { ReportCounts(report) }
                if (state.nothingMissing) {
                    item { InlineNotice(stringResource(R.string.import_report_nothing_missing)) }
                }
                if (state.sources.isNotEmpty()) {
                    item { HeadingItem(R.string.import_report_missing_sources) }
                    item {
                        MissingExtensionsList(
                            rows = state.sources,
                            installSteps = state.installSteps,
                            onInstall = viewModel::install,
                            onCancelInstall = viewModel::cancelInstall,
                            onOpenExtensions = { row ->
                                navigator.open(Route.Browse(BrowsePage.EXTENSIONS, extensionsQuery = row.query))
                            },
                        )
                    }
                }
                if (state.trackers.isNotEmpty()) {
                    item { HeadingItem(R.string.import_report_missing_trackers) }
                    items(state.trackers, key = { "tracker-${it.id}" }) { tracker ->
                        TrackerRow(
                            name = tracker.name,
                            onSignIn = { navigator.open(Route.Settings(SettingsSection.TRACKING)) },
                        )
                    }
                }
                trackerLines(report)
                item { HeadingItem(R.string.settings_section_downloads) }
                item {
                    CopyDownloadsRow(state.adoption, onPick = { pickTree.launch(STORAGE_ROOT_HINT.toUri()) })
                }
                if (state.errorGroups.isNotEmpty()) {
                    item { HeadingItem(R.string.import_report_errors) }
                    // Above the list rather than below it: a run where every entry failed lists
                    // one line per entry, and the export should not sit at the end of them.
                    item {
                        OutlinedButton(
                            onClick = { exportLog.launch(viewModel.errorLogFileName()) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Text(stringResource(R.string.import_export_error_log))
                        }
                    }
                    errorGroups(state.errorGroups)
                }
            }
        }
    }
}

/** When the run ended, what it did, and whether it was stopped before the end. */
@Composable
private fun ReportCounts(report: ImportReport) {
    ListRowLayout {
        ListRowTitle(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(report.finishedAt)))
        ListRowDetail(
            stringResource(R.string.import_report_counts_format, report.mangaInserted, report.mangaMerged, report.mangaFailed),
            maxLines = 3,
        )
        if (report.cancelled) {
            ListRowDetail(stringResource(R.string.import_report_cancelled))
        }
    }
}

/** The Downloads section's copy from a folder, with its progress while it runs. */
@Composable
private fun CopyDownloadsRow(adoption: AdoptionScheduler.State, onPick: () -> Unit) {
    ListRow(
        title = stringResource(R.string.pref_adopt_downloads),
        detail = when {
            adoption.running && adoption.total == 0 -> stringResource(R.string.pref_adopt_downloads_walking)
            adoption.running -> stringResource(R.string.pref_adopt_downloads_progress_format, adoption.done, adoption.total)
            else -> stringResource(R.string.import_report_copy_downloads_detail)
        },
        onClick = if (adoption.running) null else onPick,
    )
}

/** A tracker the run found signed out, with the Tracking section's Sign in. */
@Composable
private fun TrackerRow(name: String, onSignIn: () -> Unit) {
    ListRowLayout(
        trailing = {
            TextButton(onClick = onSignIn) { Text(stringResource(R.string.tracking_sign_in)) }
        },
    ) {
        ListRowTitle(name)
    }
}

/**
 * A line per tracker this build lacks, whose rows are kept for a later build, and per tracker id it
 * does not know, whose rows were skipped. Neither has an action in this build: the first needs a
 * tracker Saikou does not ship, the second a file from a newer app.
 */
private fun LazyListScope.trackerLines(report: ImportReport) {
    items(report.unsupportedTrackers, key = { "unsupported-$it" }) { id ->
        InlineNotice(stringResource(R.string.backup_restore_unsupported_tracker_format, id.toInt()))
    }
    items(report.unknownTrackers, key = { "unknown-$it" }) { id ->
        InlineNotice(stringResource(R.string.import_report_unknown_tracker_format, id.toInt()))
    }
}

/** A heading per kind, then its errors, one lazy row each. */
private fun LazyListScope.errorGroups(groups: List<Pair<ImportError.Kind, List<ImportError>>>) {
    groups.forEach { (kind, errors) ->
        item(key = "kind-${kind.name}") { SectionLabel(stringResource(kind.labelRes())) }
        items(errors) { error ->
            ListRow(title = ImportReportViewModel.errorSubject(error), detail = error.message)
        }
    }
}

/** The heading of a kind of error: the backup option it belongs to, where there is one. */
@StringRes
private fun ImportError.Kind.labelRes(): Int = when (this) {
    ImportError.Kind.MANGA_ROW -> R.string.backup_option_library
    ImportError.Kind.CHAPTERS -> R.string.backup_option_chapters
    ImportError.Kind.HISTORY -> R.string.backup_option_history
    ImportError.Kind.TRACKING -> R.string.backup_option_tracking
    ImportError.Kind.CATEGORIES_OF_MANGA -> R.string.import_error_kind_categories_of_manga
    ImportError.Kind.EXCLUDED_SCANLATORS -> R.string.import_error_kind_excluded_scanlators
    ImportError.Kind.CATEGORY -> R.string.backup_option_categories
    ImportError.Kind.PREFERENCE_TYPE_MISMATCH -> R.string.import_error_kind_preference_type_mismatch
    ImportError.Kind.PREFERENCE -> R.string.backup_option_app_settings
    ImportError.Kind.SOURCE_PREFERENCE -> R.string.backup_option_source_settings
    ImportError.Kind.EXTENSION_STORE -> R.string.backup_option_stores
    ImportError.Kind.UNKNOWN_TRACKER_SKIPPED -> R.string.import_error_kind_unknown_tracker
    ImportError.Kind.BATCH_FALLBACK -> R.string.import_error_kind_batch_fallback
}

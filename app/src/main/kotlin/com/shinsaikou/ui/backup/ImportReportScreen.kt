package com.shinsaikou.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
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
 * screen's Restore report row. In order: when the run ended and its counts; what Install
 * extensions did, with the extensions it left for a tap because only a store the backup added lists
 * them, each store named, and Install all; the sources the file named that have no extension here,
 * each with Install or Open Extensions, and Install all above them when it is not shown already;
 * the trackers to sign in
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
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        // The scheduler takes the read grant, and only for a run it starts.
        if (uri != null) viewModel.copyDownloadsFrom(uri.toString())
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
                extensionInstalls(report, state.waiting, state.installingAll, viewModel::installAll)
                if (state.sources.isNotEmpty()) {
                    item { HeadingItem(R.string.import_report_missing_sources) }
                    if (state.waiting.isEmpty() && state.sources.count { it.available != null } > 1) {
                        item { InstallAllButton(state.installingAll, viewModel::installAll) }
                    }
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

/** Install all, or which of how many it is installing while it runs. */
@Composable
private fun InstallAllButton(installing: RestoreExtensionInstalls.Progress?, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = installing == null,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            if (installing == null) {
                stringResource(R.string.import_report_install_all)
            } else {
                stringResource(R.string.import_report_installing_format, installing.done, installing.total)
            },
        )
    }
}

/**
 * What the restore's Install extensions did: the extensions it installed, those it left for a tap
 * because only a store the backup added lists them ([waiting], still not installed), under each
 * store's name and address with Install all, the sources by the backup's names that no extension
 * store lists (so the owner knows what to search for), the extensions that did not install (each
 * with its reason among the errors), and the stores whose index could not be read. Left out when
 * the option was off or the run stopped before it.
 */
private fun LazyListScope.extensionInstalls(
    report: ImportReport,
    waiting: List<ImportReport.FromAddedStore>,
    installingAll: RestoreExtensionInstalls.Progress?,
    onInstallAll: () -> Unit,
) {
    val installs = report.extensionInstalls ?: return
    if (installs.installed.isEmpty() && installs.notFound.isEmpty() && installs.failed.isEmpty() && waiting.isEmpty()) {
        return
    }
    item { HeadingItem(R.string.import_report_extensions) }
    if (waiting.isNotEmpty()) {
        item { FromAddedStoresRows(waiting) }
        item { InstallAllButton(installingAll, onInstallAll) }
    }
    item {
        Column {
            NamesRow(R.string.import_report_extensions_installed, installs.installed)
            NamesRow(
                R.string.import_report_extensions_not_found,
                installs.notFound,
                hintRes = R.string.import_report_extensions_not_found_detail,
            )
            NamesRow(R.string.import_report_extensions_failed, installs.failed)
            if (installs.notFound.isNotEmpty() && installs.unreadStores.isNotEmpty()) {
                val stores = installs.unreadStores.joinToString()
                InlineNotice(stringResource(R.string.import_report_extensions_unread_stores_format, stores))
            }
        }
    }
}

/**
 * The extensions waiting for Install all: why they were not installed, then a row per store with
 * its name, its address and the extensions it would install, since the address is what the owner
 * decides to trust.
 */
@Composable
private fun FromAddedStoresRows(waiting: List<ImportReport.FromAddedStore>) {
    Column {
        ListRowLayout {
            ListRowTitle(stringResource(R.string.import_report_extensions_from_added_stores))
            ListRowDetail(stringResource(R.string.import_report_extensions_from_added_stores_detail), maxLines = Int.MAX_VALUE)
        }
        waiting.groupBy { it.storeUrl }.forEach { (storeUrl, extensions) ->
            ListRowLayout {
                ListRowTitle(extensions.first().storeName.ifBlank { storeUrl })
                ListRowDetail(storeUrl, maxLines = Int.MAX_VALUE)
                ListRowDetail(extensions.joinToString { it.extensionName }, maxLines = Int.MAX_VALUE)
            }
        }
    }
}

/** A title, then [names] in full, comma-separated, and an optional hint under them. */
@Composable
private fun NamesRow(@StringRes titleRes: Int, names: List<String>, @StringRes hintRes: Int? = null) {
    if (names.isEmpty()) return
    ListRowLayout {
        ListRowTitle(stringResource(titleRes))
        ListRowDetail(names.joinToString(), maxLines = Int.MAX_VALUE)
        if (hintRes != null) ListRowDetail(stringResource(hintRes))
    }
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
    ImportError.Kind.EXTENSION_INSTALL -> R.string.backup_option_install_extensions
}

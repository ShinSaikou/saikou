package com.shinsaikou.ui.backup

import android.content.Intent
import android.text.format.Formatter
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.platform.testTag
import com.shinsaikou.backup.export.AutomaticBackup
import com.shinsaikou.domain.storage.StorageLocation
import com.shinsaikou.ui.components.ListRow
import com.shinsaikou.ui.storage.StorageLocationViewModel
import com.shinsaikou.ui.storage.rememberStorageLocationPicker
import com.shinsaikou.ui.storage.storageLocationSummary
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.flow.first
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinsaikou.backup.importer.ImportPhase
import com.shinsaikou.backup.importer.ImportProgress
import com.shinsaikou.i18n.R
import com.shinsaikou.navigation.LocalNavigator
import com.shinsaikou.navigation.Route
import com.shinsaikou.ui.components.InlineNotice
import com.shinsaikou.ui.components.ScreenTopBar
import com.shinsaikou.ui.components.SaikouSnackbarHost
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.TabText
import tachiyomi.presentation.core.i18n.stringResource

private const val BACKUP_MIME = "application/octet-stream"
private const val ANY_MIME = "*/*"
private const val BACKUP_FILE_NAME = "saikou-backup.tachibk"

/**
 * Create and Restore as two tabs under one top bar, so the owner who came to restore sees it
 * without scrolling past the export options first. The Restore tab also lists the kept automatic
 * backups, since what a row offers is to restore it or copy it out.
 *
 * A file picked with Restore backup is restored as soon as it reads: picking it was the
 * confirmation, so there is no second button to tap on coming back from the picker.
 *
 * [restoreUri] is set when the screen was opened by a file the owner tapped outside the app, in
 * which case the screen opens on Restore and that document is checked before anything is queued
 * (unit restore-report): the tab shows the file, what it names that this device lacks, and Restore
 * and Cancel, and its list scrolls to them when the options above push them out of view.
 */
@Composable
fun BackupScreen(restoreUri: String? = null) {
    val viewModel: BackupViewModel = metroViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is BackupViewModel.Event.Snackbar ->
                    snackbarHostState.showSnackbar(resources.getString(event.messageRes))
            }
        }
    }

    val storageLocation: StorageLocationViewModel = metroViewModel()
    val storageState by storageLocation.state.collectAsStateWithLifecycle()
    val chooseFolder = rememberStorageLocationPicker(storageLocation)
    // The location decides the folder the list reads, so a change lists again.
    LaunchedEffect(storageState.status, storageState.name, storageState.move.running) { viewModel.refreshAutomatic() }
    var selectedName by rememberSaveable { mutableStateOf<String?>(null) }
    var copyingName by rememberSaveable { mutableStateOf<String?>(null) }
    val saveCopy = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_MIME)) { uri ->
        val backup = state.automatic.orEmpty().firstOrNull { it.id == copyingName }
        if (uri != null && backup != null) viewModel.saveCopy(backup, uri)
        copyingName = null
    }

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BACKUP_MIME),
    ) { uri -> if (uri != null) viewModel.export(uri) }

    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // The import worker reopens the document from a later process, possibly after a resume,
            // so the read grant has to outlive this activity. A provider that refuses to persist it
            // still serves this run, which is why the refusal is not reported.
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.restorePicked(uri)
        }
    }

    // A rotation recomposes the screen while the uri is still part of the route, and this flag is
    // what keeps that from checking the same file a second time over a check already on screen.
    var intentRestoreStarted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(restoreUri) {
        if (restoreUri != null && !intentRestoreStarted) {
            intentRestoreStarted = true
            viewModel.check(restoreUri.toUri())
        }
    }

    var tab by rememberSaveable { mutableStateOf(if (restoreUri != null) BackupTab.RESTORE else BackupTab.CREATE) }
    // Each tab keeps its own place, so a look at the other one comes back to where the owner was.
    val createListState = rememberLazyListState()
    val restoreListState = rememberLazyListState()
    val pendingUri = state.pendingRestore?.uri
    LaunchedEffect(pendingUri) {
        if (pendingUri == null) return@LaunchedEffect
        tab = BackupTab.RESTORE
        // The first layout that shows the check, or that ends before its place; one that shows
        // something else there predates it. Only a check not wholly in view is scrolled to.
        val layout = snapshotFlow { restoreListState.layoutInfo }.first { layout ->
            val visible = layout.visibleItemsInfo
            visible.any { it.key == RESTORE_CHECK_KEY } ||
                visible.isNotEmpty() && RESTORE_CHECK_INDEX !in visible.first().index..visible.last().index
        }
        val check = layout.visibleItemsInfo.firstOrNull { it.key == RESTORE_CHECK_KEY }
        if (check == null || check.offset < layout.viewportStartOffset || check.offset + check.size > layout.viewportEndOffset) {
            restoreListState.animateScrollToItem(RESTORE_CHECK_INDEX)
        }
    }

    Scaffold(
        topBar = { scrollBehavior ->
            Column {
                ScreenTopBar(
                    title = stringResource(R.string.backup_title),
                    onBack = navigator::back,
                    scrollBehavior = scrollBehavior,
                )
                PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                    BackupTab.entries.forEach { entry ->
                        Tab(
                            selected = tab == entry,
                            onClick = { tab = entry },
                            unselectedContentColor = MaterialTheme.colorScheme.onSurface,
                            text = { TabText(stringResource(entry.labelRes)) },
                        )
                    }
                }
            }
        },
        snackbarHost = { SaikouSnackbarHost(snackbarHostState) },
    ) { padding ->
        when (tab) {
            BackupTab.CREATE -> CreateTab(
                state = state,
                viewModel = viewModel,
                listState = createListState,
                padding = padding,
                onCreate = { createDocument.launch(BACKUP_FILE_NAME) },
            )
            BackupTab.RESTORE -> RestoreTab(
                state = state,
                viewModel = viewModel,
                listState = restoreListState,
                padding = padding,
                storageState = storageState,
                // A backup file arrives with whatever type the provider guessed for it, which is
                // not reliably the one it was written with, so the picker filters on nothing.
                onPickFile = { openDocument.launch(arrayOf(ANY_MIME)) },
                onChooseFolder = chooseFolder,
                onOpenReport = { navigator.open(Route.ImportReport) },
                onSelectAutomatic = { selectedName = it.id },
            )
        }
    }

    val selected = state.automatic.orEmpty().firstOrNull { it.id == selectedName }
    if (selected != null) {
        AutomaticBackupDialog(
            backup = selected,
            canRestore = !state.restoring && state.restoreOptions.canRestore(),
            onRestore = {
                selectedName = null
                viewModel.restoreAutomatic(selected)
            },
            onSaveCopy = {
                selectedName = null
                copyingName = selected.id
                saveCopy.launch(selected.name)
            },
            onDismiss = { selectedName = null },
        )
    }
}

private enum class BackupTab(@StringRes val labelRes: Int) {
    CREATE(R.string.backup_tab_create),
    RESTORE(R.string.backup_tab_restore),
}

/** What goes into the file, and Create backup, which asks where to save it. */
@Composable
private fun CreateTab(
    state: BackupViewModel.State,
    viewModel: BackupViewModel,
    listState: LazyListState,
    padding: PaddingValues,
    onCreate: () -> Unit,
) {
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding)) {
        item { InlineNotice(stringResource(R.string.backup_create_intro)) }
        exportOptions(state.backupOptions, viewModel)
        item {
            Button(
                onClick = onCreate,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                enabled = !state.exporting && state.backupOptions.canCreate(),
            ) {
                Text(stringResource(R.string.backup_create))
            }
        }
        // From the tap to the end of the run, so the owner sees it working; the export counts
        // nothing it could fill a bar with, as its notification does not either.
        val message = state.exportMessage
        if (state.exporting) {
            item { WorkingStatus(stringResource(R.string.creating_backup)) }
        } else if (message != null) {
            item { InlineNotice(stringResource(message)) }
        }
    }
}

/**
 * What to take from the file, Restore backup with the check and the run it starts, the last
 * restore's report, and the kept automatic backups.
 */
@Composable
private fun RestoreTab(
    state: BackupViewModel.State,
    viewModel: BackupViewModel,
    listState: LazyListState,
    padding: PaddingValues,
    storageState: StorageLocationViewModel.State,
    onPickFile: () -> Unit,
    onChooseFolder: () -> Unit,
    onOpenReport: () -> Unit,
    onSelectAutomatic: (AutomaticBackup) -> Unit,
) {
    val resources = LocalResources.current
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding)) {
        // RESTORE_CHECK_INDEX counts the items above the check: keep it in step.
        item { InlineNotice(stringResource(R.string.backup_restore_intro)) }
        restoreOptions(state.restoreOptions, viewModel)
        item {
            Button(
                onClick = onPickFile,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                enabled = !state.restoring && !state.checking && state.restoreOptions.canRestore(),
            ) {
                Text(stringResource(R.string.backup_restore))
            }
        }

        // The check reads every entry of the file once, which takes seconds on a large one.
        if (state.checking) {
            item { WorkingStatus(stringResource(R.string.backup_reading)) }
        }
        val pending = state.pendingRestore
        if (pending != null) {
            item(key = RESTORE_CHECK_KEY) {
                RestoreCheck(
                    pending = pending,
                    canRestore = !state.restoring && state.restoreOptions.canRestore(),
                    onRestore = viewModel::restorePending,
                    onCancel = viewModel::cancelPending,
                )
            }
        }

        // From the moment the run is queued: before its first phase it is waiting for WorkManager or
        // copying the file in, which takes a while on a large one, and has nothing to count yet.
        val progress = state.progress
        if (state.restoring || progress != null) {
            item {
                if (progress != null) RestoreProgress(progress) else WorkingStatus(stringResource(R.string.restore_in_progress))
            }
        }

        if (state.restoring) {
            item {
                TextButton(
                    onClick = viewModel::cancelRestore,
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }

        // The run's own status stands in for the line that says it was queued, until it ends.
        val message = state.restoreMessage
        if (message != null && !state.restoring) {
            item { InlineNotice(stringResource(message)) }
        }

        val lastReportAt = state.lastReportAt
        if (lastReportAt != null) {
            item {
                ListRow(
                    title = stringResource(R.string.import_report_title),
                    detail = stringResource(
                        R.string.import_report_last_format,
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(lastReportAt)),
                    ),
                    onClick = onOpenReport,
                )
            }
        }

        item { HeadingItem(R.string.backup_automatic_title) }
        if (storageState.status != null && storageState.status != StorageLocation.Status.REACHABLE) {
            item {
                NoStorageLocation(
                    text = if (storageState.status == StorageLocation.Status.NOT_SET) {
                        stringResource(R.string.backup_automatic_no_location)
                    } else {
                        storageLocationSummary(resources, storageState.status, storageState.name)
                    },
                    onChoose = onChooseFolder,
                )
            }
        }
        val automatic = state.automatic
        when {
            automatic == null -> Unit
            automatic.isEmpty() -> item { InlineNotice(stringResource(R.string.backup_automatic_none)) }
            else -> items(automatic, key = { it.id }) { backup ->
                ListRow(
                    title = backupTime(backup),
                    detail = backupDetail(backup),
                    modifier = Modifier.testTag(AUTOMATIC_BACKUP_ROW_TAG),
                    onClick = { onSelectAutomatic(backup) },
                )
            }
        }
    }
}

/**
 * Upstream's `RestoreBackupScreen` lists, on this screen's Restore tab (phase 4 kept one Backup screen
 * for both halves): the file, then, when it names something this device lacks, upstream's line and its
 * two lists, a line per tracker this build has no implementation of, and Restore and Cancel. Restore
 * takes the options above as they are when it is tapped.
 */
@Composable
private fun RestoreCheck(
    pending: BackupViewModel.PendingRestore,
    canRestore: Boolean,
    onRestore: () -> Unit,
    onCancel: () -> Unit,
) {
    val results = pending.results
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(pending.fileName, style = MaterialTheme.typography.titleSmall)
        if (results.missingSources.isNotEmpty() || results.missingTrackers.isNotEmpty()) {
            Text(
                stringResource(R.string.backup_restore_content_full),
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        CheckList(R.string.backup_restore_missing_sources, results.missingSources)
        CheckList(R.string.backup_restore_missing_trackers, results.missingTrackers)
        results.unsupportedTrackers.forEach { id ->
            Text(
                stringResource(R.string.backup_restore_unsupported_tracker_format, id.toInt()),
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(modifier = Modifier.padding(top = 8.dp)) {
            Button(onClick = onRestore, enabled = canRestore) { Text(stringResource(R.string.action_restore)) }
            TextButton(onClick = onCancel, modifier = Modifier.padding(start = 8.dp)) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}

/** A bold heading and a dashed line per name, as upstream's restore screen writes its two lists. */
@Composable
private fun CheckList(@StringRes headingRes: Int, names: List<String>) {
    if (names.isEmpty()) return
    Text(
        stringResource(headingRes),
        modifier = Modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
    )
    names.forEach { name -> Text("- $name", style = MaterialTheme.typography.bodyMedium) }
}

/** The line that says why a storage location matters, with the Choose folder that sets one. */
@Composable
private fun NoStorageLocation(text: String, onChoose: () -> Unit) {
    Column {
        InlineNotice(text)
        TextButton(onClick = onChoose, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(stringResource(R.string.action_choose_folder))
        }
    }
}

/** A kept backup's restore or copy: the same restore as a picked file, or the system's save dialog. */
@Composable
private fun AutomaticBackupDialog(
    backup: AutomaticBackup,
    canRestore: Boolean,
    onRestore: () -> Unit,
    onSaveCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(backupTime(backup)) },
        text = { Text(backupDetail(backup)) },
        confirmButton = {
            TextButton(onClick = onRestore, enabled = canRestore) { Text(stringResource(R.string.action_restore)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                TextButton(onClick = onSaveCopy) { Text(stringResource(R.string.action_save_copy)) }
            }
        },
    )
}

/** The minute the backup's name was given for, in this device's date and time format. */
private fun backupTime(backup: AutomaticBackup): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(backup.createdAt))

@Composable
private fun backupDetail(backup: AutomaticBackup): String {
    val size = Formatter.formatFileSize(LocalContext.current, backup.sizeBytes)
    return when (backup.kind) {
        AutomaticBackup.Kind.AUTOMATIC -> stringResource(R.string.backup_automatic_kind_automatic_format, size)
        AutomaticBackup.Kind.SYNC_SAFETY -> stringResource(R.string.backup_automatic_kind_sync_format, size)
    }
}

/**
 * A kept backup's key on this screen: a move cut short can leave one name in both folders, and the
 * list shows both, so the folder is part of it.
 */
private val AutomaticBackup.id: String get() = if (inApp) "app/$name" else "location/$name"

/** The tag on each kept backup's row, which a device test counts. */
internal const val AUTOMATIC_BACKUP_ROW_TAG = "backup_automatic_row"

/** How many items [restoreOptions] adds: its five options. */
private const val RESTORE_OPTION_ITEMS = 5

/**
 * The check's place in the Restore tab's list: the intro, [RESTORE_OPTION_ITEMS] and the button come
 * before it. The checking bar never shows beside it, since the check replaces the bar.
 */
private const val RESTORE_CHECK_INDEX = 2 + RESTORE_OPTION_ITEMS

private const val RESTORE_CHECK_KEY = "restore_check"

private fun LazyListScope.exportOptions(options: BackupOptions, viewModel: BackupViewModel) {
    optionItem(R.string.backup_option_library, options.libraryEntries, viewModel::setExportLibraryEntries)
    optionItem(R.string.backup_option_categories, options.categories, viewModel::setExportCategories)
    optionItem(R.string.backup_option_chapters, options.chapters, viewModel::setExportChapters)
    optionItem(R.string.backup_option_tracking, options.tracking, viewModel::setExportTracking)
    optionItem(R.string.backup_option_history, options.history, viewModel::setExportHistory)
    optionItem(R.string.backup_option_read_entries, options.readEntries, viewModel::setExportReadEntries)
    optionItem(R.string.backup_option_app_settings, options.appSettings, viewModel::setExportAppSettings)
    optionItem(R.string.backup_option_stores, options.extensionStores, viewModel::setExportExtensionStores)
    optionItem(R.string.backup_option_source_settings, options.sourceSettings, viewModel::setExportSourceSettings)
    optionItem(
        R.string.backup_option_private_settings,
        options.privateSettings,
        viewModel::setExportPrivateSettings,
    )
    item { InlineNotice(stringResource(R.string.backup_private_settings_hint)) }
}

private fun LazyListScope.restoreOptions(options: RestoreOptions, viewModel: BackupViewModel) {
    optionItem(R.string.backup_option_library, options.libraryEntries, viewModel::setRestoreLibraryEntries)
    optionItem(R.string.backup_option_categories, options.categories, viewModel::setRestoreCategories)
    optionItem(R.string.backup_option_app_settings, options.appSettings, viewModel::setRestoreAppSettings)
    optionItem(R.string.backup_option_stores, options.extensionStores, viewModel::setRestoreExtensionStores)
    optionItem(
        R.string.backup_option_source_settings,
        options.sourceSettings,
        viewModel::setRestoreSourceSettings,
    )
}

private fun LazyListScope.optionItem(
    @StringRes labelRes: Int,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    item {
        CheckboxItem(
            label = stringResource(labelRes),
            checked = checked,
            onClick = { onChange(!checked) },
        )
    }
}

@Composable
private fun RestoreProgress(progress: ImportProgress) {
    WorkingStatus(
        text = stringResource(
            R.string.backup_restore_progress_format,
            stringResource(phaseLabel(progress.phase)),
            progress.done,
            progress.total,
        ),
        detail = progress.currentTitle,
        // A phase that counts nothing reports no total, and a bar drawn at zero would read as a
        // stalled run rather than as work with no countable steps.
        fraction = progress.done.toFloat().div(progress.total).takeIf { progress.total > 0 },
    )
}

/**
 * A run in flight: what it is doing, an optional second line, and a bar that fills with
 * [fraction], or moves on its own while there is nothing to count.
 */
@Composable
private fun WorkingStatus(text: String, detail: String? = null, fraction: Float? = null) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        if (detail != null) {
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }
}

@StringRes
private fun phaseLabel(phase: ImportPhase): Int = when (phase) {
    ImportPhase.CATEGORIES -> R.string.backup_phase_categories
    ImportPhase.PREFERENCES -> R.string.backup_phase_preferences
    ImportPhase.EXTENSION_STORES -> R.string.backup_phase_stores
    ImportPhase.MANGA -> R.string.backup_phase_entries
    ImportPhase.DONE -> R.string.backup_phase_done
}

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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import tachiyomi.presentation.core.i18n.stringResource

private const val BACKUP_MIME = "application/octet-stream"
private const val ANY_MIME = "*/*"
private const val BACKUP_FILE_NAME = "saikou-backup.tachibk"

/**
 * [restoreUri] is set when the screen was opened by a file the owner tapped outside the app, in
 * which case the picker is skipped and that document is checked.
 *
 * A picked or tapped file is checked before anything is queued (unit restore-report): the restore
 * half then shows the file, what it names that this device lacks, and Restore and Cancel, and the
 * list scrolls to it, since a tapped file opens the screen at its top.
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
            viewModel.check(uri)
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

    val listState = rememberLazyListState()
    val pendingUri = state.pendingRestore?.uri
    LaunchedEffect(pendingUri) {
        if (pendingUri != null) listState.animateScrollToItem(RESTORE_HEADING_INDEX)
    }

    Scaffold(
        topBar = { scrollBehavior ->
            ScreenTopBar(
                title = stringResource(R.string.backup_title),
                onBack = navigator::back,
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SaikouSnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding)) {
            // RESTORE_HEADING_INDEX counts the items above the restore heading: keep it in step.
            item { InlineNotice(stringResource(R.string.backup_intro)) }

            item { HeadingItem(R.string.backup_create) }
            exportOptions(state.backupOptions, viewModel)
            item {
                Button(
                    onClick = { createDocument.launch(BACKUP_FILE_NAME) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    enabled = !state.exporting && state.backupOptions.canCreate(),
                ) {
                    Text(
                        stringResource(
                            if (state.exporting) R.string.backup_creating else R.string.backup_create,
                        ),
                    )
                }
            }

            item { HeadingItem(R.string.backup_restore) }
            restoreOptions(state.restoreOptions, viewModel)
            item {
                // A backup file arrives with whatever type the provider guessed for it, which is
                // not reliably the one it was written with, so the picker filters on nothing.
                OutlinedButton(
                    onClick = { openDocument.launch(arrayOf(ANY_MIME)) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    enabled = !state.restoring && !state.checking && state.restoreOptions.canRestore(),
                ) {
                    Text(
                        stringResource(
                            if (state.restoring) R.string.backup_restoring else R.string.backup_restore,
                        ),
                    )
                }
            }

            // The check reads every entry of the file once, which takes seconds on a large one.
            if (state.checking) {
                item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) }
            }
            val pending = state.pendingRestore
            if (pending != null) {
                item {
                    RestoreCheck(
                        pending = pending,
                        canRestore = !state.restoring && state.restoreOptions.canRestore(),
                        onRestore = viewModel::restorePending,
                        onCancel = viewModel::cancelPending,
                    )
                }
            }

            val progress = state.progress
            if (progress != null) {
                item { RestoreProgress(progress) }
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

            val message = state.message
            if (message != null) {
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
                        onClick = { navigator.open(Route.ImportReport) },
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
                        onChoose = chooseFolder,
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
                        onClick = { selectedName = backup.id },
                    )
                }
            }
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

/**
 * Upstream's `RestoreBackupScreen` lists, as a section of this screen (phase 4 kept one Backup screen
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

/** How many items [exportOptions] adds: its ten options and the private settings hint. */
private const val EXPORT_OPTION_ITEMS = 11

/**
 * The restore heading's place in the list: the intro, the create heading, [EXPORT_OPTION_ITEMS] and
 * the create button come before it.
 */
private const val RESTORE_HEADING_INDEX = 3 + EXPORT_OPTION_ITEMS

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
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            stringResource(
                R.string.backup_restore_progress_format,
                stringResource(phaseLabel(progress.phase)),
                progress.done,
                progress.total,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        val title = progress.currentTitle
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        // A phase that counts nothing reports no total, and a bar drawn at zero would read as a
        // stalled run rather than as work with no countable steps.
        if (progress.total > 0) {
            LinearProgressIndicator(
                progress = { progress.done.toFloat() / progress.total },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
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

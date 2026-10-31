package com.shinsaikou.ui.backup

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shinsaikou.backup.export.AutomaticBackup
import com.shinsaikou.backup.export.AutomaticBackupFiles
import com.shinsaikou.backup.export.BackupScheduler
import com.shinsaikou.backup.importer.BackupImportRequest
import com.shinsaikou.backup.importer.ImportCheckpointStore
import com.shinsaikou.backup.importer.ImportProgress
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.i18n.R
import com.shinsaikou.presentation.core.viewmodel.StateViewModel
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.data.backup.BackupDecodeException
import eu.kanade.tachiyomi.data.backup.BackupFileValidator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Export and restore. Both halves are queued work, so everything the screen shows about a run in
 * flight is read back from the queue rather than tracked here: a restore outlives the process it
 * was started from, and has to look the same when the owner returns to the screen.
 *
 * The two option sets live in state rather than in preferences. They describe one run, and a choice
 * made for one file has no bearing on the next.
 *
 * A picked or tapped file is checked before it is queued (unit restore-report), as upstream's
 * `RestoreBackupScreen` checks it: [check] reads the file's header for the sources this device has
 * no extension for and the trackers it is not signed in to, and the screen shows them with Restore
 * and Cancel. Only Restore queues the run.
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(BackupViewModel::class)
@Inject
class BackupViewModel(
    private val backupScheduler: BackupScheduler,
    private val backupImportRequest: BackupImportRequest,
    private val automaticBackupFiles: AutomaticBackupFiles,
    private val backupFileCopy: BackupFileCopy,
    private val validator: BackupFileValidator,
    private val checkpoints: ImportCheckpointStore,
    private val dispatchers: Dispatchers,
) : StateViewModel<BackupViewModel.State>(State()) {

    data class State(
        val exporting: Boolean = false,
        val restoring: Boolean = false,
        val backupOptions: BackupOptions = BackupOptions(),
        val restoreOptions: RestoreOptions = RestoreOptions(),
        val progress: ImportProgress? = null,
        @StringRes val message: Int? = null,
        /** The kept automatic and sync safety backups, newest first; null until listed. */
        val automatic: List<AutomaticBackup>? = null,
        /** True while [check] reads a file's header. */
        val checking: Boolean = false,
        /** A checked file waiting for Restore or Cancel. */
        val pendingRestore: PendingRestore? = null,
        /** When the last restore's report was written, for the Restore report row; null with none. */
        val lastReportAt: Long? = null,
    )

    /** A file [check] has read, with what it found missing on this device. */
    data class PendingRestore(
        val uri: Uri,
        val fileName: String,
        val results: BackupFileValidator.Results,
    )

    sealed interface Event {
        data class Snackbar(@StringRes val messageRes: Int) : Event
    }

    private val eventChannel = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = eventChannel.receiveAsFlow()

    init {
        // A restore writes the app preferences back, the export interval among them, and nothing
        // re-arms the periodic work after that write. Arming is idempotent, and this is the screen
        // a restore is started from, so it is the one place that always sees a stale schedule.
        viewModelScope.launch { withContext(dispatchers.io) { backupScheduler.setup() } }
        viewModelScope.launch {
            backupScheduler.isManualRunning
                .flowOn(dispatchers.io)
                .collect { running -> mutableState.update { it.copy(exporting = running) } }
        }
        refreshAutomatic()
        viewModelScope.launch {
            backupImportRequest.isRunning
                .flowOn(dispatchers.io)
                .distinctUntilChanged()
                .collect { running ->
                    mutableState.update { it.copy(restoring = running) }
                    // A run that just ended has written a new report.
                    if (!running) refreshLastReport()
                }
        }
        viewModelScope.launch {
            backupImportRequest.progress
                .flowOn(dispatchers.io)
                .collect { progress -> mutableState.update { it.copy(progress = progress) } }
        }
    }

    fun setExportLibraryEntries(enabled: Boolean) = updateExport { it.copy(libraryEntries = enabled) }

    fun setExportCategories(enabled: Boolean) = updateExport { it.copy(categories = enabled) }

    fun setExportChapters(enabled: Boolean) = updateExport { it.copy(chapters = enabled) }

    fun setExportTracking(enabled: Boolean) = updateExport { it.copy(tracking = enabled) }

    fun setExportHistory(enabled: Boolean) = updateExport { it.copy(history = enabled) }

    fun setExportReadEntries(enabled: Boolean) = updateExport { it.copy(readEntries = enabled) }

    fun setExportAppSettings(enabled: Boolean) = updateExport { it.copy(appSettings = enabled) }

    fun setExportExtensionStores(enabled: Boolean) = updateExport { it.copy(extensionStores = enabled) }

    fun setExportSourceSettings(enabled: Boolean) = updateExport { it.copy(sourceSettings = enabled) }

    fun setExportPrivateSettings(enabled: Boolean) = updateExport { it.copy(privateSettings = enabled) }

    fun setRestoreLibraryEntries(enabled: Boolean) = updateRestore { it.copy(libraryEntries = enabled) }

    fun setRestoreCategories(enabled: Boolean) = updateRestore { it.copy(categories = enabled) }

    fun setRestoreAppSettings(enabled: Boolean) = updateRestore { it.copy(appSettings = enabled) }

    fun setRestoreExtensionStores(enabled: Boolean) = updateRestore { it.copy(extensionStores = enabled) }

    fun setRestoreSourceSettings(enabled: Boolean) = updateRestore { it.copy(sourceSettings = enabled) }

    /** [uri] is the document the screen's picker just created. */
    fun export(uri: Uri) {
        val options = state.value.backupOptions
        viewModelScope.launch {
            val queued = runCatching {
                withContext(dispatchers.io) { backupScheduler.exportNow(uri, options) }
            }.isSuccess
            report(if (queued) R.string.backup_export_queued else R.string.backup_failed)
        }
    }

    /**
     * Reads [uri]'s header and holds what it found for the screen; nothing is queued. A file that
     * does not decode is said as the snackbar says it, with the reason the decoder gave.
     */
    fun check(uri: Uri) {
        mutableState.update { it.copy(checking = true, pendingRestore = null) }
        viewModelScope.launch {
            val checked = try {
                withContext(dispatchers.io) {
                    PendingRestore(uri = uri, fileName = backupFileCopy.displayName(uri), results = validator.validate(uri))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(checking = false) }
                report(unreadableMessage(e))
                return@launch
            }
            mutableState.update { it.copy(checking = false, pendingRestore = checked) }
        }
    }

    /** Queues the checked file with the options as they are now; upstream's Restore. */
    fun restorePending() {
        val pending = state.value.pendingRestore ?: return
        mutableState.update { it.copy(pendingRestore = null) }
        restore(pending.uri)
    }

    /** Drops the checked file without restoring it. */
    fun cancelPending() {
        mutableState.update { it.copy(pendingRestore = null) }
    }

    private fun restore(uri: Uri) {
        val options = state.value.restoreOptions
        viewModelScope.launch {
            val queued = runCatching {
                withContext(dispatchers.io) { backupImportRequest.start(uri, options) }
            }.isSuccess
            report(if (queued) R.string.backup_restore_queued else R.string.backup_restore_failed)
        }
    }

    /**
     * The report file's time rather than its decoded `finishedAt`: a run where every entry failed
     * writes megabytes of errors, and this row only needs the date.
     */
    private fun refreshLastReport() {
        viewModelScope.launch {
            val at = withContext(dispatchers.io) {
                runCatching { checkpoints.reportFile.takeIf { it.exists() }?.lastModified() }.getOrNull()
            }
            mutableState.update { it.copy(lastReportAt = at) }
        }
    }

    /** Lists the kept backups again: on opening the screen, and after an export may have rotated them. */
    fun refreshAutomatic() {
        viewModelScope.launch {
            val listed = withContext(dispatchers.io) { runCatching { automaticBackupFiles.list() }.getOrDefault(emptyList()) }
            mutableState.update { it.copy(automatic = listed) }
        }
    }

    /**
     * Restores a kept backup through the same queued import as a picked file, by its own URI. Not
     * checked first: its dialog is already the confirmation, and it was written on this device.
     */
    fun restoreAutomatic(backup: AutomaticBackup) = restore(backup.file.uri)

    /** Copies a kept backup into [target], the document the save dialog just created. */
    fun saveCopy(backup: AutomaticBackup, target: Uri) {
        viewModelScope.launch {
            val saved = withContext(dispatchers.io) { backupFileCopy.copy(backup.file.uri, target) }
            report(if (saved) R.string.backup_copy_saved else R.string.backup_copy_failed)
        }
    }

    fun cancelRestore() {
        viewModelScope.launch {
            withContext(dispatchers.io) { backupImportRequest.cancel() }
            report(R.string.restore_cancelled)
        }
    }

    private fun updateExport(transform: (BackupOptions) -> BackupOptions) {
        mutableState.update { it.copy(backupOptions = transform(it.backupOptions)) }
    }

    private fun updateRestore(transform: (RestoreOptions) -> RestoreOptions) {
        mutableState.update { it.copy(restoreOptions = transform(it.restoreOptions)) }
    }

    @StringRes
    private fun unreadableMessage(error: Exception): Int = when ((error as? BackupDecodeException)?.reason) {
        BackupDecodeException.Reason.JSON_NOT_SUPPORTED -> R.string.invalid_backup_file_json
        else -> R.string.invalid_backup_file_unknown
    }

    /**
     * A snackbar is gone in seconds while the work it announces runs for minutes, so the same line
     * also stays in the body for as long as the screen is open.
     */
    private suspend fun report(@StringRes messageRes: Int) {
        mutableState.update { it.copy(message = messageRes) }
        eventChannel.send(Event.Snackbar(messageRes))
    }
}

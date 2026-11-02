package com.shinsaikou.ui.backup

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shinsaikou.backup.BackupRun
import com.shinsaikou.backup.BackupRunState
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Export and restore. Both halves are queued work, so everything the screen shows about a run in
 * flight is read back from the queue rather than tracked here: a restore outlives the process it
 * was started from, and has to look the same when the owner returns to the screen.
 *
 * The two option sets live in state rather than in preferences. They describe one run, and a choice
 * made for one file has no bearing on the next.
 *
 * A file picked with Restore backup is queued as soon as its header reads ([restorePicked]):
 * picking it was the confirmation, and what it names that this device lacks is in the restore
 * report afterwards, with Install and Sign in. A file tapped outside the app is checked first, as
 * upstream's `RestoreBackupScreen` checks it (unit restore-report): [check] reads the header for the
 * sources this device has no extension for and the trackers it is not signed in to, and the screen
 * shows them with Restore and Cancel, since opening a file is not yet asking to restore it.
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
        /** The last export's line, under the Create tab's button; see [reportExport]. */
        @StringRes val exportMessage: Int? = null,
        /** The same for a check, a restore or a kept backup's copy, on the Restore tab. */
        @StringRes val restoreMessage: Int? = null,
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

    /**
     * Held from queueing a run until the screen has said it is queued, and while it says how a run
     * ended. A run can end before [export] or [queue] gets back from WorkManager, and its end then
     * has to come after the queued line, not under it.
     */
    private val reports = Mutex()

    init {
        // A restore writes the app preferences back, the export interval among them, and nothing
        // re-arms the periodic work after that write. Arming is idempotent, and this is the screen
        // a restore is started from, so it is the one place that always sees a stale schedule.
        viewModelScope.launch { withContext(dispatchers.io) { backupScheduler.setup() } }
        viewModelScope.launch {
            followRun(backupScheduler.manualExportRun) { active, ended ->
                mutableState.update { it.copy(exporting = active) }
                reports.withLock {
                    when (ended) {
                        BackupRunState.SUCCEEDED -> reportExport(R.string.backup_created)
                        BackupRunState.FAILED -> reportExport(R.string.backup_failed)
                        BackupRunState.CANCELLED -> mutableState.update { it.copy(exportMessage = null) }
                        BackupRunState.ACTIVE, null -> Unit
                    }
                }
            }
        }
        refreshAutomatic()
        viewModelScope.launch {
            followRun(backupImportRequest.run) { active, ended ->
                mutableState.update { it.copy(restoring = active) }
                // A run that just ended has written a new report.
                if (!active) refreshLastReport()
                reports.withLock {
                    when (ended) {
                        BackupRunState.SUCCEEDED -> reportRestore(R.string.restore_completed)
                        BackupRunState.FAILED -> reportRestore(R.string.restore_failed)
                        BackupRunState.CANCELLED -> reportRestore(R.string.restore_cancelled)
                        BackupRunState.ACTIVE, null -> Unit
                    }
                }
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
            reports.withLock {
                val queued = runCatching {
                    withContext(dispatchers.io) { backupScheduler.exportNow(uri, options) }
                }.isSuccess
                reportExport(if (queued) R.string.backup_export_queued else R.string.backup_failed)
            }
        }
    }

    /** Reads [uri]'s header and holds what it found for the screen; nothing is queued. */
    fun check(uri: Uri) {
        mutableState.update { it.copy(checking = true, pendingRestore = null) }
        viewModelScope.launch {
            val checked = read(uri)
            mutableState.update { it.copy(checking = false, pendingRestore = checked) }
        }
    }

    /**
     * Queues [uri] with the options as they are now once its header reads, which is what keeps a
     * file that is not a backup from being queued. The checking bar stays until it is queued.
     */
    fun restorePicked(uri: Uri) {
        mutableState.update { it.copy(checking = true, pendingRestore = null) }
        viewModelScope.launch {
            if (read(uri) != null) queue(uri)
            mutableState.update { it.copy(checking = false) }
        }
    }

    /**
     * [uri]'s name and what it names that this device lacks, or null for a file that does not
     * decode, which is said as the snackbar says it, with the reason the decoder gave.
     */
    private suspend fun read(uri: Uri): PendingRestore? = try {
        withContext(dispatchers.io) {
            PendingRestore(uri = uri, fileName = backupFileCopy.displayName(uri), results = validator.validate(uri))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        reportRestore(unreadableMessage(e))
        null
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
        viewModelScope.launch { queue(uri) }
    }

    /** Queues [uri] with the options as they are now, unless a restore is already queued or working. */
    private suspend fun queue(uri: Uri) {
        val options = state.value.restoreOptions
        reports.withLock {
            val started = runCatching {
                withContext(dispatchers.io) { backupImportRequest.start(uri, options) }
            }.getOrNull()
            reportRestore(
                when (started) {
                    true -> R.string.backup_restore_queued
                    false -> R.string.backup_restore_busy
                    null -> R.string.backup_restore_failed
                },
            )
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
            reportRestore(if (saved) R.string.backup_copy_saved else R.string.backup_copy_failed)
        }
    }

    /** The run's end says it was cancelled, once WorkManager has stopped it. */
    fun cancelRestore() {
        viewModelScope.launch { withContext(dispatchers.io) { backupImportRequest.cancel() } }
    }

    /**
     * Calls [onRun] with whether [runs] has one queued or working, and, for a run that ended while
     * the screen was open, how it ended; `ended` is null otherwise. That covers a run first seen
     * ended, since WorkManager can skip a fast run's queued and working states. WorkManager keeps a
     * finished run for a while, so the end of one that was over before the screen opened, the first
     * look's, is not said again.
     */
    private suspend fun followRun(
        runs: Flow<BackupRun?>,
        onRun: suspend (active: Boolean, ended: BackupRunState?) -> Unit,
    ) {
        var first = true
        // A run's id and state together change at each step, so an ended run comes through once.
        runs.flowOn(dispatchers.io).distinctUntilChanged().collect { run ->
            val active = run?.state == BackupRunState.ACTIVE
            onRun(active, run?.state?.takeIf { !active && !first })
            first = false
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
     * also stays in the body for as long as the screen is open, on the tab the work belongs to.
     */
    private suspend fun reportExport(@StringRes messageRes: Int) {
        mutableState.update { it.copy(exportMessage = messageRes) }
        eventChannel.send(Event.Snackbar(messageRes))
    }

    private suspend fun reportRestore(@StringRes messageRes: Int) {
        mutableState.update { it.copy(restoreMessage = messageRes) }
        eventChannel.send(Event.Snackbar(messageRes))
    }
}

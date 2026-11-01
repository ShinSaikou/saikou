package com.shinsaikou.ui.backup

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shinsaikou.backup.importer.ImportCheckpointStore
import com.shinsaikou.backup.importer.ImportError
import com.shinsaikou.backup.importer.ImportReport
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.downloads.adoption.AdoptionScheduler
import com.shinsaikou.extensions.ExtensionManager
import com.shinsaikou.extensions.model.Extension
import com.shinsaikou.i18n.R
import com.shinsaikou.presentation.core.viewmodel.StateViewModel
import com.shinsaikou.tracking.TrackerManager
import com.shinsaikou.ui.browse.extension.MissingExtensions
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.extension.model.InstallStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The restore report (unit restore-report): the last run's `last-report.json`, read once on IO, and
 * the two lists it names kept current as the owner acts on them. A source whose extension gets
 * installed, and a tracker that gets signed in, leave the screen without a reload.
 *
 * Upstream's completion notification opens its error log in a viewer (`action_open_log`). Saikou's log
 * is in app-private storage, which no viewer can read, so the screen exports it instead.
 *
 * A backup carries no downloads, so the report also offers the Downloads section's copy from a
 * folder, with the run's progress, for downloads another reader or an earlier install left behind.
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(ImportReportViewModel::class)
@Inject
class ImportReportViewModel(
    private val checkpoints: ImportCheckpointStore,
    private val missingExtensions: MissingExtensions,
    private val trackerManager: TrackerManager,
    private val extensionManager: ExtensionManager,
    private val backupFileCopy: BackupFileCopy,
    private val adoptionScheduler: AdoptionScheduler,
    private val dispatchers: Dispatchers,
) : StateViewModel<ImportReportViewModel.State>(State()) {

    @Immutable
    data class State(
        val loading: Boolean = true,
        /** Null once loaded means no run has finished on this install. */
        val report: ImportReport? = null,
        val sources: List<MissingExtensions.Row> = emptyList(),
        val trackers: List<TrackerRow> = emptyList(),
        /** The errors by kind, in [ImportError.Kind]'s order. */
        val errorGroups: List<Pair<ImportError.Kind, List<ImportError>>> = emptyList(),
        val installSteps: Map<String, InstallStep> = emptyMap(),
        /** The copy of downloads from a folder, as the Downloads section shows it. */
        val adoption: AdoptionScheduler.State = AdoptionScheduler.State(running = false),
    ) {
        /** True when the report has nothing to show below its counts. */
        val nothingMissing: Boolean
            get() = sources.isEmpty() &&
                trackers.isEmpty() &&
                errorGroups.isEmpty() &&
                report?.unsupportedTrackers.isNullOrEmpty() &&
                report?.unknownTrackers.isNullOrEmpty()
    }

    data class TrackerRow(val id: Long, val name: String)

    sealed interface Event {
        data class Snackbar(@StringRes val messageRes: Int) : Event
    }

    private val eventChannel = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = eventChannel.receiveAsFlow()

    /** The install job per package, so a row's ring can cancel it, as `ExtensionsViewModel` keeps them. */
    private val installJobs = mutableMapOf<String, Job>()

    init {
        viewModelScope.launch {
            adoptionScheduler.state.collect { adoption -> mutableState.update { it.copy(adoption = adoption) } }
        }
        viewModelScope.launch {
            val loaded = withContext(dispatchers.io) {
                checkpoints.loadReport()?.let { report -> report to groupErrors(report.errors) }
            }
            mutableState.update {
                it.copy(loading = false, report = loaded?.first, errorGroups = loaded?.second.orEmpty())
            }
            val report = loaded?.first ?: return@launch
            if (report.sourcesWithoutExtension.isNotEmpty()) refreshIfNothingAvailable()
            combine(
                missingExtensions.forSources(report.sourcesWithoutExtension),
                trackerManager.loggedInTrackersFlow(),
                extensionManager.installSteps,
            ) { sources, loggedIn, steps ->
                val signedIn = loggedIn.mapTo(HashSet()) { it.id }
                val trackers = report.trackersNotSignedIn
                    .filterNot { it in signedIn }
                    .mapNotNull { id -> trackerManager.get(id)?.let { TrackerRow(id, it.name) } }
                Triple(sources, trackers, steps)
            }
                .flowOn(dispatchers.io)
                .collect { (sources, trackers, steps) ->
                    mutableState.update { it.copy(sources = sources, trackers = trackers, installSteps = steps) }
                }
        }
    }

    /**
     * The available list lives in memory only, and nothing fills it until the Extensions tab opens or
     * a start-up check finds the last fetch over six hours old, so every source row would offer Open
     * Extensions where Install belongs. A new phone is the sure case: the repositories the restore
     * brought arrive after the start-up check, which found none. The report fetches the indexes once
     * in that case, as the Extensions tab does on opening (ruling R46), and the rows follow the list
     * when it lands. A failure leaves them on Open Extensions, whose tab shows why.
     */
    private fun refreshIfNothingAvailable() {
        if (extensionManager.availableExtensions.value.isNotEmpty()) return
        viewModelScope.launch {
            try {
                withContext(dispatchers.io) { extensionManager.refreshAvailable() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) { "The restore report's index fetch failed" }
            }
        }
    }

    /** Installs [available] as the Extensions tab does, against the manager's current list. */
    fun install(available: Extension.Available) {
        val target = extensionManager.availableExtensions.value
            .firstOrNull { it.packageName == available.packageName } ?: return
        installJobs[target.packageName] = extensionManager.install(target)
    }

    fun cancelInstall(packageName: String) {
        installJobs.remove(packageName)?.cancel()
    }

    /**
     * Copies downloads from [treeUri], as the Downloads section's row does, unless a copy is running.
     * The scheduler takes the read grant on it for a run it starts.
     */
    fun copyDownloadsFrom(treeUri: String) {
        viewModelScope.launch { adoptionScheduler.start(treeUri) }
    }

    /** The name the save dialog suggests: the run's finish, to the minute. */
    fun errorLogFileName(): String {
        val finishedAt = state.value.report?.finishedAt ?: 0L
        return "saikou-restore-errors-${SimpleDateFormat(FILE_STAMP, Locale.ENGLISH).format(Date(finishedAt))}.txt"
    }

    /** Writes every error, one line each, into [target], the document the save dialog created. */
    fun exportErrors(target: Uri) {
        val errors = state.value.report?.errors ?: return
        viewModelScope.launch {
            val saved = withContext(dispatchers.io) {
                backupFileCopy.write(target, errorLogText(errors, TimeZone.getDefault()))
            }
            eventChannel.send(
                Event.Snackbar(if (saved) R.string.import_export_error_log_saved else R.string.import_export_error_log_failed),
            )
        }
    }

    companion object {
        private const val FILE_STAMP = "yyyyMMdd-HHmm"
        private const val LINE_STAMP = "yyyy-MM-dd HH:mm:ss.SSS"

        internal fun groupErrors(errors: List<ImportError>): List<Pair<ImportError.Kind, List<ImportError>>> {
            val byKind = errors.groupBy { it.kind }
            return ImportError.Kind.entries.mapNotNull { kind -> byKind[kind]?.let { kind to it } }
        }

        /** What an error is about, with its source in parentheses when it is about an entry. */
        internal fun errorSubject(error: ImportError): String {
            val label = error.sourceLabel?.takeIf { it.isNotBlank() }
            return if (label == null) error.subject else "${error.subject} ($label)"
        }

        /**
         * One error as one line: upstream's log line (`title [source]: message`), with the source in
         * parentheses as [errorSubject] gives it. The screen shows the two halves as a row's title
         * and detail.
         */
        internal fun errorLine(error: ImportError): String = "${errorSubject(error)}: ${error.message}"

        /**
         * The exported log: one line per error, in the order the run met them, each stamped with its
         * time as upstream's restore error log stamps them, then its kind's name. The kind is
         * the enum's name rather than the screen's heading, so the file reads the same in every
         * language.
         */
        internal fun errorLogText(errors: List<ImportError>, timeZone: TimeZone): String {
            val format = SimpleDateFormat(LINE_STAMP, Locale.ENGLISH).apply { this.timeZone = timeZone }
            return buildString {
                errors.forEach { error ->
                    append('[').append(format.format(Date(error.at))).append("] ")
                    append(error.kind.name).append(' ')
                    append(errorLine(error)).append('\n')
                }
            }
        }
    }
}

package com.shinsaikou.ui.tracking

import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.navigation.Route
import com.shinsaikou.presentation.core.viewmodel.StateViewModel
import com.shinsaikou.sync.SyncRunRecord
import com.shinsaikou.sync.SyncScheduler
import com.shinsaikou.tracking.ApplyResult
import com.shinsaikou.tracking.ApplyRow
import com.shinsaikou.tracking.ApplyRun
import com.shinsaikou.tracking.ApplyTrackChoice
import com.shinsaikou.tracking.CompareChoice
import com.shinsaikou.tracking.CompareFailure
import com.shinsaikou.tracking.CompareFailures
import com.shinsaikou.tracking.CompareSection
import com.shinsaikou.tracking.CompareTrackProgress
import com.shinsaikou.tracking.ComparedEntry
import com.shinsaikou.tracking.ListComparingTracker
import com.shinsaikou.tracking.StopKind
import com.shinsaikou.tracking.TrackerManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * "Compare with MyAnimeList" (phase 4 unit TC), for one tracker: the comparison on open and on
 * "Compare again", the owner's choice per row and the two bulk actions, and Apply.
 *
 * A comparison reads the links before any network call (none: the empty state, no sync, no call),
 * then starts two jobs together: a sync with the other device ([SyncScheduler.syncNow], watched on
 * WorkManager's `sync-once` for at most [SYNC_WAIT], as Sync settings watches it) and the read of
 * the tracker ([CompareTrackProgress.readRemote]). The local pass waits for both, so chapters read on
 * the other device count; a stop of the read cancels the sync's wait at once. A sync that did not
 * finish, or finished without succeeding since the tap, puts the last-synced line over the list.
 *
 * Nothing is written before Apply and its confirmation. Every row starts on "Don't change", and the
 * choices live here, for the screen's life only. Apply is [ApplyTrackChoice]'s run over the rows
 * whose choice changes something, in list order; rows done leave the list, rows the service or the
 * app changed since stay with that result and no choice, failed rows and rows a stop or a cancel did
 * not reach keep their choices. When any row wrote to this device, [SyncScheduler.syncSoon] asks for
 * a run so the other device learns of it sooner.
 *
 * The time of the tap is read from `Clock.System`, as `SyncSession` stamps its runs, so the two are
 * compared on one clock.
 */
@AssistedInject
class TrackerCompareViewModel(
    @Assisted private val trackerId: Long,
    trackerManager: TrackerManager,
    private val compareTrackProgress: CompareTrackProgress,
    private val applyTrackChoice: ApplyTrackChoice,
    private val syncScheduler: SyncScheduler,
    private val syncRunRecord: SyncRunRecord,
    private val workManager: WorkManager,
    private val dispatchers: Dispatchers,
) : StateViewModel<TrackerCompareViewModel.State>(State()) {

    data class State(
        val trackerName: String = "",
        val screen: Screen = Screen.Reading(),
        /** The confirmation of Apply, while it is open. */
        val confirming: ConfirmSummary? = null,
        /** The run in progress; the applying dialog is shown while it is set. */
        val applying: Applying? = null,
    ) {
        /** Apply is under the list: while some row can change something. */
        val applyShown: Boolean
            get() = (screen as? Screen.Compared)?.rows?.any { it.entry.section != CompareSection.CANT_COMPARE } == true

        /** Apply is enabled while a row's choice changes something and nothing is read or applied. */
        val applyEnabled: Boolean
            get() = applying == null && (screen as? Screen.Compared)?.rows?.any { it.changes } == true
    }

    sealed interface Screen {
        /**
         * The comparison is reading: [syncing] while the sync with the other device is awaited,
         * [checking] once the number of entries read one at a time is known.
         */
        data class Reading(val syncing: Boolean = false, val checking: Int? = null) : Screen

        /** No library entry is tracked on the tracker. */
        data object NothingTracked : Screen

        /** The comparison stopped (the design's 5.6); nothing is listed. */
        data class Stopped(val stop: Stop) : Screen

        /**
         * The rows that differ or cannot be compared, in the screen's order, out of [compared]
         * entries; [sync] when the sync with the other device did not complete; [runStop] after an
         * Apply run that stopped.
         */
        data class Compared(
            val compared: Int,
            val rows: List<Row>,
            val sync: SyncNotice? = null,
            val runStop: RunStop? = null,
        ) : Screen {
            val cantCompare: Int get() = rows.count { it.entry.section == CompareSection.CANT_COMPARE }
            val differing: Int get() = rows.size - cantCompare
            val agreeing: Int get() = compared - rows.size
        }
    }

    /**
     * One row: the compared [entry], the owner's [choice] (null when the row offers none, or no
     * longer does after Apply), and what Apply did with it. [ending] is what an update of the service
     * also changes there; [remoteCompleted] is whether the service shows the entry as completed.
     */
    data class Row(
        val entry: ComparedEntry,
        val choice: CompareChoice?,
        val result: RowResult? = null,
        val ending: UpdateEnding = UpdateEnding.NONE,
        val remoteCompleted: Boolean = false,
    ) {
        val mangaId: Long get() = entry.manga.id

        /** Whether this row's choice writes something when applied. */
        val changes: Boolean get() = choice != null && choice != CompareChoice.DONT_CHANGE
    }

    /** What an update of the service to [ComparedEntry.target] also changes there (the design's 7.3). */
    enum class UpdateEnding { NONE, READING, COMPLETED, ENDS_REREAD }

    /** What Apply did with a row that stays in the list. */
    sealed interface RowResult {
        /** No choice: what the owner was shown no longer holds. */
        data object ChangedSince : RowResult

        /** No choice: the service kept [remoteChapters] instead of the number sent. */
        data class Kept(val remoteChapters: Int) : RowResult

        /** No choice: [marked] of the [listed] chapters were marked; the others changed since. */
        data class PartlyMarked(val marked: Int, val listed: Int) : RowResult

        /** The row failed and keeps its choice. */
        data class Failed(val problem: Problem) : RowResult

        /** A send whose answer never came; the row keeps its choice. */
        data object Unanswered : RowResult
    }

    /** A failure as `ErrorText` reads it back (R67). */
    data class Problem(val key: ErrorKey, val detail: String?)

    /** Why a comparison or a run stopped: its [kind] decides the text and the action, [problem] the error text. */
    data class Stop(val kind: StopKind, val problem: Problem)

    /** After a run that stopped: [reached] of [total] rows were reached. */
    data class RunStop(val reached: Int, val total: Int, val stop: Stop)

    /** The sync with the other device did not complete; its last success, 0 when it never succeeded. */
    data class SyncNotice(val lastSuccessAt: Long)

    /** The applying dialog: row [current] of [total], and whether the owner asked to stop. */
    data class Applying(val current: Int, val total: Int, val stopping: Boolean = false)

    /**
     * The lines of Apply's confirmation (the design's 7.6), only the kinds present: [changes] rows
     * change something; [markChapters] chapter rows (each scanlator's copy counted, as the chapter
     * list shows them) on [markEntries] entries; [updates] entries updated on the service, [completed]
     * of which will show completed; a line per set back; [addBacks], [removals] and [keeps].
     */
    data class ConfirmSummary(
        val changes: Int,
        val markChapters: Int = 0,
        val markEntries: Int = 0,
        val updates: Int = 0,
        val setBacks: List<SetBack> = emptyList(),
        val completed: Int = 0,
        val addBacks: Int = 0,
        val removals: Int = 0,
        val keeps: Int = 0,
    )

    /** One set back line: [title] goes back from chapter [from] to [to]. */
    data class SetBack(val title: String, val from: Int, val to: Int)

    sealed interface Event {
        /** Leave the screen: "Sign in again" and "Back to settings" return to Settings, Tracking. */
        data object Back : Event

        data class Open(val route: Route) : Event

        /** "Keep the higher number" chose [chose] rows and left [left] warned ones. */
        data class ChoseHigher(val chose: Int, val left: Int) : Event

        /** A run ended: rows done, changed since, and failed (a number the service kept included). */
        data class Applied(val done: Int, val changed: Int, val failed: Int) : Event
    }

    private val tracker: ListComparingTracker? = trackerManager.get(trackerId) as? ListComparingTracker

    private val eventChannel = Channel<Event>(Channel.UNLIMITED)
    val events: Flow<Event> = eventChannel.receiveAsFlow()

    private var compareJob: Job? = null
    private val cancelled = MutableStateFlow(false)

    init {
        mutableState.update { it.copy(trackerName = tracker?.name.orEmpty()) }
        compare()
    }

    /** Compares from the start, on open and on "Compare again"; every choice made is discarded. */
    fun compare() {
        if (state.value.applying != null) return
        compareJob?.cancel()
        compareJob = viewModelScope.launch { runComparison() }
    }

    private suspend fun runComparison() {
        mutableState.update { it.copy(screen = Screen.Reading(), confirming = null) }
        val tracker = tracker ?: return mutableState.update { it.copy(screen = Screen.NothingTracked) }
        try {
            val links = withContext(dispatchers.io) { compareTrackProgress.links(trackerId) }
            if (links.isEmpty()) return mutableState.update { it.copy(screen = Screen.NothingTracked) }
            val tap = Clock.System.now().toEpochMilliseconds()
            val (sync, comparison) = coroutineScope {
                val sync = async { syncFirst(tap) }
                val remote = try {
                    withContext(dispatchers.io) {
                        compareTrackProgress.readRemote(tracker, links) { count -> updateReading { it.copy(checking = count) } }
                    }
                } catch (e: Throwable) {
                    sync.cancel()
                    throw e
                }
                val notice = sync.await()
                notice to withContext(dispatchers.io) { compareTrackProgress.compare(tracker, links, remote) }
            }
            val rows = comparison.rows.map { rowOf(tracker, it) }
            mutableState.update { it.copy(screen = Screen.Compared(comparison.entries.size, rows, sync)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutableState.update { it.copy(screen = Screen.Stopped(stopOf(CompareFailures.stopOf(e)))) }
        }
    }

    /**
     * The sync with the other device before the local pass, or null when there is nothing to say:
     * no backend is configured ([SyncScheduler.syncNow] enqueues nothing), or a run succeeded since
     * [tap]. The wait ends when a run is seen to finish, or when a run has succeeded since the tap
     * (WorkManager's flow can miss a run that starts and ends between two emissions), or after
     * [SYNC_WAIT].
     */
    private suspend fun syncFirst(tap: Long): SyncNotice? {
        if (!syncScheduler.syncNow()) return null
        updateReading { it.copy(syncing = true) }
        var seenUnfinished = false
        withTimeoutOrNull(SYNC_WAIT) {
            workManager.getWorkInfosForUniqueWorkFlow(SyncScheduler.WORK_NAME_ONCE).first { infos ->
                if (infos.any { !it.state.isFinished }) seenUnfinished = true
                syncRunRecord.lastSuccessAt.get() >= tap ||
                    (seenUnfinished && infos.isNotEmpty() && infos.all { it.state.isFinished })
            }
        }
        updateReading { it.copy(syncing = false) }
        val lastSuccess = syncRunRecord.lastSuccessAt.get()
        return if (lastSuccess >= tap) null else SyncNotice(lastSuccess)
    }

    private fun updateReading(transform: (Screen.Reading) -> Screen.Reading) {
        mutableState.update { state ->
            val reading = state.screen as? Screen.Reading ?: return@update state
            state.copy(screen = transform(reading))
        }
    }

    /** Sets [mangaId]'s row to [choice], when the row offers it. */
    fun choose(mangaId: Long, choice: CompareChoice) {
        updateRows { rows ->
            rows.map { row ->
                if (row.mangaId == mangaId && row.choice != null && choice in row.entry.choices) row.copy(choice = choice) else row
            }
        }
    }

    /**
     * "Keep the higher number": in the two "ahead" sections, every row without a warning takes "Mark
     * as read in the app" (the service ahead, something to mark) or "Update" (the app ahead). A warned
     * row, a row with nothing to mark and every row off the list keep their choice.
     */
    fun keepHigher() {
        val screen = state.value.screen as? Screen.Compared ?: return
        if (state.value.applying != null) return
        var chose = 0
        var left = 0
        val rows = screen.rows.map { row ->
            val higher = when (row.entry.section) {
                CompareSection.REMOTE_AHEAD -> CompareChoice.MARK_READ
                CompareSection.APP_AHEAD -> CompareChoice.UPDATE_REMOTE
                else -> null
            }
            when {
                higher == null || row.choice == null -> row
                row.entry.warnings.isNotEmpty() -> row.also { left++ }
                higher !in row.entry.choices -> row
                else -> row.copy(choice = higher).also { chose++ }
            }
        }
        updateRows { rows }
        eventChannel.trySend(Event.ChoseHigher(chose, left))
    }

    /** "Don't change any": every row with a choice back to "Don't change". */
    fun dontChangeAny() {
        updateRows { rows -> rows.map { if (it.choice != null) it.copy(choice = CompareChoice.DONT_CHANGE) else it } }
    }

    /** Opens Apply's confirmation over the rows whose choice changes something. */
    fun requestApply() {
        if (!state.value.applyEnabled) return
        val rows = changingRows()
        mutableState.update { it.copy(confirming = summaryOf(rows)) }
    }

    fun dismissConfirm() {
        mutableState.update { it.copy(confirming = null) }
    }

    /** Applies the rows whose choice changes something, in list order, after the confirmation. */
    fun apply() {
        val tracker = tracker ?: return
        if (state.value.applying != null) return
        val rows = changingRows()
        if (rows.isEmpty()) return dismissConfirm()
        cancelled.value = false
        mutableState.update { it.copy(confirming = null, applying = Applying(current = 1, total = rows.size)) }
        viewModelScope.launch {
            val run = applyTrackChoice.run(
                tracker = tracker,
                rows = rows.map { ApplyRow(it.entry, it.choice!!) },
                cancelled = cancelled,
                onRowStart = { index ->
                    mutableState.update { state -> state.copy(applying = state.applying?.copy(current = index + 1)) }
                },
            )
            finish(rows, run)
        }
    }

    /** Cancel in the applying dialog: the run ends after the row in progress. */
    fun cancelApply() {
        cancelled.value = true
        mutableState.update { state -> state.copy(applying = state.applying?.copy(stopping = true)) }
    }

    /**
     * The action under a stop's text: "Sign in again" and "Back to settings" go back to Settings,
     * Tracking, whose tracker row offers both; "Retry" compares again, or, after a run that stopped,
     * opens Apply's confirmation for the rows left.
     */
    fun stopAction() {
        val screen = state.value.screen
        val stop = when (screen) {
            is Screen.Stopped -> screen.stop
            is Screen.Compared -> screen.runStop?.stop
            else -> null
        } ?: return
        when {
            stop.kind == StopKind.SIGN_IN_REFUSED || stop.kind == StopKind.UNAUTHORIZED -> eventChannel.trySend(Event.Back)
            screen is Screen.Stopped -> compare()
            else -> requestApply()
        }
    }

    /** Opens [mangaId]'s manga screen, from its cover or a "Can't compare" row; nothing while applying. */
    fun openManga(mangaId: Long) {
        if (state.value.applying != null) return
        eventChannel.trySend(Event.Open(Route.MangaDetail(mangaId)))
    }

    private fun changingRows(): List<Row> = (state.value.screen as? Screen.Compared)?.rows.orEmpty().filter { it.changes }

    private fun finish(applied: List<Row>, run: ApplyRun) {
        val counted = applied.zip(run.results)
        val results = counted.associate { (row, result) -> row.mangaId to result }
        val done = counted.count { (_, result) -> result is ApplyResult.Done }
        val changed = counted.count { (_, result) -> result == ApplyResult.ChangedSince }
        val failed = counted.count { (_, result) -> result is ApplyResult.Kept || result is ApplyResult.Failed }
        // A mark, a stored answer or a removal is this device's database; a cancelled waiting update is not.
        val wrote = counted.any { (row, result) ->
            row.choice != CompareChoice.KEEP_REMOTE && result != ApplyResult.NotReached && result !is ApplyResult.Failed
        }
        val runStop = run.stop?.let { RunStop(run.reached, applied.size, stopOf(it)) }
        mutableState.update { state ->
            val screen = state.screen as? Screen.Compared ?: return@update state.copy(applying = null)
            val rows = screen.rows.mapNotNull { row ->
                val result = results[row.mangaId]
                if (result == null) row else afterRun(row, result)
            }
            state.copy(screen = screen.copy(rows = rows, runStop = runStop), applying = null)
        }
        if (wrote) syncScheduler.syncSoon()
        eventChannel.trySend(Event.Applied(done, changed, failed))
    }

    /** [row] after Apply's [result]: null when it leaves the list. */
    private fun afterRun(row: Row, result: ApplyResult): Row? = when (result) {
        is ApplyResult.Done -> if (row.choice == CompareChoice.MARK_READ && result.marked < result.listed) {
            row.copy(choice = null, result = RowResult.PartlyMarked(result.marked, result.listed))
        } else {
            null
        }
        is ApplyResult.Kept -> row.copy(choice = null, result = RowResult.Kept(result.remoteChapters))
        ApplyResult.ChangedSince -> row.copy(choice = null, result = RowResult.ChangedSince)
        is ApplyResult.Failed -> {
            val failure = result.failure
            val unanswered = failure is CompareFailure.Stop && failure.sendUnanswered
            row.copy(result = if (unanswered) RowResult.Unanswered else RowResult.Failed(problemOf(failure.error)))
        }
        ApplyResult.NotReached -> row
    }

    private fun updateRows(transform: (List<Row>) -> List<Row>) {
        mutableState.update { state ->
            val screen = state.screen as? Screen.Compared ?: return@update state
            if (state.applying != null) return@update state
            state.copy(screen = screen.copy(rows = transform(screen.rows)))
        }
    }

    private fun rowOf(tracker: ListComparingTracker, entry: ComparedEntry): Row {
        val listed = entry.listed
        val ending = when {
            listed == null || entry.section != CompareSection.APP_AHEAD -> UpdateEnding.NONE
            entry.count > 0 && entry.target.toLong() == entry.count ->
                if (listed.status == tracker.getRereadingStatus()) UpdateEnding.ENDS_REREAD else UpdateEnding.COMPLETED
            listed.chaptersRead == 0 && listed.status != tracker.getRereadingStatus() -> UpdateEnding.READING
            else -> UpdateEnding.NONE
        }
        return Row(
            entry = entry,
            choice = entry.defaultChoice,
            ending = ending,
            remoteCompleted = listed?.status == tracker.getCompletionStatus(),
        )
    }

    private fun summaryOf(rows: List<Row>): ConfirmSummary {
        val marks = rows.filter { it.choice == CompareChoice.MARK_READ }
        val updates = rows.filter { it.choice == CompareChoice.UPDATE_REMOTE }
        return ConfirmSummary(
            changes = rows.size,
            markChapters = marks.sumOf { it.entry.toMark.size },
            markEntries = marks.size,
            updates = updates.size,
            setBacks = rows.filter { it.choice == CompareChoice.SET_REMOTE_BACK }
                .map { SetBack(it.entry.manga.displayTitle, it.entry.listed?.chaptersRead ?: 0, it.entry.app) },
            completed = updates.count { it.ending == UpdateEnding.COMPLETED || it.ending == UpdateEnding.ENDS_REREAD },
            addBacks = rows.count { it.choice == CompareChoice.ADD_BACK },
            removals = rows.count { it.choice == CompareChoice.REMOVE_TRACKING },
            keeps = rows.count { it.choice == CompareChoice.KEEP_REMOTE },
        )
    }

    private fun stopOf(stop: CompareFailure.Stop) = Stop(stop.kind, problemOf(stop.error))

    private fun problemOf(e: Throwable) = Problem(ErrorKey.of(e), ErrorKey.detail(e))

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(trackerId: Long): TrackerCompareViewModel
    }

    companion object {
        /** How long a comparison waits for the sync with the other device (the design's 5.2). */
        val SYNC_WAIT = 60.seconds
    }
}

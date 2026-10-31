package com.shinsaikou.ui.manga

import androidx.lifecycle.viewModelScope
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.domain.preferences.UiPreferences
import com.shinsaikou.presentation.core.viewmodel.StateViewModel
import com.shinsaikou.tracking.AddTracks
import com.shinsaikou.tracking.ApplyResult
import com.shinsaikou.tracking.ComparedEntry
import com.shinsaikou.tracking.EditTrack
import com.shinsaikou.tracking.ListComparingTracker
import com.shinsaikou.tracking.RefreshTracks
import com.shinsaikou.tracking.RemoteProgressOffer
import com.shinsaikou.tracking.RemoveTrack
import com.shinsaikou.tracking.TrackSearchResult
import com.shinsaikou.tracking.Tracker
import com.shinsaikou.tracking.TrackerManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import java.time.format.DateTimeFormatter
import kotlin.time.Instant

/**
 * The track sheet of one manga (phase 3 unit T3), upstream's `TrackInfoDialog`: one row per signed-in
 * tracker, the search that binds or rebinds a row, the status, chapter, score and date pickers, and
 * remove. Upstream keeps each page's state in its own screen model inside a navigator in the sheet;
 * here one view model holds the page shown, so the sheet is one case of
 * [MangaDetailViewModel.Dialog] and its pages have their own back handling.
 *
 * Its own view model rather than more of [MangaDetailViewModel], which already takes 29 bindings:
 * the sheet's work (a refresh on open, a search, the edits) is its own and is tested apart. It lives
 * in the manga screen's store, the route entry's or the library pane's, so it is dropped with the
 * screen.
 *
 * Every write goes straight to the service ([EditTrack], [RemoveTrack], [AddTracks]) and a failure
 * is kept per tracker as its key and detail (R67) for the row to show; only the progress recorded
 * from reading goes through the retry queue (R95 item 3 as T3 amends it). A tracker whose sign-in
 * has expired keeps its row, with "Sign in again" in place of the service's actions, and only a
 * local remove.
 *
 * When the refresh on open finds the service ahead of the chapters read here, the row offers to
 * mark them read up to the service's number ([RemoteProgressOffer], Compare's "Mark as read" on this
 * one entry). Upstream stores the service's number and offers nothing.
 */
@AssistedInject
class TrackSheetViewModel(
    @Assisted private val mangaId: Long,
    private val trackerManager: TrackerManager,
    private val trackRepository: TrackRepository,
    private val refreshTracks: RefreshTracks,
    private val addTracks: AddTracks,
    private val editTrack: EditTrack,
    private val removeTrack: RemoveTrack,
    private val remoteProgressOffer: RemoteProgressOffer,
    private val uiPreferences: UiPreferences,
    private val dispatchers: Dispatchers,
) : StateViewModel<TrackSheetViewModel.State>(State()) {

    /**
     * How the rows write a start or finish date: the owner's date format, as upstream's
     * `TrackInfoDialog` reads it once when the sheet opens (phase 4 unit SR).
     */
    fun dateFormat(): DateTimeFormatter = UiPreferences.dateFormat(uiPreferences.dateFormat.get())

    data class State(
        /** One per signed-in tracker, in the manager's order (by id). */
        val rows: List<Row> = emptyList(),
        val page: Page = Page.Home,
        /** The last failure of a refresh or a write, by tracker id; cleared by the next write. */
        val problems: Map<Long, Problem> = emptyMap(),
        /** By tracker id, the service's chapter when the refresh on open found it ahead of the app. */
        val offers: Map<Long, Offer> = emptyMap(),
    ) {
        fun row(trackerId: Long): Row? = rows.firstOrNull { it.tracker.id == trackerId }
    }

    /** One tracker's row: [track] is null while the manga has none for it. */
    data class Row(val tracker: Tracker, val track: Track?, val authExpired: Boolean)

    /** A failure as `ErrorText` reads it back (R67). */
    data class Problem(val key: ErrorKey, val detail: String?)

    /** "<tracker> is at chapter [chapter]: mark read up to [chapter]", over Compare's [entry]. */
    data class Offer(val chapter: Int, val entry: ComparedEntry)

    /** The page the sheet shows. Every page but [Home] is about one tracker's row. */
    sealed interface Page {
        data object Home : Page

        /** [results] is null while the search runs. */
        data class Search(
            val trackerId: Long,
            val query: String,
            val results: SearchResults? = null,
            val selected: TrackSearchResult? = null,
        ) : Page

        data class Status(val trackerId: Long) : Page
        data class Chapter(val trackerId: Long) : Page
        data class Score(val trackerId: Long) : Page
        data class Date(val trackerId: Long, val start: Boolean) : Page
        data class RemoveDate(val trackerId: Long, val start: Boolean) : Page

        /** [track] is the row the dialog was opened on, the one the confirm removes (see [remove]). */
        data class Remove(val trackerId: Long, val track: Track) : Page
    }

    sealed interface SearchResults {
        data class Found(val results: List<TrackSearchResult>) : SearchResults
        data class Failed(val problem: Problem) : SearchResults
    }

    /** Whether the sheet is open; [open] refreshes once per opening, not once per composition. */
    private var opened = false
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            combine(trackRepository.getTracksByMangaIdAsFlow(mangaId), signedInTrackers()) { tracks, trackers ->
                trackers.map { (tracker, expired) ->
                    Row(tracker, tracks.firstOrNull { it.trackerId == tracker.id }, expired)
                }
            }
                .flowOn(dispatchers.io)
                .collect { rows -> mutableState.update { it.copy(rows = rows) } }
        }
    }

    /** The signed-in trackers, each with whether its sign-in has expired, as both change. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun signedInTrackers(): Flow<List<Pair<Tracker, Boolean>>> =
        trackerManager.loggedInTrackersFlow().flatMapLatest { trackers ->
            if (trackers.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(trackers.map { tracker -> tracker.isAuthExpiredFlow.map { tracker to it } }) { it.toList() }
            }
        }

    /**
     * The sheet was opened: the home page, and upstream's refresh on open of every bound row whose
     * tracker is signed in. A second call before [close] (the sheet composed again after a
     * rotation) does nothing.
     */
    fun open() {
        if (opened) return
        opened = true
        mutableState.update { it.copy(page = Page.Home, problems = emptyMap(), offers = emptyMap()) }
        viewModelScope.launch {
            val failures = refreshTracks.await(mangaId)
            mutableState.update { state ->
                state.copy(problems = state.problems + failures.associate { (tracker, e) -> tracker.id to problemOf(e) })
            }
            val failed = failures.mapTo(mutableSetOf()) { (tracker, _) -> tracker.id }
            trackerManager.trackers.filter { it.isLoggedIn && !it.isAuthExpired && it.id !in failed }
                .forEach { offer(it) }
        }
    }

    /**
     * The offer's action: the chapters up to the service's number are marked read, while the service
     * still shows it. The row offers again whatever is left to offer after it, and a failure is kept
     * for the row.
     */
    fun markReadUpToRemote(trackerId: Long) {
        val offer = state.value.offers[trackerId] ?: return
        val tracker = state.value.row(trackerId)?.tracker as? ListComparingTracker ?: return
        mutableState.update { it.copy(problems = it.problems - trackerId, offers = it.offers - trackerId) }
        viewModelScope.launch {
            try {
                val result = remoteProgressOffer.accept(tracker, offer.entry)
                if (result is ApplyResult.Failed) {
                    mutableState.update { it.copy(problems = it.problems + (trackerId to problemOf(result.failure.error))) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(problems = it.problems + (trackerId to problemOf(e))) }
            }
            offer(tracker)
        }
    }

    /** Reads [tracker]'s offer for this manga into the state; a failure to read it offers nothing. */
    private suspend fun offer(tracker: Tracker) {
        val entry = try {
            remoteProgressOffer.find(tracker, mangaId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Could not compare manga $mangaId with tracker ${tracker.id}" }
            null
        }
        val chapter = entry?.listed?.chaptersRead
        mutableState.update { state ->
            if (entry == null || chapter == null) {
                state.copy(offers = state.offers - tracker.id)
            } else {
                state.copy(offers = state.offers + (tracker.id to Offer(chapter, entry)))
            }
        }
    }

    fun close() {
        opened = false
        searchJob?.cancel()
        mutableState.update { it.copy(page = Page.Home) }
    }

    /** Back on a page: the date picker from its remove confirmation, the home page from the rest. */
    fun back() {
        val page = state.value.page
        if (page is Page.Search) searchJob?.cancel()
        showPage(if (page is Page.RemoveDate) Page.Date(page.trackerId, page.start) else Page.Home)
    }

    /**
     * Opens the search for [trackerId], with the bound row's title as the query, else [mangaTitle],
     * as upstream's does; a bound row's search is how it is bound to another entry.
     */
    fun showSearch(trackerId: Long, mangaTitle: String) {
        val row = state.value.row(trackerId) ?: return
        search(trackerId, row.track?.title ?: mangaTitle)
    }

    fun search(trackerId: Long, query: String) {
        val row = state.value.row(trackerId) ?: return
        searchJob?.cancel()
        showPage(Page.Search(trackerId, query))
        if (query.isBlank()) return
        searchJob = viewModelScope.launch {
            val results = try {
                SearchResults.Found(withContext(dispatchers.io) { row.tracker.search(query) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SearchResults.Failed(problemOf(e))
            }
            // The bound entry is selected when it is among the results, as in upstream.
            val bound = row.track?.remoteUrl
            val selected = (results as? SearchResults.Found)?.results?.firstOrNull { it.trackingUrl == bound }
            mutableState.update { state ->
                val page = state.page as? Page.Search ?: return@update state
                if (page.trackerId != trackerId || page.query != query) return@update state
                state.copy(page = page.copy(results = results, selected = selected))
            }
        }
    }

    fun select(result: TrackSearchResult) {
        mutableState.update { state ->
            val page = state.page as? Page.Search ?: return@update state
            state.copy(page = page.copy(selected = result))
        }
    }

    /** Binds the selected result and goes back to the home page while the bind runs, as upstream does. */
    fun bindSelected(private: Boolean = false) {
        val page = state.value.page as? Page.Search ?: return
        val result = page.selected ?: return
        write(page.trackerId) { tracker -> addTracks.bind(tracker, result, mangaId, private) }
    }

    fun showStatus(trackerId: Long) = showPage(Page.Status(trackerId))
    fun showChapter(trackerId: Long) = showPage(Page.Chapter(trackerId))
    fun showScore(trackerId: Long) = showPage(Page.Score(trackerId))
    fun showDate(trackerId: Long, start: Boolean) = showPage(Page.Date(trackerId, start))
    fun showRemoveDate(trackerId: Long, start: Boolean) = showPage(Page.RemoveDate(trackerId, start))

    /** Opens the remove dialog on the row as it is now; a row with no track has nothing to remove. */
    fun showRemove(trackerId: Long) {
        val track = state.value.row(trackerId)?.track ?: return
        showPage(Page.Remove(trackerId, track))
    }

    fun setStatus(trackerId: Long, status: Long) =
        write(trackerId) { editTrack.setStatus(it, mangaId, status) }

    fun setChapter(trackerId: Long, chapterNumber: Int) =
        write(trackerId) { editTrack.setChapter(it, mangaId, chapterNumber) }

    fun setScore(trackerId: Long, score: String) =
        write(trackerId) { editTrack.setScore(it, mangaId, score) }

    /** [utcMillis] is the date picker's selection, midnight UTC; stored as local midnight, as upstream does. */
    fun setDate(trackerId: Long, start: Boolean, utcMillis: Long) {
        val millis = utcMillis.convertEpochMillisZone(TimeZone.UTC, TimeZone.currentSystemDefault())
        write(trackerId) { tracker ->
            if (start) editTrack.setStartDate(tracker, mangaId, millis) else editTrack.setFinishDate(tracker, mangaId, millis)
        }
    }

    fun removeDate(trackerId: Long, start: Boolean) = write(trackerId) { tracker ->
        if (start) editTrack.setStartDate(tracker, mangaId, 0) else editTrack.setFinishDate(tracker, mangaId, 0)
    }

    fun togglePrivate(trackerId: Long) {
        val track = state.value.row(trackerId)?.track ?: return
        write(trackerId) { editTrack.setPrivate(it, mangaId, !track.private) }
    }

    /**
     * Removes the row the remove dialog was opened on, and with [alsoRemote] its entry on the
     * service; a sign-in expired removes locally only. A rebind synced in while the dialog was open
     * is kept, and the service's delete still goes to the entry the owner saw ([RemoveTrack]; phase 4
     * unit TR, R100 carry-over 1).
     */
    fun remove(alsoRemote: Boolean) {
        val page = state.value.page as? Page.Remove ?: return
        val row = state.value.row(page.trackerId) ?: return
        write(page.trackerId) { removeTrack.await(it, page.track, alsoRemote && !row.authExpired) }
    }

    private fun showPage(page: Page) {
        mutableState.update { it.copy(page = page) }
    }

    /** Back to the home page, then [block] against the row's tracker, its failure kept for the row. */
    private fun write(trackerId: Long, block: suspend (Tracker) -> Unit) {
        val tracker = state.value.row(trackerId)?.tracker ?: return
        // An edit changes what the offer was worked out from; the next opening offers again.
        mutableState.update {
            it.copy(page = Page.Home, problems = it.problems - trackerId, offers = it.offers - trackerId)
        }
        viewModelScope.launch {
            try {
                block(tracker)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(problems = it.problems + (trackerId to problemOf(e))) }
            }
        }
    }

    private fun problemOf(e: Throwable) = Problem(ErrorKey.of(e), ErrorKey.detail(e))

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(mangaId: Long): TrackSheetViewModel
    }
}

/** Upstream's `convertEpochMillisZone`: the same wall-clock time, read in [from] and written in [to]. */
internal fun Long.convertEpochMillisZone(from: TimeZone, to: TimeZone): Long =
    Instant.fromEpochMilliseconds(this).toLocalDateTime(from).toInstant(to).toEpochMilliseconds()

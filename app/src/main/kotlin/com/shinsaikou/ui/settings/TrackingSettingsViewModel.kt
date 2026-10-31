package com.shinsaikou.ui.settings

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.core.common.oauth.LoopbackListener
import com.shinsaikou.core.common.oauth.Pkce
import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.domain.preferences.AutoTrackState
import com.shinsaikou.domain.preferences.TrackPreferences
import com.shinsaikou.presentation.core.viewmodel.StateViewModel
import com.shinsaikou.signin.BrowserSignIn
import com.shinsaikou.tracking.ListComparingTracker
import com.shinsaikou.tracking.OAuthTracker
import com.shinsaikou.tracking.ResumeQueuedPushes
import com.shinsaikou.tracking.Tracker
import com.shinsaikou.tracking.TrackerManager
import com.shinsaikou.ui.util.withCurrent
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tracking settings (phase 3 unit T1): one row per configured tracker in the graph, with its sign-in
 * and sign-out, and the two behaviour rows, `autoUpdateTrack` (the reader) and
 * `autoUpdateTrackOnMarkRead` (the chapter list). Its own view model, as Sync's is, so
 * [SettingsViewModel] takes on no tracker class beyond the one list it needs to hide the section.
 *
 * A tracker's sign-in is O's [BrowserSignIn] on `LoopbackListener.TRACKER_PORT` from the visible
 * activity, as Sync's Google sign-in is: the PKCE pair, the listener's state and the code live in
 * [signIn]'s coroutine only, and the grant only in the tracker's private preferences (Q8). [State]
 * holds none of them, and a failure is kept as its key and detail (R67), never as a message. The
 * listener waits for what the tracker's redirect carries (`OAuthTracker.loopbackResponse`): a code,
 * or AniList's token in the fragment, which then stands where the code would.
 *
 * A sign-in that stored its grant then sends the pushes kept queued while the tracker was signed out
 * or expired, and removes the "sign in again" notice ([ResumeQueuedPushes]).
 *
 * Cancel cancels that coroutine: [BrowserSignIn] then closes the listener and ends the foreground
 * hold. A sign-in started anywhere else (another tracker's, or Drive's) cancels this one the same
 * way, as a [com.shinsaikou.signin.SignInCancelledException], which the row shows as nothing at all.
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(TrackingSettingsViewModel::class)
@Inject
class TrackingSettingsViewModel(
    trackerManager: TrackerManager,
    private val trackPreferences: TrackPreferences,
    private val browserSignIn: BrowserSignIn,
    private val dispatchers: Dispatchers,
    private val resumeQueuedPushes: ResumeQueuedPushes,
) : StateViewModel<TrackingSettingsViewModel.State>(State()) {

    data class State(
        /** One per configured tracker, in the manager's order (by id). */
        val trackers: List<TrackerRow> = emptyList(),
        val autoUpdateTrack: Boolean = true,
        val autoUpdateTrackOnMarkRead: AutoTrackState = AutoTrackState.ALWAYS,
    )

    /** What one tracker's row shows; [status] is derived, so the flags can never disagree with it. */
    data class TrackerRow(
        val id: Long,
        val name: String,
        val loggedIn: Boolean,
        val authExpired: Boolean,
        /** The service's name for the signed-in account; empty while signed out. */
        val displayName: String,
        /** Whether this build can start this tracker's sign-in: an [OAuthTracker]. */
        val canSignIn: Boolean,
        val signingIn: Boolean = false,
        /** Why the last sign-in did not sign in; cleared by the next attempt and by a sign-out. */
        val problem: Problem? = null,
        /** Whether this tracker can compare the owner's list with the app: a [ListComparingTracker]. */
        val canCompare: Boolean = false,
    ) {
        val status: Status
            get() = when {
                signingIn -> Status.WAITING_FOR_BROWSER
                loggedIn && authExpired -> Status.SIGN_IN_AGAIN
                loggedIn -> Status.SIGNED_IN
                else -> Status.SIGNED_OUT
            }

        /**
         * The "Compare with" row under the tracker's (phase 4 unit TC): enabled while signed in,
         * dimmed while the service wants a new sign-in, and absent while signed out or waiting for
         * the browser, where the tracker's own row offers Sign in.
         */
        val compare: CompareRow
            get() = when {
                !canCompare -> CompareRow.HIDDEN
                status == Status.SIGNED_IN -> CompareRow.ENABLED
                status == Status.SIGN_IN_AGAIN -> CompareRow.DIMMED
                else -> CompareRow.HIDDEN
            }
    }

    enum class CompareRow { HIDDEN, ENABLED, DIMMED }

    enum class Status {
        SIGNED_OUT,

        /** The browser is open on the service's page; the row offers Cancel. */
        WAITING_FOR_BROWSER,
        SIGNED_IN,

        /** Signed in, but the service refused the stored sign-in (`trackAuthExpired`, Q13). */
        SIGN_IN_AGAIN,
    }

    /**
     * A failed sign-in as `ErrorText` reads it back (R67): the loopback's own failures are O's
     * `oauth_*` keys (port in use, timed out, denied), a tracker's are its own.
     */
    data class Problem(val key: ErrorKey, val detail: String?)

    private val trackers: List<Tracker> = trackerManager.trackers.filter { it.isConfigured }

    /** The sign-in in flight per tracker id; touched on the main thread only. */
    private val signIns = mutableMapOf<Long, Job>()

    init {
        mutableState.update { state -> state.copy(trackers = trackers.map { rowOf(it) }) }
        trackers.forEach { tracker ->
            viewModelScope.launch {
                combine(
                    tracker.isLoggedInFlow.onStart { emit(tracker.isLoggedIn) },
                    tracker.isAuthExpiredFlow.onStart { emit(tracker.isAuthExpired) },
                ) { _, _ -> }
                    .collect { refresh(tracker) }
            }
        }
        viewModelScope.launch {
            trackPreferences.autoUpdateTrack.withCurrent()
                .collect { enabled -> mutableState.update { it.copy(autoUpdateTrack = enabled) } }
        }
        viewModelScope.launch {
            trackPreferences.autoUpdateTrackOnMarkRead.withCurrent()
                .collect { value -> mutableState.update { it.copy(autoUpdateTrackOnMarkRead = value) } }
        }
    }

    /**
     * Starts [trackerId]'s browser sign-in, unless it is not an [OAuthTracker] or one is already in
     * flight for it. [returnIntent] is what the sign-in's notifications open to bring the owner back.
     */
    fun signIn(trackerId: Long, returnIntent: Intent) {
        val tracker = trackers.firstOrNull { it.id == trackerId } as? OAuthTracker ?: return
        if (signIns[trackerId]?.isActive == true) return
        updateRow(trackerId) { it.copy(signingIn = true, problem = null) }
        signIns[trackerId] = viewModelScope.launch {
            val problem = try {
                val pkce = Pkce.generate()
                val result = browserSignIn.signIn(
                    port = LoopbackListener.TRACKER_PORT,
                    returnIntent = returnIntent,
                    response = tracker.loopbackResponse,
                ) { listener ->
                    tracker.authorizationUrl(listener.redirectUri, listener.state, pkce)
                }
                tracker.completeSignIn(result.callback.code, result.redirectUri, pkce)
                // A cancel that landed while the grant was stored leaves the tracker signed in, so
                // what a sign-in sets going is not cancelled either.
                withContext(NonCancellable + dispatchers.io) { resumeQueuedPushes.await(tracker) }
                null
            } catch (e: CancellationException) {
                // Cancel, or another sign-in replaced this one: nothing to show.
                withContext(NonCancellable) { settle(tracker, problem = null) }
                throw e
            } catch (e: Exception) {
                Problem(ErrorKey.of(e), ErrorKey.detail(e))
            }
            settle(tracker, problem)
        }
    }

    /**
     * Cancels [trackerId]'s sign-in in flight; its listener closes and its hold ends. A sign-in
     * already storing what the service granted finishes storing it first (`OAuthTracker.completeSignIn`
     * writes under `NonCancellable`), then resumes the queued pushes, so a cancel never leaves half a
     * sign-in.
     */
    fun cancelSignIn(trackerId: Long) {
        signIns.remove(trackerId)?.cancel()
    }

    /**
     * Signs [trackerId] out: `Tracker.logout` clears its credentials, its token and the expired
     * flag. The track rows and the queued pushes stay; the retry worker keeps a signed-out
     * tracker's pushes, and the next sign-in sends them.
     *
     * A sign-in in flight is cancelled and waited for before the logout (phase 3 unit T2, R95
     * carry-over 1): one that lands while the tracker is storing its grant would otherwise be
     * signed out between two writes and end signed in, or with a token and no credentials.
     */
    fun signOut(trackerId: Long) {
        val tracker = trackers.firstOrNull { it.id == trackerId } ?: return
        viewModelScope.launch {
            signIns.remove(trackerId)?.cancelAndJoin()
            withContext(dispatchers.io) { tracker.logout() }
            settle(tracker, problem = null)
        }
    }

    fun setAutoUpdateTrack(enabled: Boolean) {
        mutableState.update { it.copy(autoUpdateTrack = enabled) }
        viewModelScope.launch(dispatchers.io) { trackPreferences.autoUpdateTrack.set(enabled) }
    }

    fun setAutoUpdateTrackOnMarkRead(value: AutoTrackState) {
        mutableState.update { it.copy(autoUpdateTrackOnMarkRead = value) }
        viewModelScope.launch(dispatchers.io) { trackPreferences.autoUpdateTrackOnMarkRead.set(value) }
    }

    /**
     * Reads [tracker]'s sign-in again into its row, keeping the row's problem, and its sign-in in
     * flight until the sign-in's credentials land: from then the row shows signed in, not waiting
     * for the browser until the sign-in's coroutine settles it (phase 3 unit T2, R95 carry-over 6).
     */
    private fun refresh(tracker: Tracker) {
        updateRow(tracker.id) { row ->
            val read = rowOf(tracker)
            read.copy(signingIn = row.signingIn && read.status != Status.SIGNED_IN, problem = row.problem)
        }
    }

    /**
     * Ends a sign-in or a sign-out: the row read again, nothing in flight, and [problem] shown. A
     * sign-in that stored nothing changes no flow, so this reads the tracker itself.
     */
    private fun settle(tracker: Tracker, problem: Problem?) {
        updateRow(tracker.id) { rowOf(tracker).copy(problem = problem) }
    }

    private fun updateRow(trackerId: Long, transform: (TrackerRow) -> TrackerRow) {
        mutableState.update { state ->
            state.copy(trackers = state.trackers.map { if (it.id == trackerId) transform(it) else it })
        }
    }

    private fun rowOf(tracker: Tracker): TrackerRow {
        val loggedIn = tracker.isLoggedIn
        return TrackerRow(
            id = tracker.id,
            name = tracker.name,
            loggedIn = loggedIn,
            authExpired = tracker.isAuthExpired,
            displayName = if (loggedIn) tracker.getDisplayUsername() else "",
            canSignIn = tracker is OAuthTracker,
            canCompare = tracker is ListComparingTracker,
        )
    }
}

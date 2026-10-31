package com.shinsaikou.tracking

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * What a tracker's new sign-in sets going: the "sign in again" notice is removed, and the pushes the
 * retry worker kept while the tracker was signed out or its sign-in had expired are sent, by asking
 * for a run when [DelayedTrackingStore] holds one for the tracker. The worker sends each as it sends
 * any push, refreshed first, so the number on the service only goes up.
 */
@Inject
class ResumeQueuedPushes(
    private val store: DelayedTrackingStore,
    private val scheduler: DelayedTrackingScheduler,
    private val notifier: TrackerSignInNotifier,
) {

    /**
     * Runs after [tracker]'s sign-in stored its grant; it reads the store, so off the main thread. A
     * failure is logged: the sign-in stands.
     */
    suspend fun await(tracker: Tracker) {
        try {
            notifier.signedIn(tracker)
            if (store.getItems().any { it.trackerId == tracker.id }) scheduler.schedule()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Could not send tracker ${tracker.id}'s queued pushes after its sign-in" }
        }
    }
}

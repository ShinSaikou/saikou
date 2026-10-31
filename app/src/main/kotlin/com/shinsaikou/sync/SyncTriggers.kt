package com.shinsaikou.sync

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.reader.ReaderActivity
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import kotlin.time.Duration.Companion.minutes

/**
 * When a sync runs besides its period and Sync now (Q10): at app start when the last successful
 * sync is more than [START_AFTER] old, when the process moves to the background while the change log
 * holds rows this device has not pushed, and [SyncScheduler.READER_CLOSED_DELAY] after the reader
 * closes. Each automatic trigger is a [SyncScheduler.syncSoon], which keeps a request already
 * waiting, and none fires while no backend is configured.
 *
 * App start also reconciles the Google sign-in's expiry notice ([SyncNotices.reconcileExpiry]), so an
 * expiry stored by a build without notices is noticed after an upgrade.
 *
 * The scheduler, the engine and the notices are taken lazily: [install] runs on the main thread in
 * `SaikouApp.onCreate`, where neither WorkManager nor the database should be opened.
 */
@SingleIn(AppScope::class)
@Inject
class SyncTriggers(
    private val scheduler: Lazy<SyncScheduler>,
    private val engine: Lazy<SyncEngine>,
    private val notices: Lazy<SyncNotices>,
    private val syncPreferences: SyncPreferences,
    private val record: SyncRunRecord,
    private val appScope: CoroutineScope,
    private val dispatchers: Dispatchers,
) {

    private val configured: Boolean
        get() = syncPreferences.backendId.get().isNotEmpty()

    /**
     * Registers the two lifecycle triggers. Main thread, once per process: `ProcessLifecycleOwner`
     * takes observers there only.
     */
    fun install(application: Application) {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) fire("background") { onBackground() }
            },
        )
        application.registerActivityLifecycleCallbacks(ReaderClosedCallbacks { fire("reader closed") { onReaderClosed() } })
    }

    /**
     * Arms the periodic run, and asks for one now when the last success is older than [START_AFTER];
     * the expiry notice is reconciled off the caller's thread, since it reads the secret store.
     */
    fun onAppStart(now: Long) {
        scheduler.value.setup()
        if (configured && now - record.lastSuccessAt.get() > START_AFTER.inWholeMilliseconds) {
            scheduler.value.syncSoon()
        }
        fire("app start") { notices.value.reconcileExpiry() }
    }

    /** The process moved to the background: a run when the log holds rows after `lastPushedSeq`. */
    suspend fun onBackground() {
        if (configured && engine.value.hasUnpushedChanges()) scheduler.value.syncSoon()
    }

    /** The reader closed: a run [SyncScheduler.READER_CLOSED_DELAY] later, unless one is waiting. */
    fun onReaderClosed() {
        if (configured) scheduler.value.syncSoon(SyncScheduler.READER_CLOSED_DELAY)
    }

    private fun fire(trigger: String, block: suspend () -> Unit) {
        appScope.launch(dispatchers.io) {
            runCatching { block() }
                .onFailure { logcat(LogPriority.ERROR, it) { "The sync trigger on $trigger failed" } }
        }
    }

    /**
     * Calls [onClosed] when a reader activity is destroyed because it finished, not for a
     * configuration change, which destroys and recreates it.
     */
    private class ReaderClosedCallbacks(private val onClosed: () -> Unit) : Application.ActivityLifecycleCallbacks {
        override fun onActivityDestroyed(activity: Activity) {
            if (activity is ReaderActivity && activity.isFinishing) onClosed()
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }

    companion object {
        /** How old the last success may be before an app start asks for a run (Q10). */
        val START_AFTER = 15.minutes
    }
}

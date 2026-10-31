package com.shinsaikou.appupdate

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.shinsaikou.core.common.coroutines.Dispatchers
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * The automatic update check runs each time the app comes to the foreground (a launch included),
 * and [AppUpdateChecker.checkAutomatically] decides whether it is due. The checker is taken lazily:
 * [install] runs on the main thread in `SaikouApp.onCreate`, where the network client should not be
 * built.
 */
@SingleIn(AppScope::class)
@Inject
class AppUpdateTriggers(
    private val checker: Lazy<AppUpdateChecker>,
    private val appScope: CoroutineScope,
    private val dispatchers: Dispatchers,
) {

    /** Main thread, once per process: `ProcessLifecycleOwner` takes observers there only. */
    fun install() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START) {
                    appScope.launch(dispatchers.io) {
                        runCatching { checker.value.checkAutomatically() }
                            .onFailure { logcat(LogPriority.ERROR, it) { "The automatic update check failed" } }
                    }
                }
            },
        )
    }
}

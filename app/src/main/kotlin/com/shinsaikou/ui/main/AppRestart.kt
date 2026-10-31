package com.shinsaikou.ui.main

import android.content.Context
import android.content.Intent

/**
 * Ends the process and opens the app again on a [MainDestination], for the settings that are read
 * once per process: DNS over HTTPS and Verbose logging build the network client, the user agent is
 * built into each extension source's headers, and trust and the extension list override are read
 * when extensions load. Upstream only says "Requires app restart to take effect"; Saikou's snackbar
 * carries a Restart action that calls this (fixes unit settings-restart-and-wording, F13).
 *
 * ProcessPhoenix's pattern without the library: the activity is started in a new, cleared task,
 * then the process exits, and Android starts a new process for the task's pending activity. A
 * WorkManager job the exit cuts off is run again by WorkManager, as after any process death:
 * downloads and the restore resume from their checkpoints, and a sync run is idempotent.
 */
object AppRestart {

    fun restart(context: Context, destination: MainDestination) {
        context.startActivity(restartIntentOf(MainIntents.newIntent(context, destination)))
        Runtime.getRuntime().exit(0)
    }

    /**
     * [target]'s activity in a new task with the old one cleared
     * ([Intent.makeRestartActivityTask] sets `FLAG_ACTIVITY_NEW_TASK` and `FLAG_ACTIVITY_CLEAR_TASK`),
     * with [target]'s action and extras, so [MainIntents.destinationOf] reads the same destination
     * back from it.
     */
    internal fun restartIntentOf(target: Intent): Intent =
        Intent.makeRestartActivityTask(target.component).apply {
            action = target.action
            target.extras?.let { putExtras(it) }
        }
}

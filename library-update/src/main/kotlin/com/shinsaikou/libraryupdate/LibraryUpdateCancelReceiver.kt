package com.shinsaikou.libraryupdate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import java.util.UUID

/**
 * Behind the cancel action of the progress notification. The action belongs to one run, so it
 * carries that run's id ([EXTRA_RUN_ID]) and cancels that request only. It used to cancel every
 * request with [LibraryUpdateWorker.TAG], and the periodic request carries that tag too, so
 * cancelling a manual run also removed the automatic schedule until the next start-up. A cancelled
 * automatic run re-arms itself, in the worker's `finally` block.
 */
class LibraryUpdateCancelReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val runId = intent.getStringExtra(EXTRA_RUN_ID)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return
        WorkManager.getInstance(context).cancelWorkById(runId)
    }

    companion object {
        /** The id of the `WorkRequest` whose notification the action is on, as a string. */
        const val EXTRA_RUN_ID = "com.shinsaikou.libraryupdate.extra.RUN_ID"
    }
}

package com.shinsaikou.backup

import androidx.work.WorkInfo
import java.util.UUID

/**
 * Where a manual export or a restore is, for the Backup screen: waiting or working ([ACTIVE]), or
 * how it ended.
 *
 * Queued counts as [ACTIVE] with running: the owner has asked for the run, and a screen that shows
 * nothing until WorkManager starts it looks like one that ignored the tap. A restore waiting out
 * its retry backoff is [ACTIVE] too, since it comes back on its own.
 */
enum class BackupRunState {
    ACTIVE,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

/**
 * The newest run of one unique work name: which run it is ([id]) and where it is ([state]). It is
 * read from WorkManager rather than kept by the screen, so a run started before the process died
 * still shows as one. The id tells a new run from the one before it: WorkManager can report a fast
 * run only once it has ended, in the same state the last one ended in.
 */
data class BackupRun(val id: UUID, val state: BackupRunState) {
    companion object {
        /**
         * The run among [infos], the WorkInfos of one unique work name, or null when WorkManager
         * keeps none. Queueing under the name deletes the finished runs before it, so there is one;
         * should there be more, the unfinished one wins.
         */
        fun of(infos: List<WorkInfo>): BackupRun? {
            val info = infos.firstOrNull { !it.state.isFinished } ?: infos.firstOrNull() ?: return null
            val state = when (info.state) {
                WorkInfo.State.SUCCEEDED -> BackupRunState.SUCCEEDED
                WorkInfo.State.FAILED -> BackupRunState.FAILED
                WorkInfo.State.CANCELLED -> BackupRunState.CANCELLED
                WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> BackupRunState.ACTIVE
            }
            return BackupRun(info.id, state)
        }
    }
}

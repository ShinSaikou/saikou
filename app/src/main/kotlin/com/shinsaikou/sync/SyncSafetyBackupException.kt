package com.shinsaikou.sync

import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.domain.error.KeyedFailure
import java.io.IOException

/**
 * The backup written before a backend's first pull failed (plan unit G), carrying what the export
 * threw as its cause. The sync stops before anything is pulled, and Sync settings shows its own key,
 * so the owner is told to check the backup folder rather than the server.
 */
class SyncSafetyBackupException(cause: Throwable) :
    IOException("The backup before the first sync could not be written", cause), KeyedFailure {
    override val errorKey: ErrorKey get() = ErrorKey.SYNC_SAFETY_BACKUP_FAILED
}

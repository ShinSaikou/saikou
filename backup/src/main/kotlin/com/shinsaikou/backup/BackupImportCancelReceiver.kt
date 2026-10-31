package com.shinsaikou.backup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import com.shinsaikou.backup.importer.BackupImportWorker

/**
 * Behind the cancel action of the restore progress notification. A receiver cannot be injected, so
 * it talks to WorkManager directly. The checkpoint survives the cancel, so the next tap resumes
 * rather than starting over.
 */
class BackupImportCancelReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        WorkManager.getInstance(context).cancelUniqueWork(BackupImportWorker.WORK_NAME)
    }
}

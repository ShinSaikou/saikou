package com.shinsaikou.backup

import android.app.PendingIntent
import android.net.Uri

/**
 * The activities a backup notification can open. `:app` owns every activity, and `:backup` may not
 * depend on it, so the intents arrive through this interface. Same pattern as `NetworkUserMessages`
 * and `LibraryUpdateIntents`. A null return means the action is left off the notification.
 */
interface BackupIntents {

    /** The import report screen, showing the counts and the errors of the last run. */
    fun openImportReport(): PendingIntent?

    /** A share sheet for a file that was just exported. */
    fun shareBackup(uri: Uri): PendingIntent?
}

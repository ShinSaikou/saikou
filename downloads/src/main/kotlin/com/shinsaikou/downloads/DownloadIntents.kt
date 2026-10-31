package com.shinsaikou.downloads

import android.app.PendingIntent

/**
 * The activity a download notification opens, and the broadcasts its actions send. `:app` owns every
 * activity and the receiver, and `:downloads` may not depend on it, so the intent arrives through
 * this interface, the same pattern as `BackupIntents` and `LibraryUpdateIntents`. A null return
 * leaves the notification without that content intent or action.
 */
interface DownloadIntents {

    /** The download queue screen, where the rows in flight and the failed ones are listed. */
    fun openQueue(): PendingIntent?

    /** The download settings, where the summary of the last adoption of upstream's downloads is shown. */
    fun openDownloadSettings(): PendingIntent?

    /** A broadcast that pauses the queue, as the queue screen's Pause does. */
    fun pauseDownloads(): PendingIntent?

    /** A broadcast that empties the queue, as the queue screen's Clear queue does. */
    fun cancelDownloads(): PendingIntent?
}

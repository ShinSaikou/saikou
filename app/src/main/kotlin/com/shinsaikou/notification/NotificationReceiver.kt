package com.shinsaikou.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.shinsaikou.SaikouApp
import com.shinsaikou.di.AppGraph
import com.shinsaikou.downloads.DownloadQueue
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * The notification actions that do work instead of opening a screen. Manifest-registered and not
 * exported; every open action is a plain activity `PendingIntent` and does not come through here.
 *
 * The graph is read off the application rather than injected, per contract 08 decision D1: no
 * `MetroAppComponentFactory`, so a receiver reaches the graph the same way an activity does.
 */
class NotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val graph = (context.applicationContext as SaikouApp).graph
        when (intent.action) {
            ACTION_MARK_AS_READ -> {
                val chapterIds = intent.getLongArrayExtra(EXTRA_CHAPTER_IDS) ?: return
                val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
                dismiss(context, notificationId)
                val pending = goAsync()
                graph.appScope.launch(graph.dispatchers.io) {
                    try {
                        val chapters = chapterIds.toList().mapNotNull { graph.getChapter.await(it) }
                        if (chapters.isNotEmpty()) {
                            graph.setReadStatus.await(true, *chapters.toTypedArray())
                        }
                    } catch (e: Exception) {
                        logcat(LogPriority.ERROR, e) { "Could not mark the chapters read" }
                    } finally {
                        pending.finish()
                    }
                }
            }

            ACTION_DISMISS -> dismiss(context, intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1))

            // The download progress notification's actions: what the queue screen's Pause and
            // Clear queue do.
            ACTION_PAUSE_DOWNLOADS -> onDownloadQueue(graph) { pause() }
            ACTION_CANCEL_DOWNLOADS -> onDownloadQueue(graph) { clear() }
        }
    }

    private fun onDownloadQueue(graph: AppGraph, action: suspend DownloadQueue.() -> Unit) {
        val pending = goAsync()
        graph.appScope.launch(graph.dispatchers.io) {
            try {
                graph.downloadQueue.action()
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "The download notification's action failed" }
            } finally {
                pending.finish()
            }
        }
    }

    private fun dismiss(context: Context, notificationId: Int) {
        if (notificationId >= 0) NotificationManagerCompat.from(context).cancel(notificationId)
    }

    companion object {
        const val ACTION_MARK_AS_READ = "com.shinsaikou.NotificationReceiver.MARK_AS_READ"
        const val ACTION_DISMISS = "com.shinsaikou.NotificationReceiver.DISMISS"
        const val ACTION_PAUSE_DOWNLOADS = "com.shinsaikou.NotificationReceiver.PAUSE_DOWNLOADS"
        const val ACTION_CANCEL_DOWNLOADS = "com.shinsaikou.NotificationReceiver.CANCEL_DOWNLOADS"

        const val EXTRA_CHAPTER_IDS = "com.shinsaikou.extra.CHAPTER_IDS"
        const val EXTRA_NOTIFICATION_ID = "com.shinsaikou.extra.NOTIFICATION_ID"

        fun pauseDownloads(context: Context): Intent =
            Intent(context, NotificationReceiver::class.java).setAction(ACTION_PAUSE_DOWNLOADS)

        fun cancelDownloads(context: Context): Intent =
            Intent(context, NotificationReceiver::class.java).setAction(ACTION_CANCEL_DOWNLOADS)

        fun markAsRead(context: Context, chapterIds: List<Long>, notificationId: Int): Intent =
            Intent(context, NotificationReceiver::class.java).apply {
                action = ACTION_MARK_AS_READ
                putExtra(EXTRA_CHAPTER_IDS, chapterIds.toLongArray())
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            }
    }
}

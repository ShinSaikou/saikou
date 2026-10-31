package com.shinsaikou.appupdate

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.shinsaikou.SaikouApp
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Where [AppUpdateInstaller]'s session reports, manifest-registered and not exported: only the
 * session's own `PendingIntent` reaches it.
 *
 * - **Pending user action:** Android's confirmation. It opens at once while the app is on screen; a
 *   background app may not start an activity, so otherwise it waits behind a "ready to install"
 *   notification.
 * - **Success:** nothing to say; Android replaces the process with the new version.
 * - **Aborted:** the owner said no to Android's prompt, which needs no notification either.
 * - **Any other failure:** a notification with the reason.
 *
 * The graph is read off the application, as `NotificationReceiver` reads it (contract 08 D1).
 */
class AppUpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STATUS) return
        val notifier = (context.applicationContext as SaikouApp).graph.appUpdateNotifier
        val version = intent.getStringExtra(EXTRA_VERSION).orEmpty()
        val session = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1).takeIf { it >= 0 }
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                when {
                    confirm == null -> notifier.showInstallFailed(version, PackageInstaller.STATUS_FAILURE, session)
                    appOnScreen() && start(context, confirm) -> notifier.cancelStatus(session)
                    else -> notifier.showReadyToInstall(version, confirm, session)
                }
            }
            PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_FAILURE_ABORTED -> notifier.cancelStatus(session)
            else -> {
                logcat(LogPriority.WARN) {
                    "The update to $version was not installed: status $status, " +
                        intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                }
                notifier.showInstallFailed(version, status, session)
            }
        }
    }

    private fun appOnScreen(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    private fun start(context: Context, confirm: Intent): Boolean = try {
        context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        logcat(LogPriority.WARN, e) { "Android's install confirmation could not be opened" }
        false
    }

    companion object {
        const val ACTION_STATUS = "com.shinsaikou.appupdate.INSTALL_STATUS"
        const val EXTRA_VERSION = "com.shinsaikou.extra.UPDATE_VERSION"
    }
}

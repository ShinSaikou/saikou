package com.shinsaikou.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.shinsaikou.backup.BackupIntents
import com.shinsaikou.ui.main.MainDestination
import com.shinsaikou.ui.main.MainIntents
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * The two places a backup notification can send the owner. `:backup` owns the notifications and may
 * not depend on `:app`, so the intents arrive through this interface.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class AppBackupIntents(private val context: Context) : BackupIntents {

    override fun openImportReport(): PendingIntent? = PendingIntent.getActivity(
        context,
        REQUEST_IMPORT_REPORT,
        MainIntents.newIntent(context, MainDestination.IMPORT_REPORT),
        FLAGS,
    )

    override fun shareBackup(uri: Uri): PendingIntent? {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = BACKUP_MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_SHARE,
            Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            FLAGS,
        )
    }

    private companion object {
        const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        const val BACKUP_MIME_TYPE = "application/octet-stream"

        const val REQUEST_IMPORT_REPORT = 11
        const val REQUEST_SHARE = 12
    }
}

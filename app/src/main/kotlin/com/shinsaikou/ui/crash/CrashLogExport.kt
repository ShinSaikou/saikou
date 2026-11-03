package com.shinsaikou.ui.crash

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import androidx.core.content.FileProvider
import com.shinsaikou.i18n.R
import java.io.File

/**
 * Hands one crash file to whatever the device can send it with.
 *
 * The intent is the shape `AppPageImageActions.share` already uses, minus `FLAG_ACTIVITY_NEW_TASK`,
 * which a share started from an activity does not need. `provider_paths.xml` already exposes the
 * crash directory, so nothing here needs a manifest or resource change.
 *
 * The crash screen shares through the same call with [CrashLogProvider]'s [authority], so the file
 * is served from its own process rather than from the main one. More > About shares its bug report
 * through it too, from the cache's `share/` directory, with a [subject] of its own.
 */
object CrashLogExport {

    private const val MIME_TYPE = "text/plain"

    /** False when the file is gone or nothing on the device can take an `ACTION_SEND`. */
    fun share(
        activity: Activity,
        file: File,
        authority: String = "${activity.packageName}.provider",
        subject: String = activity.getString(R.string.crash_share_subject),
    ): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(activity, authority, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri(null, uri)
        }
        activity.startActivity(Intent.createChooser(send, null))
        true
    }.getOrElse { false }
}

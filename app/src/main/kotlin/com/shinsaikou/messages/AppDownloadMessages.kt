package com.shinsaikou.messages

import android.content.Context
import com.shinsaikou.downloads.DownloadMessages
import com.shinsaikou.i18n.R
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/** The download engine's text, resolved against `strings_engines.xml`. */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class AppDownloadMessages(private val context: Context) : DownloadMessages {

    override fun progressChannelName(): String = context.getString(R.string.channel_download_progress)

    override fun errorChannelName(): String = context.getString(R.string.channel_download_errors)

    override fun downloaderTitle(): String = context.getString(R.string.notification_downloader_title)

    override fun chapterAndLeft(chapterName: String, left: Int): String =
        context.resources.getQuantityString(R.plurals.notification_download_chapter_left, left, chapterName, left)

    override fun chaptersLeft(left: Int): String =
        context.resources.getQuantityString(R.plurals.notification_download_left, left, left)

    override fun chaptersRunning(count: Int): String =
        context.resources.getQuantityString(R.plurals.notification_download_running, count, count)

    override fun pauseAction(): String = context.getString(R.string.notification_download_pause)

    override fun cancelAllAction(): String = context.getString(R.string.notification_download_cancel_all)

    override fun downloadErrors(count: Int): String =
        context.resources.getQuantityString(R.plurals.notification_download_errors, count, count)

    override fun storageFull(): String = context.getString(R.string.notification_download_storage_full)

    override fun storageFullDetail(): String = context.getString(R.string.notification_download_storage_full_detail)

    override fun adoptionTitle(): String = context.getString(R.string.notification_adoption_title)

    override fun adoptionFinished(copied: Int): String =
        context.resources.getQuantityString(R.plurals.notification_adoption_finished, copied, copied)

    override fun adoptionFinishedDetail(): String = context.getString(R.string.notification_adoption_finished_detail)

    override fun exportTitle(): String = context.getString(R.string.notification_export_title)

    override fun exportFinished(copied: Int, skipped: Int, failed: Int): String =
        context.getString(R.string.notification_export_finished_format, copied, skipped, failed)

    override fun exportFinishedDetail(): String = context.getString(R.string.notification_export_finished_detail)

    override fun exportUnreachable(): String = context.getString(R.string.notification_export_unreachable)

    override fun exportStopped(): String = context.getString(R.string.notification_export_stopped)

    override fun exportProblemDetail(): String = context.getString(R.string.notification_export_problem_detail)
}

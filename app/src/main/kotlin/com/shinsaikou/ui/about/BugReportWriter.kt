package com.shinsaikou.ui.about

import android.content.Context
import android.os.Build
import android.os.Process
import com.shinsaikou.BuildConfig
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.core.common.crash.CrashLog
import com.shinsaikou.i18n.R
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * The Android half of the bug report: this build and this device ([deviceInfo]), the app's own
 * log read with `logcat`, which an app may read for its own process without any permission, and
 * the newest of [CrashLog]'s files. [write] puts the report in the cache's `share/` directory,
 * which `provider_paths.xml` already exposes to the app's FileProvider, and keeps only the newest
 * report there.
 */
@Inject
class BugReportWriter(
    private val context: Context,
    private val crashLog: CrashLog,
    private val dispatchers: Dispatchers,
) {

    fun deviceInfo(): DeviceInfo = DeviceInfo(
        appName = context.getString(R.string.app_name),
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        buildType = BuildConfig.BUILD_TYPE,
        debuggable = BuildConfig.DEBUG,
        commit = BuildConfig.COMMIT_SHA,
        packageName = context.packageName,
        androidRelease = Build.VERSION.RELEASE,
        sdk = Build.VERSION.SDK_INT,
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        abis = Build.SUPPORTED_ABIS.toList(),
    )

    /** The report's file. Throws [IOException] when it cannot be written. */
    suspend fun write(): File = withContext(dispatchers.io) {
        val crash = crashLog.latest()?.let { file ->
            runCatching { CrashFile(file.name, file.readText()) }.getOrNull()
        }
        val text = BugReport.text(
            info = deviceInfo(),
            writtenAt = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ZonedDateTime.now()),
            crash = crash,
            log = readLog(),
        )
        val directory = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
        directory.listFiles { file -> file.name.startsWith(PREFIX) }?.forEach { it.delete() }
        File(directory, "$PREFIX${System.currentTimeMillis()}.txt").apply { writeText(text) }
    }

    /**
     * This process's last [BugReport.LOG_LINES] lines, or null when `logcat` could not be run. `-t`
     * dumps and exits, so the read ends; the wait after it only reaps the process.
     */
    private fun readLog(): String? = try {
        val process = ProcessBuilder(
            "logcat", "-d", "-v", "threadtime", "-t", BugReport.LOG_LINES.toString(), "--pid=${Process.myPid()}",
        ).redirectErrorStream(true).start()
        try {
            process.inputStream.bufferedReader().use { it.readText() }
        } finally {
            if (!process.waitFor(LOGCAT_WAIT_SECONDS, TimeUnit.SECONDS)) process.destroy()
        }
    } catch (e: IOException) {
        logcat(LogPriority.WARN, e) { "The bug report could not read the log" }
        null
    }

    private companion object {
        const val DIRECTORY = "share"
        const val PREFIX = "bug-report-"
        const val LOGCAT_WAIT_SECONDS = 5L
    }
}

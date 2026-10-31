package com.shinsaikou.appupdate

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Trace
import androidx.core.content.pm.PackageInfoCompat
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.extensions.loader.ExtensionSignatures
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Android half of installing an update: may this app ask to install packages, is a downloaded
 * APK this app signed with this app's key ([ArchiveRules]), and the `PackageInstaller` session that
 * installs it. Every `PackageManager` call runs on the IO dispatcher.
 *
 * The session's status goes to [AppUpdateInstallReceiver]. Android always asks the owner to
 * confirm, except on Android 12 and up when this app is already the installer of record of itself
 * (an earlier update installed by this path), where [PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED]
 * lets the update go through without the prompt.
 */
@SingleIn(AppScope::class)
@Inject
class AppUpdateInstaller(
    private val context: Context,
    private val dispatchers: Dispatchers,
) {

    /**
     * Held from the cleanup of earlier sessions through the commit, so two download runs (two
     * versions) never abandon each other's session mid-write or commit two at once. This class is
     * a singleton, so the lock covers the process.
     */
    private val sessionLock = Mutex()

    /** Whether the owner allowed this app to install apps ("Install unknown apps"). */
    suspend fun canRequestInstalls(): Boolean = withContext(dispatchers.io) {
        context.packageManager.canRequestPackageInstalls()
    }

    /** Null when [apk] may be installed over this app, else why not. */
    suspend fun check(apk: File): UpdateRefusal? = withContext(dispatchers.io) {
        val archive = archivePackage(apk)?.facts()
        ArchiveRules.judge(archive = archive, installed = installedPackage().facts())
    }

    /**
     * Writes [apk] into a new session and commits it; the status, with Android's confirmation when it
     * wants one, arrives at [AppUpdateInstallReceiver] tagged with [version]. A failure before the
     * commit abandons the session and throws.
     */
    suspend fun install(apk: File, version: String) = sessionLock.withLock {
        withContext(dispatchers.io) {
            val installer = context.packageManager.packageInstaller
            // One install at a time: a session an earlier attempt left open, still waiting for
            // Android's confirmation or orphaned when its process died, is abandoned first. Its
            // status arrives as aborted and clears only its own notification.
            installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(apk.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
            val sessionId = installer.createSession(params)
            try {
                installer.openSession(sessionId).use { session ->
                    apk.inputStream().use { input ->
                        session.openWrite(SESSION_FILE, 0, apk.length()).use { output ->
                            input.copyTo(output)
                            session.fsync(output)
                        }
                    }
                    session.commit(statusReceiver(version).intentSender)
                }
            } catch (e: Exception) {
                runCatching { installer.abandonSession(sessionId) }
                throw e
            }
        }
    }

    /**
     * Explicit, so mutable is safe, and mutable because the installer adds the status extras to it
     * (required from Android 12, where an intent is immutable unless it says otherwise).
     */
    private fun statusReceiver(version: String): PendingIntent {
        val intent = Intent(context, AppUpdateInstallReceiver::class.java)
            .setAction(AppUpdateInstallReceiver.ACTION_STATUS)
            .setPackage(context.packageName)
            .putExtra(AppUpdateInstallReceiver.EXTRA_VERSION, version)
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(
            context,
            AppUpdateNotifier.REQUEST_INSTALL_STATUS,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutable,
        )
    }

    @Suppress("DEPRECATION")
    private fun archivePackage(apk: File): PackageInfo? =
        context.packageManager.getPackageArchiveInfo(apk.absolutePath, SIGNER_FLAGS)

    /** Inside a trace section of its name, as every `getPackageInfo` call here is (ruling R60). */
    private fun installedPackage(): PackageInfo {
        Trace.beginSection("PackageManager.getPackageInfo")
        try {
            return context.packageManager.getPackageInfo(context.packageName, SIGNER_FLAGS)
        } finally {
            Trace.endSection()
        }
    }

    /**
     * The signer set the extension loader compares, so the two agree on what a signer is: the
     * certificate history of a single signer, the current set of several.
     */
    private fun PackageInfo.facts(): PackageFacts = PackageFacts(
        packageName = packageName,
        versionCode = PackageInfoCompat.getLongVersionCode(this),
        signers = ExtensionSignatures.fingerprints(this).orEmpty().toSet(),
    )

    private companion object {
        const val SESSION_FILE = "base.apk"

        @Suppress("DEPRECATION")
        val SIGNER_FLAGS: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
    }
}

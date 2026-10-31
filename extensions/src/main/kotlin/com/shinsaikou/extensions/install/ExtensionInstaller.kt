package com.shinsaikou.extensions.install

import android.content.Context
import com.shinsaikou.extensions.loader.ExtensionLoader
import com.shinsaikou.extensions.loader.InstallOutcome
import com.shinsaikou.extensions.loader.InstallRejection
import com.shinsaikou.extensions.model.InstallFailedException
import com.shinsaikou.extensions.repo.ExtensionRepoClient
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.extension.model.InstallStep
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import logcat.logcat
import java.io.File

/**
 * Downloads an APK and hands it to the loader. No `Service`, no `PackageInstaller`, no
 * notification: the download is a coroutine owned by the extension manager's scope, so it survives
 * a rotation and the browse screen just watches the steps.
 */
@SingleIn(AppScope::class)
@Inject
class ExtensionInstaller(
    private val context: Context,
    private val loader: ExtensionLoader,
    private val repoClient: ExtensionRepoClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Emits every step and completes after [InstallStep.Installed], or fails with
     * [InstallFailedException] carrying the rejection, or with the download's own failure, which
     * says more (no network, an HTTP status) than any rejection could. The temp file is always
     * removed.
     */
    fun downloadAndInstall(packageName: String, apkUrl: String): Flow<InstallStep> = flow {
        emit(InstallStep.Pending)
        val temp = File(File(context.cacheDir, DOWNLOAD_DIR), "$packageName.apk.part")
        temp.parentFile?.mkdirs()
        temp.delete()
        try {
            emit(InstallStep.Downloading)
            // InstallStep carries no byte count, so progress only reaches the log; the browse
            // screen names the step over an indeterminate ring (ruling R54). No test reads this
            // line back; it is for a person reading the log.
            var downloaded = 0L
            repoClient.downloadApk(apkUrl, temp) { bytes, _ -> downloaded = bytes }.getOrThrow()
            this@ExtensionInstaller.logcat { "Downloaded $downloaded bytes for $packageName" }

            emit(InstallStep.Installing)
            val archive = loader.readArchive(temp) ?: throw InstallFailedException(InstallRejection.NOT_AN_EXTENSION)
            // Trust is decided at load time, but an unsigned APK never reaches the .ext directory.
            if (archive.signerFingerprints.isEmpty()) throw InstallFailedException(InstallRejection.UNSIGNED)

            when (val outcome = loader.installPrivateExtensionFile(temp)) {
                is InstallOutcome.Installed -> emit(InstallStep.Installed)
                is InstallOutcome.Rejected -> throw InstallFailedException(outcome.reason)
            }
        } finally {
            temp.delete()
        }
    }.flowOn(ioDispatcher)

    private companion object {
        const val DOWNLOAD_DIR = "ext-downloads"
    }
}

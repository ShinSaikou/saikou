package com.shinsaikou.downloads.export

import com.hippo.unifile.UniFile
import com.shinsaikou.domain.storage.FolderCopy
import com.shinsaikou.downloads.DownloadRoot
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.download.DownloadProvider
import java.io.File

/**
 * Export downloads (F4): every downloaded chapter copied into [target], the storage location's
 * `downloads` folder, in the layout the downloads already have in app storage, upstream's
 * `<source>/<manga>/<chapter folder or .cbz>` ([DownloadRoot]). That is the layout **Copy downloads
 * from a folder** reads, on this phone or another, so the export needs nothing of its own there.
 *
 * A copy, never a move: the downloads stay where they are. A file already in the target with the
 * same size is skipped, so a second export, or a run WorkManager starts again after the process
 * died, copies only what is new or was cut short. A chapter still downloading (its `_tmp` folder or
 * `.cbz_tmp` file) is not a download yet and is left out.
 */
@Inject
@SingleIn(AppScope::class)
class DownloadExport(private val downloadRoot: DownloadRoot) {

    suspend fun run(target: UniFile, onProgress: suspend (FolderCopy.Progress) -> Unit = {}): FolderCopy.Result =
        FolderCopy.copy(downloadRoot.root(), target, move = false, include = ::isExported, onProgress = onProgress)

    companion object {
        fun isExported(file: File): Boolean = !file.name.endsWith(DownloadProvider.TMP_DIR_SUFFIX)
    }
}

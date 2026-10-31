package com.shinsaikou.downloads

import android.content.Context
import com.shinsaikou.domain.preferences.LibraryPreferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.util.storage.DiskUtil
import java.io.File

/**
 * `<filesDir>/downloads/<source>/<manga>/<chapter>`, upstream's layout under the app's internal files
 * directory (phase 2 decision Q4(b)). Folder names come from [DownloadProvider] under the owner's
 * current [LibraryPreferences.disallowNonAsciiFilenames].
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class FilesDirDownloadRoot(
    private val context: Context,
    private val libraryPreferences: LibraryPreferences,
) : DownloadRoot {

    override fun root(): File = File(context.filesDir, DOWNLOADS_DIR)

    override fun sourceDir(sourceName: String): File =
        sourceDirIn(root(), sourceName, libraryPreferences.disallowNonAsciiFilenames.get())

    override fun mangaDir(sourceName: String, mangaTitle: String): File =
        mangaDirIn(root(), sourceName, mangaTitle, libraryPreferences.disallowNonAsciiFilenames.get())

    /** Read off [Context.getFilesDir], which always exists, where the root may not yet. */
    override fun availableBytes(): Long = DiskUtil.getAvailableStorageSpace(context.filesDir)

    companion object {
        const val DOWNLOADS_DIR = "downloads"
    }
}

internal fun sourceDirIn(root: File, sourceName: String, disallowNonAscii: Boolean): File =
    File(root, DownloadProvider.getSourceDirName(sourceName, disallowNonAscii))

internal fun mangaDirIn(root: File, sourceName: String, mangaTitle: String, disallowNonAscii: Boolean): File = File(
    sourceDirIn(root, sourceName, disallowNonAscii),
    DownloadProvider.getMangaDirName(mangaTitle, disallowNonAscii),
)

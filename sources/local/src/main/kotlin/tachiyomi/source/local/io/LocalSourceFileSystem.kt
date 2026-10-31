// Vendored from upstream v0.20.4: source-local/src/main/kotlin/tachiyomi/source/local/io/LocalSourceFileSystem.kt (modified: yes)
// Modifications: upstream's StorageManager is replaced by LocalSourcePreferences, because Saikou has no
// StorageManager and contract 06 decision F6 puts the root behind the app-state key
// storage_local_dir. An empty value means the app-specific external directory, created on demand;
// a set value is a persisted tree URI. Nothing is cached, as in upstream: every call is a live listing.
// Unit storage-location: upstream's storage location comes first again, as upstream's StorageManager has
// it, through StorageLocation's `local` folder; an unreachable one falls through to the key and the
// app folder. readingFrom names the folder for the empty listing.
package tachiyomi.source.local.io

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import com.shinsaikou.domain.storage.StorageLocation
import com.shinsaikou.sources.local.LocalSourcePreferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File

@SingleIn(AppScope::class)
@Inject
class LocalSourceFileSystem(
    private val context: Context,
    private val preferences: LocalSourcePreferences,
    private val storageLocation: StorageLocation,
) {

    fun getBaseDirectory(): UniFile? {
        storageLocation.folder(StorageLocation.LOCAL)?.let { return it }
        val configured = preferences.localSourceDirectory.get()
        return if (configured.isBlank()) {
            defaultDirectory()
        } else {
            UniFile.fromUri(context, Uri.parse(configured))
        }
    }

    /**
     * The folder [getBaseDirectory] reads, as the owner would find it: `<storage location>/local`,
     * the older picked folder's name, or the app folder from `Android/` on.
     */
    fun readingFrom(): String {
        if (storageLocation.folder(StorageLocation.LOCAL) != null) {
            return "${storageLocation.displayName()}/${StorageLocation.LOCAL}"
        }
        val configured = preferences.localSourceDirectory.get()
        if (configured.isNotBlank()) return StorageLocation.nameOf(configured)
        return shownPath(defaultDirectoryFile())
    }

    /** The app's own Local folder, whatever is set: where the files lived before a storage location. */
    fun defaultDirectoryFile(): File = File(context.getExternalFilesDir(null), DEFAULT_DIRECTORY_NAME)

    fun getFilesInBaseDirectory(): List<UniFile> {
        return getBaseDirectory()?.listFiles().orEmpty().toList()
    }

    fun getMangaDirectory(name: String): UniFile? {
        return getBaseDirectory()
            ?.findFile(name)
            ?.takeIf { it.isDirectory }
    }

    fun getFilesInMangaDirectory(name: String): List<UniFile> {
        return getMangaDirectory(name)?.listFiles().orEmpty().toList()
    }

    private fun defaultDirectory(): UniFile? {
        val directory = defaultDirectoryFile()
        if (!directory.exists() && !directory.mkdirs()) {
            logcat(LogPriority.WARN) { "Could not create the default local source directory" }
            return null
        }
        return UniFile.fromFile(directory)
    }

    companion object {
        private const val DEFAULT_DIRECTORY_NAME = "local"

        /** `/storage/emulated/0/Android/data/<id>/files/local` is `Android/data/<id>/files/local`. */
        fun shownPath(file: File): String {
            val path = file.path.replace(File.separatorChar, '/')
            val android = path.indexOf("/Android/")
            return if (android >= 0) path.substring(android + 1) else path
        }
    }
}

// Vendored from upstream v0.20.4: source-local/src/main/kotlin/tachiyomi/source/local/image/LocalCoverManager.kt (modified: yes)
// Modifications: update() takes the manga's folder name instead of a mutable SManga and no longer
// writes a thumbnail url back into a model, because MangaInfo is immutable. The caller reads the
// returned file's uri. Injekt is replaced by constructor injection.
package tachiyomi.source.local.image

import android.content.Context
import com.hippo.unifile.UniFile
import com.shinsaikou.image.ImageUtil
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.util.storage.DiskUtil
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.source.local.io.LocalSourceFileSystem
import java.io.InputStream

private const val DEFAULT_COVER_NAME = "cover.jpg"

@SingleIn(AppScope::class)
@Inject
class LocalCoverManager(
    private val context: Context,
    private val fileSystem: LocalSourceFileSystem,
) {

    fun find(mangaUrl: String): UniFile? {
        return fileSystem.getFilesInMangaDirectory(mangaUrl)
            // Get all file whose names start with "cover"
            .filter { it.isFile && it.nameWithoutExtension.equals("cover", ignoreCase = true) }
            // Get the first actual image
            .firstOrNull { ImageUtil.isImage(it.name) { it.openInputStream() } }
    }

    fun update(
        mangaUrl: String,
        inputStream: InputStream,
    ): UniFile? {
        val directory = fileSystem.getMangaDirectory(mangaUrl)
        if (directory == null) {
            inputStream.close()
            return null
        }

        val targetFile = find(mangaUrl) ?: directory.createFile(DEFAULT_COVER_NAME)!!

        inputStream.use { input ->
            targetFile.openOutputStream().use { output ->
                input.copyTo(output)
            }
        }

        DiskUtil.createNoMediaFile(directory, context)

        return targetFile
    }
}

package com.shinsaikou.ui.manga

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.manga.interactor.UpdateManga
import com.shinsaikou.i18n.R
import com.shinsaikou.reader.SHARE_DIRECTORY
import com.shinsaikou.reader.SavedPictures
import com.shinsaikou.reader.shareChooser
import com.shinsaikou.sources.local.isLocal
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.coroutines.withContext
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.image.LocalCoverManager
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * The entry's cover viewer actions (phase 5 unit ED), upstream's `MangaCoverViewModel` over the
 * calls Saikou already has: a picked image becomes the custom cover through
 * `CoverCache.setCustomCoverToCache` (a Local entry's goes into its folder through
 * [LocalCoverManager], as `AppPageImageActions.setAsCover` does), delete removes the custom file,
 * and both bump `coverLastModified` so every cover key changes and the new image shows. A custom
 * cover is not synced; a backup carries it (R165 item 3, `CoverCacheCustomCoverFiles`).
 *
 * Save and share write the cover as Coil decodes it, a JPEG at full quality, as upstream's
 * `Image.Cover` does, so the bytes are the same whichever of the custom file, the Local folder, the
 * cover cache or the network the fetcher read.
 */
@SingleIn(AppScope::class)
@Inject
class MangaCoverActions(
    private val context: Context,
    private val coverCache: CoverCache,
    private val coverManager: LocalCoverManager,
    private val updateManga: UpdateManga,
    private val imageLoader: ImageLoader,
    private val savedPictures: SavedPictures,
    private val dispatchers: Dispatchers,
) {

    /**
     * Whether the cover can be changed at all: a Local entry's lives in its folder, and a library
     * entry's custom cover is a file keyed on its id. An entry only being browsed has neither, as
     * `setAsCover` refuses it.
     */
    fun canEdit(manga: Manga): Boolean = manga.isLocal() || manga.favorite

    /** Whether the entry has a custom cover file, which delete would remove. */
    suspend fun customCoverExists(mangaId: Long): Boolean =
        withContext(dispatchers.io) { coverCache.getCustomCoverFile(mangaId).exists() }

    /** The image at [uri], from the system picker, as the entry's cover. */
    suspend fun setCustomCover(manga: Manga, uri: Uri): Result<Unit> = withContext(dispatchers.io) {
        runCatching {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("No image at $uri")
            when {
                manga.isLocal() -> input.use { coverManager.update(manga.url, it) }
                    ?: throw IOException(context.getString(R.string.error_no_storage_location))
                manga.favorite -> input.use { coverCache.setCustomCoverToCache(manga, it) }
                else -> {
                    input.close()
                    throw IOException(context.getString(R.string.error_manga_not_in_library))
                }
            }
            updateManga.awaitUpdateCoverLastModified(manga.id)
            Unit
        }
    }

    /** Removes the custom cover, so the source's shows again. */
    suspend fun deleteCustomCover(manga: Manga): Result<Unit> = withContext(dispatchers.io) {
        runCatching {
            coverCache.deleteCustomCover(manga.id)
            updateManga.awaitUpdateCoverLastModified(manga.id)
            Unit
        }
    }

    /**
     * Writes the cover where saved pages go (see [SavedPictures]), named for the entry and never in
     * the entry's sub-folder, as upstream's cover goes straight into its own Pictures folder.
     */
    suspend fun save(manga: Manga): Result<Uri> = withContext(dispatchers.io) {
        runCatching {
            val bitmap = decode(manga)
            savedPictures.save(fileName(manga), MIME_TYPE, subFolder = null) { bitmap.writeTo(it) }
        }
    }

    /** Writes the cover to the shareable cache folder and opens the system share sheet on it. */
    suspend fun share(manga: Manga): Result<Uri> = withContext(dispatchers.io) {
        runCatching {
            val bitmap = decode(manga)
            val directory = File(context.cacheDir, SHARE_DIRECTORY).apply { mkdirs() }
            val target = File(directory, fileName(manga))
            target.outputStream().use { bitmap.writeTo(it) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", target)
            context.startActivity(shareChooser(uri, MIME_TYPE))
            uri
        }
    }

    private suspend fun decode(manga: Manga): Bitmap {
        val request = ImageRequest.Builder(context)
            .data(manga)
            .size(Size.ORIGINAL)
            // A hardware bitmap cannot be compressed back to bytes.
            .allowHardware(false)
            .build()
        val result = imageLoader.execute(request) as? SuccessResult
            ?: throw IOException("The cover could not be loaded")
        return result.image.toBitmap()
    }

    private fun Bitmap.writeTo(output: OutputStream) {
        if (!compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
            throw IOException("The cover could not be encoded")
        }
    }

    private fun fileName(manga: Manga): String = savedCoverFileName(manga.title)

    private companion object {
        const val MIME_TYPE = "image/jpeg"
        const val JPEG_QUALITY = 100
    }
}

/** Upstream's cover name: the entry's title as a file name, a JPEG. The source's title, as a saved page's. */
internal fun savedCoverFileName(mangaTitle: String): String = "${DiskUtil.buildValidFilename(mangaTitle)}.jpg"

package com.shinsaikou.reader

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.core.content.contentValuesOf
import com.hippo.unifile.UniFile
import com.shinsaikou.domain.preferences.StoragePreferences
import com.shinsaikou.i18n.R
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.util.storage.DiskUtil
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * Where a saved picture goes, a reader page or an entry's cover (unit saved-pages-gallery, F6).
 * Upstream's `ImageSaver` in Saikou's terms, one of three places, [savedPictureDestination] choosing:
 *
 * 1. The export tree, when the owner has picked one. Nothing in the app sets it today; a value
 *    written before is still honoured.
 * 2. On API 29 and later, the gallery's `Pictures/Saikou` through MediaStore, as upstream uses its
 *    own folder under Pictures, in [subFolder][save] when there is one. MediaStore needs no permission for
 *    the app's own rows, and they stay when the app is uninstalled.
 * 3. On API 26 to 28, the app's own external pictures folder. Upstream writes the public folder there
 *    under `WRITE_EXTERNAL_STORAGE`, which Saikou does not hold by decision, so this is a named
 *    divergence confined to those three versions.
 */
@SingleIn(AppScope::class)
@Inject
class SavedPictures(
    private val context: Context,
    private val storagePreferences: StoragePreferences,
) {

    /**
     * Writes one picture named [displayName] of [mimeType] through [write], inside [subFolder] when it
     * is not null, and returns where it went. [subFolder] is a single valid file name; the callers
     * build it with `DiskUtil.buildValidFilename`. Called off the main thread.
     *
     * NewApi is suppressed because lint cannot follow the level through [savedPictureDestination],
     * which answers [SavedPictureDestination.GALLERY] only from API 29 (Q).
     */
    @SuppressLint("NewApi")
    fun save(displayName: String, mimeType: String, subFolder: String?, write: (OutputStream) -> Unit): Uri {
        val exportDirectory = storagePreferences.exportDirectory.get()
        return when (savedPictureDestination(exportDirectory, Build.VERSION.SDK_INT)) {
            SavedPictureDestination.EXPORT_TREE ->
                saveToFolder(UniFile.fromUri(context, Uri.parse(exportDirectory)), displayName, subFolder, write)
            SavedPictureDestination.GALLERY -> saveToGallery(displayName, mimeType, subFolder, write)
            SavedPictureDestination.APP_FOLDER -> saveToFolder(
                context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)?.let(UniFile::fromFile),
                displayName,
                subFolder,
                write,
            )
        }
    }

    private fun saveToFolder(
        root: UniFile?,
        displayName: String,
        subFolder: String?,
        write: (OutputStream) -> Unit,
    ): Uri {
        val directory = root?.let { pictureDirectory(it, subFolder) } ?: throw noPlace()
        val target = directory.createFile(displayName) ?: throw noPlace()
        target.openOutputStream().use(write)
        DiskUtil.scanMedia(context, target.uri)
        return target.uri
    }

    /**
     * Upstream's `saveApi29`: an image mime the platform knows goes to the images collection under
     * `Pictures`, anything else to the files collection under `Documents`. A row of this app's with
     * the same path and name is written over, as upstream's `findUriOrDefault` does; a new row stays
     * pending while it is written, so no gallery shows half a picture, and is removed if the write
     * fails.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToGallery(
        displayName: String,
        mimeType: String,
        subFolder: String?,
        write: (OutputStream) -> Unit,
    ): Uri {
        val resolver = context.contentResolver
        val isImage = MimeTypeMap.getSingleton().hasMimeType(mimeType)
        val collection = if (isImage) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val relativePath = galleryRelativePath(isImage, subFolder)
        val existing = findRow(collection, relativePath, displayName)
        val picture = existing ?: resolver.insert(
            collection,
            contentValuesOf(
                MediaStore.MediaColumns.RELATIVE_PATH to relativePath,
                MediaStore.MediaColumns.DISPLAY_NAME to displayName,
                MediaStore.MediaColumns.MIME_TYPE to mimeType,
                MediaStore.MediaColumns.IS_PENDING to 1,
            ),
        ) ?: throw noPlace()
        try {
            // "wt", not upstream's "w": an overwrite with fewer bytes must not keep the old tail.
            (resolver.openOutputStream(picture, "wt") ?: throw noPlace()).use(write)
        } catch (e: Exception) {
            if (existing == null) resolver.delete(picture, null, null)
            throw e
        }
        resolver.update(
            picture,
            contentValuesOf(
                MediaStore.MediaColumns.IS_PENDING to 0,
                MediaStore.MediaColumns.DATE_MODIFIED to System.currentTimeMillis() / 1000,
            ),
            null,
            null,
        )
        return picture
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun findRow(collection: Uri, relativePath: String, displayName: String): Uri? {
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?"
        // MediaStore keeps a relative path with its trailing separator.
        val arguments = arrayOf("$relativePath${File.separator}", displayName)
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        return context.contentResolver.query(collection, projection, selection, arguments, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                ContentUris.withAppendedId(collection, id)
            } else {
                null
            }
        }
    }

    private fun noPlace() = IOException(context.getString(R.string.error_no_storage_location))
}

/** The three places [SavedPictures.save] can write to, in the order it tries them. */
internal enum class SavedPictureDestination { EXPORT_TREE, GALLERY, APP_FOLDER }

/** The picked export tree wins; otherwise the gallery from API 29 (Q), the app's folder below it. */
internal fun savedPictureDestination(exportDirectory: String, sdkInt: Int): SavedPictureDestination = when {
    exportDirectory.isNotBlank() -> SavedPictureDestination.EXPORT_TREE
    sdkInt >= Build.VERSION_CODES.Q -> SavedPictureDestination.GALLERY
    else -> SavedPictureDestination.APP_FOLDER
}

/** The folder under `Pictures` (or `Documents`, for a mime the gallery cannot show) that Saikou's pictures go into. */
internal const val GALLERY_FOLDER = "Saikou"

/**
 * The MediaStore relative path of a saved picture: `Pictures/Saikou`, or `Documents/Saikou` when the
 * mime is not an image the platform knows, as upstream's, then [subFolder] when there is one. No trailing
 * separator; the row's own column carries one, which the lookup adds. The two top folders are the
 * values of `Environment.DIRECTORY_PICTURES` and `DIRECTORY_DOCUMENTS`, written out because those
 * fields are not constants and read as nothing on the JVM.
 */
internal fun galleryRelativePath(isImage: Boolean, subFolder: String?): String = listOfNotNull(
    if (isImage) "Pictures" else "Documents",
    GALLERY_FOLDER,
    subFolder,
).joinToString(File.separator)

/** Inside a picked tree or the app's folder: [root] itself, or its [subFolder], made when missing. */
internal fun pictureDirectory(root: UniFile, subFolder: String?): UniFile? =
    if (subFolder == null) root else root.createDirectory(subFolder)

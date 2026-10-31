package com.shinsaikou.ui.backup

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.zacsweers.metro.Inject
import java.io.IOException

/**
 * The backup screens' documents. Save a copy (units storage-location, F2 and F11): a kept automatic
 * or sync safety backup, read through its own URI (a `file://` one in app storage, a document one in
 * the storage location), written into the document the owner just created with the system's save
 * dialog. And for the restore (unit restore-report): the name of the file about to be restored, and
 * the restore report's exported error log.
 */
@Inject
class BackupFileCopy(private val context: Context) {

    /** False when either end could not be opened or the copy broke off. */
    fun copy(from: Uri, to: Uri): Boolean = runCatching {
        val resolver = context.contentResolver
        val input = resolver.openInputStream(from) ?: throw IOException("The backup cannot be read")
        input.use { source ->
            val output = resolver.openOutputStream(to) ?: throw IOException("The copy cannot be written")
            output.use { source.copyTo(it) }
        }
    }.isSuccess

    /**
     * What the provider calls [uri], else its last path segment (a `file://` kept backup's own
     * name), else the whole URI. A provider that will not answer is not an error here: the name is
     * only shown.
     */
    fun displayName(uri: Uri): String {
        val provided = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
        }.getOrNull()
        return provided?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: uri.toString()
    }

    /** Writes [text] as UTF-8 into [to]; false when it could not be opened or the write broke off. */
    fun write(to: Uri, text: String): Boolean = runCatching {
        val output = context.contentResolver.openOutputStream(to) ?: throw IOException("The file cannot be written")
        output.bufferedWriter().use { it.write(text) }
    }.isSuccess
}

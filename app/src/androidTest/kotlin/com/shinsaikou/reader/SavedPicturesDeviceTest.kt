package com.shinsaikou.reader

import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shinsaikou.domain.preferences.StoragePreferences
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * [SavedPictures] on a real MediaStore (unit saved-pages-gallery, F6). On API 29 and later a saved
 * PNG is a row of the images collection under `Pictures/Saikou/<sub-folder>/` with its name, holding
 * the bytes written, no longer pending, and a second save of the same name writes over that row as
 * upstream's `findUriOrDefault` does instead of adding `name (1)`. On API 26 to 28 the same save is a
 * file in the app's own pictures folder. Each test returns with a `Skipped:` line on the API levels
 * it is not for, under `saikou.strict` too: the other test covers that device, so the return is the
 * right answer there, as a one pane return is on a phone. Every row and file written is deleted.
 *
 * The preferences are an in-memory store, so no export tree the device's install may hold is used.
 */
@RunWith(AndroidJUnit4::class)
class SavedPicturesDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val savedPictures = SavedPictures(context, StoragePreferences(InMemoryPreferenceStore()))
    private val written = mutableListOf<Uri>()
    private val writtenFiles = mutableListOf<File>()

    @After
    fun deleteWhatWasWritten() {
        written.forEach { context.contentResolver.delete(it, null, null) }
        writtenFiles.forEach { it.delete() }
    }

    @Test
    fun fromApi29ASavedPictureIsAGalleryRowUnderPicturesSaikou() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.i(
                LOG_TAG,
                "Skipped: fromApi29ASavedPictureIsAGalleryRowUnderPicturesSaikou is for API 29 and later, " +
                    "this is ${Build.VERSION.SDK_INT}",
            )
            return
        }
        val name = "SavedPicturesDeviceTest-${System.nanoTime()}.png"
        val first = png(Color.RED)
        val second = png(Color.BLUE)

        val uri = savedPictures.save(name, PNG, SUB_FOLDER) { it.write(first) }
        written += uri

        val rows = rowsNamed(name)
        assertEquals("rows under $RELATIVE_PATH named $name", listOf(uri), rows)
        assertArrayEquals(first, read(uri))
        assertEquals(0, pending(uri))

        val again = savedPictures.save(name, PNG, SUB_FOLDER) { it.write(second) }
        if (again != uri) written += again

        assertEquals("a second save of the same name writes over the first row", uri, again)
        assertEquals(listOf(uri), rowsNamed(name))
        assertArrayEquals(second, read(uri))
    }

    @Test
    fun onApi26To28ASavedPictureIsAFileInTheAppsOwnPicturesFolder() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Log.i(
                LOG_TAG,
                "Skipped: onApi26To28ASavedPictureIsAFileInTheAppsOwnPicturesFolder is for API 26 to 28, " +
                    "this is ${Build.VERSION.SDK_INT}",
            )
            return
        }
        val name = "SavedPicturesDeviceTest-${System.nanoTime()}.png"
        val bytes = png(Color.RED)

        savedPictures.save(name, PNG, SUB_FOLDER) { it.write(bytes) }

        val file = File(File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), SUB_FOLDER), name)
        writtenFiles += file
        assertTrue("no file at $file", file.isFile)
        assertArrayEquals(bytes, file.readBytes())
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun rowsNamed(name: String): List<Uri> {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?"
        val cursor = context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            selection,
            arrayOf(RELATIVE_PATH, name),
            null,
        ) ?: return emptyList()
        return cursor.use {
            buildList {
                while (it.moveToNext()) add(ContentUris.withAppendedId(collection, it.getLong(0)))
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun pending(uri: Uri): Int =
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)!!.use {
            it.moveToFirst()
            it.getInt(0)
        }

    private fun read(uri: Uri): ByteArray = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }

    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private companion object {
        const val LOG_TAG = "SaikouSavedPictures"
        const val PNG = "image/png"
        const val SUB_FOLDER = "SavedPicturesDeviceTest"
        const val RELATIVE_PATH = "Pictures/Saikou/$SUB_FOLDER/"
    }
}

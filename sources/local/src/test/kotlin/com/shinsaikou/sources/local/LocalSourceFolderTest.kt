package com.shinsaikou.sources.local

import android.content.Context
import com.hippo.unifile.UniFile
import com.shinsaikou.domain.preferences.StoragePreferences
import com.shinsaikou.domain.storage.StorageLocation
import com.shinsaikou.domain.storage.StorageTrees
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.source.local.io.LocalSourceFileSystem
import java.io.File

/**
 * Which folder the Local source reads (unit storage-location): the storage location's `local` folder
 * when one is set and reachable, the app's own folder otherwise, and the line the empty listing
 * shows naming it. A series is its folder's name wherever the folder is, so an entry in the library
 * finds its series again after a move.
 */
class LocalSourceFolderTest {

    @TempDir
    lateinit var temp: File

    private val externalFilesDir: File get() = File(temp, "storage/emulated/0/Android/data/com.shinsaikou/files")
    private val tree: File get() = File(temp, "storage/emulated/0/Saikou")
    private val context = mockk<Context> { every { getExternalFilesDir(null) } answers { externalFilesDir } }
    private val storagePreferences = StoragePreferences(InMemoryPreferenceStore())
    private val localPreferences = LocalSourcePreferences(InMemoryPreferenceStore())
    private val fileSystem = LocalSourceFileSystem(
        context,
        localPreferences,
        StorageLocation(storagePreferences, FileTrees),
    )

    private fun pick() {
        tree.mkdirs()
        storagePreferences.storageLocation.set("file:${tree.path}")
    }

    @Test
    fun `with no storage location the app folder is read and the empty listing names it from Android on`() {
        fileSystem.getBaseDirectory()!!.filePath shouldBe File(externalFilesDir, "local").path
        fileSystem.readingFrom() shouldBe "Android/data/com.shinsaikou/files/local"
    }

    @Test
    fun `with a storage location its local folder is read and named`() {
        pick()
        File(tree, "local/Series One").mkdirs()

        fileSystem.getBaseDirectory()!!.filePath shouldBe File(tree, "local").path
        fileSystem.getMangaDirectory("Series One")!!.filePath shouldBe File(tree, "local/Series One").path
        fileSystem.readingFrom() shouldBe "Saikou/local"
    }

    @Test
    fun `a storage location that cannot be reached falls back to the app folder`() {
        pick()
        tree.deleteRecursively()

        fileSystem.getBaseDirectory()!!.filePath shouldBe File(externalFilesDir, "local").path
        fileSystem.readingFrom() shouldBe "Android/data/com.shinsaikou/files/local"
    }

    @Test
    fun `a series moved from the app folder is found under the same name`() {
        File(externalFilesDir, "local/Series One/ch1").mkdirs()
        fileSystem.getMangaDirectory("Series One")!!.name shouldBe "Series One"

        pick()
        File(externalFilesDir, "local/Series One").renameTo(File(tree, "local/Series One").apply { parentFile!!.mkdirs() })

        fileSystem.getMangaDirectory("Series One")!!.filePath shouldBe File(tree, "local/Series One").path
        fileSystem.getFilesInMangaDirectory("Series One").map { it.name } shouldBe listOf("ch1")
    }

    @Test
    fun `the app folder is where the files lived before, whatever is set`() {
        pick()

        fileSystem.defaultDirectoryFile().path shouldBe File(externalFilesDir, "local").path
    }

    private object FileTrees : StorageTrees {
        override fun open(uri: String): UniFile? = UniFile.fromFile(File(uri.removePrefix("file:")))
    }
}

package com.shinsaikou.domain.storage

import com.hippo.unifile.UniFile
import com.shinsaikou.domain.preferences.StoragePreferences
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.io.File

/**
 * The storage location's derived folders and what every reader falls back on: a location never set,
 * a folder deleted after it was picked, and a file where a folder should be all read as "no folder",
 * so the caller uses its app-private one. The tree is a plain folder opened as a raw UniFile, the
 * way the app opens a `file://` URI.
 */
class StorageLocationTest {

    @TempDir
    lateinit var temp: File

    private val preferences = StoragePreferences(InMemoryPreferenceStore())
    private val location = StorageLocation(preferences, FileTrees)
    private val tree: File get() = File(temp, "Saikou")

    private fun pick() {
        tree.mkdirs()
        preferences.storageLocation.set("file:${tree.path}")
    }

    @Test
    fun `with no location set there is no folder and the status says so`() {
        location.status() shouldBe StorageLocation.Status.NOT_SET
        location.root().shouldBeNull()
        location.folder(StorageLocation.AUTOBACKUP).shouldBeNull()
        location.createFolders() shouldBe false
        location.displayName().shouldBeNull()
    }

    @Test
    fun `the three folders are upstream's names under the picked tree and are made on choosing`() {
        pick()

        location.createFolders() shouldBe true

        location.status() shouldBe StorageLocation.Status.REACHABLE
        tree.list()!!.toList() shouldContainExactlyInAnyOrder listOf("autobackup", "local", "downloads")
        location.folder(StorageLocation.AUTOBACKUP)!!.filePath shouldBe File(tree, "autobackup").path
        location.folder(StorageLocation.LOCAL)!!.filePath shouldBe File(tree, "local").path
        location.folder(StorageLocation.DOWNLOADS)!!.filePath shouldBe File(tree, "downloads").path
    }

    @Test
    fun `a folder is made the first time it is asked for, and asking again makes nothing beside it`() {
        pick()

        location.folder(StorageLocation.LOCAL)
        location.folder(StorageLocation.LOCAL)

        tree.list()!!.toList() shouldBe listOf("local")
    }

    @Test
    fun `a deleted tree is unreachable, has no folder, and keeps its name for the row`() {
        pick()
        location.createFolders()

        tree.deleteRecursively()

        location.status() shouldBe StorageLocation.Status.UNREACHABLE
        location.root().shouldBeNull()
        location.folder(StorageLocation.AUTOBACKUP).shouldBeNull()
        location.displayName() shouldBe "Saikou"
    }

    @Test
    fun `a folder handed out before is not handed out again once it is gone`() {
        pick()
        location.folder(StorageLocation.AUTOBACKUP)!!.filePath shouldBe File(tree, "autobackup").path

        tree.deleteRecursively()

        location.folder(StorageLocation.AUTOBACKUP).shouldBeNull()
    }

    @Test
    fun `a new location is asked afresh, not answered with the old one's folder`() {
        pick()
        location.folder(StorageLocation.LOCAL)
        val other = File(temp, "Other").apply { mkdirs() }

        preferences.storageLocation.set("file:${other.path}")

        location.folder(StorageLocation.LOCAL)!!.filePath shouldBe File(other, "local").path
    }

    @Test
    fun `a file where a folder should be is not taken for the folder`() {
        pick()
        File(tree, "local").writeText("not a folder")

        location.folder(StorageLocation.LOCAL).shouldBeNull()
        tree.list()!!.toList() shouldBe listOf("local")
    }

    @Test
    fun `a tree URI is named by the last segment of its document id`() {
        StorageLocation.nameOf(
            "content://com.android.externalstorage.documents/tree/primary%3ASaikou",
        ) shouldBe "Saikou"
        StorageLocation.nameOf(
            "content://com.android.externalstorage.documents/tree/primary%3ABooks%2FSaikou",
        ) shouldBe "Saikou"
        StorageLocation.nameOf("content://com.android.externalstorage.documents/tree/1A2B-3C4D%3A") shouldBe "1A2B-3C4D:"
        StorageLocation.nameOf("file:/storage/emulated/0/Saikou") shouldBe "Saikou"
    }

    @Test
    fun `the previous location opens while its folder is there, and is nothing once it is gone or unset`() {
        location.previousRoot().shouldBeNull()
        location.previousDisplayName().shouldBeNull()
        val old = File(temp, "Old").apply { mkdirs() }

        preferences.previousStorageLocation.set("file:${old.path}")

        location.previousRoot()!!.filePath shouldBe old.path
        location.previousDisplayName() shouldBe "Old"
        old.deleteRecursively()
        location.previousRoot().shouldBeNull()
        location.previousDisplayName() shouldBe "Old"
    }

    @Test
    fun `two trees are on the same storage only when the external storage provider says the same volume`() {
        val primary = "content://com.android.externalstorage.documents/tree/primary%3ASaikou"
        val primaryOther = "content://com.android.externalstorage.documents/tree/primary%3ABooks%2FSaikou/document/primary%3ABooks%2FSaikou"
        val card = "content://com.android.externalstorage.documents/tree/1A2B-3C4D%3ASaikou"

        StorageLocation.sameVolume(primary, primaryOther) shouldBe true
        StorageLocation.sameVolume(primary, card) shouldBe false
        StorageLocation.sameVolume("content://other.provider/tree/primary%3ASaikou", primary) shouldBe false
        StorageLocation.sameVolume("file:/storage/emulated/0/A", "file:/storage/emulated/0/B") shouldBe false
    }

    @Test
    fun `a tree inside the other's three folders overlaps it, and a sibling or a parent does not`() {
        val tree = "content://com.android.externalstorage.documents/tree/"
        StorageLocation.overlaps("${tree}primary%3ASaikou", "${tree}primary%3ASaikou%2Flocal") shouldBe true
        StorageLocation.overlaps("${tree}primary%3ASaikou%2Fdownloads%2FNew", "${tree}primary%3ASaikou") shouldBe true
        StorageLocation.overlaps("${tree}primary%3A", "${tree}primary%3Aautobackup") shouldBe true
        StorageLocation.overlaps("${tree}primary%3ASaikou", "${tree}primary%3ASaikou%2FSub") shouldBe false
        StorageLocation.overlaps("${tree}primary%3ABooks", "${tree}primary%3ABooks%2FSaikou") shouldBe false
        StorageLocation.overlaps("${tree}primary%3ASaikou", "${tree}primary%3ASaikoulocal") shouldBe false
        StorageLocation.overlaps("file:/sdcard/Old", "file:/sdcard/Old/local/New") shouldBe true
        StorageLocation.overlaps("content://other/tree/a", "content://other/tree/a%2Flocal") shouldBe false
    }

    /** Opens `file:<path>` as a raw file, as UniFile.fromUri does for a file URI on a device. */
    private object FileTrees : StorageTrees {
        override fun open(uri: String): UniFile? = UniFile.fromFile(File(uri.removePrefix("file:")))
    }
}

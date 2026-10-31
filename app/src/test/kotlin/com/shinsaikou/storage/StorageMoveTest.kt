package com.shinsaikou.storage

import android.content.Context
import android.net.Uri
import android.text.TextUtils
import com.hippo.unifile.UniFile
import com.shinsaikou.backup.export.AutomaticBackupFiles
import com.shinsaikou.backup.export.BackupFileNames
import com.shinsaikou.domain.preferences.StoragePreferences
import com.shinsaikou.domain.storage.FolderCopy
import com.shinsaikou.domain.storage.StorageLocation
import com.shinsaikou.domain.storage.StorageTrees
import com.shinsaikou.source.SourceIds
import com.shinsaikou.sources.local.LocalSourcePreferences
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.source.local.io.LocalSourceFileSystem
import java.io.File
import java.io.FileNotFoundException

/**
 * The move a chosen storage location starts (unit storage-location): what it counts for the confirm
 * dialog, the kept backups into `autobackup` and the Local series into `local`, copy before delete,
 * a rerun that skips what is there, a failure that leaves the file in both places, and nothing
 * moved while the location cannot be reached. Unit storage-move-between-folders: a second location
 * takes the first one's `autobackup`, `local` and `downloads`, whole where the tree can move a
 * document and file by file where it cannot, and the first is kept, grant and all, until it is empty.
 */
class StorageMoveTest {

    @TempDir
    lateinit var temp: File

    private val filesDir: File get() = File(temp, "data/files")
    private val externalFilesDir: File get() = File(temp, "sdcard/Android/data/com.shinsaikou/files")
    private val tree: File get() = File(temp, "sdcard/Saikou")
    private val appBackups: File get() = File(filesDir, "backups")
    private val appLocal: File get() = File(externalFilesDir, "local")

    private val context = mockk<Context> {
        every { filesDir } answers { this@StorageMoveTest.filesDir }
        every { getExternalFilesDir(null) } answers { externalFilesDir }
    }
    private val storagePreferences = StoragePreferences(InMemoryPreferenceStore())
    private val trees = FileTrees()
    private val location = StorageLocation(storagePreferences, trees)
    private val favorites = mutableListOf<Manga>()
    private val updates = mutableListOf<MangaUpdate>()
    private val mangaRepository = mockk<MangaRepository> {
        coEvery { getFavorites() } answers { favorites.toList() }
        coEvery { updateAll(any()) } answers { updates += firstArg<List<MangaUpdate>>(); true }
    }
    private val grants = mockk<StorageGrants>(relaxed = true)
    private val move = StorageMove(
        AutomaticBackupFiles(context, storagePreferences, location),
        LocalSourceFileSystem(context, LocalSourcePreferences(InMemoryPreferenceStore()), location),
        location,
        mangaRepository,
        storagePreferences,
        trees,
        grants,
    )

    @BeforeEach
    fun stubPlatform() {
        mockkStatic(TextUtils::class)
        every { TextUtils.isEmpty(any()) } answers { firstArg<CharSequence?>().isNullOrEmpty() }
        // A raw file's URI, as the platform writes it; the cover relink stores it.
        mockkStatic(Uri::class)
        every { Uri.fromFile(any()) } answers {
            val file = firstArg<File>()
            mockk<Uri> { every { this@mockk.toString() } returns file.toURI().toString() }
        }
    }

    @AfterEach
    fun unstub() {
        unmockkStatic(TextUtils::class)
        unmockkStatic(Uri::class)
    }

    private fun pick() {
        tree.mkdirs()
        storagePreferences.storageLocation.set("file:${tree.path}")
    }

    private fun write(file: File, text: String = "x") = file.apply { parentFile!!.mkdirs(); writeText(text) }

    private fun File.paths(): List<String> =
        if (!exists()) emptyList() else walkTopDown().filter { it.isFile }.map { it.relativeTo(this).invariantSeparatorsPath }.sorted().toList()

    private val auto1 = BackupFileNames.suggest(1_700_000_000_000L)
    private val auto2 = BackupFileNames.suggest(1_700_100_000_000L)
    private val sync1 = BackupFileNames.suggestSync(1_700_000_000_000L)

    private fun seed() {
        write(File(appBackups, auto1), "auto one")
        write(File(appBackups, auto2), "auto two")
        write(File(appBackups, sync1), "sync")
        write(File(appBackups, "manual.tachibk"), "not the app's name")
        write(File(appLocal, "Series One/ch1.cbz"), "zip")
        write(File(appLocal, "Series One/cover.jpg"), "jpg")
        write(File(appLocal, "Series Two/ch1/001.jpg"), "page")
        write(File(appLocal, ".nomedia"), "")
    }

    @Test
    fun `the dialog's counts are the kept backups and the Local series folders`() {
        move.pending() shouldBe StorageMove.Pending(backups = 0, localSeries = 0)
        move.pending().isEmpty shouldBe true

        seed()

        move.pending() shouldBe StorageMove.Pending(backups = 3, localSeries = 2, bytes = SEED_BYTES)
    }

    @Test
    fun `backups go to autobackup and Local series to local, and the app folders keep only what the app did not name`() = runTest {
        seed()
        pick()

        val result = move.run().files

        result.failed shouldBe 0
        File(tree, "autobackup").paths() shouldContainExactly listOf(auto1, auto2, sync1).sorted()
        File(tree, "local").paths() shouldContainExactly listOf(
            ".nomedia",
            "Series One/ch1.cbz",
            "Series One/cover.jpg",
            "Series Two/ch1/001.jpg",
        )
        appBackups.paths() shouldContainExactly listOf("manual.tachibk")
        appLocal.paths() shouldBe emptyList()
        move.pending() shouldBe StorageMove.Pending(backups = 0, localSeries = 0)
        File(tree, "local/Series One/ch1.cbz").readText() shouldBe "zip"
    }

    @Test
    fun `a rerun after a stop skips what is already there and moves the rest`() = runTest {
        seed()
        pick()
        // As a stop leaves it: one file copied whole but its source not yet deleted.
        write(File(tree, "autobackup/$auto1"), "auto one")

        val result = move.run().files

        result.skipped shouldBe 1
        result.copied shouldBe 6
        appBackups.paths() shouldContainExactly listOf("manual.tachibk")
    }

    @Test
    fun `a file that cannot be read stays in the app beside its partial copy, and the next run moves it`() = runTest {
        seed()
        pick()
        val stuck = File(appLocal, "Series Two/ch1/001.jpg").apply { setReadable(false) }

        val result = move.run().files

        result.failed shouldBe 1
        stuck.exists() shouldBe true
        File(tree, "local/Series Two/ch1/001.jpg").exists() shouldBe true
        move.pending().localSeries shouldBe 1

        stuck.setReadable(true)
        move.run().files.copied shouldBe 1
        File(tree, "local/Series Two/ch1/001.jpg").readText() shouldBe "page"
        appLocal.paths() shouldBe emptyList()
    }

    @Test
    fun `a moved Local library entry's cover is pointed at the same file in its new place`() = runTest {
        seed()
        pick()
        val cover = File(appLocal, "Series One/cover.jpg")
        favorites += Manga.create().copy(id = 1, source = SourceIds.LOCAL, url = "Series One", thumbnailUrl = cover.toURI().toString())
        favorites += Manga.create().copy(id = 2, source = SourceIds.LOCAL, url = "Series Two", thumbnailUrl = "content://elsewhere/cover")
        favorites += Manga.create().copy(id = 3, source = 42L, url = "/x", thumbnailUrl = cover.toURI().toString())

        move.run()

        updates.map { it.id to it.thumbnailUrl } shouldContainExactly listOf(
            1L to File(tree, "local/Series One/cover.jpg").toURI().toString(),
        )
    }

    @Test
    fun `a cover address is read relative to the old folder, spaces and all`() {
        val root = File("/storage/emulated/0/Android/data/com.shinsaikou/files/local")
        StorageMove.relativeCoverPath(File(root, "Series One/cover.jpg").toURI().toString(), root) shouldBe "Series One/cover.jpg"
        StorageMove.relativeCoverPath("${root.path}/Series One/cover.jpg", root) shouldBe "Series One/cover.jpg"
        StorageMove.relativeCoverPath("content://provider/document/cover", root) shouldBe null
        StorageMove.relativeCoverPath("file:///storage/emulated/0/Other/cover.jpg", root) shouldBe null
        StorageMove.relativeCoverPath(null, root) shouldBe null
    }

    @Test
    fun `with the location unreachable nothing moves`() = runTest {
        seed()
        pick()
        tree.deleteRecursively()

        shouldThrow<FileNotFoundException> { move.run() }

        move.pending() shouldBe StorageMove.Pending(backups = 3, localSeries = 2, bytes = SEED_BYTES)
    }

    // Unit storage-move-between-folders: a second location takes the first one's three folders.

    private val oldTree: File get() = File(temp, "sdcard/Old")
    private val oldUri: String get() = "file:${oldTree.path}"
    private val newUri: String get() = "file:${tree.path}"

    /** The first location as an earlier pick and export left it, set as the storage location. */
    private fun seedOld() {
        write(File(oldTree, "autobackup/$auto1"), "auto one")
        write(File(oldTree, "autobackup/$sync1"), "sync")
        write(File(oldTree, "autobackup/manual.tachibk"), "not the app's name")
        write(File(oldTree, "local/Series One/ch1.cbz"), "zip")
        write(File(oldTree, "local/Series One/cover.jpg"), "jpg")
        write(File(oldTree, "local/Series Two/ch1/001.jpg"), "page")
        write(File(oldTree, "local/.nomedia"), "")
        write(File(oldTree, "downloads/Source/Entry/Chapter 1/001.jpg"), "p1")
        write(File(oldTree, "downloads/Source/Entry/Chapter 1/002.jpg"), "p2")
        write(File(oldTree, "downloads/Source/Entry/Chapter 2.cbz"), "cbz")
        write(File(oldTree, "downloads/Source/Other/Chapter 1/001.jpg"), "p3")
        storagePreferences.storageLocation.set(oldUri)
    }

    /** Picks the new tree and says Move, as the dialog does. */
    private fun chooseNew(): StorageMove.Pending {
        tree.mkdirs()
        val pending = move.pendingFor(newUri)
        move.setLocation(newUri, pending)
        location.createFolders()
        return pending
    }

    @Test
    fun `the counts for a second location include the first one's three folders and their bytes`() {
        seedOld()
        tree.mkdirs()

        move.pendingFor(newUri) shouldBe StorageMove.Pending(
            backups = 2,
            localSeries = 2,
            exportedChapters = 3,
            bytes = OLD_BYTES,
            fromOldLocation = true,
            oldLocationBytes = OLD_BYTES,
        )
        // The same folder picked again moves nothing.
        move.pendingFor(oldUri) shouldBe StorageMove.Pending(backups = 0, localSeries = 0)
    }

    @Test
    fun `the move takes autobackup, local and downloads from the old tree, then gives its grant back`() = runTest {
        seedOld()
        chooseNew()
        storagePreferences.previousStorageLocation.get() shouldBe oldUri
        verify(exactly = 0) { grants.release(oldUri) }

        val moved = move.run()

        moved.files.failed shouldBe 0
        moved.files.copied shouldBe 10
        File(tree, "autobackup").paths() shouldContainExactly listOf(auto1, sync1).sorted()
        File(tree, "local").paths() shouldContainExactly listOf(".nomedia", "Series One/ch1.cbz", "Series One/cover.jpg", "Series Two/ch1/001.jpg")
        File(tree, "downloads").paths() shouldContainExactly listOf(
            "Source/Entry/Chapter 1/001.jpg",
            "Source/Entry/Chapter 1/002.jpg",
            "Source/Entry/Chapter 2.cbz",
            "Source/Other/Chapter 1/001.jpg",
        )
        // What the app did not name stays in the owner's folder; the emptied folders go.
        oldTree.paths() shouldContainExactly listOf("autobackup/manual.tachibk")
        oldTree.list()!!.toList() shouldContainExactly listOf("autobackup")
        storagePreferences.previousStorageLocation.get() shouldBe ""
        verify(exactly = 1) { grants.release(oldUri) }
        move.pending() shouldBe StorageMove.Pending(backups = 0, localSeries = 0)
    }

    @Test
    fun `a rerun skips what is already in the new folders`() = runTest {
        seedOld()
        chooseNew()
        // As a stop leaves it: one chapter copied whole but its source not yet deleted.
        write(File(tree, "downloads/Source/Entry/Chapter 2.cbz"), "cbz")

        val moved = move.run()

        moved.files.skipped shouldBe 1
        moved.files.failed shouldBe 0
        File(oldTree, "downloads").exists() shouldBe false
    }

    @Test
    fun `on one storage each child moves whole, and the files arrive unchanged`() = runTest {
        seedOld()
        chooseNew()
        trees.moves = { child, _, to -> File(child.filePath!!).renameTo(File(to.filePath!!, child.name!!)) }

        val moved = move.run()

        // Two backups, three Local entries and one source folder, each a rename.
        moved.files shouldBe FolderCopy.Result(copied = 6, skipped = 0, failed = 0, bytesCopied = 0)
        trees.moved shouldContainExactlyInAnyOrder listOf(auto1, sync1, ".nomedia", "Series One", "Series Two", "Source")
        File(tree, "downloads/Source/Entry/Chapter 2.cbz").readText() shouldBe "cbz"
        File(tree, "local/Series Two/ch1/001.jpg").readText() shouldBe "page"
        verify(exactly = 1) { grants.release(oldUri) }
    }

    @Test
    fun `a child whose move fails is copied file by file, a file that fails keeps its source and the old grant`() = runTest {
        seedOld()
        chooseNew()
        trees.moves = { child, _, to ->
            if (child.name == "Series Two") throw IllegalStateException("another storage")
            File(child.filePath!!).renameTo(File(to.filePath!!, child.name!!))
        }
        val stuck = File(oldTree, "local/Series Two/ch1/001.jpg").apply { setReadable(false) }

        val moved = move.run()

        moved.failedInPrevious shouldBe 1
        moved.previousName shouldBe "Old"
        stuck.exists() shouldBe true
        storagePreferences.previousStorageLocation.get() shouldBe oldUri
        verify(exactly = 0) { grants.release(oldUri) }
        move.pending() shouldBe StorageMove.Pending(backups = 0, localSeries = 1, bytes = 4, fromOldLocation = true, oldLocationBytes = 4)

        stuck.setReadable(true)
        move.run().files.failed shouldBe 0
        File(tree, "local/Series Two/ch1/001.jpg").readText() shouldBe "page"
        storagePreferences.previousStorageLocation.get() shouldBe ""
        verify(exactly = 1) { grants.release(oldUri) }
    }

    @Test
    fun `the export is copied an entry at a time, and an entry with a file that fails stays in the old folder`() = runTest {
        seedOld()
        chooseNew()
        val stuck = File(oldTree, "downloads/Source/Other/Chapter 1/001.jpg").apply { setReadable(false) }

        val moved = move.run()

        moved.failedInPrevious shouldBe 1
        File(oldTree, "downloads").paths() shouldContainExactly listOf("Source/Other/Chapter 1/001.jpg")
        File(oldTree, "downloads/Source").list()!!.toList() shouldContainExactly listOf("Other")
        File(tree, "downloads/Source/Entry/Chapter 2.cbz").readText() shouldBe "cbz"
        move.pending().exportedChapters shouldBe 1
        stuck.setReadable(true)
    }

    @Test
    fun `a Local library entry's cover under the old tree is pointed at the same file in the new one`() = runTest {
        seedOld()
        val cover = File(oldTree, "local/Series One/cover.jpg")
        favorites += Manga.create().copy(id = 1, source = SourceIds.LOCAL, url = "Series One", thumbnailUrl = cover.toURI().toString())
        chooseNew()

        move.run()

        updates.map { it.id to it.thumbnailUrl } shouldContainExactly listOf(
            1L to File(tree, "local/Series One/cover.jpg").toURI().toString(),
        )
    }

    @Test
    fun `a cover in a tree is read relative to the old Local folder by its document id`() {
        val tree = "content://com.android.externalstorage.documents/tree/primary%3AOld"
        val root = "$tree/document/primary%3AOld%2Flocal"
        StorageMove.relativeCoverPath("$tree/document/primary%3AOld%2Flocal%2FSeries%20One%2Fcover.jpg", root) shouldBe "Series One/cover.jpg"
        StorageMove.relativeCoverPath("$tree/document/primary%3AOld%2Flocalized%2Fcover.jpg", root) shouldBe null
        StorageMove.relativeCoverPath("content://com.android.externalstorage.documents/tree/primary%3AOther/document/primary%3AOld%2Flocal%2Fx.jpg", root) shouldBe null
        StorageMove.relativeCoverPath("content://other/document/opaque", root) shouldBe null
    }

    @Test
    fun `an unreachable old location is left out of the count and the move, and no previous location is written`() = runTest {
        seed()
        seedOld()
        oldTree.deleteRecursively()

        val pending = chooseNew()

        pending shouldBe StorageMove.Pending(backups = 3, localSeries = 2, bytes = SEED_BYTES)
        storagePreferences.previousStorageLocation.get() shouldBe ""
        verify(exactly = 1) { grants.release(oldUri) }
        move.run().failedInPrevious shouldBe 0
        File(tree, "autobackup").paths() shouldContainExactly listOf(auto1, auto2, sync1).sorted()
    }

    @Test
    fun `a folder picked inside the old location's own folders moves nothing from it`() {
        seedOld()
        val inside = File(oldTree, "local/New").apply { mkdirs() }

        move.pendingFor("file:${inside.path}") shouldBe StorageMove.Pending(backups = 0, localSeries = 0)
        move.pendingFor(newUri).fromOldLocation shouldBe true
    }

    @Test
    fun `a new pick forgets an older previous location and gives its grant back`() {
        seedOld()
        storagePreferences.previousStorageLocation.set("file:/elsewhere")

        chooseNew()

        storagePreferences.previousStorageLocation.get() shouldBe oldUri
        verify(exactly = 1) { grants.release("file:/elsewhere") }
        verify(exactly = 0) { grants.release(oldUri) }
    }

    /** Opens `file:<path>` as a raw file; [moves] stands in for `DocumentsContract.moveDocument`, false unless a test says so. */
    private class FileTrees : StorageTrees {
        var moves: (UniFile, UniFile, UniFile) -> Boolean = { _, _, _ -> false }
        val moved = mutableListOf<String>()

        override fun open(uri: String): UniFile? = UniFile.fromFile(File(uri.removePrefix("file:")))

        override fun move(child: UniFile, from: UniFile, to: UniFile): Boolean =
            moves(child, from, to).also { if (it) moved += child.name.orEmpty() }
    }

    private companion object {
        /** [seed]'s kept backups (8 + 8 + 4) and Local files (3 + 3 + 4 + 0). */
        const val SEED_BYTES = 30L

        /** [seedOld]'s kept backups (8 + 4), Local files (3 + 3 + 4 + 0) and export (2 + 2 + 3 + 2). */
        const val OLD_BYTES = 31L
    }
}

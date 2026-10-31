package com.shinsaikou.backup

import android.content.Context
import com.hippo.unifile.UniFile
import com.shinsaikou.backup.export.AutomaticBackup
import com.shinsaikou.backup.export.AutomaticBackupFiles
import com.shinsaikou.backup.export.BackupFileNames
import com.shinsaikou.domain.preferences.StoragePreferences
import com.shinsaikou.domain.storage.StorageLocation
import com.shinsaikou.domain.storage.StorageTrees
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The automatic export's folder and names, and sync's safety backups beside them (phase 3 unit G):
 * one folder, two sets of names, two rotations that never touch each other's files. With no folder
 * configured the files are app-private. With a storage location they are in its `autobackup` folder,
 * keep-four applied there, and with the location unreachable they fall back to the app folder.
 */
class AutomaticBackupFilesTest {

    @TempDir
    lateinit var filesDir: File

    private val context = mockk<Context> { every { filesDir } answers { this@AutomaticBackupFilesTest.filesDir } }
    private val preferences = StoragePreferences(InMemoryPreferenceStore())
    private val files = AutomaticBackupFiles(context, preferences, StorageLocation(preferences, FileTrees))
    private val directory: File get() = File(filesDir, "backups")
    /** The picked folder, beside the app's own files rather than in them. */
    private val tree: File get() = File(filesDir, "shared/Saikou")
    private val autobackup: File get() = File(tree, "autobackup")

    private fun pickStorageLocation() {
        tree.mkdirs()
        preferences.storageLocation.set("file:${tree.path}")
    }

    /** An automatic file named for [now], holding [text], as the export worker writes one. */
    private suspend fun AutomaticBackupFiles.created(now: Long, text: String = "new"): UniFile =
        write(now) { file -> file.openOutputStream().use { it.write(text.toByteArray()) } }

    @Test
    fun `a new file is named for its time in the app-private folder`() = runTest {
        val now = 1_700_000_000_000L

        val created = files.created(now)

        created.name shouldBe BackupFileNames.suggest(now)
        directory.list()!!.toList() shouldBe listOf(BackupFileNames.suggest(now))
    }

    @Test
    fun `creating one keeps the newest automatic files and never a file the app did not name`() = runTest {
        val day = TimeUnit.DAYS.toMillis(1)
        val start = 1_700_000_000_000L
        directory.mkdirs()
        val older = (0..4).map { BackupFileNames.suggest(start + it * day) }
        older.forEach { File(directory, it).writeText("old") }
        File(directory, "manual-export.tachibk").writeText("manual")

        val created = files.created(start + 10 * day)

        directory.list()!!.toList() shouldContainExactlyInAnyOrder
            older.takeLast(BackupFileNames.MAX_KEPT - 1) + created.name!! + "manual-export.tachibk"
    }

    @Test
    fun `the new file is written under a temporary name, and nothing older goes until it is complete`() = runTest {
        val day = TimeUnit.DAYS.toMillis(1)
        val start = 1_700_000_000_000L
        directory.mkdirs()
        val older = (0..3).map { BackupFileNames.suggest(start + it * day) }
        older.forEach { File(directory, it).writeText("old") }
        val now = start + 10 * day

        files.write(now) { file ->
            file.name shouldBe BackupFileNames.pending(now)
            directory.list()!!.toList() shouldContainExactlyInAnyOrder older + BackupFileNames.pending(now)
            files.list().map { it.name } shouldContainExactlyInAnyOrder older
            file.openOutputStream().use { it.write("new".toByteArray()) }
        }

        directory.list()!!.toList() shouldContainExactlyInAnyOrder older.drop(1) + BackupFileNames.suggest(now)
        File(directory, BackupFileNames.suggest(now)).readText() shouldBe "new"
    }

    @Test
    fun `a write that fails costs no older file and leaves nothing of its own`() = runTest {
        val day = TimeUnit.DAYS.toMillis(1)
        val start = 1_700_000_000_000L
        directory.mkdirs()
        val older = (0..3).map { BackupFileNames.suggest(start + it * day) }
        older.forEach { File(directory, it).writeText("old") }

        shouldThrow<IOException> {
            files.write(start + 10 * day) { file ->
                file.openOutputStream().use { it.write("half".toByteArray()) }
                throw IOException("fixture: the storage filled up")
            }
        }

        directory.list()!!.toList() shouldContainExactlyInAnyOrder older
    }

    @Test
    fun `the file of a run killed mid-write is deleted by the next run`() = runTest {
        val start = 1_700_000_000_000L
        directory.mkdirs()
        File(directory, BackupFileNames.pending(start)).writeText("half")

        val created = files.created(start + TimeUnit.DAYS.toMillis(1))

        directory.list()!!.toList() shouldContainExactly listOf(created.name!!)
    }

    @Test
    fun `a file named for the same minute is replaced by the new one`() = runTest {
        val now = 1_700_000_000_000L
        directory.mkdirs()
        File(directory, BackupFileNames.suggest(now)).writeText("earlier")

        files.created(now)

        directory.list()!!.toList() shouldContainExactly listOf(BackupFileNames.suggest(now))
        File(directory, BackupFileNames.suggest(now)).readText() shouldBe "new"
    }

    /**
     * A provider without rename support, which UniFile reports as a false rename, still gets every
     * automatic backup: the finished file is copied under its name and the pending one deleted.
     */
    @Test
    fun `a folder that refuses renames gets the new file by a copy under its name`() = runTest {
        val now = 1_700_000_000_000L
        val name = BackupFileNames.suggest(now)
        tree.mkdirs()
        preferences.backupDirectory.set("file:${tree.path}")
        val folder = spyk(UniFile.fromFile(tree)!!)
        every { folder.createFile(any()) } answers {
            spyk(callOriginal()!!) { every { renameTo(any()) } returns false }
        }
        val files = AutomaticBackupFiles(context, preferences, StorageLocation(preferences) { folder })

        files.created(now).name shouldBe name

        tree.list()!!.toList() shouldContainExactly listOf(name)
        File(tree, name).readText() shouldBe "new"
    }

    /**
     * A file named for the same minute is replaced only once the new one has the name: when the
     * rename is refused and the copy fails too, the earlier file takes its name back as it was.
     */
    @Test
    fun `a refused rename whose copy fails keeps the file named for the same minute`() = runTest {
        val now = 1_700_000_000_000L
        val name = BackupFileNames.suggest(now)
        tree.mkdirs()
        File(tree, name).writeText("earlier")
        preferences.backupDirectory.set("file:${tree.path}")
        val folder = spyk(UniFile.fromFile(tree)!!)
        every { folder.createFile(any()) } answers {
            val created = callOriginal()!!
            spyk(created) {
                every { renameTo(name) } returns false
                if (created.name == name) every { openOutputStream() } throws IOException("full")
            }
        }
        val files = AutomaticBackupFiles(context, preferences, StorageLocation(preferences) { folder })

        shouldThrow<IllegalStateException> { files.created(now) }

        tree.list()!!.toList() shouldContainExactly listOf(name)
        File(tree, name).readText() shouldBe "earlier"
    }

    /** A rename that throws rather than returning false gives the earlier file its name back too. */
    @Test
    fun `a rename that throws keeps the file named for the same minute`() = runTest {
        val now = 1_700_000_000_000L
        val name = BackupFileNames.suggest(now)
        tree.mkdirs()
        File(tree, name).writeText("earlier")
        preferences.backupDirectory.set("file:${tree.path}")
        val folder = spyk(UniFile.fromFile(tree)!!)
        every { folder.createFile(any()) } answers {
            spyk(callOriginal()!!) {
                every { renameTo(name) } throws SecurityException("fixture: the grant was revoked")
            }
        }
        val files = AutomaticBackupFiles(context, preferences, StorageLocation(preferences) { folder })

        shouldThrow<SecurityException> { files.created(now) }

        tree.list()!!.toList() shouldContainExactly listOf(name)
        File(tree, name).readText() shouldBe "earlier"
    }

    /**
     * A run killed after setting a same-minute file aside left it under a name nothing lists, rotates
     * or cleans up. The next run gives it its name back, or deletes it when the new file had the
     * name already and only the delete was missing.
     */
    @Test
    fun `a file a killed run set aside takes its name back with the next run`() = runTest {
        val day = TimeUnit.DAYS.toMillis(1)
        val start = 1_700_000_000_000L
        directory.mkdirs()
        // Killed before the new file had the name.
        File(directory, BackupFileNames.replaced(start)).writeText("earlier")
        File(directory, BackupFileNames.pending(start)).writeText("new")
        // Killed after the new file had the name.
        File(directory, BackupFileNames.replaced(start + day)).writeText("earlier")
        File(directory, BackupFileNames.suggest(start + day)).writeText("new")

        val created = files.created(start + 2 * day)

        directory.list()!!.toList() shouldContainExactlyInAnyOrder
            listOf(BackupFileNames.suggest(start), BackupFileNames.suggest(start + day), created.name!!)
        File(directory, BackupFileNames.suggest(start)).readText() shouldBe "earlier"
        File(directory, BackupFileNames.suggest(start + day)).readText() shouldBe "new"
    }

    @Test
    fun `the rotation keeps the count the owner chose, the new file among them`() = runTest {
        val day = TimeUnit.DAYS.toMillis(1)
        val start = 1_700_000_000_000L
        directory.mkdirs()
        val older = (0..8).map { BackupFileNames.suggest(start + it * day) }
        older.forEach { File(directory, it).writeText("old") }

        val more = files.write(start + 20 * day, keep = 7) { it.openOutputStream().use { s -> s.write(1) } }
        directory.list()!!.toList() shouldContainExactlyInAnyOrder older.takeLast(6) + more.name!!

        val fewer = files.write(start + 21 * day, keep = 2) { it.openOutputStream().use { s -> s.write(1) } }
        directory.list()!!.toList() shouldContainExactlyInAnyOrder listOf(more.name!!, fewer.name!!)
    }

    @Test
    fun `the automatic rotation never deletes a safety backup`() = runTest {
        val day = TimeUnit.DAYS.toMillis(1)
        val start = 1_700_000_000_000L
        directory.mkdirs()
        val safety = (0..2).map { BackupFileNames.suggestSync(start + it * day) }
        safety.forEach { File(directory, it).writeText("safety") }
        val automatic = (0..4).map { BackupFileNames.suggest(start + it * day) }
        automatic.forEach { File(directory, it).writeText("auto") }

        val created = files.created(start + 10 * day)

        directory.list()!!.toList() shouldContainExactlyInAnyOrder
            safety + automatic.takeLast(BackupFileNames.MAX_KEPT - 1) + created.name!!
    }

    @Test
    fun `a completed safety backup keeps itself and the one before it, and no automatic file is touched`() = runTest {
        val day = TimeUnit.DAYS.toMillis(1)
        val start = 1_700_000_000_000L
        directory.mkdirs()
        val older = (0..2).map { BackupFileNames.suggestSync(start + it * day) }
        older.forEach { File(directory, it).writeText("safety") }
        val automatic = BackupFileNames.suggest(start)
        File(directory, automatic).writeText("auto")

        val created = files.createSync(start + 10 * day)
        created.existed shouldBe false
        // Nothing is deleted until the new file is complete.
        directory.list()!!.toList() shouldContainExactlyInAnyOrder older + automatic + created.file.name!!

        files.pruneSync(keep = created.file.name!!)

        directory.list()!!.toList() shouldContainExactlyInAnyOrder listOf(older.last(), created.file.name!!, automatic)
    }

    @Test
    fun `the file just written is kept even when an older safety backup sorts after it`() = runTest {
        val day = TimeUnit.DAYS.toMillis(1)
        val start = 1_700_000_000_000L
        directory.mkdirs()
        val later = (1..3).map { BackupFileNames.suggestSync(start + it * day) }
        later.forEach { File(directory, it).writeText("safety") }

        val created = files.createSync(start)
        files.pruneSync(keep = created.file.name!!)

        directory.list()!!.toList() shouldContainExactlyInAnyOrder listOf(later.last(), created.file.name!!)
    }

    @Test
    fun `an automatic export and a safety backup in the same minute are two files with their own bytes`() = runTest {
        val now = 1_700_000_000_000L

        val automatic = files.created(now, "automatic")
        val safety = files.createSync(now + 30_000L)
        safety.file.openOutputStream().use { it.write("safety".toByteArray()) }

        (automatic.name == safety.file.name) shouldBe false
        File(directory, BackupFileNames.suggest(now)).readText() shouldBe "automatic"
        File(directory, BackupFileNames.suggestSync(now)).readText() shouldBe "safety"
    }

    @Test
    fun `a safety backup's name already in the folder is reported as existing`() = runTest {
        val now = 1_700_000_000_000L
        directory.mkdirs()
        File(directory, BackupFileNames.suggestSync(now)).writeText("earlier sign-in")

        files.createSync(now).existed shouldBe true
    }

    @Test
    fun `with a storage location an automatic file goes into its autobackup folder and keep-four holds there`() = runTest {
        val day = TimeUnit.DAYS.toMillis(1)
        val start = 1_700_000_000_000L
        pickStorageLocation()
        autobackup.mkdirs()
        val older = (0..4).map { BackupFileNames.suggest(start + it * day) }
        older.forEach { File(autobackup, it).writeText("old") }
        File(autobackup, "manual-export.tachibk").writeText("manual")

        val created = files.created(start + 10 * day)

        created.filePath shouldBe File(autobackup, BackupFileNames.suggest(start + 10 * day)).path
        autobackup.list()!!.toList() shouldContainExactlyInAnyOrder
            older.takeLast(BackupFileNames.MAX_KEPT - 1) + created.name!! + "manual-export.tachibk"
        directory.exists() shouldBe false
    }

    @Test
    fun `with a storage location a sync safety backup goes into the same autobackup folder`() = runTest {
        val now = 1_700_000_000_000L
        pickStorageLocation()

        val created = files.createSync(now)

        created.file.filePath shouldBe File(autobackup, BackupFileNames.suggestSync(now)).path
    }

    @Test
    fun `a storage location that cannot be reached falls back to the app folder instead of failing`() = runTest {
        val now = 1_700_000_000_000L
        pickStorageLocation()
        tree.deleteRecursively()

        val created = files.created(now)
        val safety = files.createSync(now)

        created.filePath shouldBe File(directory, BackupFileNames.suggest(now)).path
        safety.file.filePath shouldBe File(directory, BackupFileNames.suggestSync(now)).path
    }

    @Test
    fun `the list holds both kinds, newest first, with sizes, and nothing the app did not name`() = runTest {
        val hour = TimeUnit.HOURS.toMillis(1)
        val start = 1_700_000_000_000L
        directory.mkdirs()
        File(directory, BackupFileNames.suggest(start)).writeText("12345")
        File(directory, BackupFileNames.suggestSync(start + hour)).writeText("123")
        File(directory, BackupFileNames.suggest(start + 2 * hour)).writeText("1")
        File(directory, "manual-export.tachibk").writeText("manual")

        val listed = files.list()

        listed.map { it.name } shouldContainExactly listOf(
            BackupFileNames.suggest(start + 2 * hour),
            BackupFileNames.suggestSync(start + hour),
            BackupFileNames.suggest(start),
        )
        listed.map { it.kind } shouldContainExactly listOf(
            AutomaticBackup.Kind.AUTOMATIC,
            AutomaticBackup.Kind.SYNC_SAFETY,
            AutomaticBackup.Kind.AUTOMATIC,
        )
        listed.map { it.sizeBytes } shouldContainExactly listOf(1L, 3L, 5L)
        // The name carries the minute; the seconds of `start` are not in it.
        listed.last().createdAt shouldBe start - start % TimeUnit.MINUTES.toMillis(1)
        listed.all { it.inApp } shouldBe true
    }

    @Test
    fun `with a storage location the list also holds what is still in the app folder, in time order`() = runTest {
        val hour = TimeUnit.HOURS.toMillis(1)
        val start = 1_700_000_000_000L
        pickStorageLocation()
        autobackup.mkdirs()
        directory.mkdirs()
        File(autobackup, BackupFileNames.suggest(start + hour)).writeText("tree")
        File(directory, BackupFileNames.suggest(start + 2 * hour)).writeText("app")

        val listed = files.list()

        listed.map { it.name to it.inApp } shouldContainExactly listOf(
            BackupFileNames.suggest(start + 2 * hour) to true,
            BackupFileNames.suggest(start + hour) to false,
        )
    }

    @Test
    fun `a sync safety backup is found by its name in the location or in the app folder, and not once gone`() = runTest {
        val now = 1_700_000_000_000L
        directory.mkdirs()
        File(directory, BackupFileNames.suggestSync(now)).writeText("app")
        files.find(BackupFileNames.suggestSync(now))!!.inApp shouldBe true

        pickStorageLocation()
        autobackup.mkdirs()
        File(directory, BackupFileNames.suggestSync(now)).renameTo(File(autobackup, BackupFileNames.suggestSync(now)))
        files.find(BackupFileNames.suggestSync(now))!!.inApp shouldBe false

        File(autobackup, BackupFileNames.suggestSync(now)).delete()
        files.find(BackupFileNames.suggestSync(now)) shouldBe null
    }

    @Test
    fun `the app folder's kept files are the ones a move takes`() {
        files.appFiles().shouldBeEmpty()
        directory.mkdirs()
        File(directory, BackupFileNames.suggest(1_700_000_000_000L)).writeText("auto")
        File(directory, BackupFileNames.suggestSync(1_700_000_000_000L)).writeText("sync")
        File(directory, "manual-export.tachibk").writeText("manual")

        files.appFiles().map { it.name } shouldContainExactlyInAnyOrder listOf(
            BackupFileNames.suggest(1_700_000_000_000L),
            BackupFileNames.suggestSync(1_700_000_000_000L),
        )
    }

    /** Opens `file:<path>` as a raw file, as UniFile.fromUri does for a file URI on a device. */
    private object FileTrees : StorageTrees {
        override fun open(uri: String): UniFile? = UniFile.fromFile(File(uri.removePrefix("file:")))
    }
}

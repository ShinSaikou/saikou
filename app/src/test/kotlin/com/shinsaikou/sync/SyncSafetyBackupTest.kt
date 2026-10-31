package com.shinsaikou.sync

import android.content.Context
import android.text.TextUtils
import com.shinsaikou.backup.export.AutomaticBackupFiles
import com.shinsaikou.backup.export.BackupExporter
import com.shinsaikou.backup.export.BackupFileNames
import com.shinsaikou.domain.preferences.StoragePreferences
import com.shinsaikou.domain.storage.StorageLocation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The safety backup over the real folder (phase 3 unit G): its own names beside the automatic
 * export's, the newest two kept once a file is complete, and a failed write that deletes only the
 * file it created.
 */
class SyncSafetyBackupTest {

    @TempDir
    lateinit var filesDir: File

    private val context = mockk<Context> { every { filesDir } answers { this@SyncSafetyBackupTest.filesDir } }
    private val storagePreferences = StoragePreferences(MapPreferenceStore())
    private val files = AutomaticBackupFiles(
        context,
        storagePreferences,
        StorageLocation(storagePreferences) { null },
    )
    private val workPreferences = SyncWorkPreferences(MapPreferenceStore())
    private val exporter = mockk<BackupExporter> {
        coEvery { build(any()) } returns mockk(relaxed = true)
        coEvery { write(any(), any()) } returns 1L
    }
    private val safetyBackup = SyncSafetyBackup(exporter, files, workPreferences)
    private val directory: File get() = File(filesDir, "backups").apply { mkdirs() }

    // UniFile's java.io files call TextUtils, which this module's unit tests do not stub.
    @BeforeEach
    fun stubTextUtils() {
        mockkStatic(TextUtils::class)
        every { TextUtils.isEmpty(any()) } answers { firstArg<CharSequence?>().isNullOrEmpty() }
    }

    @AfterEach
    fun unstubTextUtils() = unmockkStatic(TextUtils::class)

    @Test
    fun `a failed write deletes the file it created and leaves the automatic export of the same minute`() = runTest {
        File(directory, BackupFileNames.suggest(NOW)).writeText("automatic")
        coEvery { exporter.write(any(), any()) } throws IOException("fixture: no space left")

        shouldThrow<SyncSafetyBackupException> { safetyBackup.ensure(BACKEND, NOW) }

        coVerify(exactly = 1) { exporter.write(any(), any()) }
        directory.list()!!.toList() shouldContainExactlyInAnyOrder listOf(BackupFileNames.suggest(NOW))
        File(directory, BackupFileNames.suggest(NOW)).readText() shouldBe "automatic"
        workPreferences.safetyBackupFile.get() shouldBe ""
    }

    @Test
    fun `a failed write never deletes a safety backup whose name was already there`() = runTest {
        File(directory, BackupFileNames.suggestSync(NOW)).writeText("an earlier sign-in's backup")
        coEvery { exporter.write(any(), any()) } throws IOException("fixture: no space left")

        shouldThrow<SyncSafetyBackupException> { safetyBackup.ensure(BACKEND, NOW) }

        coVerify(exactly = 1) { exporter.write(any(), any()) }
        File(directory, BackupFileNames.suggestSync(NOW)).exists() shouldBe true
    }

    @Test
    fun `a completed backup is recorded and keeps only the safety backup before it`() = runTest {
        val day = TimeUnit.DAYS.toMillis(1)
        val older = (1..3).map { BackupFileNames.suggestSync(NOW - it * day) }
        older.forEach { File(directory, it).writeText("safety") }
        File(directory, BackupFileNames.suggest(NOW - day)).writeText("automatic")

        safetyBackup.ensure(BACKEND, NOW)

        directory.list()!!.toList() shouldContainExactlyInAnyOrder listOf(
            BackupFileNames.suggestSync(NOW - day),
            BackupFileNames.suggestSync(NOW),
            BackupFileNames.suggest(NOW - day),
        )
        workPreferences.safetyBackupFile.get() shouldBe BackupFileNames.suggestSync(NOW)
        workPreferences.safetyBackupBackend.get() shouldBe BACKEND
    }

    private companion object {
        const val BACKEND = "webdav-0123456789abcdef"
        const val NOW = 1_790_000_000_000L
    }
}

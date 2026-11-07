package com.shinsaikou.backup

import com.shinsaikou.backup.fixture.BackupTestEnvironment
import com.shinsaikou.backup.fixture.SyntheticBackupFixture
import com.shinsaikou.backup.importer.BackupImporter
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * A backup carries no merges (R163 item 4), but a restore writes `read` on the members of groups
 * already on the device, so the library's groups are reconciled once the entries are back.
 */
class BackupImporterMergedReadTest {

    @TempDir
    lateinit var tempDir: File

    private val fixture = SyntheticBackupFixture.backup(mangaCount = 5, chaptersPerManga = 2)

    @Test
    fun `a restore of the library reconciles every live group once, after the entries are back`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val report = env.importer.import(env.writeImportFile(SyntheticBackupFixture.tachibk(fixture)), RestoreOptions()) {}

            report.mangaInserted shouldBe fixture.backupManga.size
            coVerify(exactly = 1) { env.reconcileMergedRead.awaitAll() }
        }
    }

    @Test
    fun `a restore that leaves the library entries out reconciles nothing`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            env.importer.import(
                env.writeImportFile(SyntheticBackupFixture.tachibk(fixture)),
                RestoreOptions(libraryEntries = false),
            ) {}

            coVerify(exactly = 0) { env.reconcileMergedRead.awaitAll() }
        }
    }

    @Test
    fun `a reconcile that fails is logged and the restore still finishes`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            coEvery { env.reconcileMergedRead.awaitAll() } throws IllegalStateException("db busy")

            val report = env.importer.import(env.writeImportFile(SyntheticBackupFixture.tachibk(fixture)), RestoreOptions()) {}

            report.cancelled shouldBe false
            report.mangaInserted shouldBe fixture.backupManga.size
            env.syncRestorePreferences.restoredSinceSync.get() shouldBe true
        }
    }

    @Test
    fun `a restore cancelled between chunks reconciles nothing`() = runTest {
        val twoChunks = SyntheticBackupFixture.backup(
            mangaCount = BackupImporter.MANGA_BATCH_SIZE + 50,
            chaptersPerManga = 2,
        )
        BackupTestEnvironment(tempDir).use { env ->
            val file = env.writeImportFile(SyntheticBackupFixture.tachibk(twoChunks))

            lateinit var job: Job
            job = launch { env.importer.import(file, RestoreOptions()) { job.cancel() } }
            job.join()

            job.isCancelled shouldBe true
            coVerify(exactly = 0) { env.reconcileMergedRead.awaitAll() }
        }
    }
}

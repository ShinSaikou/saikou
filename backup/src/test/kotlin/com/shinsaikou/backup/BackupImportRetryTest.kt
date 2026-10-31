package com.shinsaikou.backup

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.work.ForegroundInfo
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.google.common.util.concurrent.ListenableFuture
import com.shinsaikou.backup.importer.BackupImportWorker
import com.shinsaikou.backup.importer.BackupImporter
import com.shinsaikou.backup.importer.ImportCheckpointStore
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
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
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * A restore that fails with a checkpoint behind it is retried, but not forever: a failure that
 * outlasts the backoff (a full storage, a file the heap cannot take) used to run the restore again
 * without end. After [BackupImportWorker.MAX_RETRIES] retries the run fails, says so, and leaves
 * the checkpoint for the owner's next try.
 */
class BackupImportRetryTest {

    @TempDir
    lateinit var filesDir: File

    private val resolver = mockk<ContentResolver> {
        every { openInputStream(any()) } answers { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }
    }
    private val context = mockk<Context>(relaxed = true) {
        every { applicationContext } returns this
        every { contentResolver } returns resolver
    }
    private val notifier = mockk<BackupNotifier>(relaxed = true)
    private val importer = mockk<BackupImporter> {
        coEvery { import(any(), any(), any()) } throws IOException("fixture: the storage is full")
    }
    private val checkpoints = mockk<ImportCheckpointStore> {
        every { load() } returns mockk(relaxed = true)
        every { importFile } answers { File(filesDir, "import.tachibk") }
    }

    @BeforeEach
    fun mockPlatformStatics() {
        mockkStatic(Uri::class, PendingIntent::class)
        every { Uri.parse(any()) } returns mockk()
        every { PendingIntent.getBroadcast(any(), any(), any(), any()) } returns mockk()
    }

    @AfterEach
    fun unmockPlatformStatics() {
        unmockkStatic(Uri::class, PendingIntent::class)
    }

    @Test
    fun `a failure with a checkpoint is retried while attempts are left`() = runTest {
        worker(attempt = BackupImportWorker.MAX_RETRIES - 1).doWork() shouldBe ListenableWorker.Result.retry()

        verify(exactly = 0) { notifier.showRestoreFailed() }
    }

    @Test
    fun `the last attempt fails the run and says so`() = runTest {
        worker(attempt = BackupImportWorker.MAX_RETRIES).doWork() shouldBe ListenableWorker.Result.failure()

        verify(exactly = 1) { notifier.showRestoreFailed() }
        verify(exactly = 1) { notifier.cancelRestoreProgress() }
    }

    private fun worker(attempt: Int): BackupImportWorker =
        TestListenableWorkerBuilder.from(context, BackupImportWorker::class.java)
            .setInputData(
                workDataOf(
                    BackupImportWorker.KEY_URI to "content://fixture.invalid/document/1",
                    BackupImportWorker.KEY_OPTIONS to RestoreOptions().asBooleanArray(),
                ),
            )
            .setRunAttemptCount(attempt)
            .setForegroundUpdater(BackgroundForegroundUpdater)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ) = BackupImportWorker(context, workerParameters, importer, checkpoints, notifier)
                },
            )
            .build()

    /** The app in the background, where the platform refuses the foreground; the run goes on without it. */
    private object BackgroundForegroundUpdater : ForegroundUpdater {
        override fun setForegroundAsync(context: Context, id: UUID, foregroundInfo: ForegroundInfo): ListenableFuture<Void> =
            throw IllegalStateException("fixture: a foreground service is not allowed from the background")
    }
}

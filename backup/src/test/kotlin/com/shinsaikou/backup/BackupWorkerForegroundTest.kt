package com.shinsaikou.backup

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.google.common.util.concurrent.ListenableFuture
import com.shinsaikou.backup.export.BackupExportWorker
import com.shinsaikou.backup.export.BackupExporter
import com.shinsaikou.backup.importer.BackupImportWorker
import com.shinsaikou.backup.importer.BackupImporter
import com.shinsaikou.backup.importer.ImportCheckpointStore
import com.shinsaikou.backup.importer.ImportReport
import com.shinsaikou.domain.preferences.BackupPreferences
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * Both backup workers started while the app is in the background, where the platform refuses a
 * foreground service (phase 2 plan, section 1.6). Each worker is built the way WorkManager builds
 * it; the updater stands in for the platform and refuses with the [IllegalStateException]
 * WorkManager reports. Only the platform edges are faked: the document the run reads or writes,
 * and the notification's cancel action.
 */
class BackupWorkerForegroundTest {

    @TempDir
    lateinit var filesDir: File

    private val resolver = mockk<ContentResolver>()
    private val context = mockk<Context>(relaxed = true) {
        every { applicationContext } returns this
        every { contentResolver } returns resolver
    }
    private val notifier = mockk<BackupNotifier>(relaxed = true)

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
    fun `a manual export refused the foreground still writes the backup and reports it`() = runTest {
        val exporter = mockk<BackupExporter>()
        coEvery { exporter.build(any()) } returns mockk(relaxed = true)
        coEvery { exporter.write(any(), any()) } returns 0L
        val written = ByteArrayOutputStream()
        every { resolver.openOutputStream(any(), "wt") } returns written
        val updater = RefusingForegroundUpdater()

        val input = workDataOf(
            BackupExportWorker.KEY_URI to DOCUMENT,
            BackupExportWorker.KEY_OPTIONS to BackupOptions().asBooleanArray(),
        )

        val worker = build(updater, input) { params ->
            BackupExportWorker(
                context = context,
                params = params,
                exporter = exporter,
                notifier = notifier,
                intents = mockk(relaxed = true),
                backupPreferences = BackupPreferences(InMemoryPreferenceStore()),
                automaticFiles = mockk(),
            )
        }

        worker.doWork() shouldBe ListenableWorker.Result.success()

        updater.calls shouldBe 1
        coVerify(exactly = 1) { exporter.write(any(), any()) }
        verify(exactly = 1) { notifier.showBackupComplete(any()) }
        verify(exactly = 0) { notifier.showBackupError() }
        verify(exactly = 1) { notifier.cancelBackupProgress() }
    }

    @Test
    fun `a restore refused the foreground still imports and reports it`() = runTest {
        val importer = mockk<BackupImporter>()
        val report = mockk<ImportReport> { every { cancelled } returns false }
        coEvery { importer.import(any(), any(), any()) } returns report
        val checkpoints = mockk<ImportCheckpointStore> {
            every { load() } returns null
            every { importFile } returns File(filesDir, "import.tachibk")
        }
        every { resolver.openInputStream(any()) } answers { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }
        val updater = RefusingForegroundUpdater()

        val input = workDataOf(
            BackupImportWorker.KEY_URI to DOCUMENT,
            BackupImportWorker.KEY_OPTIONS to RestoreOptions().asBooleanArray(),
        )

        val worker = build(updater, input) { params ->
            BackupImportWorker(
                context = context,
                params = params,
                importer = importer,
                checkpoints = checkpoints,
                notifier = notifier,
            )
        }

        worker.doWork() shouldBe ListenableWorker.Result.success()

        updater.calls shouldBe 1
        coVerify(exactly = 1) { importer.import(File(filesDir, "import.tachibk"), RestoreOptions(), any()) }
        verify(exactly = 1) { notifier.showRestoreComplete(report) }
        verify(exactly = 1) { notifier.cancelRestoreProgress() }
    }

    private inline fun <reified W : ListenableWorker> build(
        updater: ForegroundUpdater,
        input: Data,
        crossinline create: (WorkerParameters) -> W,
    ): W = TestListenableWorkerBuilder.from(context, W::class.java)
        .setInputData(input)
        .setForegroundUpdater(updater)
        .setWorkerFactory(
            object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    create(workerParameters)
            },
        )
        .build()

    /** What WorkManager raises when a background app asks for a foreground service on API 31 and above. */
    private class RefusingForegroundUpdater : ForegroundUpdater {
        var calls = 0
            private set

        override fun setForegroundAsync(context: Context, id: UUID, foregroundInfo: ForegroundInfo): ListenableFuture<Void> {
            calls++
            throw IllegalStateException("fixture: a foreground service is not allowed from the background")
        }
    }

    private companion object {
        const val DOCUMENT = "content://fixture.invalid/document/1"
    }
}

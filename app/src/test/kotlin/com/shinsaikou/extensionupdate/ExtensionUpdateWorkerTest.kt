package com.shinsaikou.extensionupdate

import android.content.Context
import androidx.work.ForegroundInfo
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.util.concurrent.ListenableFuture
import com.shinsaikou.domain.preferences.ExtensionsPreferences
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.io.IOException
import java.util.UUID

/**
 * The worker's result for each way a daily run ends: a run that met a restore is retried after the
 * backoff, a run that finished posts its outcome once, a switch turned off after the job was handed
 * over does nothing, and a defect fails this day's run only. Built the way WorkManager builds it.
 */
class ExtensionUpdateWorkerTest {

    private val context = mockk<Context>(relaxed = true)
    private val autoUpdate = mockk<ExtensionAutoUpdate>()
    private val notifier = mockk<ExtensionUpdateNotifier>(relaxed = true)
    private val preferences = ExtensionsPreferences(InMemoryPreferenceStore()).also { it.autoInstallUpdates.set(true) }

    @Test
    fun `a finished run posts its outcome and succeeds`() = runTest {
        val outcome = ExtensionAutoUpdate.Outcome(updated = listOf("One"))
        coEvery { autoUpdate.run() } returns outcome

        build().doWork() shouldBe ListenableWorker.Result.success()

        verify(exactly = 1) { notifier.showFinished(outcome) }
    }

    @Test
    fun `a run that met a restore retries and posts nothing`() = runTest {
        coEvery { autoUpdate.run() } returns ExtensionAutoUpdate.Outcome(busy = true)

        build().doWork() shouldBe ListenableWorker.Result.retry()

        verify(exactly = 0) { notifier.showFinished(any()) }
    }

    @Test
    fun `a switch turned off after the job was handed over runs nothing`() = runTest {
        preferences.autoInstallUpdates.set(false)
        coEvery { autoUpdate.run() } returns ExtensionAutoUpdate.Outcome(updated = listOf("One"))

        build().doWork() shouldBe ListenableWorker.Result.success()

        coVerify(exactly = 0) { autoUpdate.run() }
        verify(exactly = 0) { notifier.showFinished(any()) }
    }

    @Test
    fun `a run that throws fails this run and posts nothing`() = runTest {
        coEvery { autoUpdate.run() } throws IllegalStateException("fixture: a defect")

        build().doWork() shouldBe ListenableWorker.Result.failure()

        verify(exactly = 0) { notifier.showFinished(any()) }
    }

    @Test
    fun `an I-O failure is not retried in a loop either`() = runTest {
        coEvery { autoUpdate.run() } throws IOException("fixture: connection reset")

        build().doWork() shouldBe ListenableWorker.Result.failure()
    }

    @Test
    fun `a cancel propagates`() = runTest {
        coEvery { autoUpdate.run() } throws CancellationException("fixture: stopped")

        shouldThrow<CancellationException> { build().doWork() }
    }

    @Test
    fun `the work name is the one the scheduler arms and cancels`() {
        ExtensionUpdateWorker.WORK_NAME shouldBe "extension-auto-update"
    }

    private fun build(): ExtensionUpdateWorker = TestListenableWorkerBuilder.from(context, ExtensionUpdateWorker::class.java)
        .setForegroundUpdater(RefusingForegroundUpdater())
        .setWorkerFactory(
            object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    ExtensionUpdateWorker(appContext, workerParameters, autoUpdate, notifier, preferences)
            },
        )
        .build()

    private class RefusingForegroundUpdater : ForegroundUpdater {
        override fun setForegroundAsync(context: Context, id: UUID, foregroundInfo: ForegroundInfo): ListenableFuture<Void> =
            throw IllegalStateException("fixture: the daily run never goes to the foreground")
    }
}

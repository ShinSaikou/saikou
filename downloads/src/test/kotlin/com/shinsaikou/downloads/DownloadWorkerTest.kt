package com.shinsaikou.downloads

import android.content.Context
import androidx.work.ForegroundInfo
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.util.concurrent.ListenableFuture
import com.shinsaikou.core.common.crash.StartMode
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.time.TimeSource

/**
 * The force-stop row's resume: WorkManager restarts the download worker with the process, while
 * the app is in the background, where the platform refuses a foreground service (phase 2 plan,
 * section 1.6). The worker is built the way WorkManager builds it; the updater stands in for the
 * platform and refuses with the [IllegalStateException] WorkManager reports.
 */
class DownloadWorkerTest {

    private val context = mockk<Context>(relaxed = true)
    private val engine = mockk<DownloadEngine>()
    private val sweep = mockk<DownloadManifestSweep>(relaxed = true)
    private val notifier = mockk<DownloadNotifier>(relaxed = true)

    @Test
    fun `a run refused the foreground still sweeps and downloads the queue`() = runTest {
        coEvery { engine.run(any()) } returns DownloadEngine.Outcome(completed = 2, failed = 0)
        val updater = ThrowingForegroundUpdater {
            IllegalStateException("fixture: a foreground service is not allowed from the background")
        }

        val result = build(updater).doWork()

        result shouldBe ListenableWorker.Result.success()
        updater.calls shouldBe 1
        coVerify(exactly = 1) { sweep.run() }
        coVerify(exactly = 1) { engine.run(any()) }
        verify(exactly = 1) { notifier.showErrors(0) }
        verify(exactly = 1) { notifier.cancelProgress() }
    }

    /** WorkManager holds the retry for its backoff and its network constraint, then runs the rest. */
    @Test
    fun `a run that ended on a network failure asks WorkManager for a retry`() = runTest {
        coEvery { engine.run(any()) } returns DownloadEngine.Outcome(completed = 1, failed = 0, interrupted = true)

        build(ThrowingForegroundUpdater { IllegalStateException("fixture") }).doWork() shouldBe
            ListenableWorker.Result.retry()
    }

    @Test
    fun `a run stopped for storage ends and posts its notice`() = runTest {
        coEvery { engine.run(any()) } returns DownloadEngine.Outcome(completed = 0, failed = 0, storageFull = true)

        build(ThrowingForegroundUpdater { IllegalStateException("fixture") }).doWork() shouldBe
            ListenableWorker.Result.success()
        verify(exactly = 1) { notifier.showStorageFull() }
    }

    @Test
    fun `a stop while entering the foreground still clears the progress notification`() = runTest {
        val updater = ThrowingForegroundUpdater { CancellationException("fixture: stopped") }

        val result = build(updater).doWork()

        // A stopped run ends the way a stop during the download ends; the finally still runs.
        result shouldBe ListenableWorker.Result.success()
        coVerify(exactly = 0) { engine.run(any()) }
        verify(exactly = 1) { notifier.cancelProgress() }
    }

    /**
     * A start without extensions: every chapter would fail as a source that is not installed, so
     * the run is handed back untouched for the next start. Nothing is shown, and a plain progress
     * notification a run cut off with the last process left is cancelled.
     */
    @Test
    fun `a run in a start without extensions retries later and touches nothing`() = runTest {
        val updater = ThrowingForegroundUpdater { IllegalStateException("fixture") }

        build(updater, StartMode(withoutExtensions = true)).doWork() shouldBe ListenableWorker.Result.retry()

        updater.calls shouldBe 0
        coVerify(exactly = 0) { sweep.run() }
        coVerify(exactly = 0) { engine.run(any()) }
        verify(exactly = 1) { notifier.cancelProgress() }
    }

    /**
     * The system brought WorkManager's foreground service back with the last process's progress.
     * WorkManager takes it down only for a run that entered the foreground, so the waiting run does.
     */
    @Test
    fun `a waiting run takes the foreground when a dead run's progress is still up`() = runTest {
        every { notifier.isProgressShown() } returns true
        val updater = ThrowingForegroundUpdater { IllegalStateException("fixture") }

        build(updater, StartMode(withoutExtensions = true)).doWork() shouldBe ListenableWorker.Result.retry()

        updater.calls shouldBe 1
        coVerify(exactly = 0) { engine.run(any()) }
    }

    private fun build(updater: ForegroundUpdater, startMode: StartMode = StartMode.NORMAL): DownloadWorker =
        TestListenableWorkerBuilder.from(context, DownloadWorker::class.java)
            .setTags(listOf(DownloadWorker.TAG))
            .setForegroundUpdater(updater)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                        DownloadWorker(
                            context = appContext,
                            params = workerParameters,
                            engine = engine,
                            sweep = sweep,
                            notifier = notifier,
                            timeSource = TimeSource.Monotonic,
                            startMode = startMode,
                        )
                },
            )
            .build()

    /**
     * Stands in for the platform: an [IllegalStateException] is what WorkManager raises when a
     * background app asks for a foreground service on API 31 and above, a [CancellationException]
     * is a stop that lands during the call.
     */
    private class ThrowingForegroundUpdater(private val failure: () -> Throwable) : ForegroundUpdater {
        var calls = 0
            private set

        override fun setForegroundAsync(context: Context, id: UUID, foregroundInfo: ForegroundInfo): ListenableFuture<Void> {
            calls++
            throw failure()
        }
    }
}

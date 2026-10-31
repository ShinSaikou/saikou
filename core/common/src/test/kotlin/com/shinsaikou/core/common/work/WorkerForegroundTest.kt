package com.shinsaikou.core.common.work

import android.app.Notification
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.util.concurrent.ListenableFuture
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * The guard every worker enters the foreground through. The platform's refusal reaches a worker as
 * an [IllegalStateException] from [CoroutineWorker.setForeground]; the updater here raises one in
 * its place, so the worker is built the way WorkManager builds it and only the platform is faked.
 */
class WorkerForegroundTest {

    private val context = mockk<Context>(relaxed = true)

    @Test
    fun `a refused foreground start is logged and the run carries on`() = runTest {
        val updater = ThrowingForegroundUpdater { IllegalStateException("fixture: not allowed in the background") }
        val worker = build(updater)

        worker.doWork() shouldBe ListenableWorker.Result.success()

        updater.calls shouldBe 1
        worker.ranPastTheForeground shouldBe true
        worker.enteredForeground shouldBe false
    }

    @Test
    fun `a granted foreground start says so`() = runTest {
        val updater = GrantingForegroundUpdater()
        val worker = build(updater)

        worker.doWork() shouldBe ListenableWorker.Result.success()

        updater.calls shouldBe 1
        worker.enteredForeground shouldBe true
    }

    @Test
    fun `a stop that lands while entering the foreground still stops the run`() = runTest {
        // A coroutine cancellation is an IllegalStateException too. Swallowing it would let a
        // stopped worker keep going.
        val updater = ThrowingForegroundUpdater { CancellationException("fixture: stopped") }
        val worker = build(updater)

        shouldThrow<CancellationException> { worker.doWork() }

        worker.ranPastTheForeground shouldBe false
    }

    @Test
    fun `any other failure to enter the foreground is the worker's own`() = runTest {
        val updater = ThrowingForegroundUpdater { IOException("fixture: something else") }
        val worker = build(updater)

        shouldThrow<IOException> { worker.doWork() }

        worker.ranPastTheForeground shouldBe false
    }

    private fun build(updater: ForegroundUpdater): GuardedWorker =
        TestListenableWorkerBuilder.from(context, GuardedWorker::class.java)
            .setForegroundUpdater(updater)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                        GuardedWorker(appContext, workerParameters)
                },
            )
            .build()

    class GuardedWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        var ranPastTheForeground = false
            private set

        var enteredForeground: Boolean? = null
            private set

        override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(1, mockk<Notification>())

        override suspend fun doWork(): Result {
            enteredForeground = setForegroundSafely()
            ranPastTheForeground = true
            return Result.success()
        }
    }

    private class GrantingForegroundUpdater : ForegroundUpdater {
        var calls = 0
            private set

        override fun setForegroundAsync(context: Context, id: UUID, foregroundInfo: ForegroundInfo): ListenableFuture<Void> {
            calls++
            return Granted
        }
    }

    /** A foreground start the platform granted: a future that is already done. */
    private object Granted : ListenableFuture<Void> {
        override fun addListener(listener: Runnable, executor: Executor) = executor.execute(listener)
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false
        override fun isCancelled(): Boolean = false
        override fun isDone(): Boolean = true
        override fun get(): Void? = null
        override fun get(timeout: Long, unit: TimeUnit): Void? = null
    }

    private class ThrowingForegroundUpdater(private val failure: () -> Throwable) : ForegroundUpdater {
        var calls = 0
            private set

        override fun setForegroundAsync(context: Context, id: UUID, foregroundInfo: ForegroundInfo): ListenableFuture<Void> {
            calls++
            throw failure()
        }
    }
}

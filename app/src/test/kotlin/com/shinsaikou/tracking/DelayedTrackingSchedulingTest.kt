package com.shinsaikou.tracking

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.util.concurrent.ListenableFuture
import com.shinsaikou.core.common.coroutines.Dispatchers
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The retry worker's request and outcomes (phase 3 unit T1): one unique name, a network
 * constraint and upstream's backoff; KEEP with nothing waiting, nothing while a run waits, a run
 * appended behind one that is running; and the pass's answer mapped to WorkManager's, with upstream's
 * three retries.
 */
class DelayedTrackingSchedulingTest {

    private val workManager = mockk<WorkManager>(relaxed = true)
    private val scheduler = WorkManagerDelayedTrackingScheduler(
        lazyOf(workManager),
        kotlinx.coroutines.Dispatchers.Unconfined.let { Dispatchers(io = it, default = it, main = it) },
    )
    private val update = mockk<DelayedTrackingUpdate>()
    private val context = mockk<Context>(relaxed = true)

    private fun runs(vararg states: WorkInfo.State) {
        val future = mockk<ListenableFuture<List<WorkInfo>>>()
        every { future.get() } returns states.map { WorkInfo(UUID.randomUUID(), it, setOf(WorkManagerDelayedTrackingScheduler.TAG)) }
        every { workManager.getWorkInfosForUniqueWork(WorkManagerDelayedTrackingScheduler.WORK_NAME) } returns future
    }

    private fun enqueued(): Pair<ExistingWorkPolicy, OneTimeWorkRequest>? {
        val policy = slot<ExistingWorkPolicy>()
        val request = slot<OneTimeWorkRequest>()
        verify(atLeast = 0, atMost = 1) {
            workManager.enqueueUniqueWork(WorkManagerDelayedTrackingScheduler.WORK_NAME, capture(policy), capture(request))
        }
        return if (policy.isCaptured) policy.captured to request.captured else null
    }

    @Test
    fun `with no run unfinished the request is KEEP on one name, needs a network and backs off from five minutes`() = runTest {
        runs(WorkInfo.State.SUCCEEDED)

        scheduler.schedule()

        val (policy, request) = enqueued()!!
        policy shouldBe ExistingWorkPolicy.KEEP
        request.workSpec.workerClassName shouldBe DelayedTrackingUpdateWorker::class.java.name
        request.workSpec.constraints.requiredNetworkType shouldBe NetworkType.CONNECTED
        request.workSpec.backoffPolicy shouldBe BackoffPolicy.EXPONENTIAL
        request.workSpec.backoffDelayDuration shouldBe TimeUnit.MINUTES.toMillis(5)
        (WorkManagerDelayedTrackingScheduler.TAG in request.tags) shouldBe true
    }

    @Test
    fun `a run waiting to start, a retry's backoff included, will read the push, so nothing is enqueued`() = runTest {
        for (state in listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED)) {
            runs(WorkInfo.State.RUNNING, state)
            scheduler.schedule()
        }

        enqueued() shouldBe null
    }

    @Test
    fun `a push recorded while a run is running is sent by a run appended behind it`() = runTest {
        runs(WorkInfo.State.RUNNING)

        scheduler.schedule()

        enqueued()!!.first shouldBe ExistingWorkPolicy.APPEND_OR_REPLACE
    }

    @Test
    fun `a pass that settled every entry succeeds, and one that kept an entry retries`() = runTest {
        coEvery { update.run() } returns true
        worker().doWork() shouldBe ListenableWorker.Result.success()

        coEvery { update.run() } returns false
        worker().doWork() shouldBe ListenableWorker.Result.retry()
    }

    @Test
    fun `a pass that throws is retried`() = runTest {
        coEvery { update.run() } throws IOException("fixture: the database is locked")
        worker().doWork() shouldBe ListenableWorker.Result.retry()
    }

    @Test
    fun `after three retries the work fails without a pass, and the entries stay for the next push`() = runTest {
        coEvery { update.run() } returns false
        worker(runAttemptCount = 3).doWork() shouldBe ListenableWorker.Result.retry()
        worker(runAttemptCount = 4).doWork() shouldBe ListenableWorker.Result.failure()
        io.mockk.coVerify(exactly = 1) { update.run() }
    }

    @Test
    fun `a cancel propagates`() = runTest {
        coEvery { update.run() } throws CancellationException("fixture: stopped")
        shouldThrow<CancellationException> { worker().doWork() }
    }

    private fun worker(runAttemptCount: Int = 0): DelayedTrackingUpdateWorker =
        TestListenableWorkerBuilder.from(context, DelayedTrackingUpdateWorker::class.java)
            .setRunAttemptCount(runAttemptCount)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                        DelayedTrackingUpdateWorker(appContext, workerParameters, update)
                },
            )
            .build()
}

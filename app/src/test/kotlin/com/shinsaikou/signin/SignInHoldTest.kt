package com.shinsaikou.signin

import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.util.concurrent.ListenableFuture
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * The foreground hold (phase 3 unit O, Q1): one expedited `sign-in-hold` request per sign-in, a
 * worker that says whether it entered the foreground and waits for the hold to end, "Signed in, tap
 * to return" only when a code came back, and never a worker that outlives its sign-in. The worker is
 * built the way WorkManager builds it, and run the moment WorkManager is handed the request.
 */
class SignInHoldTest {

    private val context = mockk<Context>(relaxed = true)
    private val workManager = mockk<WorkManager>()
    private val notifier = mockk<SignInNotifier>(relaxed = true)
    private val hold = SignInHold(lazyOf(workManager))
    private val returnIntent = mockk<Intent>()

    private val requests = mutableListOf<OneTimeWorkRequest>()
    private val updater = CountingForegroundUpdater(granted = true)

    /** Each enqueue starts a worker in [scope]; its result is the last one in the list. */
    private val runs = mutableListOf<Deferred<ListenableWorker.Result>>()

    private fun TestScope.workManagerRunsTheWorker() {
        every { workManager.enqueueUniqueWork(any<String>(), any(), capture(requests)) } answers {
            val worker = build()
            runs += async { worker.doWork() }
            mockk()
        }
    }

    @Test
    fun `start enqueues one expedited sign-in-hold request that replaces any other`() = runTest {
        workManagerRunsTheWorker()

        val handle = hold.start(returnIntent)
        handle.inForeground shouldBe true

        verify(exactly = 1) { workManager.enqueueUniqueWork("sign-in-hold", ExistingWorkPolicy.REPLACE, any<OneTimeWorkRequest>()) }
        val request = requests.single()
        request.workSpec.workerClassName shouldBe SignInHoldWorker::class.java.name
        request.workSpec.expedited shouldBe true
        request.workSpec.outOfQuotaPolicy shouldBe OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST
        (SignInHold.TAG in request.tags) shouldBe true
        hold.end(handle, false)
    }

    @Test
    fun `a code that came back ends the worker with the signed-in notification`() = runTest {
        workManagerRunsTheWorker()

        val handle = hold.start(returnIntent)
        handle.inForeground shouldBe true
        updater.calls shouldBe 1
        runs.single().isActive shouldBe true

        hold.end(handle, signedIn = true)
        runs.single().await() shouldBe ListenableWorker.Result.success()
        verify(exactly = 1) { notifier.signedIn(returnIntent) }
    }

    @Test
    fun `a sign-in that ended without a code ends the worker quietly`() = runTest {
        workManagerRunsTheWorker()

        val handle = hold.start(returnIntent)
        handle.inForeground shouldBe true
        hold.end(handle, signedIn = false)

        runs.single().await() shouldBe ListenableWorker.Result.success()
        verify(exactly = 0) { notifier.signedIn(any()) }
        hold.active() shouldBe null
    }

    @Test
    fun `a refused foreground start says so and the worker still waits for the end`() = runTest {
        updater.granted = false
        workManagerRunsTheWorker()

        val handle = hold.start(returnIntent)
        handle.inForeground shouldBe false
        updater.calls shouldBe 1
        runs.single().isActive shouldBe true

        hold.end(handle, signedIn = true)
        runs.single().await() shouldBe ListenableWorker.Result.success()
    }

    @Test
    fun `a worker WorkManager never starts does not stop the sign-in past five seconds`() = runTest {
        every { workManager.enqueueUniqueWork(any<String>(), any(), any<OneTimeWorkRequest>()) } returns mockk()

        val started = async { hold.start(returnIntent) }
        advanceTimeBy(SignInHold.ENTER_TIMEOUT - 1.seconds)
        started.isActive shouldBe true
        advanceTimeBy(2.seconds)
        val handle = started.await()
        handle.inForeground shouldBe false
        hold.end(handle, false)
    }

    /** A start its caller cancelled while it waited for the worker ends its own hold. */
    @Test
    fun `a start cancelled before the worker entered ends its own hold`() = runTest {
        every { workManager.enqueueUniqueWork(any<String>(), any(), any<OneTimeWorkRequest>()) } returns mockk()

        val started = async { hold.start(returnIntent) }
        runCurrent()
        val pending = hold.active()!!
        started.cancel()
        runCurrent()

        pending.ended.isCompleted shouldBe true
        pending.ended.await() shouldBe false
        hold.active() shouldBe null
    }

    @Test
    fun `a worker with no hold in its process ends at once without the foreground`() = runTest {
        build().doWork() shouldBe ListenableWorker.Result.success()

        updater.calls shouldBe 0
        verify(exactly = 0) { notifier.signedIn(any()) }
    }

    @Test
    fun `a second sign-in ends the first hold as not signed in`() = runTest {
        workManagerRunsTheWorker()

        hold.start(returnIntent).inForeground shouldBe true
        val first = runs.single()
        val second = hold.start(returnIntent)
        second.inForeground shouldBe true
        runCurrent()

        first.isCompleted shouldBe true
        first.await() shouldBe ListenableWorker.Result.success()
        verify(exactly = 0) { notifier.signedIn(any()) }
        runs.last().isActive shouldBe true

        hold.end(second, signedIn = true)
        runs.last().await() shouldBe ListenableWorker.Result.success()
        verify(exactly = 1) { notifier.signedIn(returnIntent) }
    }

    /**
     * Fix round 1 item 2: sign-in A, abandoned in the browser, then sign-in B. A's end arrives after
     * B's start, as signed in or not; B's hold and B's worker are untouched, and only B's own end
     * ends them.
     */
    @Test
    fun `a late end from an earlier sign-in never ends the later one's hold`() = runTest {
        workManagerRunsTheWorker()

        val a = hold.start(returnIntent)
        val b = hold.start(returnIntent)
        runCurrent()
        hold.end(a, signedIn = true)
        hold.end(a, signedIn = false)
        runCurrent()

        runs.size shouldBe 2
        runs.first().isCompleted shouldBe true
        runs.last().isActive shouldBe true
        hold.active() shouldBe b.hold
        b.hold.ended.isCompleted shouldBe false
        verify(exactly = 0) { notifier.signedIn(any()) }

        hold.end(b, signedIn = true)
        runs.last().await() shouldBe ListenableWorker.Result.success()
        verify(exactly = 1) { notifier.signedIn(returnIntent) }
        hold.active() shouldBe null
    }

    @Test
    fun `a hold nobody ends lets the worker go a minute after the listener's timeout`() = runTest {
        workManagerRunsTheWorker()

        val handle = hold.start(returnIntent)
        handle.inForeground shouldBe true
        advanceTimeBy(SignInHoldWorker.MAX_HOLD - 1.seconds)
        runs.single().isActive shouldBe true
        advanceTimeBy(2.seconds)

        runs.single().isCompleted shouldBe true
        runs.single().await() shouldBe ListenableWorker.Result.success()
        verify(exactly = 0) { notifier.signedIn(any()) }
        hold.end(handle, false)
    }

    private fun build(): SignInHoldWorker = TestListenableWorkerBuilder.from(context, SignInHoldWorker::class.java)
        .setForegroundUpdater(updater)
        .setWorkerFactory(
            object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    SignInHoldWorker(appContext, workerParameters, hold, notifier)
            },
        )
        .build()

    /**
     * Grants the foreground with a future that is already done, or refuses it the way WorkManager
     * does for a background app on API 31 and above.
     */
    private class CountingForegroundUpdater(var granted: Boolean) : ForegroundUpdater {
        var calls = 0
            private set

        override fun setForegroundAsync(context: Context, id: UUID, foregroundInfo: ForegroundInfo): ListenableFuture<Void> {
            calls++
            if (!granted) throw IllegalStateException("fixture: a foreground service is not allowed from the background")
            return Granted
        }
    }

    private object Granted : ListenableFuture<Void> {
        override fun addListener(listener: Runnable, executor: Executor) = executor.execute(listener)
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false
        override fun isCancelled(): Boolean = false
        override fun isDone(): Boolean = true
        override fun get(): Void? = null
        override fun get(timeout: Long, unit: TimeUnit): Void? = null
    }
}

package com.shinsaikou.sync

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * The expiry notice's worker (fixes unit sync-notices), built the way WorkManager builds it: its run
 * is [SyncNotices.expiryDue], and a failure ends the work without a retry, since the next sync run or
 * app start arms the notice again.
 */
class SyncExpiryNoticeWorkerTest {

    private val context = mockk<Context>(relaxed = true)
    private val notices = mockk<SyncNotices>(relaxed = true)

    @Test
    fun `its run is the notices' expiry check, and a failure is not retried`() = runTest {
        build().doWork() shouldBe ListenableWorker.Result.success()
        coVerify(exactly = 1) { notices.expiryDue() }

        coEvery { notices.expiryDue() } throws IOException("fixture: the store refused")
        build().doWork() shouldBe ListenableWorker.Result.failure()
    }

    private fun build(): SyncExpiryNoticeWorker = TestListenableWorkerBuilder.from(context, SyncExpiryNoticeWorker::class.java)
        .setWorkerFactory(
            object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    SyncExpiryNoticeWorker(appContext, workerParameters, notices)
            },
        )
        .build()
}

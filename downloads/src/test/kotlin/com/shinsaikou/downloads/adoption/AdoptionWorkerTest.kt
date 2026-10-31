package com.shinsaikou.downloads.adoption

import android.content.Context
import androidx.work.ForegroundInfo
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.util.concurrent.ListenableFuture
import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.domain.error.KeyedFailure
import com.shinsaikou.domain.preferences.StoragePreferences
import com.shinsaikou.downloads.DownloadNotifier
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.io.IOException
import java.util.UUID

/**
 * The adoption worker enters the foreground through the one guard in `:core:common`, inside the try
 * whose finally clears its progress notification, as the other workers do (unit B2's carry-over 1).
 * A run that fails is summarised with the key and detail `ErrorKey` gives the failure.
 * The worker is built the way WorkManager builds it; the updater stands in for the platform.
 */
class AdoptionWorkerTest {

    private val context = mockk<Context>(relaxed = true)
    private val adoption = mockk<DownloadAdoption>()
    private val summaryStore = mockk<AdoptionSummaryStore>(relaxed = true)
    private val storagePreferences = StoragePreferences(InMemoryPreferenceStore())
    private val notifier = mockk<DownloadNotifier>(relaxed = true)

    @Test
    fun `a stop while entering the foreground ends the run, keeps the tree for later and clears the notification`() =
        runTest {
            storagePreferences.adoptionTree.set(PICKED)
            val updater = ThrowingForegroundUpdater { CancellationException("fixture: stopped") }

            val result = build(updater).doWork()

            // A stopped run ends the way a stop during the copy ends: nothing is summarised, and the
            // key stays so the run can be finished later.
            result shouldBe ListenableWorker.Result.success()
            updater.calls shouldBe 1
            coVerify(exactly = 0) { adoption.run(any(), any()) }
            coVerify(exactly = 0) { summaryStore.replace(any()) }
            storagePreferences.adoptionTree.get() shouldBe PICKED
            verify(exactly = 1) { notifier.cancelAdoptionProgress() }
        }

    @Test
    fun `a run that fails is summarised with the failure's key and the detail the shared resolver gives it`() =
        runTest {
            storagePreferences.adoptionTree.set(PICKED)
            // An IOException: the foreground guard lets it through as the run's own failure.
            val updater = ThrowingForegroundUpdater { Keyed(ErrorKey.WEBDAV_HTTP, "507") }
            val summaries = mutableListOf<AdoptionSummary>()
            coEvery { summaryStore.replace(capture(summaries)) } just Runs

            val result = build(updater).doWork()

            result shouldBe ListenableWorker.Result.failure()
            coVerify(exactly = 0) { adoption.run(any(), any()) }
            summaries.single().treeUri shouldBe PICKED
            val failure = summaries.single().failures.single()
            failure.path shouldBe ""
            failure.reason shouldBe AdoptionFailureReason.ERROR
            failure.key shouldBe ErrorKey.WEBDAV_HTTP
            failure.detail shouldBe "507"
            verify(exactly = 1) { notifier.cancelAdoptionProgress() }
        }

    private fun build(updater: ForegroundUpdater): AdoptionWorker =
        TestListenableWorkerBuilder.from(context, AdoptionWorker::class.java)
            .setTags(listOf(AdoptionWorker.TAG))
            .setForegroundUpdater(updater)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                        AdoptionWorker(
                            context = appContext,
                            params = workerParameters,
                            adoption = adoption,
                            summaryStore = summaryStore,
                            storagePreferences = storagePreferences,
                            notifier = notifier,
                        )
                },
            )
            .build()

    /** Stands in for the platform: a [CancellationException] is a stop that lands during the call. */
    private class ThrowingForegroundUpdater(private val failure: () -> Throwable) : ForegroundUpdater {
        var calls = 0
            private set

        override fun setForegroundAsync(context: Context, id: UUID, foregroundInfo: ForegroundInfo): ListenableFuture<Void> {
            calls++
            throw failure()
        }
    }

    /** A failure that declares its own key and detail, the way sync's and a tracker's do. */
    private class Keyed(
        override val errorKey: ErrorKey,
        override val errorDetail: String?,
    ) : IOException("keyed text"), KeyedFailure

    private companion object {
        const val PICKED = "file:///picked"
    }
}

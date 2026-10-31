package com.shinsaikou.sync

import android.content.Context
import androidx.work.ForegroundInfo
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.util.concurrent.ListenableFuture
import com.shinsaikou.domain.sync.model.SyncLibraryCounts
import com.shinsaikou.sync.drive.DriveHttpException
import com.shinsaikou.sync.drive.DriveMalformedResponseException
import com.shinsaikou.sync.drive.DriveStorageFullException
import com.shinsaikou.sync.drive.DriveUnavailableException
import com.shinsaikou.sync.drive.DriveWriteNotStoredException
import com.shinsaikou.sync.format.MalformedSyncFileException
import com.shinsaikou.sync.google.GoogleAuthRefusedException
import com.shinsaikou.sync.google.GoogleUnavailableException
import com.shinsaikou.sync.format.NewerFormatException
import com.shinsaikou.sync.webdav.WebDavHostNameMismatchException
import com.shinsaikou.sync.webdav.WebDavHttpException
import com.shinsaikou.sync.webdav.WebDavMalformedResponseException
import com.shinsaikou.sync.webdav.WebDavRedirectException
import com.shinsaikou.sync.webdav.WebDavUntrustedCertificateException
import com.shinsaikou.sync.webdav.WebDavUnreachableException
import com.shinsaikou.sync.webdav.WebDavUrlRefusedException
import com.shinsaikou.sync.webdav.WebDavWriteNotStoredException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * The worker's outcome for each way a run ends (plan unit G): a failure the owner has to act on is
 * final and never retried in a loop, any other I/O failure is retried with backoff, a run turned
 * away by the lock is a success, and a cancel propagates. The worker is built the way WorkManager
 * builds it, refused the foreground the way a background start is.
 */
class SyncWorkerTest {

    private val context = mockk<Context>(relaxed = true)
    private val session = mockk<SyncSession>()
    private val notices = mockk<SyncNotices>(relaxed = true)

    private val finalFailures: List<Throwable> = listOf(
        SyncSignedOutException("fixture: 401"),
        NewerFormatException(found = 2),
        MalformedSyncFileException("fixture: a cut line"),
        WebDavUntrustedCertificateException("dav.example.invalid", SSLHandshakeException("fixture")),
        WebDavHostNameMismatchException("dav.example.invalid", SSLPeerUnverifiedException("fixture")),
        WebDavRedirectException(308, "PUT", "saikou-sync/v1/x", "http", "elsewhere.example.invalid"),
        WebDavMalformedResponseException("fixture: no multistatus"),
        WebDavUrlRefusedException(WebDavUrlRefusedException.Reason.CLEARTEXT, "fixture"),
        SyncSafetyBackupException(IOException("fixture: no space left")),
        // Unit H: Q1's standing fallback, a full Drive and an answer the backend cannot read.
        GoogleAuthRefusedException("invalid_client", 401),
        DriveStorageFullException("saikou-sync/v1/x"),
        DriveMalformedResponseException("fixture: a file without a size"),
        IllegalStateException("fixture: a defect, not a transient failure"),
    )

    private val transientFailures: List<Throwable> = listOf(
        IOException("fixture: connection reset"),
        SocketTimeoutException("fixture"),
        UnknownHostException("dav.example.invalid"),
        WebDavHttpException(503, "PROPFIND", "saikou-sync/v1/"),
        WebDavWriteNotStoredException("saikou-sync/v1/x", "fixture: length 0"),
        WebDavUnreachableException("dav.example.invalid", ConnectException("fixture: refused")),
        WebDavUnreachableException("dav.example.invalid", SocketTimeoutException("fixture")),
        // Unit H: what the backend's own waits could not outlast, a status no rule accepts, and a
        // write that did not read back.
        GoogleUnavailableException(503),
        GoogleUnavailableException(null, IOException("fixture: dropped")),
        DriveUnavailableException(503, null),
        DriveUnavailableException(null, null, IOException("fixture: dropped")),
        DriveHttpException(400, "badRequest", "files.list"),
        DriveWriteNotStoredException("saikou-sync/v1/x", "fixture: no file has the id"),
    )

    @Test
    fun `each failure the owner has to act on ends the work without a retry`() = runTest {
        for (error in finalFailures) {
            coEvery { session.run() } throws error
            withClue(error) { build().doWork() shouldBe ListenableWorker.Result.failure() }
        }
    }

    @Test
    fun `any other I-O failure is retried`() = runTest {
        for (error in transientFailures) {
            coEvery { session.run() } throws error
            withClue(error) { build().doWork() shouldBe ListenableWorker.Result.retry() }
        }
    }

    @Test
    fun `a run turned away by the lock is a success with no work, and so is a finished run`() = runTest {
        coEvery { session.run() } returns null
        build().doWork() shouldBe ListenableWorker.Result.success()

        coEvery { session.run() } returns RESULT
        build().doWork() shouldBe ListenableWorker.Result.success()
    }

    /**
     * Fixes unit sync-notices: every finished run reaches the notices, a success as itself and a
     * failure with whether the owner is looking at it, which only Sync now's first attempt is; a run
     * the lock turned away is neither.
     */
    @Test
    fun `each run's outcome reaches the notices, user-started only on Sync now's first attempt`() = runTest {
        coEvery { session.run() } returns null
        build().doWork()
        coVerify(exactly = 0) { notices.syncSucceeded() }

        coEvery { session.run() } returns RESULT
        build().doWork()
        coVerify(exactly = 1) { notices.syncSucceeded() }

        val error = SyncSignedOutException("fixture: 401")
        coEvery { session.run() } throws error
        build().doWork()
        coVerify(exactly = 1) { notices.syncFailed(error, false) }
        build(userStarted = true).doWork()
        coVerify(exactly = 1) { notices.syncFailed(error, true) }
        // WorkManager's retry of a Sync now run comes later, in the background.
        build(userStarted = true, attempt = 1).doWork()
        coVerify(exactly = 2) { notices.syncFailed(error, false) }
    }

    @Test
    fun `a notice that fails changes no outcome`() = runTest {
        coEvery { notices.syncFailed(any(), any()) } throws IOException("fixture: the store refused")
        coEvery { notices.syncSucceeded() } throws IOException("fixture: the store refused")

        coEvery { session.run() } throws IOException("fixture: connection reset")
        build().doWork() shouldBe ListenableWorker.Result.retry()
        coEvery { session.run() } throws SyncSignedOutException("fixture: 401")
        build().doWork() shouldBe ListenableWorker.Result.failure()
        coEvery { session.run() } returns RESULT
        build().doWork() shouldBe ListenableWorker.Result.success()
    }

    @Test
    fun `a cancel propagates`() = runTest {
        coEvery { session.run() } throws CancellationException("fixture: stopped")

        shouldThrow<CancellationException> { build().doWork() }
    }

    private suspend fun withClue(error: Throwable, block: suspend () -> Unit) =
        io.kotest.assertions.withClue(error::class.simpleName) { block() }

    private fun build(userStarted: Boolean = false, attempt: Int = 0): SyncWorker = TestListenableWorkerBuilder.from(context, SyncWorker::class.java)
        .setTags(listOf(SyncScheduler.TAG))
        .setInputData(if (userStarted) workDataOf(SyncScheduler.KEY_USER_STARTED to true) else workDataOf())
        .setRunAttemptCount(attempt)
        .setForegroundUpdater(RefusingForegroundUpdater())
        .setWorkerFactory(
            object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    SyncWorker(appContext, workerParameters, session, mockk(relaxed = true), notices)
            },
        )
        .build()

    private companion object {
        val RESULT = SyncRunResult(
            segmentsApplied = 0, exportsApplied = 0, snapshotApplied = false, rowsWritten = 0, rowsSkipped = 0,
            stoppedAtGap = false, segmentsPushed = 0, exported = false, compacted = false, filesDeleted = 0,
            conflicts = emptyList(), before = SyncLibraryCounts(0, 0, 0, 0), after = SyncLibraryCounts(0, 0, 0, 0),
        )
    }

    /** What WorkManager raises when a background app asks for a foreground service on API 31 and above. */
    private class RefusingForegroundUpdater : ForegroundUpdater {
        override fun setForegroundAsync(context: Context, id: UUID, foregroundInfo: ForegroundInfo): ListenableFuture<Void> =
            throw IllegalStateException("fixture: a foreground service is not allowed from the background")
    }
}

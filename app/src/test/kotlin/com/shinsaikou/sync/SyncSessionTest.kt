package com.shinsaikou.sync

import com.hippo.unifile.UniFile
import com.shinsaikou.backup.export.AutomaticBackupFiles
import com.shinsaikou.backup.export.BackupExporter
import com.shinsaikou.backup.export.SyncBackupFile
import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.domain.sync.model.SyncLibraryCounts
import com.shinsaikou.sync.drive.DriveAccount
import com.shinsaikou.sync.drive.DriveSyncBackend
import com.shinsaikou.sync.format.NewerFormatException
import com.shinsaikou.sync.webdav.WebDavAccount
import com.shinsaikou.sync.webdav.WebDavHttpException
import com.shinsaikou.sync.webdav.WebDavSyncBackend
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * A run, sign-in and sign-out under the session's one lock (phase 3 unit G): the safety backup
 * before a backend's first pull, the configured backend or signed out, the run record, and a
 * sign-out that waits for a run in progress. The engine is a stand-in; `SyncEngineForgetTest` in
 * `:sync` runs the real one through a sign-out and a fresh join.
 */
class SyncSessionTest {

    private val values = mutableMapOf<String, Any>()
    private val syncPreferences = SyncPreferences(MapPreferenceStore(values))
    private val workPreferences = SyncWorkPreferences(MapPreferenceStore(values))
    private val record = SyncRunRecord(MapPreferenceStore(values))

    // The stand-in forgets what the real `SyncEngine.forgetBackend` does (`SyncEngineForgetTest`
    // covers that one), so a test can see whether this device's place on the backend survived.
    private val engine = mockk<SyncEngine> {
        coEvery { forgetBackend() } answers {
            syncPreferences.backendId.delete()
            syncPreferences.deviceId.delete()
            syncPreferences.pullCursors.delete()
            syncPreferences.lastPushedSeq.delete()
            syncPreferences.exportedBackend.delete()
        }
    }
    private val webDavAccount = mockk<WebDavAccount> {
        coEvery { signOut() } just Runs
    }
    private val driveAccount = mockk<DriveAccount> {
        coEvery { signOut() } just Runs
        coEvery { backend() } returns null
    }
    private val scheduler = mockk<SyncScheduler>(relaxed = true)
    private val notices = mockk<SyncNotices>(relaxed = true)

    private val exporter = mockk<BackupExporter> {
        coEvery { build(any()) } returns mockk(relaxed = true)
        coEvery { write(any(), any()) } returns 1L
    }
    private val backupFile = mockk<UniFile> {
        every { openOutputStream() } answers { ByteArrayOutputStream() }
        every { name } returns BACKUP_NAME
        every { delete() } returns true
    }
    private val files = mockk<AutomaticBackupFiles> {
        coEvery { createSync(any()) } returns SyncBackupFile(backupFile, existed = false)
        coEvery { pruneSync(any()) } just Runs
    }

    private val session = SyncSession(
        engine = engine,
        syncPreferences = syncPreferences,
        webDavAccount = webDavAccount,
        driveAccount = driveAccount,
        safetyBackup = SyncSafetyBackup(exporter, files, workPreferences),
        workPreferences = workPreferences,
        record = record,
        scheduler = scheduler,
        notices = notices,
    )

    private fun backend(id: String = BACKEND): WebDavSyncBackend = mockk { every { this@mockk.id } returns id }

    private fun signedIn(backend: WebDavSyncBackend = backend()): WebDavSyncBackend {
        syncPreferences.backendId.set(backend.id)
        coEvery { webDavAccount.backend() } returns backend
        return backend
    }

    private val result = SyncRunResult(
        segmentsApplied = 0,
        exportsApplied = 1,
        snapshotApplied = false,
        rowsWritten = 3,
        rowsSkipped = 0,
        stoppedAtGap = false,
        segmentsPushed = 0,
        exported = true,
        compacted = false,
        filesDeleted = 0,
        conflicts = emptyList(),
        before = SyncLibraryCounts(1, 1, 1, 1),
        after = SyncLibraryCounts(2, 1, 1, 1),
    )

    @Test
    fun `the safety backup is written once per backend, before the first pull`() = runTest {
        val backend = signedIn()
        coEvery { engine.run(backend) } returns result

        session.run() shouldBe result
        session.run() shouldBe result

        coVerify(exactly = 1) { exporter.write(any(), any()) }
        coVerify(exactly = 2) { engine.run(backend) }
        coVerifyOrder {
            exporter.build(any())
            files.createSync(any())
            exporter.write(any(), any())
            files.pruneSync(BACKUP_NAME)
            engine.run(backend)
            engine.run(backend)
        }
        workPreferences.safetyBackupBackend.get() shouldBe BACKEND
        workPreferences.safetyBackupFile.get() shouldBe BACKUP_NAME
    }

    @Test
    fun `a failing safety backup stops the run with nothing pulled, and the next run tries again`() = runTest {
        val backend = signedIn()
        val diskFull = IOException("fixture: no space left")
        coEvery { exporter.write(any(), any()) } throws diskFull

        val error = shouldThrow<SyncSafetyBackupException> { session.run() }

        // Coroutines' stack trace recovery may hand back a copy of the cause, so it is compared by value.
        error.cause.shouldBeInstanceOf<IOException>().message shouldBe diskFull.message
        coVerify(exactly = 0) { engine.run(any()) }
        verify(exactly = 1) { backupFile.delete() }
        workPreferences.safetyBackupBackend.get() shouldBe ""
        workPreferences.safetyBackupFile.get() shouldBe ""
        record.outcome() shouldBe SyncOutcome.Failure(ErrorKey.SYNC_SAFETY_BACKUP_FAILED, null)
        (record.lastAttemptAt.get() > 0L) shouldBe true

        coEvery { exporter.write(any(), any()) } returns 1L
        coEvery { engine.run(backend) } returns result
        session.run() shouldBe result
        coVerify(exactly = 2) { exporter.write(any(), any()) }
        workPreferences.safetyBackupBackend.get() shouldBe BACKEND
        record.outcome() shouldBe SyncOutcome.Success
    }

    @Test
    fun `with no backend, or a stored sign-in that is not the configured backend, a run is signed out`() = runTest {
        shouldThrow<SyncSignedOutException> { session.run() }
        record.outcome() shouldBe SyncOutcome.Failure(ErrorKey.SYNC_SIGNED_OUT, null)

        syncPreferences.backendId.set(BACKEND)
        coEvery { webDavAccount.backend() } returns null
        shouldThrow<SyncSignedOutException> { session.run() }

        coEvery { webDavAccount.backend() } returns backend("webdav-ffffffffffffffff")
        shouldThrow<SyncSignedOutException> { session.run() }

        coVerify(exactly = 0) { engine.run(any()) }
        coVerify(exactly = 0) { exporter.build(any()) }
    }

    @Test
    fun `a run records its attempt, its success and its report, and an engine failure by key`() = runTest {
        val backend = signedIn()
        coEvery { engine.run(backend) } returns result

        session.run()

        (record.lastAttemptAt.get() > 0L) shouldBe true
        (record.lastSuccessAt.get() >= record.lastAttemptAt.get()) shouldBe true
        record.reports.get().single().exported shouldBe true
        record.outcome() shouldBe SyncOutcome.Success

        val lastSuccess = record.lastSuccessAt.get()
        coEvery { engine.run(backend) } throws NewerFormatException(found = 2)
        shouldThrow<NewerFormatException> { session.run() }
        record.outcome() shouldBe SyncOutcome.Failure(ErrorKey.SYNC_NEWER_FORMAT, null)
        record.lastSuccessAt.get() shouldBe lastSuccess
        record.reports.get().size shouldBe 1
    }

    @Test
    fun `a run that finds another in progress returns at once and records nothing`() = runTest {
        val backend = signedIn()
        val release = CompletableDeferred<Unit>()
        coEvery { engine.run(backend) } coAnswers {
            release.await()
            result
        }

        val first = async { session.run() }
        advanceUntilIdle()
        session.run().shouldBeNull()
        record.lastOutcome.get() shouldBe ""

        release.complete(Unit)
        first.await() shouldBe result
        coVerify(exactly = 1) { engine.run(backend) }
        record.reports.get().size shouldBe 1
    }

    @Test
    fun `the run count is odd while a run holds the lock, and moves by two per run, a failed one too`() = runTest {
        val backend = signedIn()
        val release = CompletableDeferred<Unit>()
        coEvery { engine.run(backend) } coAnswers {
            release.await()
            result
        }
        session.runs shouldBe 0L

        val first = async { session.run() }
        advanceUntilIdle()
        session.runs shouldBe 1L
        // A run that finds the lock held does nothing, and is not counted.
        session.run().shouldBeNull()
        session.runs shouldBe 1L

        release.complete(Unit)
        first.await() shouldBe result
        session.runs shouldBe 2L

        coEvery { engine.run(backend) } throws NewerFormatException(found = 2)
        shouldThrow<NewerFormatException> { session.run() }
        session.runs shouldBe 4L
    }

    @Test
    fun `sign-out cancels both names, waits for the run in progress, then removes the secrets and forgets`() = runTest {
        val backend = signedIn()
        workPreferences.safetyBackupBackend.set(BACKEND)
        workPreferences.safetyBackupFile.set(BACKUP_NAME)
        val release = CompletableDeferred<Unit>()
        coEvery { engine.run(backend) } coAnswers {
            release.await()
            result
        }
        val run = async { session.run() }
        advanceUntilIdle()

        val signOut = launch { session.signOut() }
        advanceUntilIdle()
        verify(exactly = 1) { scheduler.cancel() }
        signOut.isCompleted shouldBe false
        coVerify(exactly = 0) { webDavAccount.signOut() }
        coVerify(exactly = 0) { driveAccount.signOut() }
        coVerify(exactly = 0) { engine.forgetBackend() }

        release.complete(Unit)
        run.await()
        signOut.join()
        // Both accounts delete their own secrets, whichever backend was configured (unit H).
        coVerifyOrder {
            engine.run(backend)
            webDavAccount.signOut()
            driveAccount.signOut()
            engine.forgetBackend()
        }
        workPreferences.safetyBackupBackend.get() shouldBe ""
        workPreferences.safetyBackupFile.get() shouldBe ""
    }

    @Test
    fun `a sign-in to another backend forgets the old one first, and the same one keeps its state`() = runTest {
        signedIn()
        workPreferences.safetyBackupBackend.set(BACKEND)

        session.signedIn(backend())
        coVerify(exactly = 0) { engine.forgetBackend() }
        verify(exactly = 0) { scheduler.cancel() }
        workPreferences.safetyBackupBackend.get() shouldBe BACKEND

        val other = backend("webdav-ffffffffffffffff")
        session.signedIn(other)
        coVerify(exactly = 1) { engine.forgetBackend() }
        // Both schedules are cancelled before the reset, as a sign-out cancels them.
        coVerifyOrder {
            scheduler.cancel()
            engine.forgetBackend()
        }
        syncPreferences.backendId.get() shouldBe other.id
        workPreferences.safetyBackupBackend.get() shouldBe ""
        // Each sign-in arms the schedule and asks for a first run.
        verify(exactly = 2) { scheduler.setup() }
        verify(exactly = 2) { scheduler.syncSoon(any()) }

        // The next run on the new backend writes its own safety backup.
        coEvery { webDavAccount.backend() } returns other
        coEvery { engine.run(other) } returns result
        session.run()
        coVerify(exactly = 1) { exporter.write(any(), any()) }
        workPreferences.safetyBackupBackend.get() shouldBe other.id
    }

    @Test
    fun `signing in again to the same backend keeps this device's place and clears the signed-out outcome`() = runTest {
        signedIn()
        syncPreferences.deviceId.set(DEVICE)
        syncPreferences.pullCursors.set(mapOf("another-device" to 5L))
        syncPreferences.lastPushedSeq.set(7L)
        syncPreferences.exportedBackend.set(BACKEND)
        workPreferences.safetyBackupBackend.set(BACKEND)
        workPreferences.safetyBackupFile.set(BACKUP_NAME)
        record.failed(SyncSignedOutException("fixture: 401"))

        session.signedIn(backend())

        coVerify(exactly = 0) { engine.forgetBackend() }
        verify(exactly = 0) { scheduler.cancel() }
        syncPreferences.backendId.get() shouldBe BACKEND
        syncPreferences.deviceId.get() shouldBe DEVICE
        syncPreferences.pullCursors.get() shouldBe mapOf("another-device" to 5L)
        syncPreferences.lastPushedSeq.get() shouldBe 7L
        syncPreferences.exportedBackend.get() shouldBe BACKEND
        workPreferences.safetyBackupFile.get() shouldBe BACKUP_NAME
        record.outcome().shouldBeNull()
        record.lastOutcomeDetail.get() shouldBe ""
        verify(exactly = 1) { scheduler.syncSoon(any()) }
    }

    @Test
    fun `a sign-in clears only a signed-out outcome, and any other stays`() = runTest {
        signedIn()
        record.failed(NewerFormatException(found = 2))
        session.signedIn(backend())
        record.outcome() shouldBe SyncOutcome.Failure(ErrorKey.SYNC_NEWER_FORMAT, null)

        record.failed(WebDavHttpException(503, "PROPFIND", ""))
        session.signedIn(backend())
        record.outcome() shouldBe SyncOutcome.Failure(ErrorKey.WEBDAV_HTTP, "503")

        record.succeeded(1L, result)
        session.signedIn(backend())
        record.outcome() shouldBe SyncOutcome.Success
    }

    @Test
    fun `a sign-in to another backend waits for the run in progress before it forgets`() = runTest {
        val backend = signedIn()
        syncPreferences.deviceId.set(DEVICE)
        val release = CompletableDeferred<Unit>()
        coEvery { engine.run(backend) } coAnswers {
            release.await()
            result
        }
        val run = async { session.run() }
        advanceUntilIdle()

        val other = backend("webdav-ffffffffffffffff")
        val signIn = launch { session.signedIn(other) }
        advanceUntilIdle()
        verify(exactly = 1) { scheduler.cancel() }
        signIn.isCompleted shouldBe false
        coVerify(exactly = 0) { engine.forgetBackend() }
        syncPreferences.deviceId.get() shouldBe DEVICE

        release.complete(Unit)
        run.await()
        signIn.join()
        coVerify(exactly = 1) { engine.forgetBackend() }
        syncPreferences.backendId.get() shouldBe other.id
        syncPreferences.deviceId.get() shouldBe ""
    }

    /**
     * The configured id's prefix picks the account (unit H): a `drive-` id runs the Drive account's
     * backend and never asks WebDAV, a `webdav-` id the reverse, each checked against the stored id,
     * and an id with neither prefix is signed out.
     */
    @Test
    fun `a run builds its backend from the account the configured id's prefix names`() = runTest {
        val drive = mockk<DriveSyncBackend> { every { id } returns DRIVE_BACKEND }
        syncPreferences.backendId.set(DRIVE_BACKEND)
        coEvery { driveAccount.backend() } returns drive
        coEvery { engine.run(drive) } returns result

        session.run() shouldBe result
        coVerify(exactly = 1) { engine.run(drive) }
        coVerify(exactly = 0) { webDavAccount.backend() }

        // A Drive sign-in of another client or account is another id, and is refused.
        coEvery { driveAccount.backend() } returns mockk<DriveSyncBackend> { every { id } returns "drive-ffffffffffffffff" }
        shouldThrow<SyncSignedOutException> { session.run() }
        coEvery { driveAccount.backend() } returns null
        shouldThrow<SyncSignedOutException> { session.run() }

        val webDav = signedIn()
        coEvery { engine.run(webDav) } returns result
        session.run() shouldBe result
        coVerify(exactly = 3) { driveAccount.backend() }

        syncPreferences.backendId.set("memory")
        shouldThrow<SyncSignedOutException> { session.run() }
        coVerify(exactly = 3) { driveAccount.backend() }
        coVerify(exactly = 1) { webDavAccount.backend() }
    }

    /**
     * Fixes unit sync-notices: the three moments the Drive expiry is stored, changed or deleted tell
     * the notices: every finished run (a refresh may have stored a new expiry), a sign-in, after the
     * backend is configured, and a sign-out, after the secrets are gone. A notice that fails changes
     * neither the run's outcome nor the sign-in's.
     */
    @Test
    fun `a run, a sign-in and a sign-out each tell the notices, and a failing notice changes nothing`() = runTest {
        val backend = signedIn()
        coEvery { engine.run(backend) } returns result
        session.run() shouldBe result
        coVerify(exactly = 1) { notices.reconcileExpiry() }

        coEvery { engine.run(backend) } throws WebDavHttpException(503, "PROPFIND", "saikou-sync/v1/")
        shouldThrow<WebDavHttpException> { session.run() }
        coVerify(exactly = 2) { notices.reconcileExpiry() }

        coEvery { notices.reconcileExpiry() } throws IOException("fixture: the store refused")
        coEvery { engine.run(backend) } returns result
        session.run() shouldBe result
        record.outcome() shouldBe SyncOutcome.Success

        coEvery { notices.signedIn() } throws IOException("fixture: the store refused")
        session.signedIn(backend)
        coVerifyOrder {
            notices.signedIn()
            scheduler.setup()
            scheduler.syncSoon(any())
        }
        syncPreferences.backendId.get() shouldBe BACKEND

        session.signOut()
        coVerifyOrder {
            webDavAccount.signOut()
            driveAccount.signOut()
            notices.signedOut()
        }
    }

    private companion object {
        const val DRIVE_BACKEND = "drive-0123456789abcdef"
        const val DEVICE = "0b6e1c2e-0000-4000-8000-000000000001"
        const val BACKEND = "webdav-0123456789abcdef"
        const val BACKUP_NAME = "com.shinsaikou_sync_2026-09-26_01-30.tachibk"
    }
}

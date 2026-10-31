package com.shinsaikou.sync

import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.sync.drive.DriveAccount
import com.shinsaikou.sync.drive.DriveSyncBackend
import com.shinsaikou.sync.webdav.WebDavAccount
import com.shinsaikou.sync.webdav.WebDavSyncBackend
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Clock

/**
 * One sync run, sign-in and sign-out, under one lock (phase 3 unit G). A run builds the configured
 * backend, writes the safety backup when one is due, runs the engine and records the outcome;
 * sign-out waits for a run in progress, then forgets the backend. Holding one lock across the backend
 * check and the engine's run is what keeps a run that started before a sign-out from pushing to the
 * server the owner just signed out of under a fresh device id.
 *
 * Changing the backend is a sign-out followed by a sign-in, so [forget] is the one reset: the
 * engine's [SyncEngine.forgetBackend] and this unit's safety backup state. The next sign-in is a new
 * device on its backend: a new id, the safety backup, a full pull and a full export.
 *
 * The Google sign-in's expiry is stored or changed only by a sign-in and by a refresh during a run,
 * and deleted by a sign-out, so those three tell [SyncNotices] (fixes unit sync-notices); a notice
 * change that fails is logged and changes nothing else.
 */
@SingleIn(AppScope::class)
@Inject
class SyncSession(
    private val engine: SyncEngine,
    private val syncPreferences: SyncPreferences,
    private val webDavAccount: WebDavAccount,
    private val driveAccount: DriveAccount,
    private val safetyBackup: SyncSafetyBackup,
    private val workPreferences: SyncWorkPreferences,
    private val record: SyncRunRecord,
    private val scheduler: SyncScheduler,
    private val notices: SyncNotices,
) {

    private val mutex = Mutex()
    private val runSequence = AtomicLong()

    /**
     * Moves by one when a run takes the lock and again when it lets go of it, so it is odd while a run
     * is in progress, whether or not WorkManager has reported it yet. A reader that finds it even, and
     * the same once its own work is done, knows no run through this session wrote the library
     * meanwhile: the library fingerprint in Sync settings reads it so (phase 3 unit SP, verifier
     * finding 2).
     */
    val runs: Long get() = runSequence.get()

    /**
     * One run: null, doing nothing and recording nothing, when another run or a sign-out holds the
     * lock (Q10). Throws [SyncSignedOutException] when no backend is configured or the stored sign-in
     * is not the configured backend, [SyncSafetyBackupException] when the backup before a first pull
     * fails, and whatever the engine throws; each is recorded as the last outcome first. A cancel
     * records no outcome and propagates. Either way the expiry notice is reconciled, since the run
     * may have refreshed the Drive grant.
     */
    suspend fun run(): SyncRunResult? {
        if (!mutex.tryLock()) return null
        runSequence.incrementAndGet()
        try {
            record.attempted(now())
            val result = try {
                val backend = backend()
                safetyBackup.ensure(backend.id, now())
                engine.run(backend)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                record.failed(e)
                quietly { notices.reconcileExpiry() }
                throw e
            }
            // Null only if a caller outside this class holds the engine's lock; that run records nothing here.
            result?.let { record.succeeded(now(), it) }
            quietly { notices.reconcileExpiry() }
            return result
        } finally {
            runSequence.incrementAndGet()
            mutex.unlock()
        }
    }

    /**
     * After a sign-in the backend answered: [backend] becomes the configured one and the schedule is
     * armed with a first run. A different backend already configured is forgotten first, as a
     * sign-out would: both schedules cancelled, a run in progress waited for, then the reset. The same
     * backend signed in again (Sync settings' Sign in again) keeps its device id, cursors and export
     * state. Either way a [ErrorKey.SYNC_SIGNED_OUT] outcome is cleared, since the sign-in it asked
     * for has happened; any other outcome stays, and so do the sign-in notices ([SyncNotices.signedIn]),
     * which also arms the expiry notice for this sign-in.
     */
    suspend fun signedIn(backend: SyncBackend) {
        val configured = syncPreferences.backendId.get()
        if (configured.isNotEmpty() && configured != backend.id) scheduler.cancel()
        mutex.withLock {
            val current = syncPreferences.backendId.get()
            if (current.isNotEmpty() && current != backend.id) forget()
            syncPreferences.backendId.set(backend.id)
            record.clearSignedOut()
        }
        quietly { notices.signedIn() }
        scheduler.setup()
        scheduler.syncSoon()
    }

    /**
     * Cancels both schedules, waits for a run in progress to stop, then removes both accounts' secrets
     * (each deletes only its own; Drive revokes its grant first when one is stored) and forgets the
     * backend. The remote files and the local library are untouched; the old device id's files stay
     * on the server. Every sync notice goes ([SyncNotices.signedOut]).
     */
    suspend fun signOut() {
        scheduler.cancel()
        mutex.withLock {
            webDavAccount.signOut()
            driveAccount.signOut()
            forget()
        }
        quietly { notices.signedOut() }
    }

    private suspend fun forget() {
        engine.forgetBackend()
        workPreferences.safetyBackupBackend.delete()
        workPreferences.safetyBackupFile.delete()
    }

    /**
     * The configured backend, from the account its id's prefix names (`drive-` or `webdav-`). A stored
     * sign-in whose id is not the configured one is refused rather than run, because the engine's
     * cursors belong to the configured backend.
     */
    private suspend fun backend(): SyncBackend {
        val id = syncPreferences.backendId.get()
        if (id.isEmpty()) throw SyncSignedOutException("No sync backend is configured")
        val stored = when {
            id.startsWith(DriveSyncBackend.ID_PREFIX) -> driveAccount.backend()
            id.startsWith(WebDavSyncBackend.ID_PREFIX) -> webDavAccount.backend()
            else -> null
        }
        return stored?.takeIf { it.id == id }
            ?: throw SyncSignedOutException("The stored sign-in is not the configured backend")
    }

    /** Runs a notice change; its failure is logged and goes no further. */
    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "The sync notice failed" }
        }
    }

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()
}

package com.shinsaikou.sync

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.sync.drive.DriveAccount
import com.shinsaikou.sync.drive.DriveSyncBackend
import com.shinsaikou.sync.webdav.WebDavAccount
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * What sync tells the owner outside Sync settings (fixes unit sync-notices, F12), each notice
 * opening Sync settings through [SyncNotifier]:
 *
 * - **The Google sign-in's coming expiry.** While Google Drive is the configured backend and its
 *   sign-in has a stored expiry ([DriveAccount.refreshTokenExpiresAt], which a Google project in
 *   Testing gives and one in production does not), one notice [NOTICE_BEFORE] it, through
 *   [SyncExpiryNoticeWorker] under [WORK_NAME_EXPIRY]. [reconcileExpiry] arms it whenever the expiry
 *   may have been stored or changed (a sign-in, a run, which may refresh the grant, and app start)
 *   and cancels it, with a notice already shown, once there is no expiry or no Drive backend. An
 *   expiry less than [NOTICE_BEFORE] away is noticed at once.
 * - **A background sync stopped for a reason the owner can act on** ([Reason]), decided on the
 *   failure's [ErrorKey] and the configured backend, never on a message. A failure no action fixes
 *   (no network, a server error) posts nothing, since the worker retries it, and so does a run the
 *   owner started with Sync now, whose error Sync settings shows. Each reason posts once until a
 *   run succeeds ([notified]); a success removes every one shown.
 *
 * Sign-out removes everything ([signedOut]); a sign-in removes the sign-in notices, whose action it
 * was ([signedIn]). The two keys are app state, never exported.
 */
@SingleIn(AppScope::class)
@Inject
class SyncNotices(
    private val workManager: Lazy<WorkManager>,
    private val syncPreferences: SyncPreferences,
    private val driveAccount: DriveAccount,
    private val webDavAccount: WebDavAccount,
    private val notifier: SyncNotifier,
    private val clock: Clock,
    preferenceStore: PreferenceStore,
) {

    /** Why a background sync stopped, as a notice says it; each has its own notification. */
    enum class Reason {
        /** The configured backend's stored sign-in is gone from this device. */
        SIGN_IN_LOST,

        /** Google no longer accepts the stored Drive sign-in: expired, revoked or lacking its scope. */
        GOOGLE_SIGN_IN_REFUSED,

        /** The WebDAV server refused the saved password. */
        PASSWORD_REFUSED,

        /** Google Drive refused a write for want of space. */
        DRIVE_FULL,
    }

    /** One notice change at a time: a run's outcome and a sign-in or sign-out may land together. */
    private val mutex = Mutex()

    /** The expiry, in epoch milliseconds, the armed notice is for, or 0 while none is armed. */
    val expiryNoticeFor: Preference<Long> =
        preferenceStore.getLong(Preference.appStateKey("sync_expiry_notice_for"), 0L)

    /** The reasons posted since the last success; a reason in it is not posted again. */
    val notified: Preference<Set<Reason>> = preferenceStore.getObjectFromString(
        key = Preference.appStateKey("sync_failure_notices"),
        defaultValue = emptySet(),
        serializer = { reasons -> reasons.joinToString(",") { it.name } },
        deserializer = { value ->
            value.split(',').mapNotNull { name -> Reason.entries.firstOrNull { it.name == name } }.toSet()
        },
    )

    /**
     * Arms, moves or cancels the expiry notice for the stored expiry, read through the configured
     * backend: none unless it is Drive. An expiry within [SAME_EXPIRY] of the armed one is the same
     * expiry, since Google's `refresh_token_expires_in` counts down from each answer and lands a
     * second or so apart; anything further is a new sign-in, whose shown notice is removed.
     */
    suspend fun reconcileExpiry() {
        mutex.withLock { reconcileLocked() }
    }

    /**
     * [SyncExpiryNoticeWorker]'s run: the notice, when the stored expiry is still ahead and within
     * [NOTICE_BEFORE] (give or take [SAME_EXPIRY]). A sign-in since, or an expiry already past, posts
     * nothing: the first is rearmed by [reconcileExpiry], and the second is the next run's
     * [Reason.GOOGLE_SIGN_IN_REFUSED].
     */
    suspend fun expiryDue() {
        mutex.withLock {
            val expiresAt = storedExpiry() ?: return
            val left = expiresAt - now()
            if (left <= 0L || left > (NOTICE_BEFORE + SAME_EXPIRY).inWholeMilliseconds) return
            notifier.postExpiry(expiresAt)
        }
    }

    /**
     * A run ended with [error]: the notice for its [Reason], unless the owner started the run
     * ([userStarted]), the failure has no reason, or that reason was posted since the last success.
     */
    suspend fun syncFailed(error: Throwable, userStarted: Boolean) {
        if (userStarted) return
        mutex.withLock {
            val reason = reasonFor(ErrorKey.of(error)) ?: return
            val shown = notified.get()
            if (reason in shown) return
            notifier.postFailure(reason)
            notified.set(shown + reason)
        }
    }

    /** A run succeeded: every failure notice shown since the last success goes. */
    suspend fun syncSucceeded() {
        mutex.withLock { clear(Reason.entries) }
    }

    /** The owner signed in: the sign-in notices go, since this was their action, and the expiry is read again. */
    suspend fun signedIn() {
        mutex.withLock { clear(SIGN_IN_REASONS) }
        reconcileExpiry()
    }

    /** The owner signed out: the expiry notice and every failure notice go. */
    suspend fun signedOut() {
        mutex.withLock {
            cancelExpiry()
            clear(Reason.entries)
        }
    }

    /** [reconcileExpiry]'s work; the caller holds [mutex]. */
    private suspend fun reconcileLocked() {
        val expiresAt = storedExpiry()
        val armed = expiryNoticeFor.get()
        if (expiresAt == null) {
            if (armed != 0L) cancelExpiry()
            return
        }
        if (armed != 0L && abs(expiresAt - armed) < SAME_EXPIRY.inWholeMilliseconds) return
        if (armed != 0L) notifier.cancel(SyncNotifier.ID_EXPIRY)
        val delay = (expiresAt - NOTICE_BEFORE.inWholeMilliseconds - now()).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<SyncExpiryNoticeWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        workManager.value.enqueueUniqueWork(WORK_NAME_EXPIRY, ExistingWorkPolicy.REPLACE, request)
        expiryNoticeFor.set(expiresAt)
    }

    /** Drive's stored expiry while Drive is the configured backend, else null. */
    private suspend fun storedExpiry(): Long? =
        if (syncPreferences.backendId.get().startsWith(DriveSyncBackend.ID_PREFIX)) driveAccount.refreshTokenExpiresAt() else null

    /**
     * The reason a failure keyed [key] has, as Sync settings reads it (`SyncSettingsViewModel.State.signInAgain`):
     * signed out with the stored sign-in gone is [Reason.SIGN_IN_LOST], with it still stored the
     * backend's refusal; a full Drive is [Reason.DRIVE_FULL]. Nothing while no backend is
     * configured, since the owner signed out on purpose.
     */
    private suspend fun reasonFor(key: ErrorKey): Reason? {
        val backendId = syncPreferences.backendId.get()
        if (backendId.isEmpty()) return null
        val drive = backendId.startsWith(DriveSyncBackend.ID_PREFIX)
        return when (key) {
            ErrorKey.DRIVE_STORAGE_FULL -> Reason.DRIVE_FULL
            ErrorKey.SYNC_SIGNED_OUT -> when {
                drive -> if (driveAccount.signedInAs() == null) Reason.SIGN_IN_LOST else Reason.GOOGLE_SIGN_IN_REFUSED
                webDavAccount.signedInAs() == null -> Reason.SIGN_IN_LOST
                else -> Reason.PASSWORD_REFUSED
            }
            else -> null
        }
    }

    private fun cancelExpiry() {
        workManager.value.cancelUniqueWork(WORK_NAME_EXPIRY)
        notifier.cancel(SyncNotifier.ID_EXPIRY)
        expiryNoticeFor.delete()
    }

    /** Removes the shown notices among [reasons]; the caller holds [mutex]. */
    private fun clear(reasons: Collection<Reason>) {
        val shown = notified.get()
        val gone = shown.intersect(reasons.toSet())
        if (gone.isEmpty()) return
        gone.forEach(notifier::cancelFailure)
        val left = shown - gone
        if (left.isEmpty()) notified.delete() else notified.set(left)
    }

    private fun now(): Long = clock.now().toEpochMilliseconds()

    companion object {
        const val WORK_NAME_EXPIRY = "sync-expiry-notice"

        /** How long before the expiry the notice comes. */
        val NOTICE_BEFORE: Duration = 1.days

        /** How far apart two stored expiries may be and still be one sign-in's. */
        val SAME_EXPIRY: Duration = 1.hours

        private val SIGN_IN_REASONS = listOf(Reason.SIGN_IN_LOST, Reason.GOOGLE_SIGN_IN_REFUSED, Reason.PASSWORD_REFUSED)
    }
}

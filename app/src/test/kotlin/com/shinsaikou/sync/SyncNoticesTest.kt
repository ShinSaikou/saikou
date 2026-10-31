package com.shinsaikou.sync

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.sync.drive.DriveAccount
import com.shinsaikou.sync.drive.DriveAvailability
import com.shinsaikou.sync.drive.DriveBackoff
import com.shinsaikou.sync.drive.DriveStorageFullException
import com.shinsaikou.sync.drive.DriveUnavailableException
import com.shinsaikou.sync.google.GoogleInvalidGrantException
import com.shinsaikou.sync.google.GoogleUnavailableException
import com.shinsaikou.sync.webdav.WebDavAccount
import com.shinsaikou.sync.webdav.WebDavHttpException
import com.shinsaikou.sync.webdav.WebDavSignIn
import com.shinsaikou.sync.webdav.WebDavUnreachableException
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Sync's notices (fixes unit sync-notices, F12): the Google sign-in's expiry notice armed, moved and
 * cancelled through WorkManager, and posted only while the expiry is a day or less ahead; and a
 * background sync's failure noticed once per actionable reason until a success, never for a run the
 * owner started or a failure the worker retries. The Drive sign-in is the real [DriveAccount] over an
 * in-memory secret store, so the expiry is read where the app reads it; the clock is fixed, and
 * nothing here reaches Google.
 */
class SyncNoticesTest {

    private val values = mutableMapOf<String, Any>()
    private val syncPreferences = SyncPreferences(MapPreferenceStore(values))

    private var now: Instant = Instant.fromEpochMilliseconds(1_800_000_000_000L)
    private val clock = object : Clock {
        override fun now(): Instant = now
    }

    private val secrets = object : SecretStore {
        val stored = mutableMapOf<String, String>()
        override fun get(name: String): String? = stored[name]
        override fun put(name: String, value: String) {
            stored[name] = value
        }
        override fun delete(name: String) {
            stored.remove(name)
        }
    }
    private val dispatcher = StandardTestDispatcher()
    private val driveAccount = DriveAccount(
        secrets = secrets,
        oauthClient = { error("fixture: nothing here reaches Google") },
        httpClient = mockk<SyncHttpClient>(),
        availability = mockk<DriveAvailability> { every { offered } returns true },
        clock = clock,
        backoff = DriveBackoff(emptyList()) {},
        dispatchers = Dispatchers(io = dispatcher, default = dispatcher, main = dispatcher),
    )
    private val webDavAccount = mockk<WebDavAccount> {
        coEvery { signedInAs() } returns null
    }
    private val workManager = mockk<WorkManager>(relaxed = true)
    private val notifier = mockk<SyncNotifier>(relaxed = true)

    private val notices = SyncNotices(
        workManager = lazyOf(workManager),
        syncPreferences = syncPreferences,
        driveAccount = driveAccount,
        webDavAccount = webDavAccount,
        notifier = notifier,
        clock = clock,
        preferenceStore = MapPreferenceStore(values),
    )

    private fun driveConfigured(email: String? = "reader@example.invalid") {
        syncPreferences.backendId.set("drive-0123456789abcdef")
        if (email == null) secrets.stored.remove(DriveAccount.EMAIL) else secrets.stored[DriveAccount.EMAIL] = email
    }

    private fun webDavConfigured(signIn: WebDavSignIn?) {
        syncPreferences.backendId.set("webdav-0123456789abcdef")
        coEvery { webDavAccount.signedInAs() } returns signIn
    }

    private fun storeExpiry(after: Duration?) {
        if (after == null) {
            secrets.stored.remove(DriveAccount.REFRESH_TOKEN_EXPIRES_AT)
        } else {
            secrets.stored[DriveAccount.REFRESH_TOKEN_EXPIRES_AT] = (now + after).toEpochMilliseconds().toString()
        }
    }

    /** The expiry notice requests handed to WorkManager, in order. */
    private val requests = mutableListOf<OneTimeWorkRequest>()

    init {
        val request = slot<OneTimeWorkRequest>()
        every {
            workManager.enqueueUniqueWork(SyncNotices.WORK_NAME_EXPIRY, ExistingWorkPolicy.REPLACE, capture(request))
        } answers {
            requests += request.captured
            mockk(relaxed = true)
        }
    }

    private fun OneTimeWorkRequest.delay(): Duration = workSpec.initialDelay.milliseconds

    @Test
    fun `a stored expiry arms one notice a day before it, through the expiry worker`() = runTest(dispatcher) {
        driveConfigured()
        storeExpiry(7.days)

        notices.reconcileExpiry()

        requests.size shouldBe 1
        requests.single().workSpec.workerClassName shouldBe SyncExpiryNoticeWorker::class.java.name
        requests.single().delay() shouldBe 6.days
        notices.expiryNoticeFor.get() shouldBe (now + 7.days).toEpochMilliseconds()
        verify(exactly = 0) { workManager.cancelUniqueWork(any()) }
    }

    @Test
    fun `the same expiry is not armed again, and a new one moves the notice and removes one shown`() = runTest(dispatcher) {
        driveConfigured()
        storeExpiry(7.days)
        notices.reconcileExpiry()

        // A refresh's answer counts down from its own moment: a second apart is the same sign-in.
        now += 1.days
        storeExpiry(6.days + 1.seconds)
        notices.reconcileExpiry()
        requests.size shouldBe 1
        verify(exactly = 0) { notifier.cancel(SyncNotifier.ID_EXPIRY) }

        // Signing in again gives a new expiry seven days from now.
        storeExpiry(7.days)
        notices.reconcileExpiry()
        requests.size shouldBe 2
        requests.last().delay() shouldBe 6.days
        notices.expiryNoticeFor.get() shouldBe (now + 7.days).toEpochMilliseconds()
        verify(exactly = 1) { notifier.cancel(SyncNotifier.ID_EXPIRY) }
    }

    @Test
    fun `a deleted expiry, a sign-out or a backend that is not Drive cancels the notice`() = runTest(dispatcher) {
        driveConfigured()
        storeExpiry(7.days)
        notices.reconcileExpiry()

        storeExpiry(null)
        notices.reconcileExpiry()
        verify(exactly = 1) { workManager.cancelUniqueWork(SyncNotices.WORK_NAME_EXPIRY) }
        verify(exactly = 1) { notifier.cancel(SyncNotifier.ID_EXPIRY) }
        notices.expiryNoticeFor.isSet() shouldBe false

        storeExpiry(7.days)
        notices.reconcileExpiry()
        notices.signedOut()
        verify(exactly = 2) { workManager.cancelUniqueWork(SyncNotices.WORK_NAME_EXPIRY) }
        notices.expiryNoticeFor.isSet() shouldBe false

        notices.reconcileExpiry()
        webDavConfigured(WebDavSignIn("https://dav.example.invalid/", "reader"))
        notices.reconcileExpiry()
        verify(exactly = 3) { workManager.cancelUniqueWork(SyncNotices.WORK_NAME_EXPIRY) }
        requests.size shouldBe 3
    }

    @Test
    fun `an expiry less than a day away is noticed at once`() = runTest(dispatcher) {
        driveConfigured()
        storeExpiry(3.hours)

        notices.reconcileExpiry()

        requests.single().delay() shouldBe Duration.ZERO
    }

    @Test
    fun `no expiry arms nothing and cancels nothing`() = runTest(dispatcher) {
        driveConfigured()
        notices.reconcileExpiry()

        requests.shouldBeEmpty()
        verify(exactly = 0) { workManager.cancelUniqueWork(any()) }
        notices.expiryDue()
        verify(exactly = 0) { notifier.postExpiry(any()) }
    }

    /**
     * The Google project went to In production on 2026-10-24: a sign-in since then stores no expiry
     * (DriveAccount deletes it), so the notice armed for the older sign-in goes, and the worker's run,
     * should WorkManager still start it, posts nothing at any time.
     */
    @Test
    fun `a sign-in that stores no expiry cancels the armed notice, and nothing posts it later`() = runTest(dispatcher) {
        driveConfigured()
        storeExpiry(7.days)
        notices.reconcileExpiry()
        requests.size shouldBe 1

        storeExpiry(null)
        notices.signedIn()

        verify(exactly = 1) { workManager.cancelUniqueWork(SyncNotices.WORK_NAME_EXPIRY) }
        verify(exactly = 1) { notifier.cancel(SyncNotifier.ID_EXPIRY) }
        notices.expiryNoticeFor.isSet() shouldBe false
        requests.size shouldBe 1
        for (later in listOf(1.minutes, 6.days, 6.days + 12.hours, 7.days, 30.days)) {
            now += later
            notices.reconcileExpiry()
            notices.expiryDue()
        }
        requests.size shouldBe 1
        verify(exactly = 0) { notifier.postExpiry(any()) }
    }

    @Test
    fun `the worker's run posts only while the expiry is still ahead and within the day`() = runTest(dispatcher) {
        driveConfigured()
        storeExpiry(1.days)
        notices.expiryDue()
        verify(exactly = 1) { notifier.postExpiry((now + 1.days).toEpochMilliseconds()) }

        clearMocks(notifier)
        // A sign-in since the notice was armed moved the expiry: the rearmed notice posts later.
        storeExpiry(7.days)
        notices.expiryDue()
        // Already past: the next run's refusal is the notice.
        storeExpiry(-1.minutes)
        notices.expiryDue()
        verify(exactly = 0) { notifier.postExpiry(any()) }
    }

    @Test
    fun `each reason the owner can act on posts its notice`() = runTest(dispatcher) {
        val cases = listOf(
            Triple("Drive refused", { driveConfigured() }, SyncSignedOutException("fixture", GoogleInvalidGrantException("refresh_token"))),
            Triple("Drive gone", { driveConfigured(email = null) }, SyncSignedOutException("fixture: no sign-in stored")),
            Triple("WebDAV password", { webDavConfigured(WebDavSignIn("https://dav.example.invalid/", "reader")) }, SyncSignedOutException("fixture: 401")),
            Triple("WebDAV gone", { webDavConfigured(null) }, SyncSignedOutException("fixture: no sign-in stored")),
            Triple("Drive full", { driveConfigured() }, DriveStorageFullException("saikou-sync/v1/x")),
        )
        val expected = listOf(
            SyncNotices.Reason.GOOGLE_SIGN_IN_REFUSED,
            SyncNotices.Reason.SIGN_IN_LOST,
            SyncNotices.Reason.PASSWORD_REFUSED,
            SyncNotices.Reason.SIGN_IN_LOST,
            SyncNotices.Reason.DRIVE_FULL,
        )
        for ((case, reason) in cases.zip(expected)) {
            val (name, configure, error) = case
            withClue(name) {
                notices.signedOut()
                clearMocks(notifier)
                configure()
                notices.syncFailed(error, userStarted = false)
                verify(exactly = 1) { notifier.postFailure(reason) }
                verify(exactly = 1) { notifier.postFailure(any()) }
            }
        }
    }

    @Test
    fun `a failure no action fixes posts nothing, and neither does one with no backend configured`() = runTest(dispatcher) {
        val retried = listOf(
            IOException("fixture: connection reset"),
            UnknownHostException("dav.example.invalid"),
            WebDavHttpException(503, "PROPFIND", "saikou-sync/v1/"),
            WebDavUnreachableException("dav.example.invalid", ConnectException("fixture: refused")),
            GoogleUnavailableException(503),
            DriveUnavailableException(503, null),
        )
        driveConfigured()
        for (error in retried) notices.syncFailed(error, userStarted = false)
        webDavConfigured(WebDavSignIn("https://dav.example.invalid/", "reader"))
        for (error in retried) notices.syncFailed(error, userStarted = false)

        syncPreferences.backendId.delete()
        notices.syncFailed(SyncSignedOutException("No sync backend is configured"), userStarted = false)

        verify(exactly = 0) { notifier.postFailure(any()) }
        notices.notified.get().shouldBeEmpty()
    }

    @Test
    fun `a reason posts once until a success, which removes every notice shown`() = runTest(dispatcher) {
        driveConfigured()
        val refused = SyncSignedOutException("fixture: 401")
        notices.syncFailed(refused, userStarted = false)
        notices.syncFailed(refused, userStarted = false)
        notices.syncFailed(DriveStorageFullException("saikou-sync/v1/x"), userStarted = false)
        verify(exactly = 1) { notifier.postFailure(SyncNotices.Reason.GOOGLE_SIGN_IN_REFUSED) }
        verify(exactly = 1) { notifier.postFailure(SyncNotices.Reason.DRIVE_FULL) }

        notices.syncSucceeded()
        verify(exactly = 1) { notifier.cancelFailure(SyncNotices.Reason.GOOGLE_SIGN_IN_REFUSED) }
        verify(exactly = 1) { notifier.cancelFailure(SyncNotices.Reason.DRIVE_FULL) }
        notices.notified.isSet() shouldBe false

        notices.syncFailed(refused, userStarted = false)
        verify(exactly = 2) { notifier.postFailure(SyncNotices.Reason.GOOGLE_SIGN_IN_REFUSED) }
    }

    @Test
    fun `a run the owner started posts nothing`() = runTest(dispatcher) {
        driveConfigured()
        notices.syncFailed(SyncSignedOutException("fixture: 401"), userStarted = true)
        notices.syncFailed(DriveStorageFullException("saikou-sync/v1/x"), userStarted = true)

        verify(exactly = 0) { notifier.postFailure(any()) }
        notices.notified.get().shouldBeEmpty()
    }

    @Test
    fun `a sign-in removes the sign-in notices and keeps a full Drive's`() = runTest(dispatcher) {
        driveConfigured()
        notices.syncFailed(SyncSignedOutException("fixture: 401"), userStarted = false)
        notices.syncFailed(DriveStorageFullException("saikou-sync/v1/x"), userStarted = false)

        notices.signedIn()

        verify(exactly = 1) { notifier.cancelFailure(SyncNotices.Reason.GOOGLE_SIGN_IN_REFUSED) }
        verify(exactly = 0) { notifier.cancelFailure(SyncNotices.Reason.DRIVE_FULL) }
        notices.notified.get().toList() shouldContainExactly listOf(SyncNotices.Reason.DRIVE_FULL)
    }
}

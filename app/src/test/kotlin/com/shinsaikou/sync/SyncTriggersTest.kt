package com.shinsaikou.sync

import com.shinsaikou.core.common.coroutines.Dispatchers
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Q10's automatic triggers: app start after 15 minutes, unpushed rows at the move to the background, the reader closing. */
class SyncTriggersTest {

    private val values = mutableMapOf<String, Any>()
    private val syncPreferences = SyncPreferences(MapPreferenceStore(values))
    private val record = SyncRunRecord(MapPreferenceStore(values))
    private val scheduler = mockk<SyncScheduler>(relaxed = true)
    private val engine = mockk<SyncEngine>()
    private val notices = mockk<SyncNotices>(relaxed = true)
    private val dispatcher = StandardTestDispatcher()
    private val triggers = SyncTriggers(
        scheduler = lazyOf(scheduler),
        engine = lazyOf(engine),
        notices = lazyOf(notices),
        syncPreferences = syncPreferences,
        record = record,
        appScope = TestScope(dispatcher),
        dispatchers = Dispatchers(io = dispatcher, default = dispatcher, main = dispatcher),
    )

    private fun signIn() = syncPreferences.backendId.set("webdav-0123456789abcdef")

    @Test
    fun `app start arms the schedule, and asks for a run only when the last success is more than 15 minutes old`() {
        val now = 100.minutes.inWholeMilliseconds
        triggers.onAppStart(now)
        verify(exactly = 1) { scheduler.setup() }
        verify(exactly = 0) { scheduler.syncSoon(any()) }

        signIn()
        record.lastSuccessAt.set(now - 15.minutes.inWholeMilliseconds)
        triggers.onAppStart(now)
        verify(exactly = 0) { scheduler.syncSoon(any()) }

        record.lastSuccessAt.set(now - 15.minutes.inWholeMilliseconds - 1)
        triggers.onAppStart(now)
        verify(exactly = 1) { scheduler.syncSoon(Duration.ZERO) }

        // Never synced: the last success is 0.
        record.lastSuccessAt.delete()
        triggers.onAppStart(now)
        verify(exactly = 2) { scheduler.syncSoon(Duration.ZERO) }
        verify(exactly = 4) { scheduler.setup() }
    }

    /** Fixes unit sync-notices: an expiry stored before notices existed is armed at the first start after the upgrade. */
    @Test
    fun `app start reconciles the expiry notice off the caller's thread`() {
        triggers.onAppStart(100.minutes.inWholeMilliseconds)
        coVerify(exactly = 0) { notices.reconcileExpiry() }

        dispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 1) { notices.reconcileExpiry() }
    }

    @Test
    fun `the move to the background asks for a run only while the log holds rows after the pushed seq`() = runTest {
        coEvery { engine.hasUnpushedChanges() } returns true
        triggers.onBackground()
        // Signed out: the log is not even read.
        coVerify(exactly = 0) { engine.hasUnpushedChanges() }

        signIn()
        coEvery { engine.hasUnpushedChanges() } returns false
        triggers.onBackground()
        verify(exactly = 0) { scheduler.syncSoon(any()) }

        coEvery { engine.hasUnpushedChanges() } returns true
        triggers.onBackground()
        verify(exactly = 1) { scheduler.syncSoon(Duration.ZERO) }
    }

    @Test
    fun `the reader closing asks for a run 30 seconds later, and nothing while signed out`() {
        triggers.onReaderClosed()
        verify(exactly = 0) { scheduler.syncSoon(any()) }

        signIn()
        triggers.onReaderClosed()
        verify(exactly = 1) { scheduler.syncSoon(30.seconds) }
        SyncScheduler.READER_CLOSED_DELAY shouldBe 30.seconds
    }
}

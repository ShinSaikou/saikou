package com.shinsaikou.tracking

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * A new sign-in removes the "sign in again" notice and asks for the worker when the tracker has
 * pushes kept queued, and only then; a scheduler that fails leaves the sign-in standing.
 */
class ResumeQueuedPushesTest {

    private val preferences = MapPreferenceStore()
    private val store = DelayedTrackingStore(preferences, preferences::write)
    private val notifier = FakeSignInNotifier()
    private var scheduled = 0
    private var schedulerFailure: Exception? = null
    private val resume = ResumeQueuedPushes(
        store,
        DelayedTrackingScheduler {
            schedulerFailure?.let { throw it }
            scheduled++
        },
        notifier,
    )

    @Test
    fun `a sign-in with pushes kept for the tracker asks for the worker and removes the notice`() = runTest {
        store.add(MANGA_ID, 1, 1010, 7.0)

        resume.await(FakeTracker(1))

        scheduled shouldBe 1
        notifier.signedIn shouldBe listOf(1L)
    }

    @Test
    fun `a sign-in with nothing kept for the tracker asks for no worker`() = runTest {
        store.add(MANGA_ID, 2, 1010, 7.0)

        resume.await(FakeTracker(1))

        scheduled shouldBe 0
        notifier.signedIn shouldBe listOf(1L)
    }

    @Test
    fun `a scheduler that fails is logged, not thrown`() = runTest {
        store.add(MANGA_ID, 1, 1010, 7.0)
        schedulerFailure = IOException("fixture: WorkManager unavailable")

        resume.await(FakeTracker(1))

        scheduled shouldBe 0
    }
}

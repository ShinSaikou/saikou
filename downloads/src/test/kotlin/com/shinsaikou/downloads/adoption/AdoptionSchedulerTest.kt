package com.shinsaikou.downloads.adoption

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.common.util.concurrent.ListenableFuture
import com.shinsaikou.domain.preferences.StoragePreferences
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

/**
 * The scheduler, not the picker, takes the persisted read grant on a picked folder, and only for a
 * run it starts: the worker gives the grant back when a run ends, so a grant taken for a start that
 * was refused would never be given back, and persisted grants are capped per app.
 */
class AdoptionSchedulerTest {

    private val workManager = mockk<WorkManager>(relaxed = true)
    private val resolver = mockk<ContentResolver>(relaxed = true)
    private val context = mockk<Context> { every { contentResolver } returns resolver }
    private val storagePreferences = StoragePreferences(InMemoryPreferenceStore())
    private val scheduler = AdoptionScheduler(context, storagePreferences)
    private val picked = mockk<Uri>()

    @Volatile
    private var existing = emptyList<WorkInfo>()

    @BeforeEach
    fun setUp() {
        mockkObject(WorkManager.Companion)
        mockkStatic(Uri::class)
        every { WorkManager.getInstance(any<Context>()) } returns workManager
        every { Uri.parse(PICKED) } returns picked
        every { workManager.getWorkInfosForUniqueWork(AdoptionWorker.WORK_NAME) } answers {
            mockk<ListenableFuture<List<WorkInfo>>> { every { get() } returns existing }
        }
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(WorkManager.Companion)
        unmockkStatic(Uri::class)
    }

    @Test
    fun `a start takes the read grant on the picked folder before the run is enqueued`() = runTest {
        scheduler.start(PICKED) shouldBe true

        verifyOrder {
            resolver.takePersistableUriPermission(picked, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            workManager.enqueueUniqueWork(AdoptionWorker.WORK_NAME, ExistingWorkPolicy.KEEP, any<OneTimeWorkRequest>())
        }
        storagePreferences.adoptionTree.get() shouldBe PICKED
    }

    @Test
    fun `a provider that refuses to persist the grant still gets its run`() = runTest {
        every { resolver.takePersistableUriPermission(any(), any()) } throws SecurityException("fixture: refused")

        scheduler.start(PICKED) shouldBe true

        verify(exactly = 1) {
            workManager.enqueueUniqueWork(AdoptionWorker.WORK_NAME, ExistingWorkPolicy.KEEP, any<OneTimeWorkRequest>())
        }
    }

    @Test
    fun `a start refused for a running run takes no grant and leaves that run's tree and grant alone`() = runTest {
        storagePreferences.adoptionTree.set(RUNNING)
        existing = listOf(mockk { every { state } returns WorkInfo.State.RUNNING })

        scheduler.start(PICKED) shouldBe false

        verify(exactly = 0) { resolver.takePersistableUriPermission(any(), any()) }
        verify(exactly = 0) { resolver.releasePersistableUriPermission(any(), any()) }
        verify(exactly = 0) { workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) }
        storagePreferences.adoptionTree.get() shouldBe RUNNING
    }

    @Test
    fun `two starts at once start one run and take one grant`() = runTest {
        every { workManager.enqueueUniqueWork(any<String>(), any(), any<OneTimeWorkRequest>()) } answers {
            existing = listOf(mockk { every { state } returns WorkInfo.State.ENQUEUED })
            mockk(relaxed = true)
        }

        val started = List(2) { async { scheduler.start(PICKED) } }.awaitAll()

        started.count { it } shouldBe 1
        verify(exactly = 1) { resolver.takePersistableUriPermission(picked, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        verify(exactly = 1) { workManager.enqueueUniqueWork(any<String>(), any(), any<OneTimeWorkRequest>()) }
    }

    private companion object {
        const val PICKED = "content://fixture/tree/primary%3APicked"
        const val RUNNING = "content://fixture/tree/primary%3ARunning"
    }
}

package com.shinsaikou.tracking

import com.shinsaikou.domain.merged.interactor.GetMergedGroup
import com.shinsaikou.domain.merged.model.MergedGroup
import com.shinsaikou.domain.merged.model.MergedMember
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** A merged entry's tracks live on its main entry, so a member's finished chapter records against that row. */
class TrackProgressRecorderMergedTest {

    private val preferences = MapPreferenceStore()
    private val store = DelayedTrackingStore(preferences, preferences::write)
    private var scheduled = 0
    private val scheduler = DelayedTrackingScheduler { scheduled++ }

    private val main = MANGA_ID
    private val member = MANGA_ID + 1

    private val group = MergedGroup(
        id = 1,
        mainMangaId = main,
        showAll = false,
        members = listOf(main, member).map { MergedMember(it, source = it, url = "/$it", position = it, checkUpdates = true) },
    )

    private fun recorder(repository: FakeTrackRepository, group: MergedGroup?) = TrackProgressRecorder(
        repository,
        TrackerManager(setOf(FakeTracker(1))),
        store,
        scheduler,
        mockk<GetMergedGroup> { coEvery { await(any()) } returns group },
    )

    @Test
    fun `a member's chapter pushes against the track of the main entry and queues under the main`() = runTest {
        val repository = FakeTrackRepository(track(id = 10, trackerId = 1, mangaId = main, lastChapterRead = 4.0))

        recorder(repository, group).record(member, 5.0) shouldBe 1

        store.getItems() shouldBe listOf(DelayedTrackingStore.DelayedTrackingItem(main, 1, 1010, 5.0))
        scheduled shouldBe 1
    }

    @Test
    fun `a member's own track row is not the one that decides`() = runTest {
        val repository = FakeTrackRepository(
            track(id = 10, trackerId = 1, mangaId = main, lastChapterRead = 9.0),
            track(id = 11, trackerId = 1, mangaId = member, lastChapterRead = 1.0),
        )

        val recorder = recorder(repository, group)

        recorder.wouldRecord(member, 5.0) shouldBe false
        recorder.record(member, 5.0) shouldBe 0
        store.getItems() shouldBe emptyList()
        scheduled shouldBe 0
    }

    @Test
    fun `a merged title whose main entry has no track records nothing, though the member has one`() = runTest {
        val repository = FakeTrackRepository(track(id = 11, trackerId = 1, mangaId = member))

        recorder(repository, group).record(member, 5.0) shouldBe 0

        store.getItems() shouldBe emptyList()
    }

    @Test
    fun `the main entry's own chapter records against itself`() = runTest {
        val repository = FakeTrackRepository(track(id = 10, trackerId = 1, mangaId = main))

        recorder(repository, group).record(main, 5.0) shouldBe 1

        store.getItems() shouldBe listOf(DelayedTrackingStore.DelayedTrackingItem(main, 1, 1010, 5.0))
    }

    @Test
    fun `an entry in no group records against its own row`() = runTest {
        val repository = FakeTrackRepository(track(id = 11, trackerId = 1, mangaId = member))

        recorder(repository, null).record(member, 5.0) shouldBe 1

        store.getItems() shouldBe listOf(DelayedTrackingStore.DelayedTrackingItem(member, 1, 1011, 5.0))
    }
}

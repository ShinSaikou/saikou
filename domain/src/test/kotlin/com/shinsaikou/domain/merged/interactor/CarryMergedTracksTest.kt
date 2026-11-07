package com.shinsaikou.domain.merged.interactor

import com.shinsaikou.domain.database.TransactionRunner
import com.shinsaikou.domain.merged.model.MergedGroup
import com.shinsaikou.domain.merged.model.MergedMember
import com.shinsaikou.domain.merged.repository.MergedGroupRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository

/** Which tracks the main entry of a merged title holds (R163): carried as `MigrateManga` carries them. */
class CarryMergedTracksTest {

    private val trackRepository = mockk<TrackRepository>(relaxed = true)
    private val carry = CarryMergedTracks(trackRepository)

    private fun track(mangaId: Long, trackerId: Long, remoteId: Long, read: Double = 0.0) = Track(
        id = mangaId * 100 + trackerId,
        mangaId = mangaId,
        trackerId = trackerId,
        remoteId = remoteId,
        libraryId = null,
        title = "t",
        lastChapterRead = read,
        totalChapters = 0,
        status = 0,
        score = 0.0,
        remoteUrl = "",
        startDate = 0,
        finishDate = 0,
        private = false,
    )

    private fun has(mangaId: Long, vararg tracks: Track) {
        coEvery { trackRepository.getTracksByMangaId(mangaId) } returns tracks.toList()
    }

    @Test
    fun `a merge gives the main a tracker it lacks, under the main's id`() = runTest {
        has(1)
        has(2, track(2, trackerId = 7, remoteId = 70))

        carry.ontoMain(mainId = 1, memberIds = listOf(2))

        coVerify(exactly = 1) { trackRepository.insert(track(2, 7, 70).copy(mangaId = 1)) }
    }

    @Test
    fun `a tracker the main is bound to stays bound to its entry`() = runTest {
        has(1, track(1, 7, remoteId = 70, read = 3.0))
        has(2, track(2, 7, remoteId = 99, read = 50.0))

        carry.ontoMain(1, listOf(2))

        coVerify(exactly = 0) { trackRepository.insert(any()) }
        coVerify(exactly = 0) { trackRepository.rebind(any()) }
    }

    @Test
    fun `for the same remote entry the row further along wins`() = runTest {
        has(1, track(1, 7, remoteId = 70, read = 3.0))
        has(2, track(2, 7, remoteId = 70, read = 9.0))

        carry.ontoMain(1, listOf(2))

        coVerify(exactly = 1) { trackRepository.insert(track(2, 7, 70, read = 9.0).copy(mangaId = 1)) }
    }

    @Test
    fun `for the same remote entry the main keeps its row when it is further along or level`() = runTest {
        has(1, track(1, 7, remoteId = 70, read = 9.0))
        has(2, track(2, 7, remoteId = 70, read = 9.0))
        has(3, track(3, 7, remoteId = 70, read = 4.0))

        carry.ontoMain(1, listOf(2, 3))

        coVerify(exactly = 0) { trackRepository.insert(any()) }
    }

    @Test
    fun `two members on one tracker bind the main once, to the first, unless the next is further on the same entry`() = runTest {
        has(1)
        has(2, track(2, 7, remoteId = 70, read = 1.0))
        has(3, track(3, 7, remoteId = 71, read = 99.0), track(3, 8, remoteId = 80))

        carry.ontoMain(1, listOf(2, 3))

        coVerify(exactly = 1) { trackRepository.insert(match { it.trackerId == 7L }) }
        coVerify(exactly = 1) { trackRepository.insert(match { it.trackerId == 7L && it.remoteId == 70L }) }
        coVerify(exactly = 1) { trackRepository.insert(match { it.trackerId == 8L && it.mangaId == 1L }) }
    }

    @Test
    fun `a merge never reads the main's own id among the members as a source`() = runTest {
        has(1, track(1, 7, remoteId = 70))

        carry.ontoMain(1, listOf(1))

        coVerify(exactly = 1) { trackRepository.getTracksByMangaId(1) }
        coVerify(exactly = 0) { trackRepository.insert(any()) }
    }

    @Test
    fun `a new main takes the old main's tracker it lacks by a plain insert`() = runTest {
        has(2)
        has(1, track(1, 7, remoteId = 70))

        carry.toNewMain(oldMainId = 1, newMainId = 2)

        coVerify(exactly = 1) { trackRepository.insert(track(1, 7, 70).copy(mangaId = 2)) }
        coVerify(exactly = 0) { trackRepository.rebind(any()) }
    }

    @Test
    fun `a new main with another remote entry on a tracker is rebound to the group's`() = runTest {
        has(2, track(2, 7, remoteId = 99))
        has(1, track(1, 7, remoteId = 70))

        carry.toNewMain(1, 2)

        coVerify(exactly = 1) { trackRepository.rebind(track(1, 7, 70).copy(mangaId = 2)) }
        coVerify(exactly = 0) { trackRepository.insert(any()) }
    }

    @Test
    fun `a new main on the same remote entry moves progress with a plain insert, not a rebind`() = runTest {
        has(2, track(2, 7, remoteId = 70, read = 1.0))
        has(1, track(1, 7, remoteId = 70, read = 8.0))

        carry.toNewMain(1, 2)

        coVerify(exactly = 1) { trackRepository.insert(track(1, 7, 70, read = 8.0).copy(mangaId = 2)) }
        coVerify(exactly = 0) { trackRepository.rebind(any()) }
    }

    @Test
    fun `the old main keeps its own rows when the main moves`() = runTest {
        has(2)
        has(1, track(1, 7, remoteId = 70))

        carry.toNewMain(1, 2)

        coVerify(exactly = 0) { trackRepository.deleteIfBoundTo(any(), any(), any()) }
    }

    // ---- The two interactors that hand the main role on ----

    private val repository = mockk<MergedGroupRepository>(relaxed = true)
    private val getMergedGroup = mockk<GetMergedGroup>()
    private val carried = mockk<CarryMergedTracks>(relaxed = true)
    private val inline = object : TransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    }

    private fun member(id: Long, position: Long) =
        MergedMember(mangaId = id, source = id, url = "/$id", position = position, checkUpdates = true)

    private fun groupOf(main: Long, vararg ids: Long) = MergedGroup(
        id = 5,
        mainMangaId = main,
        showAll = false,
        members = ids.mapIndexed { index, id -> member(id, index.toLong()) },
    )

    @Test
    fun `SetMergedMain rebinds the group's tracks to the new main in the same transaction as the move`() = runTest {
        coEvery { getMergedGroup.await(2) } returns groupOf(1, 1, 2, 3)

        SetMergedMain(repository, getMergedGroup, carried, inline).await(2)

        coVerifyOrder {
            repository.setMain(5, 2)
            carried.toNewMain(oldMainId = 1, newMainId = 2)
        }
    }

    @Test
    fun `SetMergedMain on the main itself writes and carries nothing`() = runTest {
        coEvery { getMergedGroup.await(1) } returns groupOf(1, 1, 2)

        SetMergedMain(repository, getMergedGroup, carried, inline).await(1)

        coVerify(exactly = 0) { repository.setMain(any(), any()) }
        coVerify(exactly = 0) { carried.toNewMain(any(), any()) }
    }

    @Test
    fun `SetMergedMain on an entry in no group writes and carries nothing`() = runTest {
        coEvery { getMergedGroup.await(9) } returns null

        SetMergedMain(repository, getMergedGroup, carried, inline).await(9)

        coVerify(exactly = 0) { repository.setMain(any(), any()) }
        coVerify(exactly = 0) { carried.toNewMain(any(), any()) }
    }

    @Test
    fun `removing the main hands the tracks to the first member left`() = runTest {
        coEvery { getMergedGroup.await(1) } returns groupOf(1, 1, 2, 3)

        RemoveMergedMember(repository, getMergedGroup, carried, inline).await(1)

        coVerifyOrder {
            repository.setMain(5, 2)
            carried.toNewMain(oldMainId = 1, newMainId = 2)
            repository.removeMember(1)
        }
    }

    @Test
    fun `removing a member that is not the main carries nothing`() = runTest {
        coEvery { getMergedGroup.await(3) } returns groupOf(1, 1, 2, 3)

        RemoveMergedMember(repository, getMergedGroup, carried, inline).await(3)

        coVerify(exactly = 1) { repository.removeMember(3) }
        coVerify(exactly = 0) { carried.toNewMain(any(), any()) }
        coVerify(exactly = 0) { repository.setMain(any(), any()) }
    }

    @Test
    fun `removing one of the last two unmerges and carries nothing`() = runTest {
        coEvery { getMergedGroup.await(1) } returns groupOf(1, 1, 2)

        RemoveMergedMember(repository, getMergedGroup, carried, inline).await(1)

        coVerify(exactly = 1) { repository.unmerge(5) }
        coVerify(exactly = 0) { carried.toNewMain(any(), any()) }
        coVerify(exactly = 0) { repository.removeMember(any()) }
    }
}

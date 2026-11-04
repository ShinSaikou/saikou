package com.shinsaikou.domain.manga.interactor

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.model.Manga

/**
 * The Experimental custom interval switch going off: only the library entries holding a chosen
 * interval (a negative [Manga.fetchInterval]) go back on their estimate, each through the write the
 * Automatic chip makes.
 */
class ClearCustomIntervalsTest {

    private val getLibraryManga = mockk<GetLibraryManga>()
    private val updateManga = mockk<UpdateManga>(relaxed = true)
    private val clearCustomIntervals = ClearCustomIntervals(getLibraryManga, updateManga)

    private fun entry(id: Long, fetchInterval: Int) = LibraryManga(
        manga = Manga.create().copy(id = id, favorite = true, fetchInterval = fetchInterval),
        categories = emptyList(),
        totalChapters = 0L,
        readCount = 0L,
        bookmarkCount = 0L,
        latestUpload = 0L,
        chapterFetchedAt = 0L,
        lastRead = 0L,
    )

    @Test
    fun `each entry with a chosen interval is written back to automatic`() = runTest {
        val seven = entry(id = 1L, fetchInterval = -7)
        val one = entry(id = 3L, fetchInterval = -1)
        coEvery { getLibraryManga.await() } returns listOf(seven, entry(id = 2L, fetchInterval = 5), one)

        clearCustomIntervals.await()

        coVerify(exactly = 1) { updateManga.awaitUpdateCustomInterval(seven.manga, 0) }
        coVerify(exactly = 1) { updateManga.awaitUpdateCustomInterval(one.manga, 0) }
        coVerify(exactly = 2) { updateManga.awaitUpdateCustomInterval(any(), any()) }
    }

    @Test
    fun `an entry on its estimate is never written`() = runTest {
        coEvery { getLibraryManga.await() } returns listOf(entry(id = 1L, fetchInterval = 14), entry(id = 2L, fetchInterval = 1))

        clearCustomIntervals.await()

        coVerify(exactly = 0) { updateManga.awaitUpdateCustomInterval(any(), any()) }
    }

    @Test
    fun `an entry with no interval yet is never written`() = runTest {
        coEvery { getLibraryManga.await() } returns listOf(entry(id = 1L, fetchInterval = 0))

        clearCustomIntervals.await()

        coVerify(exactly = 0) { updateManga.awaitUpdateCustomInterval(any(), any()) }
    }

    @Test
    fun `an empty library writes nothing`() = runTest {
        coEvery { getLibraryManga.await() } returns emptyList()

        clearCustomIntervals.await()

        coVerify(exactly = 0) { updateManga.awaitUpdateCustomInterval(any(), any()) }
    }
}

package com.shinsaikou.domain.manga.interactor

import com.shinsaikou.domain.chapter.interactor.SyncChaptersWithSource
import com.shinsaikou.domain.merged.interactor.ReconcileMergedRead
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.source.Source
import com.shinsaikou.source.model.ChapterInfo
import com.shinsaikou.source.model.MangaInfo
import com.shinsaikou.source.model.MangaUpdate
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.model.Manga
import java.io.IOException
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers

/** New chapters of a member can be copies of a number already read, so a fetch that inserted any reconciles. */
class RefreshMangaFromSourceMergedTest {

    private val getMangaWithChapters = mockk<GetMangaWithChapters>()
    private val syncChaptersWithSource = mockk<SyncChaptersWithSource>()
    private val fetchInterval = mockk<FetchInterval>()
    private val reconcile = mockk<ReconcileMergedRead>(relaxed = true)
    private val source = mockk<Source>()
    private val stored = Manga.create().copy(id = 7L, source = 3L, url = "/entry")

    private val refresh = RefreshMangaFromSource(
        getMangaWithChapters = getMangaWithChapters,
        updateManga = mockk(),
        syncChaptersWithSource = syncChaptersWithSource,
        fetchInterval = fetchInterval,
        libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
        reconcileMergedRead = reconcile,
        dispatchers = AppDispatchers(io = Dispatchers.Unconfined, default = Dispatchers.Unconfined, main = Dispatchers.Unconfined),
    )

    init {
        coEvery { source.getMangaUpdate(any(), any(), false, true) } returns
            MangaUpdate(manga = null, chapters = listOf(ChapterInfo(url = "/entry/2", name = "Chapter 2")))
        coEvery { getMangaWithChapters.awaitChapters(7L, any()) } returns emptyList()
        every { fetchInterval.getWindow(any(), any()) } returns Pair(0L, 0L)
    }

    @Test
    fun `a fetch that inserted chapters reconciles the entry's group`() = runTest {
        coEvery { syncChaptersWithSource.await(any(), any(), any(), any()) } returns
            listOf(Chapter.create().copy(id = 70L, mangaId = 7L))

        refresh(source, stored, fetchChapters = true)

        coVerify(exactly = 1) { reconcile.await(7L) }
    }

    @Test
    fun `a fetch that inserted nothing does not reconcile`() = runTest {
        coEvery { syncChaptersWithSource.await(any(), any(), any(), any()) } returns emptyList()

        refresh(source, stored, fetchChapters = true)

        coVerify(exactly = 0) { reconcile.await(any<Long>()) }
    }

    @Test
    fun `a details only refresh does not reconcile`() = runTest {
        coEvery { source.getMangaUpdate(any(), any(), true, false) } returns
            MangaUpdate(manga = MangaInfo(url = "/entry", title = "T"), chapters = null)
        coEvery { getMangaWithChapters.awaitManga(7L) } returns stored
        coEvery { syncChaptersWithSource.await(any(), any(), any(), any()) } returns emptyList()

        runCatching { refresh(source, stored, fetchDetails = true) }

        coVerify(exactly = 0) { reconcile.await(any<Long>()) }
    }

    @Test
    fun `a failing fetch is thrown and reconciles nothing`() = runTest {
        coEvery { source.getMangaUpdate(any(), any(), false, true) } throws IOException("offline")

        shouldThrow<IOException> { refresh(source, stored, fetchChapters = true) }

        coVerify(exactly = 0) { reconcile.await(any<Long>()) }
    }

    @Test
    fun `a reconcile that fails still returns the fetched chapters`() = runTest {
        val inserted = listOf(Chapter.create().copy(id = 70L, mangaId = 7L))
        coEvery { syncChaptersWithSource.await(any(), any(), any(), any()) } returns inserted
        coEvery { reconcile.await(7L) } throws IllegalStateException("db busy")

        val result = refresh(source, stored, fetchChapters = true)

        result.newChapters shouldBe inserted
    }

    @Test
    fun `a cancelled reconcile is rethrown`() = runTest {
        coEvery { syncChaptersWithSource.await(any(), any(), any(), any()) } returns
            listOf(Chapter.create().copy(id = 70L, mangaId = 7L))
        coEvery { reconcile.await(7L) } throws CancellationException("cancelled")

        shouldThrow<CancellationException> { refresh(source, stored, fetchChapters = true) }
    }
}

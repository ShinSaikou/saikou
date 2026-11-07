package com.shinsaikou.domain.manga.interactor

import com.shinsaikou.domain.chapter.interactor.SyncChaptersWithSource
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.source.Source
import com.shinsaikou.source.model.ChapterInfo
import com.shinsaikou.source.model.MangaInfo
import com.shinsaikou.source.model.MangaUpdate
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
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

/**
 * Upstream's `UpdateMangaFromRemote` flags on [RefreshMangaFromSource] (phase 4 unit MA): one flag on
 * asks for that half alone, both keep the manga screen's order (the chapters asked with the row the
 * details left), every call carries the stored chapters in source order, and a failing call is
 * thrown.
 */
class RefreshMangaFromSourceTest {

    private val getMangaWithChapters = mockk<GetMangaWithChapters>()
    private val updateManga = mockk<UpdateManga>()
    private val syncChaptersWithSource = mockk<SyncChaptersWithSource>()
    private val fetchInterval = mockk<FetchInterval>()
    private val source = mockk<Source>()

    private val stored = Manga.create().copy(id = 7L, source = 3L, url = "/entry", title = "Stored title")
    private val refreshed = stored.copy(title = "Refreshed title", initialized = true)
    private val inserted = listOf(Chapter.create().copy(id = 70L, mangaId = 7L, name = "Chapter 2"))

    /** As the database returns them, not in source order. */
    private val storedChapters = listOf(
        Chapter.create().copy(id = 61L, mangaId = 7L, url = "/entry/0", name = "Chapter 0", sourceOrder = 1L),
        Chapter.create().copy(id = 62L, mangaId = 7L, url = "/entry/1", name = "Chapter 1", sourceOrder = 0L),
    )

    private val refresh = RefreshMangaFromSource(
        getMangaWithChapters = getMangaWithChapters,
        updateManga = updateManga,
        syncChaptersWithSource = syncChaptersWithSource,
        fetchInterval = fetchInterval,
        libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
        reconcileMergedRead = mockk(relaxed = true),
        dispatchers = AppDispatchers(io = Dispatchers.Unconfined, default = Dispatchers.Unconfined, main = Dispatchers.Unconfined),
    )

    init {
        coEvery { source.getMangaUpdate(any(), any(), true, false) } returns
            MangaUpdate(manga = MangaInfo(url = "/entry", title = "Refreshed title"), chapters = null)
        coEvery { source.getMangaUpdate(any(), any(), false, true) } returns
            MangaUpdate(manga = null, chapters = listOf(ChapterInfo(url = "/entry/2", name = "Chapter 2")))
        coEvery { getMangaWithChapters.awaitChapters(7L, any()) } returns storedChapters
        coEvery { updateManga.awaitUpdateFromSource(any(), any(), any(), any()) } returns true
        coEvery { getMangaWithChapters.awaitManga(7L) } returns refreshed
        coEvery { syncChaptersWithSource.await(any(), any(), any(), any()) } returns inserted
        every { fetchInterval.getWindow(any(), any()) } returns Pair(0L, 0L)
    }

    @Test
    fun `chapters alone make one chapter list call and no details call`() = runTest {
        val result = refresh(source, stored, fetchChapters = true)

        coVerify(exactly = 1) { source.getMangaUpdate(any(), any(), false, true) }
        coVerify(exactly = 0) { source.getMangaUpdate(any(), any(), true, any()) }
        coVerify(exactly = 0) { updateManga.awaitUpdateFromSource(any(), any(), any(), any()) }
        coVerify(exactly = 1) { syncChaptersWithSource.await(any(), stored, false, Pair(0L, 0L)) }
        result.manga shouldBe stored
        result.newChapters shouldContainExactly inserted
    }

    @Test
    fun `details alone make one details call and no chapter list call`() = runTest {
        val result = refresh(source, stored, fetchDetails = true)

        coVerify(exactly = 1) { source.getMangaUpdate(any(), any(), true, false) }
        coVerify(exactly = 0) { source.getMangaUpdate(any(), any(), any(), true) }
        coVerify(exactly = 0) { syncChaptersWithSource.await(any(), any(), any(), any()) }
        result.manga shouldBe refreshed
        result.newChapters.shouldBeEmpty()
    }

    @Test
    fun `both keep the details first and ask the chapters with the refreshed row`() = runTest {
        val result = refresh(source, stored, fetchDetails = true, fetchChapters = true, manualFetch = true)

        coVerifyOrder {
            source.getMangaUpdate(match { it.title == "Stored title" }, any(), true, false)
            updateManga.awaitUpdateFromSource(stored, any(), true, false)
            getMangaWithChapters.awaitManga(7L)
            source.getMangaUpdate(match { it.title == "Refreshed title" }, any(), false, true)
            syncChaptersWithSource.await(any(), refreshed, true, Pair(0L, 0L))
        }
        result.manga shouldBe refreshed
        result.newChapters shouldContainExactly inserted
    }

    @Test
    fun `neither flag makes no call`() = runTest {
        val result = refresh(source, stored)

        coVerify(exactly = 0) { source.getMangaUpdate(any(), any(), any(), any()) }
        coVerify(exactly = 0) { getMangaWithChapters.awaitChapters(any(), any()) }
        result.manga shouldBe stored
        result.newChapters.shouldBeEmpty()
    }

    @Test
    fun `a failing chapter list call is thrown`() = runTest {
        coEvery { source.getMangaUpdate(any(), any(), false, true) } throws IOException("offline")

        shouldThrow<IOException> { refresh(source, stored, fetchChapters = true) }
        coVerify(exactly = 0) { syncChaptersWithSource.await(any(), any(), any(), any()) }
    }

    @Test
    fun `every call carries the stored chapters in source order`() = runTest {
        refresh(source, stored, fetchDetails = true, fetchChapters = true)

        val inSourceOrder = listOf("/entry/1", "/entry/0")
        coVerify(exactly = 1) {
            source.getMangaUpdate(any(), match { list -> list.map { it.url } == inSourceOrder }, true, false)
        }
        coVerify(exactly = 1) {
            source.getMangaUpdate(any(), match { list -> list.map { it.url } == inSourceOrder }, false, true)
        }
        coVerify(exactly = 1) { getMangaWithChapters.awaitChapters(7L, any()) }
    }
}

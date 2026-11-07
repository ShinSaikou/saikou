package com.shinsaikou.domain.merged.interactor

import com.shinsaikou.domain.manga.interactor.UpdateManga
import com.shinsaikou.domain.preferences.LibraryPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

/** The library add a merge needs for a member that is not a favourite yet. */
class AddMergedEntryTest {

    private val updateManga = mockk<UpdateManga>(relaxed = true)
    private val mangaRepository = mockk<MangaRepository>(relaxed = true)
    private val getCategories = mockk<GetCategories>()
    private val setMangaCategories = mockk<SetMangaCategories>(relaxed = true)
    private val preferences = LibraryPreferences(InMemoryPreferenceStore())
    private val add = AddMergedEntry(updateManga, mangaRepository, getCategories, setMangaCategories, preferences)

    private val stranger = Manga.create().copy(id = 4, favorite = false)

    init {
        coEvery { getCategories.await() } returns listOf(Category(id = 3, name = "Reading", order = 1, flags = 0))
    }

    @Test
    fun `an entry already in the library is left alone, so its date added is kept`() = runTest {
        preferences.defaultCategory.set(3)

        add.await(stranger.copy(favorite = true))

        coVerify(exactly = 0) { updateManga.awaitUpdateFavorite(any(), any()) }
        coVerify(exactly = 0) { setMangaCategories.await(any(), any()) }
    }

    @Test
    fun `an entry outside the library becomes a favourite`() = runTest {
        add.await(stranger)

        coVerify(exactly = 1) { updateManga.awaitUpdateFavorite(4, true) }
    }

    @Test
    fun `with no default category set the new favourite gets no category`() = runTest {
        add.await(stranger)

        coVerify(exactly = 0) { setMangaCategories.await(any(), any()) }
    }

    @Test
    fun `with a default category that exists the new favourite is put in it`() = runTest {
        preferences.defaultCategory.set(3)

        add.await(stranger)

        coVerify(exactly = 1) { setMangaCategories.await(4, listOf(3L)) }
    }

    @Test
    fun `a default category that was deleted is not written`() = runTest {
        preferences.defaultCategory.set(8)

        add.await(stranger)

        coVerify(exactly = 1) { updateManga.awaitUpdateFavorite(4, true) }
        coVerify(exactly = 0) { setMangaCategories.await(any(), any()) }
    }

    @Test
    fun `an entry deleted on another device is undeleted before it becomes a favourite`() = runTest {
        add.await(stranger.copy(deletedAt = 1_000))

        coVerifyOrder {
            mangaRepository.undelete(4)
            updateManga.awaitUpdateFavorite(4, true)
        }
    }

    @Test
    fun `an entry with no tombstone is not undeleted`() = runTest {
        add.await(stranger)

        coVerify(exactly = 0) { mangaRepository.undelete(any()) }
    }
}

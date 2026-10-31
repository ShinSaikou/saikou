package com.shinsaikou.domain.manga.interactor

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository

/** Browse's bulk add: [UpdateManga.awaitUpdateFavorites] is one repository write sharing one date added. */
class UpdateMangaFavoritesTest {

    private val mangaRepository = mockk<MangaRepository>()
    private val written = slot<List<MangaUpdate>>()
    private val updateManga = UpdateManga(mangaRepository, mockk<FetchInterval>())

    init {
        coEvery { mangaRepository.updateAll(capture(written)) } returns true
    }

    @Test
    fun `adding writes every id in one call with favorite on and one shared non-zero date added`() = runTest {
        updateManga.awaitUpdateFavorites(listOf(3L, 1L, 2L), favorite = true) shouldBe true

        coVerify(exactly = 1) { mangaRepository.updateAll(any()) }
        written.captured.map { it.id } shouldContainExactly listOf(3L, 1L, 2L)
        written.captured.all { it.favorite == true } shouldBe true
        val dates = written.captured.map { it.dateAdded }.toSet()
        dates.size shouldBe 1
        (dates.single()!! > 0L) shouldBe true
    }

    @Test
    fun `removing writes every id in one call with favorite off and date added zero`() = runTest {
        updateManga.awaitUpdateFavorites(listOf(3L, 1L), favorite = false)

        coVerify(exactly = 1) { mangaRepository.updateAll(any()) }
        written.captured.map { it.id } shouldContainExactly listOf(3L, 1L)
        written.captured.all { it.favorite == false } shouldBe true
        written.captured.all { it.dateAdded == 0L } shouldBe true
    }
}

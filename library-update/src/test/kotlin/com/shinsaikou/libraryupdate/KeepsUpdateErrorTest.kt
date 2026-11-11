package com.shinsaikou.libraryupdate

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

class KeepsUpdateErrorTest {

    private val getManga = mockk<GetManga>()

    private fun lookup(manga: Manga?) {
        every { getManga.subscribeOrNull(1L) } returns flowOf(manga)
    }

    @Test
    fun `a live favourite keeps its error`() = runTest {
        lookup(Manga.create().copy(id = 1L, favorite = true))

        getManga.keepsUpdateError(1L) shouldBe true
    }

    @Test
    fun `a missing row drops its error`() = runTest {
        lookup(null)

        getManga.keepsUpdateError(1L) shouldBe false
    }

    @Test
    fun `an entry that is no longer a favourite drops its error`() = runTest {
        lookup(Manga.create().copy(id = 1L, favorite = false))

        getManga.keepsUpdateError(1L) shouldBe false
    }

    @Test
    fun `a trashed favourite drops its error`() = runTest {
        lookup(Manga.create().copy(id = 1L, favorite = true, deletedAt = 5L))

        getManga.keepsUpdateError(1L) shouldBe false
    }

    @Test
    fun `a lookup that fails in the flow keeps the error`() = runTest {
        every { getManga.subscribeOrNull(1L) } returns flow { throw IllegalStateException("fixture: db") }

        getManga.keepsUpdateError(1L) shouldBe true
    }

    @Test
    fun `a lookup that throws on call keeps the error`() = runTest {
        every { getManga.subscribeOrNull(1L) } throws IOException("fixture: db closed")

        getManga.keepsUpdateError(1L) shouldBe true
    }

    @Test
    fun `a cancellation is rethrown rather than treated as a failed lookup`() = runTest {
        every { getManga.subscribeOrNull(1L) } returns flow { throw CancellationException("fixture: cancelled") }

        shouldThrow<CancellationException> { getManga.keepsUpdateError(1L) }
    }
}

package com.shinsaikou.domain.manga.interactor

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

/**
 * The edit dialog's save and reset (phase 5 unit ED, R138 item 2): what reaches the override
 * columns for what the dialog holds, against the source's text the row stores.
 */
class SetMangaDetailsOverrideTest {

    private val mangaRepository = mockk<MangaRepository>()
    private val written = mutableListOf<Pair<String?, String?>>()
    private val setDetails = SetMangaDetailsOverride(mangaRepository)

    init {
        coEvery { mangaRepository.getMangaById(ID) } returns
            Manga.create().copy(id = ID, title = "Source title", description = "Source text\n")
        coEvery { mangaRepository.setDetailsOverride(ID, any(), any()) } answers {
            written += secondArg<String?>() to thirdArg<String?>()
            true
        }
    }

    @Test
    fun `an edited title and description are written as the overrides, trimmed`() = runTest {
        setDetails.await(ID, "  My title ", "My text\n") shouldBe true

        written shouldBe listOf<Pair<String?, String?>>("My title" to "My text")
    }

    @Test
    fun `a value equal to the source's clears that override, the other one stays an edit`() = runTest {
        setDetails.await(ID, "Source title", "My text") shouldBe true
        setDetails.await(ID, "My title", "Source text") shouldBe true

        written shouldBe listOf<Pair<String?, String?>>(null to "My text", "My title" to null)
    }

    @Test
    fun `an empty description over a source description is an edit to no text`() = runTest {
        setDetails.await(ID, "Source title", "") shouldBe true

        written shouldBe listOf<Pair<String?, String?>>(null to "")
    }

    @Test
    fun `a blank title is refused and nothing is written`() = runTest {
        setDetails.await(ID, "   ", "My text") shouldBe false

        written shouldBe emptyList()
        coVerify(exactly = 0) { mangaRepository.setDetailsOverride(any(), any(), any()) }
    }

    @Test
    fun `reset clears both overrides`() = runTest {
        setDetails.awaitReset(ID) shouldBe true

        written shouldBe listOf<Pair<String?, String?>>(null to null)
    }

    @Test
    fun `a failed write is the result`() = runTest {
        coEvery { mangaRepository.setDetailsOverride(ID, any(), any()) } returns false

        setDetails.await(ID, "My title", "My text") shouldBe false
    }

    @Test
    fun `a migration's carry writes the old entry's edits over the target's, field by field`() = runTest {
        val target = Manga.create().copy(id = ID, title = "Source title", titleOverride = "Target edit", descriptionOverride = "Kept")
        val from = Manga.create().copy(id = 1L, title = "Old source title", titleOverride = "Old edit")

        setDetails.awaitCarry(from = from, to = target) shouldBe true

        written shouldBe listOf<Pair<String?, String?>>("Old edit" to "Kept")
    }

    @Test
    fun `a carried edit equal to the target's source text is no edit, and an unedited entry writes nothing`() = runTest {
        val target = Manga.create().copy(id = ID, title = "Source title", description = "Source text\n")
        val from = Manga.create().copy(id = 1L, title = "Old", titleOverride = "Source title", descriptionOverride = "Source text")

        setDetails.awaitCarry(from = from, to = target) shouldBe true
        setDetails.awaitCarry(from = Manga.create().copy(id = 1L), to = target.copy(titleOverride = "Target edit")) shouldBe true

        written shouldBe emptyList()
        coVerify(exactly = 0) { mangaRepository.setDetailsOverride(any(), any(), any()) }
    }

    private companion object {
        const val ID = 7L
    }
}

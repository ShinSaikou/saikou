package com.shinsaikou.domain.manga.interactor

import com.shinsaikou.domain.manga.model.MangaDetailsOverrides
import com.shinsaikou.source.model.MangaStatus
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

/**
 * The edit dialog's save and reset (phase 5 unit ED, R138 item 2; R165 item 4): what reaches the
 * override columns for what the dialog holds, against the source's values the row stores.
 */
class SetMangaDetailsOverrideTest {

    private val mangaRepository = mockk<MangaRepository>()
    private val written = mutableListOf<MangaDetailsOverrides>()
    private val setDetails = SetMangaDetailsOverride(mangaRepository)

    init {
        coEvery { mangaRepository.getMangaById(ID) } returns SOURCE
        coEvery { mangaRepository.setDetailsOverride(ID, any()) } answers {
            written += secondArg<MangaDetailsOverrides>()
            true
        }
    }

    @Test
    fun `an edited title and description are written as the overrides, trimmed`() = runTest {
        setDetails.await(ID, UNCHANGED.copy(title = "  My title ", description = "My text\n")) shouldBe true

        written shouldBe listOf(MangaDetailsOverrides(title = "My title", description = "My text"))
    }

    @Test
    fun `a value equal to the source's clears that override, the other one stays an edit`() = runTest {
        setDetails.await(ID, UNCHANGED.copy(title = "Source title", description = "My text")) shouldBe true
        setDetails.await(ID, UNCHANGED.copy(title = "My title", description = "Source text")) shouldBe true

        written shouldBe listOf(
            MangaDetailsOverrides(description = "My text"),
            MangaDetailsOverrides(title = "My title"),
        )
    }

    @Test
    fun `an empty description over a source description is an edit to no text`() = runTest {
        setDetails.await(ID, UNCHANGED.copy(description = "")) shouldBe true

        written shouldBe listOf(MangaDetailsOverrides(description = ""))
    }

    @Test
    fun `a blank title is refused and nothing is written`() = runTest {
        setDetails.await(ID, UNCHANGED.copy(title = "   ", description = "My text")) shouldBe false

        written shouldBe emptyList()
        coVerify(exactly = 0) { mangaRepository.setDetailsOverride(any(), any()) }
    }

    @Test
    fun `the dialog saved as it opened on an unedited entry writes no override`() = runTest {
        setDetails.await(ID, UNCHANGED) shouldBe true

        written shouldBe listOf(MangaDetailsOverrides.NONE)
    }

    @Test
    fun `an edited author, artist, status and genres are written as the overrides, the texts trimmed`() = runTest {
        val edit = UNCHANGED.copy(
            author = " My author ",
            artist = "My artist\n",
            status = MangaStatus.COMPLETED.code.toLong(),
            genres = listOf(" Comedy", "Drama "),
        )

        setDetails.await(ID, edit) shouldBe true

        written shouldBe listOf(
            MangaDetailsOverrides(
                author = "My author",
                artist = "My artist",
                status = MangaStatus.COMPLETED.code.toLong(),
                genre = listOf("Comedy", "Drama"),
            ),
        )
    }

    @Test
    fun `a value equal to the source's clears each of the four, typed back or with other spacing`() = runTest {
        val edit = UNCHANGED.copy(
            author = "Source author ",
            artist = " Source artist",
            status = MangaStatus.ONGOING.code.toLong(),
            genres = listOf("Action ", " Drama", "Action"),
        )

        setDetails.await(ID, edit) shouldBe true

        written shouldBe listOf(MangaDetailsOverrides.NONE)
    }

    @Test
    fun `a blank author or artist, From source and an empty genre list store no override`() = runTest {
        val edit = UNCHANGED.copy(author = "  ", artist = "", status = null, genres = listOf(" ", ""))

        setDetails.await(ID, edit) shouldBe true

        written shouldBe listOf(MangaDetailsOverrides.NONE)
    }

    @Test
    fun `a genre list that drops one of the source's is an edit, and repeats and blanks are dropped`() = runTest {
        setDetails.await(ID, UNCHANGED.copy(genres = listOf("Drama", "Drama", " "))) shouldBe true

        written shouldBe listOf(MangaDetailsOverrides(genre = listOf("Drama")))
    }

    @Test
    fun `an author over a source with none is an edit, and a blank one is not`() = runTest {
        coEvery { mangaRepository.getMangaById(ID) } returns SOURCE.copy(author = null, status = 0L, genre = null)

        setDetails.await(ID, UNCHANGED.copy(author = "Found author", status = 0L, genres = emptyList())) shouldBe true
        setDetails.await(ID, UNCHANGED.copy(author = "", status = 0L, genres = emptyList())) shouldBe true

        written shouldBe listOf(MangaDetailsOverrides(author = "Found author"), MangaDetailsOverrides.NONE)
    }

    @Test
    fun `reset all clears every override`() = runTest {
        setDetails.awaitReset(ID) shouldBe true

        written shouldBe listOf(MangaDetailsOverrides.NONE)
    }

    @Test
    fun `a failed write is the result`() = runTest {
        coEvery { mangaRepository.setDetailsOverride(ID, any()) } returns false

        setDetails.await(ID, UNCHANGED.copy(title = "My title")) shouldBe false
    }

    @Test
    fun `a migration's carry writes the old entry's edits over the target's, field by field`() = runTest {
        val target = Manga.create().copy(
            id = ID, title = "Source title", titleOverride = "Target edit", descriptionOverride = "Kept",
            author = "Target author", authorOverride = "Target author edit", artistOverride = "Target artist edit",
        )
        val from = Manga.create().copy(
            id = 1L, title = "Old source title", titleOverride = "Old edit", artistOverride = "Old artist edit",
            statusOverride = MangaStatus.CANCELLED.code.toLong(), genreOverride = listOf("Old genre"),
        )

        setDetails.awaitCarry(from = from, to = target) shouldBe true

        written shouldBe listOf(
            MangaDetailsOverrides(
                title = "Old edit",
                description = "Kept",
                author = "Target author edit",
                artist = "Old artist edit",
                status = MangaStatus.CANCELLED.code.toLong(),
                genre = listOf("Old genre"),
            ),
        )
    }

    @Test
    fun `a carried edit equal to the target's source value is no edit, and an unedited entry writes nothing`() = runTest {
        val target = Manga.create().copy(
            id = ID, title = "Source title", description = "Source text\n", author = "Source author",
            status = MangaStatus.ONGOING.code.toLong(), genre = listOf("Action"),
        )
        val from = Manga.create().copy(
            id = 1L, title = "Old", titleOverride = "Source title", descriptionOverride = "Source text",
            authorOverride = "Source author", statusOverride = MangaStatus.ONGOING.code.toLong(),
            genreOverride = listOf("Action"),
        )

        setDetails.awaitCarry(from = from, to = target) shouldBe true
        setDetails.awaitCarry(from = Manga.create().copy(id = 1L), to = target.copy(titleOverride = "Target edit")) shouldBe
            true

        written shouldBe emptyList()
        coVerify(exactly = 0) { mangaRepository.setDetailsOverride(any(), any()) }
    }

    private companion object {
        const val ID = 7L

        /** The row the dialog edits: every detail from the source, none edited. */
        val SOURCE = Manga.create().copy(
            id = ID,
            title = "Source title",
            description = "Source text\n",
            author = "Source author",
            artist = "Source artist",
            status = MangaStatus.ONGOING.code.toLong(),
            genre = listOf("Action", "Drama"),
        )

        /** What the dialog holds when it opens on [SOURCE]. */
        val UNCHANGED = SetMangaDetailsOverride.Edit(
            title = "Source title",
            description = "Source text",
            author = "Source author",
            artist = "Source artist",
            status = MangaStatus.ONGOING.code.toLong(),
            genres = listOf("Action", "Drama"),
        )
    }
}

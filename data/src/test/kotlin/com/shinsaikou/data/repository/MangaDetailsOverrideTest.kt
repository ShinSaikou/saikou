package com.shinsaikou.data.repository

import com.shinsaikou.domain.manga.model.MangaDetailsOverrides
import com.shinsaikou.domain.manga.model.detailsOverrides
import com.shinsaikou.source.model.MangaStatus
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.manga.model.MangaUpdate

/**
 * The owner's edit of an entry's details through the repository (phase 5 unit ED, R138 item 2; R165
 * item 4): the overrides reach the domain model beside the source's values, a source refresh
 * rewrites only the source's columns, and the write logs and stamps through the triggers of schema
 * versions 3 and 7.
 */
class MangaDetailsOverrideTest : RepositoryTest() {

    private val repository by lazy { MangaRepositoryImpl(handler) }

    @BeforeEach
    fun seed() {
        insertManga(id = 1)
        exec(
            """
            UPDATE mangas SET description = 'Source text', author = 'Source author', artist = 'Source artist',
                status = ${MangaStatus.ONGOING.code}, genre = 'Action, Drama'
            WHERE _id = 1
            """,
        )
        exec("DELETE FROM sync_change_log")
    }

    @Test
    fun `an edit is read back beside the source's text, and the display helpers show it`() = runTest {
        repository.setDetailsOverride(1, MangaDetailsOverrides(title = "Edited", description = "Edited text")) shouldBe
            true

        val manga = repository.getMangaById(1)
        manga.title shouldBe "Title 1"
        manga.titleOverride shouldBe "Edited"
        manga.displayTitle shouldBe "Edited"
        manga.description shouldBe "Source text"
        manga.displayDescription shouldBe "Edited text"
        repository.getLibraryManga().single().manga.displayTitle shouldBe "Edited"
    }

    @Test
    fun `an edit of the author, artist, status and genres is read back beside the source's values`() = runTest {
        repository.setDetailsOverride(1, EVERY_DETAIL) shouldBe true

        val manga = repository.getMangaById(1)
        manga.detailsOverrides shouldBe EVERY_DETAIL
        manga.author shouldBe "Source author"
        manga.displayAuthor shouldBe "Edited author"
        manga.artist shouldBe "Source artist"
        manga.displayArtist shouldBe "Edited artist"
        manga.status shouldBe MangaStatus.ONGOING.code.toLong()
        manga.displayStatus shouldBe MangaStatus.COMPLETED.code.toLong()
        manga.genre shouldBe listOf("Action", "Drama")
        manga.displayGenre shouldBe listOf("Comedy", "Slice of life")
        // Stored in the source's genre form, so the library's rows read it with the same adapter.
        scalar("SELECT genre_override FROM mangas WHERE _id = 1") shouldBe "Comedy, Slice of life"
        repository.getLibraryManga().single().manga.detailsOverrides shouldBe EVERY_DETAIL
    }

    @Test
    fun `the edit logs the entry once and stamps its clock`() = runTest {
        repository.setDetailsOverride(1, MangaDetailsOverrides(title = "Edited"))

        scalar("SELECT count(*) FROM sync_change_log WHERE entity_type = 'manga' AND entity_id = 1") shouldBe "1"
        scalar("SELECT details_modified_at FROM mangas WHERE _id = 1").shouldNotBeNull()
        // The same values again change nothing, so nothing logs.
        repository.setDetailsOverride(1, MangaDetailsOverrides(title = "Edited"))
        scalar("SELECT count(*) FROM sync_change_log") shouldBe "1"
    }

    @Test
    fun `an edit of each new detail alone logs the entry and moves the clock`() = runTest {
        val edits = listOf(
            MangaDetailsOverrides(author = "Edited author"),
            MangaDetailsOverrides(artist = "Edited artist"),
            MangaDetailsOverrides(status = MangaStatus.CANCELLED.code.toLong()),
            MangaDetailsOverrides(genre = listOf("Comedy")),
        )
        edits.forEachIndexed { index, edit ->
            val clock = scalar("SELECT coalesce(details_modified_at, 0) FROM mangas WHERE _id = 1")!!.toLong()

            repository.setDetailsOverride(1, edit) shouldBe true

            scalar("SELECT details_modified_at FROM mangas WHERE _id = 1")!!.toLong() shouldBeGreaterThan clock
            scalar("SELECT count(*) FROM sync_change_log WHERE entity_id = 1") shouldBe "${index + 1}"
        }
    }

    @Test
    fun `a source refresh rewrites the source's text and keeps the edit, and a reset shows the new text`() =
        runTest {
            repository.setDetailsOverride(1, MangaDetailsOverrides(title = "Edited", description = "Edited text"))

            repository.update(MangaUpdate(id = 1, title = "Renamed by the source", description = "New source text"))

            val refreshed = repository.getMangaById(1)
            refreshed.displayTitle shouldBe "Edited"
            refreshed.title shouldBe "Renamed by the source"
            refreshed.displayDescription shouldBe "Edited text"

            repository.setDetailsOverride(1, MangaDetailsOverrides.NONE)

            val reset = repository.getMangaById(1)
            reset.displayTitle shouldBe "Renamed by the source"
            reset.displayDescription shouldBe "New source text"
        }

    @Test
    fun `a source refresh of the four details keeps their edits, and a reset shows the source's new values`() =
        runTest {
            repository.setDetailsOverride(1, EVERY_DETAIL)

            repository.update(
                MangaUpdate(
                    id = 1,
                    author = "New author",
                    artist = "New artist",
                    status = MangaStatus.ON_HIATUS.code.toLong(),
                    genre = listOf("Mystery"),
                ),
            )

            val refreshed = repository.getMangaById(1)
            refreshed.detailsOverrides shouldBe EVERY_DETAIL
            refreshed.author shouldBe "New author"
            refreshed.genre shouldBe listOf("Mystery")

            repository.setDetailsOverride(1, MangaDetailsOverrides.NONE)

            val reset = repository.getMangaById(1)
            reset.detailsOverrides shouldBe MangaDetailsOverrides.NONE
            reset.displayAuthor shouldBe "New author"
            reset.displayArtist shouldBe "New artist"
            reset.displayStatus shouldBe MangaStatus.ON_HIATUS.code.toLong()
            reset.displayGenre shouldBe listOf("Mystery")
        }

    private companion object {
        val EVERY_DETAIL = MangaDetailsOverrides(
            title = "Edited",
            description = "Edited text",
            author = "Edited author",
            artist = "Edited artist",
            status = MangaStatus.COMPLETED.code.toLong(),
            genre = listOf("Comedy", "Slice of life"),
        )
    }
}

package com.shinsaikou.data.repository

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.manga.model.MangaUpdate

/**
 * The owner's title and description edit through the repository (phase 5 unit ED, R138 item 2):
 * the overrides reach the domain model beside the source's text, a source refresh rewrites only
 * the source's columns, and the write logs and stamps through schema version 3's triggers.
 */
class MangaDetailsOverrideTest : RepositoryTest() {

    private val repository by lazy { MangaRepositoryImpl(handler) }

    @BeforeEach
    fun seed() {
        insertManga(id = 1)
        exec("UPDATE mangas SET description = 'Source text' WHERE _id = 1")
        exec("DELETE FROM sync_change_log")
    }

    @Test
    fun `an edit is read back beside the source's text, and the display helpers show it`() = runTest {
        repository.setDetailsOverride(1, titleOverride = "Edited", descriptionOverride = "Edited text") shouldBe true

        val manga = repository.getMangaById(1)
        manga.title shouldBe "Title 1"
        manga.titleOverride shouldBe "Edited"
        manga.displayTitle shouldBe "Edited"
        manga.description shouldBe "Source text"
        manga.displayDescription shouldBe "Edited text"
        repository.getLibraryManga().single().manga.displayTitle shouldBe "Edited"
    }

    @Test
    fun `the edit logs the entry once and stamps its clock`() = runTest {
        repository.setDetailsOverride(1, titleOverride = "Edited", descriptionOverride = null)

        scalar("SELECT count(*) FROM sync_change_log WHERE entity_type = 'manga' AND entity_id = 1") shouldBe "1"
        scalar("SELECT details_modified_at FROM mangas WHERE _id = 1").shouldNotBeNull()
        // The same values again change nothing, so nothing logs.
        repository.setDetailsOverride(1, titleOverride = "Edited", descriptionOverride = null)
        scalar("SELECT count(*) FROM sync_change_log") shouldBe "1"
    }

    @Test
    fun `a source refresh rewrites the source's text and keeps the edit, and a reset shows the new text`() =
        runTest {
            repository.setDetailsOverride(1, titleOverride = "Edited", descriptionOverride = "Edited text")

            repository.update(MangaUpdate(id = 1, title = "Renamed by the source", description = "New source text"))

            val refreshed = repository.getMangaById(1)
            refreshed.displayTitle shouldBe "Edited"
            refreshed.title shouldBe "Renamed by the source"
            refreshed.displayDescription shouldBe "Edited text"

            repository.setDetailsOverride(1, titleOverride = null, descriptionOverride = null)

            val reset = repository.getMangaById(1)
            reset.displayTitle shouldBe "Renamed by the source"
            reset.displayDescription shouldBe "New source text"
        }
}

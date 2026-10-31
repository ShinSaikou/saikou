package com.shinsaikou.data.repository

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.manga.interactor.UpdateMangaNotes

/**
 * The entry screen's notes editor writes through [UpdateMangaNotes], and these check that the write
 * reaches sync the way the title and description edit does: the entry's notes clock moves and a
 * library entry is logged once, while an unchanged write logs nothing.
 */
class MangaNotesTest : RepositoryTest() {

    private val repository by lazy { MangaRepositoryImpl(handler) }
    private val updateMangaNotes by lazy { UpdateMangaNotes(repository) }

    @BeforeEach
    fun seed() {
        insertManga(id = 1)
        insertManga(id = 2, favorite = false)
        exec("DELETE FROM sync_change_log")
    }

    @Test
    fun `a notes edit is read back, stamps its clock and logs the library entry once`() = runTest {
        updateMangaNotes(1, "Stopped at the second arc") shouldBe true

        repository.getMangaById(1).notes shouldBe "Stopped at the second arc"
        scalar("SELECT notes_modified_at FROM mangas WHERE _id = 1").shouldNotBeNull()
        scalar("SELECT count(*) FROM sync_change_log WHERE entity_type = 'manga' AND entity_id = 1") shouldBe "1"
        // The same notes again change nothing, so nothing logs.
        updateMangaNotes(1, "Stopped at the second arc")
        scalar("SELECT count(*) FROM sync_change_log") shouldBe "1"
    }

    @Test
    fun `notes on an entry outside the library stamp the clock and log nothing`() = runTest {
        scalar("SELECT notes_modified_at FROM mangas WHERE _id = 2").shouldBeNull()

        updateMangaNotes(2, "Maybe later") shouldBe true

        scalar("SELECT notes_modified_at FROM mangas WHERE _id = 2").shouldNotBeNull()
        scalar("SELECT count(*) FROM sync_change_log") shouldBe "0"
    }
}

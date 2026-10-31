package com.shinsaikou.domain.manga.interactor

import com.shinsaikou.source.model.MangaInfo
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository

/**
 * When fetched details replace a manga's title (phase 4 unit SR, the coordinator's follow-up): as
 * upstream's UpdateMangaFromRemote does, a library entry keeps its title unless "Update library manga
 * titles to match source" is on, and a manual refresh or a manual library update does not override
 * that. An entry outside the library always takes the source's title.
 */
class UpdateMangaTitleTest {

    private val mangaRepository = mockk<MangaRepository>()
    private val written = slot<MangaUpdate>()
    private val updateManga = UpdateManga(mangaRepository, mockk<FetchInterval>())

    init {
        coEvery { mangaRepository.update(capture(written)) } returns true
    }

    private fun titleWritten(favorite: Boolean, manualFetch: Boolean, updateTitles: Boolean): String? {
        var title: String? = "not written"
        runTest {
            updateManga.awaitUpdateFromSource(
                localManga = Manga.create().copy(id = 7L, title = "Old title", favorite = favorite),
                remote = MangaInfo(url = "/entry", title = "Source title"),
                manualFetch = manualFetch,
                updateTitles = updateTitles,
            )
            title = written.captured.title
        }
        return title
    }

    @Test
    fun `a manual refresh keeps a library entry's title while the setting is off`() {
        titleWritten(favorite = true, manualFetch = true, updateTitles = false) shouldBe null
    }

    @Test
    fun `an automatic update keeps a library entry's title while the setting is off`() {
        titleWritten(favorite = true, manualFetch = false, updateTitles = false) shouldBe null
    }

    @Test
    fun `with the setting on a library entry takes the source's title, manual or not`() {
        titleWritten(favorite = true, manualFetch = true, updateTitles = true) shouldBe "Source title"
        titleWritten(favorite = true, manualFetch = false, updateTitles = true) shouldBe "Source title"
    }

    @Test
    fun `an entry outside the library always takes the source's title`() {
        titleWritten(favorite = false, manualFetch = false, updateTitles = false) shouldBe "Source title"
    }

    /**
     * Phase 5 unit ED (R138 item 2): an edited title is no gate. The source's title and description
     * go to the plain columns as for any entry, and the update names no override, so the edit stays
     * and a reset shows the source's current text at once.
     */
    @Test
    fun `an edited entry's refresh writes the source's text and leaves the edit alone`() {
        runTest {
            updateManga.awaitUpdateFromSource(
                localManga = Manga.create().copy(
                    id = 7L, title = "Old title", favorite = true, titleOverride = "Edited", descriptionOverride = "Mine",
                ),
                remote = MangaInfo(url = "/entry", title = "Source title", description = "Source text"),
                manualFetch = true,
                updateTitles = true,
            )
        }
        written.captured.title shouldBe "Source title"
        written.captured.description shouldBe "Source text"
    }

    @Test
    fun `a blank source title is never written`() {
        var title: String? = "not written"
        runTest {
            updateManga.awaitUpdateFromSource(
                localManga = Manga.create().copy(id = 7L, title = "Old title", favorite = false),
                remote = MangaInfo(url = "/entry", title = " "),
                manualFetch = true,
                updateTitles = true,
            )
            title = written.captured.title
        }
        title shouldBe null
    }
}

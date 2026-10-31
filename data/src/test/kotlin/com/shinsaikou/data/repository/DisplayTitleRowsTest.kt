package com.shinsaikou.data.repository

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.data.history.HistoryRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.updates.UpdatesRepositoryImpl

/**
 * The Updates and History rows carry the title those screens show, the owner's edit over the
 * source's (phase 5 unit ED), beside the source's own; History's search finds an entry by either.
 */
class DisplayTitleRowsTest : RepositoryTest() {

    private val mangaRepository by lazy { MangaRepositoryImpl(handler) }
    private val historyRepository by lazy { HistoryRepositoryImpl(handler) }
    private val updatesRepository by lazy { UpdatesRepositoryImpl(handler) }

    @BeforeEach
    fun seed() {
        insertManga(id = 1)
        insertChapter(id = 10, mangaId = 1)
        exec("UPDATE chapters SET date_fetch = 2000, date_upload = 2000 WHERE _id = 10")
        exec("INSERT INTO history(chapter_id, last_read, time_read) VALUES (10, 3000, 1)")
    }

    @Test
    fun `an unedited entry's rows show the source's title`() = runTest {
        historyRepository.getHistory("").first().single().displayTitle shouldBe "Title 1"
        updates().single().displayTitle shouldBe "Title 1"
    }

    @Test
    fun `an edited entry's rows show the edit and keep the source's title beside it`() = runTest {
        mangaRepository.setDetailsOverride(1, titleOverride = "My name for it", descriptionOverride = null)

        val history = historyRepository.getHistory("").first().single()
        history.displayTitle shouldBe "My name for it"
        history.title shouldBe "Title 1"
        historyRepository.getLastHistory()?.displayTitle shouldBe "My name for it"

        val update = updates().single()
        update.displayTitle shouldBe "My name for it"
        update.mangaTitle shouldBe "Title 1"
    }

    @Test
    fun `history's search finds an edited entry by the shown title or the source's`() = runTest {
        mangaRepository.setDetailsOverride(1, titleOverride = "My name for it", descriptionOverride = null)

        historyRepository.getHistory("name for").first().map { it.mangaId } shouldBe listOf(1L)
        historyRepository.getHistory("title 1").first().map { it.mangaId } shouldBe listOf(1L)
        historyRepository.getHistory("absent").first() shouldBe emptyList()
    }

    /** The Updates tab's rows with no filter set. */
    private suspend fun updates() = updatesRepository.subscribeAll(
        after = 0,
        limit = 10,
        unread = null,
        started = null,
        bookmarked = null,
        hideExcludedScanlators = false,
        includedCategories = emptyList(),
        excludedCategories = emptyList(),
    ).first()
}

package com.shinsaikou.reader

import com.shinsaikou.domain.manga.model.MangaNotFoundException
import com.shinsaikou.reader.state.MANGA_ID
import com.shinsaikou.reader.state.ReaderEntries
import com.shinsaikou.reader.state.ReaderPhase
import com.shinsaikou.reader.state.chapter
import com.shinsaikou.reader.state.manga
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import kotlinx.coroutines.Dispatchers as KotlinxDispatchers

/**
 * A merged title's other entries (R163): the row of any entry the session reads going ends the
 * session, naming that entry, and a row that only changes decides nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelMergedGoneTest {

    private val testDispatcher = StandardTestDispatcher()

    private val firstEntry = manga(id = MANGA_ID, source = 1L)
    private val secondEntry = manga(id = SECOND_ID, source = 2L)

    /** The second entry's row as the database holds it. */
    private val secondRow = MutableStateFlow<Manga?>(secondEntry)

    private val chapters = listOf(
        chapter(1L, mangaId = MANGA_ID),
        chapter(2L, mangaId = MANGA_ID),
        chapter(3L, mangaId = SECOND_ID),
    )

    @BeforeEach
    fun setUp() {
        KotlinxDispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        KotlinxDispatchers.resetMain()
    }

    private fun opened() = readerViewModel(
        savedState = openedOn(chapterId = 1L),
        chapters = chapters,
        dispatcher = testDispatcher,
        getManga = mockk<GetManga> {
            coEvery { await(MANGA_ID) } returns firstEntry
            every { subscribeOrNull(MANGA_ID) } returns emptyFlow()
            every { subscribeOrNull(SECOND_ID) } returns secondRow
        },
        readerEntries = ReaderEntries { listOf(firstEntry, secondEntry) },
    )

    @Test
    fun `the second entry's row going ends the session, naming that entry`() = runTest(testDispatcher) {
        val viewModel = opened()
        advanceUntilIdle()
        viewModel.state.value.phase shouldBe ReaderPhase.Ready

        secondRow.value = null
        advanceUntilIdle()

        val phase = viewModel.state.value.phase.shouldBeInstanceOf<ReaderPhase.Failed>()
        phase.error.shouldBeInstanceOf<MangaNotFoundException>().message shouldBe
            MangaNotFoundException(SECOND_ID).message
    }

    @Test
    fun `the second entry's row changing leaves the session open`() = runTest(testDispatcher) {
        val viewModel = opened()
        advanceUntilIdle()

        secondRow.value = secondEntry.copy(title = "renamed", viewerFlags = 3L)
        advanceUntilIdle()

        viewModel.state.value.phase shouldBe ReaderPhase.Ready
    }

    @Test
    fun `a session of one entry watches no other row`() = runTest(testDispatcher) {
        val viewModel = readerViewModel(
            savedState = openedOn(chapterId = 1L),
            chapters = chapters.filter { it.mangaId == MANGA_ID },
            dispatcher = testDispatcher,
            getManga = mockk<GetManga> {
                coEvery { await(MANGA_ID) } returns firstEntry
                every { subscribeOrNull(MANGA_ID) } returns emptyFlow()
            },
        )
        secondRow.value = null
        advanceUntilIdle()

        viewModel.state.value.phase shouldBe ReaderPhase.Ready
    }

    private companion object {
        const val SECOND_ID = 20L
    }
}

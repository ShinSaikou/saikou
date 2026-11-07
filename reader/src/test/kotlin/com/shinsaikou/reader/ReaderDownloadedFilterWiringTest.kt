package com.shinsaikou.reader

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.shinsaikou.core.common.preferences.BasePreferences
import com.shinsaikou.domain.chapter.interactor.GetBookmarkNotes
import com.shinsaikou.domain.chapter.interactor.GetScanlatorPriority
import com.shinsaikou.domain.chapter.interactor.RemoveBookmarkNote
import com.shinsaikou.domain.chapter.interactor.ToggleBookmark
import com.shinsaikou.domain.chapter.interactor.UpsertBookmarkNote
import com.shinsaikou.domain.download.DownloadedChapters
import com.shinsaikou.domain.manga.interactor.SetMangaViewerFlags
import com.shinsaikou.domain.preferences.ReaderPreferences
import com.shinsaikou.reader.memory.MemoryInfoProvider
import com.shinsaikou.reader.pipeline.PageLoaderResolver
import com.shinsaikou.reader.state.IncognitoPolicy
import com.shinsaikou.reader.state.PreloadPolicy
import com.shinsaikou.reader.state.ProgressCommitter
import io.kotest.matchers.collections.shouldContainExactly
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers
import kotlinx.coroutines.Dispatchers as KotlinxDispatchers

/**
 * The reader's half of R45's sixth site, above `ReaderSession.isFilteredOut`: the view model has to
 * ask [DownloadedChapters] for the entry's set and hand it to the session it builds, and it only
 * asks when the downloaded filter is set, because the answer costs a folder check per manifest row.
 * `ReaderSessionTest` covers what the session does with the set; this covers that the set arrives.
 *
 * The page loader is left to fail: the session is built before any chapter loads, and it is the
 * session's chapter list that is read here.
 *
 * The title's scanlator ranking (phase 4 unit SC) reaches the session the same way: read through
 * [GetScanlatorPriority] only while "Skip duplicate chapters" is on, since only that pass reads it.
 * `ReaderSessionTest` and `ChapterRemoveDuplicatesTest` cover the pick itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderDownloadedFilterWiringTest {

    private val testDispatcher = StandardTestDispatcher()

    private val getManga = mockk<GetManga>()
    private val getChaptersByMangaId = mockk<GetChaptersByMangaId>()
    private val downloadedChapters = mockk<DownloadedChapters>()
    private val pageLoaderResolver = mockk<PageLoaderResolver>()
    private val getScanlatorPriority = mockk<GetScanlatorPriority>()
    private val readerPreferences = ReaderPreferences(InMemoryPreferenceStore())
    private val basePreferences = BasePreferences(InMemoryPreferenceStore())

    private val chapters = (1L..3L).map { id ->
        Chapter.create().copy(
            id = id,
            mangaId = MANGA_ID,
            url = "/chapter/$id",
            name = "Chapter $id",
            chapterNumber = id.toDouble(),
        )
    }

    @BeforeEach
    fun setUp() {
        KotlinxDispatchers.setMain(testDispatcher)
        coEvery { getChaptersByMangaId.await(MANGA_ID, applyScanlatorFilter = true) } returns chapters
        every { downloadedChapters.subscribeByMangaId(MANGA_ID) } returns flowOf(setOf(1L, 3L))
        coEvery { pageLoaderResolver.resolve(any(), any(), any(), any()) } throws IllegalStateException("not loaded")
    }

    @AfterEach
    fun tearDown() {
        KotlinxDispatchers.resetMain()
    }

    @Test
    fun `the session under the downloaded filter holds the downloaded chapters and the tapped one`() =
        runTest(testDispatcher) {
            stubManga(Manga.CHAPTER_SORTING_NUMBER or Manga.CHAPTER_SHOW_DOWNLOADED)

            val viewModel = viewModel(openedChapter = 1L)
            advanceUntilIdle()

            val session = checkNotNull(viewModel.state.value.session) {
                "no session: ${viewModel.state.value.phase}"
            }
            session.chapters.map { it.chapter.id } shouldContainExactly listOf(1L, 3L)
        }

    @Test
    fun `with no downloaded filter the set is never asked for`() = runTest(testDispatcher) {
        stubManga(Manga.CHAPTER_SORTING_NUMBER)

        val viewModel = viewModel(openedChapter = 1L)
        advanceUntilIdle()

        val session = checkNotNull(viewModel.state.value.session) { "no session: ${viewModel.state.value.phase}" }
        session.chapters.map { it.chapter.id } shouldContainExactly listOf(1L, 2L, 3L)
        verify(exactly = 0) { downloadedChapters.subscribeByMangaId(any()) }
        coVerify(exactly = 0) { getScanlatorPriority.await(any()) }
    }

    /**
     * The app's downloaded only mode, which upstream's reader applies too: with no filter on the
     * entry, the session holds the downloaded chapters (1 and 3) and the tapped one.
     */
    @Test
    fun `under downloaded only the session holds the downloaded chapters and the tapped one`() =
        runTest(testDispatcher) {
            stubManga(Manga.CHAPTER_SORTING_NUMBER)
            basePreferences.downloadedOnly.set(true)

            val viewModel = viewModel(openedChapter = 1L)
            advanceUntilIdle()

            val session = checkNotNull(viewModel.state.value.session) {
                "no session: ${viewModel.state.value.phase}"
            }
            session.chapters.map { it.chapter.id } shouldContainExactly listOf(1L, 3L)
        }

    /**
     * Chapters 1 to 3 are Group A's, and 4 and 5 are Group B's copies of numbers 2 and 3. Opened on
     * Group A's chapter 1, upstream's rule keeps Group A's copies; with Group B ranked, its copies.
     */
    @Test
    fun `under skip duplicates the session holds the ranked group's copies`() = runTest(testDispatcher) {
        stubManga(Manga.CHAPTER_SORTING_NUMBER)
        readerPreferences.skipDupe.set(true)
        val groupA = chapters.map { it.copy(scanlator = "Group A") }
        val groupB = listOf(4L to 2.0, 5L to 3.0).map { (id, number) ->
            groupA.first().copy(id = id, url = "/chapter/$id", chapterNumber = number, scanlator = "Group B")
        }
        coEvery { getChaptersByMangaId.await(MANGA_ID, applyScanlatorFilter = true) } returns groupA + groupB
        coEvery { getScanlatorPriority.await(MANGA_ID) } returns mapOf("Group B" to 0L)

        val viewModel = viewModel(openedChapter = 1L)
        advanceUntilIdle()

        val session = checkNotNull(viewModel.state.value.session) { "no session: ${viewModel.state.value.phase}" }
        session.chapters.map { it.chapter.id } shouldContainExactly listOf(1L, 4L, 5L)
        coVerify(exactly = 1) { getScanlatorPriority.await(MANGA_ID) }
    }

    private fun stubManga(chapterFlags: Long) {
        val manga = Manga.create()
            .copy(id = MANGA_ID, source = SOURCE_ID, title = "Fixture Manga", chapterFlags = chapterFlags)
        coEvery { getManga.await(MANGA_ID) } returns manga
        every { getManga.subscribeOrNull(MANGA_ID) } returns flowOf(manga)
    }

    private fun viewModel(openedChapter: Long) = ReaderViewModel(
        savedState = SavedStateHandle(
            mapOf(ReaderIntents.KEY_MANGA_ID to MANGA_ID, ReaderIntents.KEY_CHAPTER_ID to openedChapter),
        ),
        sourceManager = readyRegistry(),
        getManga = getManga,
        getChaptersByMangaId = getChaptersByMangaId,
        // No row by id: a chapter loaded inside the session keeps the row it was built from.
        getChapter = mockk<GetChapter> { coEvery { await(any<Long>()) } returns null },
        getScanlatorPriority = getScanlatorPriority,
        setMangaViewerFlags = mockk<SetMangaViewerFlags>(relaxed = true),
        toggleBookmark = mockk<ToggleBookmark>(relaxed = true),
        getBookmarkNotes = GetBookmarkNotes(FakeBookmarkNoteRepository()),
        upsertBookmarkNote = mockk<UpsertBookmarkNote>(),
        removeBookmarkNote = mockk<RemoveBookmarkNote>(),
        pageLoaderResolver = pageLoaderResolver,
        downloadedChapters = downloadedChapters,
        basePreferences = basePreferences,
        progressCommitterFactory = mockk<ProgressCommitter.Factory> {
            every { create(any()) } returns mockk(relaxed = true)
        },
        incognitoPolicy = mockk<IncognitoPolicy> { every { isIncognito(any()) } returns false },
        readerEntries = { listOf(it) },
        readerPreferences = readerPreferences,
        preloadPolicy = mockk<PreloadPolicy> { every { compute() } returns PreloadPolicy.DEFAULT },
        animatedImagePolicy = mockk(relaxed = true),
        dispatchers = AppDispatchers(io = testDispatcher, default = testDispatcher, main = testDispatcher),
        context = mockk<Context>(relaxed = true),
        memory = mockk<MemoryInfoProvider>(relaxed = true),
    )

    private companion object {
        const val MANGA_ID = 1L
        const val SOURCE_ID = 7L
    }
}

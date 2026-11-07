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
import com.shinsaikou.reader.pipeline.PageLoader
import com.shinsaikou.reader.pipeline.PageLoaderResolver
import com.shinsaikou.reader.pipeline.PageSplit
import com.shinsaikou.reader.state.IncognitoPolicy
import com.shinsaikou.reader.state.MANGA_ID
import com.shinsaikou.reader.state.PreloadPolicy
import com.shinsaikou.reader.state.ProgressCommitter
import com.shinsaikou.reader.state.ReaderEvent
import com.shinsaikou.reader.state.ReaderPhase
import com.shinsaikou.reader.state.chapter
import com.shinsaikou.reader.state.manga
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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
import tachiyomi.domain.manga.interactor.GetManga
import kotlinx.coroutines.Dispatchers as KotlinxDispatchers
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers

/**
 * The reader's half of contract 07 part 2 section 9.2's process-death restore, which
 * `tools/ci/reader_process_death.sh` checks on a device.
 *
 * Found by that check (unit K's report): the handle's page was written on every committed page
 * and never read, so a reader killed in the background came back on page 1, and its chapter was
 * never written at all, so a reader that had moved on to the next chapter came back on the one
 * the intent named. What is asserted here is the handle and nothing the viewer does: a handle
 * carrying a chapter and a page opens there, and the chapter and page the reader shows are the
 * ones the handle holds, incognito included, with a slider drag counted only once it is let go.
 *
 * Each test drives a real [ReaderViewModel] over a two-chapter session whose loaders hand back
 * [PAGES] plain pages, so the state machine's own chapter and page rules run.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelRestoreTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        KotlinxDispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        KotlinxDispatchers.resetMain()
    }

    @Test
    fun `a handle that carries a chapter and a page reopens on that page`() = runTest(testDispatcher) {
        // Set rather than put in the map: a map literal mixing Long and Int values boxes the page as a
        // Long, which no handle the platform restores would hold.
        val handle = startedAt(chapterId = 2L).apply { set(ReaderIntents.KEY_PAGE, 3) }
        val viewModel = viewModel(handle)
        advanceUntilIdle()

        val state = viewModel.state.value
        state.phase shouldBe ReaderPhase.Ready
        state.currentChapter?.chapter?.id shouldBe 2L
        state.currentPage shouldBe 3
    }

    @Test
    fun `the page and the chapter the reader moves to are kept in the handle, incognito too`() =
        runTest(testDispatcher) {
            val handle = startedAt(chapterId = 1L)
            val viewModel = viewModel(handle, incognito = true)
            advanceUntilIdle()
            handle.position() shouldBe (1L to 0)

            viewModel.send(ReaderEvent.PageSelected(viewModel.chapter(1L).pages!![2]))
            advanceUntilIdle()
            handle.position() shouldBe (1L to 2)

            viewModel.send(ReaderEvent.PageSelected(viewModel.chapter(2L).pages!![1]))
            advanceUntilIdle()
            viewModel.state.value.currentChapter?.chapter?.id shouldBe 2L
            handle.position() shouldBe (2L to 1)
        }

    @Test
    fun `a page the slider passes through is kept only once the slider is let go`() = runTest(testDispatcher) {
        val handle = startedAt(chapterId = 1L)
        val viewModel = viewModel(handle)
        advanceUntilIdle()

        viewModel.send(ReaderEvent.SliderMoved(3))
        advanceUntilIdle()
        viewModel.state.value.scrubbing shouldBe true
        handle.position() shouldBe (1L to 0)

        viewModel.send(ReaderEvent.SliderReleased(3))
        advanceUntilIdle()
        handle.position() shouldBe (1L to 3)
    }

    private fun startedAt(chapterId: Long) = SavedStateHandle(
        mapOf(ReaderIntents.KEY_MANGA_ID to MANGA_ID, ReaderIntents.KEY_CHAPTER_ID to chapterId),
    )

    private fun SavedStateHandle.position(): Pair<Long?, Int?> =
        get<Long>(ReaderIntents.KEY_CHAPTER_ID) to get<Int>(ReaderIntents.KEY_PAGE)

    private fun ReaderViewModel.chapter(id: Long): ReaderChapter =
        checkNotNull(state.value.session).chapters.first { it.chapter.id == id }

    private fun viewModel(savedState: SavedStateHandle, incognito: Boolean = false): ReaderViewModel {
        val getManga = mockk<GetManga> {
            coEvery { await(MANGA_ID) } returns manga()
            every { subscribeOrNull(MANGA_ID) } returns emptyFlow()
        }
        val getChapters = mockk<GetChaptersByMangaId> {
            coEvery { await(MANGA_ID, any()) } returns listOf(chapter(1L), chapter(2L))
        }
        val resolver = mockk<PageLoaderResolver> {
            coEvery { resolve(any(), any(), any(), any()) } answers { PlainLoader() }
        }
        return ReaderViewModel(
            savedState = savedState,
            sourceManager = readyRegistry(),
            getManga = getManga,
            getChaptersByMangaId = getChapters,
            // No row by id: a chapter loaded inside the session keeps the row it was built from.
            getChapter = mockk<GetChapter> { coEvery { await(any<Long>()) } returns null },
            getScanlatorPriority = mockk<GetScanlatorPriority>(),
            setMangaViewerFlags = mockk<SetMangaViewerFlags>(relaxed = true),
            toggleBookmark = mockk<ToggleBookmark>(),
            getBookmarkNotes = GetBookmarkNotes(FakeBookmarkNoteRepository()),
            upsertBookmarkNote = mockk<UpsertBookmarkNote>(),
            removeBookmarkNote = mockk<RemoveBookmarkNote>(),
            pageLoaderResolver = resolver,
            downloadedChapters = mockk<DownloadedChapters>(),
            basePreferences = BasePreferences(InMemoryPreferenceStore()),
            progressCommitterFactory = mockk<ProgressCommitter.Factory> {
                every { create(any()) } returns mockk(relaxed = true)
            },
            incognitoPolicy = mockk<IncognitoPolicy> { every { isIncognito(any()) } returns incognito },
            readerEntries = { listOf(it) },
            readerPreferences = ReaderPreferences(InMemoryPreferenceStore()),
            preloadPolicy = mockk<PreloadPolicy> { every { compute() } returns PreloadPolicy.DEFAULT },
            animatedImagePolicy = mockk(),
            dispatchers = AppDispatchers(io = testDispatcher, default = testDispatcher, main = testDispatcher),
            context = mockk<Context>(relaxed = true),
            memory = NoMemory,
        )
    }

    private class PlainLoader : PageLoader {
        override val isLocal = true
        override val isRecycled = false
        override val splits: Flow<PageSplit> = emptyFlow()

        override suspend fun getPages(): List<ReaderPage> = List(PAGES) { ReaderPage(it) }
        override suspend fun loadPage(page: ReaderPage) = Unit
        override fun retryPage(page: ReaderPage) = Unit
        override fun recycle() = Unit
    }

    private object NoMemory : MemoryInfoProvider {
        override val maxHeapBytes: Long = 1_000_000_000L
        override val isLowRamDevice: Boolean = false
        override val usedHeapBytes: Long = 0L
    }

    private companion object {
        const val PAGES = 5
    }
}

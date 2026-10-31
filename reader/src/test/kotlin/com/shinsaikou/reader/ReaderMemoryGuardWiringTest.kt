package com.shinsaikou.reader

import android.content.ComponentCallbacks
import android.content.ComponentCallbacks2
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.shinsaikou.domain.chapter.interactor.GetScanlatorPriority
import com.shinsaikou.domain.preferences.ReaderPreferences
import com.shinsaikou.reader.memory.MemoryInfoProvider
import com.shinsaikou.reader.memory.ReaderMemoryGuard
import com.shinsaikou.reader.pipeline.AnimatedImagePolicy
import com.shinsaikou.reader.pipeline.PageLoaderResolver
import com.shinsaikou.reader.state.IncognitoPolicy
import com.shinsaikou.reader.state.PreloadPolicy
import com.shinsaikou.reader.state.ProgressCommitter
import com.shinsaikou.reader.state.ReaderEvent
import com.shinsaikou.reader.state.loadedChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import com.shinsaikou.domain.chapter.interactor.GetBookmarkNotes
import com.shinsaikou.domain.chapter.interactor.RemoveBookmarkNote
import com.shinsaikou.domain.chapter.interactor.ToggleBookmark
import com.shinsaikou.domain.chapter.interactor.UpsertBookmarkNote
import com.shinsaikou.domain.download.DownloadedChapters
import com.shinsaikou.domain.manga.interactor.SetMangaViewerFlags
import kotlinx.coroutines.Dispatchers as KotlinxDispatchers
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers

/**
 * The wiring contract 07 part 1b section 4.10 gives [ReaderMemoryGuard], which nothing held up
 * until now: the guard existed, `ReaderMemoryGuardTest` covered its trim table and its watermark,
 * and no production code ever constructed it. So the reader's whole answer to memory pressure was
 * unreachable, and the only trim the reader saw was the platform's, which on API 34 and up is
 * delivered when the process is already in the background.
 *
 * What is asserted here is the wiring and nothing the guard itself does: registration on the
 * application context, unregistration when the view model is cleared, and the two call sites of
 * `sample()`. The view model constructs the guard itself, so the guard's own instance is observed
 * through the context it registers on and through the [MemoryInfoProvider] it reads.
 *
 * Every test builds its own view model, which is also what makes the sampling tests honest: the
 * guard rate limits itself to one sample in `SAMPLE_INTERVAL_MS` and its default clock is
 * `SystemClock.uptimeMillis`, which is a frozen zero under `isReturnDefaultValues`, so a second
 * sample in the same view model could never be seen whether it happened or not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("DEPRECATION") // TRIM_MEMORY_RUNNING_LOW, one of the five levels the guard exists for.
class ReaderMemoryGuardWiringTest {

    private val testDispatcher = StandardTestDispatcher()

    private val context = mockk<Context>(relaxed = true)
    private val memory = CountingMemory()

    @BeforeEach
    fun setUp() {
        KotlinxDispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        KotlinxDispatchers.resetMain()
    }

    @Test
    fun `one guard is registered per view model, on the context the graph injects`() = runTest(testDispatcher) {
        viewModel()
        advanceUntilIdle()

        val registered = slot<ComponentCallbacks>()
        verify(exactly = 1) { context.registerComponentCallbacks(capture(registered)) }
        registered.captured.shouldBeInstanceOf<ReaderMemoryGuard>()
        verify(exactly = 0) { context.unregisterComponentCallbacks(any()) }
    }

    @Test
    fun `clearing the view model unregisters the guard it registered`() = runTest(testDispatcher) {
        val store = ViewModelStore()
        store.put("reader", viewModel())
        advanceUntilIdle()

        val registered = slot<ComponentCallbacks>()
        verify(exactly = 1) { context.registerComponentCallbacks(capture(registered)) }

        store.clear()

        verify(exactly = 1) { context.unregisterComponentCallbacks(registered.captured) }
    }

    @Test
    fun `a page selection samples the heap`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        memory.reads shouldBe 0

        viewModel.send(ReaderEvent.PageSelected(ReaderPage(0)))
        advanceUntilIdle()

        memory.reads shouldBe 1
    }

    @Test
    fun `a chapter load samples the heap`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.send(ReaderEvent.ChapterLoaded(loadedChapter(1L), requestedPage = 0))
        advanceUntilIdle()

        memory.reads shouldBe 1
    }

    @Test
    fun `no other event samples the heap`() = runTest(testDispatcher) {
        val viewModel = viewModel()

        listOf(
            ReaderEvent.Resumed,
            ReaderEvent.ToggleMenu,
            ReaderEvent.AdjacentChapterLoaded(loadedChapter(2L)),
            ReaderEvent.SliderMoved(3),
            ReaderEvent.TrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW),
            ReaderEvent.Paused,
        ).forEach(viewModel::send)
        advanceUntilIdle()

        memory.reads shouldBe 0
    }

    /**
     * The activity's half of the same ruling. The framework delivers a trim to the activity and to
     * every registered `ComponentCallbacks2`, so with the guard registered an activity override
     * would run the trim twice per trim, on the main thread, which part 2 section 8.3 lists as a
     * jank source. The override is therefore gone rather than emptied, and this asserts its absence
     * directly; if a later change needs the override point back for something else, replace this
     * with an assertion that it does not send `ReaderEvent.TrimMemory`.
     *
     * The three overrides the activity does declare are asserted alongside it, so a scan that stops
     * seeing the class fails here instead of passing having looked at nothing.
     */
    @Test
    fun `the activity does not handle a trim itself`() {
        val declared = ReaderActivity::class.java.declaredMethods.map { it.name }

        declared shouldNotContain "onTrimMemory"
        declared shouldContainAll listOf("onResume", "onPause", "finish")
    }

    private fun viewModel() = ReaderViewModel(
        // Empty, so loadSession reports a missing manga and chapter and stops before it touches the
        // database. This class is about the guard, and a loaded session would only add fixtures.
        savedState = SavedStateHandle(),
        sourceManager = mockk(),
        getManga = mockk<GetManga>(),
        getChaptersByMangaId = mockk<GetChaptersByMangaId>(),
        getChapter = mockk<GetChapter>(),
        getScanlatorPriority = mockk<GetScanlatorPriority>(),
        setMangaViewerFlags = mockk<SetMangaViewerFlags>(),
        toggleBookmark = mockk<ToggleBookmark>(),
        getBookmarkNotes = GetBookmarkNotes(FakeBookmarkNoteRepository()),
        upsertBookmarkNote = mockk<UpsertBookmarkNote>(),
        removeBookmarkNote = mockk<RemoveBookmarkNote>(),
        pageLoaderResolver = mockk<PageLoaderResolver>(),
        downloadedChapters = mockk<DownloadedChapters>(),
        progressCommitterFactory = progressCommitterFactory(),
        incognitoPolicy = mockk<IncognitoPolicy>(),
        readerPreferences = ReaderPreferences(InMemoryPreferenceStore()),
        preloadPolicy = mockk<PreloadPolicy> { every { compute() } returns PreloadPolicy.DEFAULT },
        animatedImagePolicy = mockk<AnimatedImagePolicy>(),
        dispatchers = AppDispatchers(io = testDispatcher, default = testDispatcher, main = testDispatcher),
        context = context,
        memory = memory,
    )

    private fun progressCommitterFactory() = mockk<ProgressCommitter.Factory> {
        every { create(any()) } returns mockk(relaxed = true)
    }

    /** Counts what [ReaderMemoryGuard.sample] reads, which is the only trace a sample leaves. */
    private class CountingMemory : MemoryInfoProvider {
        var reads = 0
            private set

        override val maxHeapBytes: Long = 1_000_000_000L
        override val isLowRamDevice: Boolean = false
        override val usedHeapBytes: Long
            get() {
                reads++
                return 0L
            }
    }

}

package com.shinsaikou.reader

import app.cash.turbine.test
import com.shinsaikou.domain.download.DownloadedChapters
import com.shinsaikou.reader.pipeline.PageLoaderResolver
import com.shinsaikou.reader.state.IncognitoPolicy
import com.shinsaikou.reader.state.MANGA_ID
import com.shinsaikou.reader.state.ReaderEntries
import com.shinsaikou.reader.state.ReaderEvent
import com.shinsaikou.reader.state.ReaderPhase
import com.shinsaikou.reader.state.chapter
import com.shinsaikou.reader.state.manga
import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceManager
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import kotlinx.coroutines.Dispatchers as KotlinxDispatchers

/**
 * A title of two entries on two sources (R163): each chapter is served by the entry it belongs to,
 * while the session stands on the first entry, which names the title and holds the filters.
 *
 * The first entry is [MANGA_ID] on [FIRST_SOURCE]; the second is [SECOND_ID] on [SECOND_SOURCE].
 * Chapters 1 and 2 belong to the first, 3 and 4 to the second.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelEntriesTest {

    private val testDispatcher = StandardTestDispatcher()

    private val firstEntry = manga(id = MANGA_ID, source = FIRST_SOURCE)
    private val secondEntry = manga(id = SECOND_ID, source = SECOND_SOURCE)
    private val firstSource = mockk<Source>(relaxed = true)
    private val secondSource = mockk<Source>(relaxed = true)

    private val chapters = listOf(
        chapter(1L, mangaId = MANGA_ID),
        chapter(2L, mangaId = MANGA_ID),
        chapter(3L, mangaId = SECOND_ID),
        chapter(4L, mangaId = SECOND_ID),
    )

    private val twoEntries = ReaderEntries { listOf(firstEntry, secondEntry) }

    @BeforeEach
    fun setUp() {
        KotlinxDispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        KotlinxDispatchers.resetMain()
    }

    @Test
    fun `a chapter of the second entry is resolved with that entry and its source`() = runTest(testDispatcher) {
        val resolver = RecordingResolver()
        val viewModel = merged(openedOn(chapterId = 3L), resolver = resolver.mock)
        advanceUntilIdle()

        resolver.calls.first { it.chapterId == 3L }.let {
            it.ownerId shouldBe SECOND_ID
            it.source shouldBe secondSource
        }
        verify { registry.getOrStub(SECOND_SOURCE) }
        viewModel.state.value.currentChapter?.chapter?.id shouldBe 3L
    }

    @Test
    fun `a chapter of the first entry is resolved with the first entry and its source`() = runTest(testDispatcher) {
        val resolver = RecordingResolver()
        merged(openedOn(chapterId = 1L), resolver = resolver.mock)
        advanceUntilIdle()

        resolver.calls.first { it.chapterId == 1L }.let {
            it.ownerId shouldBe MANGA_ID
            it.source shouldBe firstSource
        }
        verify { registry.getOrStub(FIRST_SOURCE) }
    }

    @Test
    fun `opening on a chapter of the second entry works, and the state stands on the first entry`() =
        runTest(testDispatcher) {
            val viewModel = merged(openedOn(chapterId = 3L))
            advanceUntilIdle()

            val state = viewModel.state.value
            state.phase shouldBe ReaderPhase.Ready
            state.manga?.id shouldBe MANGA_ID
            state.currentChapter?.chapter?.id shouldBe 3L
            state.currentOwner?.id shouldBe SECOND_ID
        }

    @Test
    fun `the session holds both entries' chapters and Next crosses from one entry to the other`() =
        runTest(testDispatcher) {
            val resolver = RecordingResolver()
            val viewModel = merged(openedOn(chapterId = 2L), resolver = resolver.mock)
            advanceUntilIdle()
            checkNotNull(viewModel.state.value.session).chapters.map { it.chapter.id } shouldContainExactly
                listOf(1L, 2L, 3L, 4L)

            viewModel.send(ReaderEvent.PreloadRequested(viewModel.chapter(3L)))
            advanceUntilIdle()
            viewModel.send(ReaderEvent.NextChapter)
            advanceUntilIdle()

            viewModel.state.value.currentChapter?.chapter?.id shouldBe 3L
            viewModel.state.value.currentOwner?.id shouldBe SECOND_ID
            resolver.calls.first { it.chapterId == 3L }.source shouldBe secondSource
        }

    @Test
    fun `a chapter hidden by the scanlator filter in the second entry can still be opened`() =
        runTest(testDispatcher) {
            val viewModel = merged(openedOn(chapterId = 3L), excluded = setOf(3L, 4L))
            advanceUntilIdle()

            val state = viewModel.state.value
            state.phase shouldBe ReaderPhase.Ready
            state.currentChapter?.chapter?.id shouldBe 3L
            checkNotNull(state.session).chapters.map { it.chapter.id } shouldContainExactly listOf(1L, 2L, 3L)
        }

    @Test
    fun `the session is incognito when only the second entry's source is`() = runTest(testDispatcher) {
        val policy = mockk<IncognitoPolicy> {
            every { isIncognito(any()) } returns false
            every { isIncognito(SECOND_SOURCE) } returns true
        }
        val viewModel = merged(openedOn(chapterId = 1L), incognitoPolicy = policy)
        advanceUntilIdle()

        viewModel.state.value.incognito shouldBe true
    }

    @Test
    fun `the session is not incognito when neither entry's source is`() = runTest(testDispatcher) {
        val viewModel = merged(openedOn(chapterId = 1L))
        advanceUntilIdle()

        viewModel.state.value.incognito shouldBe false
    }

    @Test
    fun `downloadedChapterIds is the union of both entries and follows a change in either`() =
        runTest(testDispatcher) {
            val first = MutableStateFlow(setOf(1L))
            val second = MutableStateFlow(setOf(3L))
            val downloaded = mockk<DownloadedChapters> {
                every { subscribeByMangaId(MANGA_ID) } returns first
                every { subscribeByMangaId(SECOND_ID) } returns second
            }
            val viewModel = merged(openedOn(chapterId = 1L), downloaded = downloaded)
            advanceUntilIdle()

            viewModel.downloadedChapterIds.test {
                advanceUntilIdle()
                expectMostRecentItem() shouldBe setOf(1L, 3L)

                second.value = setOf(3L, 4L)
                advanceUntilIdle()
                expectMostRecentItem() shouldBe setOf(1L, 3L, 4L)

                first.value = emptySet()
                advanceUntilIdle()
                expectMostRecentItem() shouldBe setOf(3L, 4L)
            }
        }

    @Test
    fun `downloadedChapterIds follows the one entry of a single entry session`() = runTest(testDispatcher) {
        val only = MutableStateFlow(setOf(1L))
        val downloaded = mockk<DownloadedChapters> { every { subscribeByMangaId(MANGA_ID) } returns only }
        val viewModel = readerViewModel(
            openedOn(chapterId = 1L),
            listOf(chapter(1L), chapter(2L)),
            testDispatcher,
            downloadedChapters = downloaded,
        )
        advanceUntilIdle()

        viewModel.downloadedChapterIds.test {
            advanceUntilIdle()
            expectMostRecentItem() shouldBe setOf(1L)

            only.value = setOf(1L, 2L)
            advanceUntilIdle()
            expectMostRecentItem() shouldBe setOf(1L, 2L)
        }
    }

    @Test
    fun `a single entry from readerEntries leaves the session as it was`() = runTest(testDispatcher) {
        val viewModel = readerViewModel(
            openedOn(chapterId = 1L),
            listOf(chapter(1L), chapter(2L)),
            testDispatcher,
        )
        advanceUntilIdle()

        val session = checkNotNull(viewModel.state.value.session)
        session.entryIds shouldBe setOf(MANGA_ID)
        session.ownerOf(session.chapters.first().chapter).id shouldBe MANGA_ID
        session.ownerOf(session.chapters.last().chapter) shouldBe viewModel.state.value.manga
        viewModel.state.value.currentOwner shouldBe viewModel.state.value.manga
    }

    private val registry: SourceManager = mockk {
        every { isInitialized } returns MutableStateFlow(true)
        every { getOrStub(any()) } returns mockk(relaxed = true)
        every { getOrStub(FIRST_SOURCE) } returns firstSource
        every { getOrStub(SECOND_SOURCE) } returns secondSource
    }

    private fun merged(
        savedState: androidx.lifecycle.SavedStateHandle,
        resolver: PageLoaderResolver = resolverOf { PlainLoader() },
        excluded: Set<Long> = emptySet(),
        downloaded: DownloadedChapters = mockk(),
        incognitoPolicy: IncognitoPolicy = mockk { every { isIncognito(any()) } returns false },
    ) = readerViewModel(
        savedState = savedState,
        chapters = chapters,
        dispatcher = testDispatcher,
        sourceManager = registry,
        resolver = resolver,
        getManga = mockk {
            coEvery { await(MANGA_ID) } returns firstEntry
            every { subscribeOrNull(any()) } returns kotlinx.coroutines.flow.emptyFlow()
        },
        excluded = excluded,
        readerEntries = twoEntries,
        downloadedChapters = downloaded,
        incognitoPolicy = incognitoPolicy,
    )

    private class Call(val ownerId: Long, val chapterId: Long, val source: Source)

    private class RecordingResolver {
        val calls = mutableListOf<Call>()
        val mock: PageLoaderResolver = mockk {
            coEvery { resolve(any(), any(), any(), any()) } answers {
                calls += Call(firstArg<Manga>().id, secondArg<eu.kanade.tachiyomi.ui.reader.model.ReaderChapter>().chapter.id, thirdArg())
                PlainLoader()
            }
        }
    }

    private companion object {
        const val SECOND_ID = 20L
        const val FIRST_SOURCE = 1L
        const val SECOND_SOURCE = 2L
    }
}

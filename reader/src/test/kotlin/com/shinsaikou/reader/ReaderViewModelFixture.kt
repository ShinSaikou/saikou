package com.shinsaikou.reader

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.shinsaikou.core.common.preferences.BasePreferences
import com.shinsaikou.domain.chapter.interactor.GetBookmarkNotes
import com.shinsaikou.domain.chapter.interactor.GetScanlatorPriority
import com.shinsaikou.domain.chapter.interactor.RemoveBookmarkNote
import com.shinsaikou.domain.chapter.interactor.ToggleBookmark
import com.shinsaikou.domain.chapter.interactor.UpsertBookmarkNote
import com.shinsaikou.domain.chapter.model.BookmarkNote
import com.shinsaikou.domain.chapter.repository.BookmarkNoteRepository
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
import com.shinsaikou.reader.state.ReaderEntries
import com.shinsaikou.reader.state.manga
import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceManager
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers

/**
 * A real [ReaderViewModel] over one entry ([MANGA_ID]) whose chapters are [chapters], for the view
 * model tests that need a whole session: the state machine's chapter and page rules run as they do
 * on a device, and only the database, the registry and the page pipeline are fakes.
 *
 * [resolver] builds each chapter's page loader. The default hands out a [PlainLoader], whose splits
 * never complete, which is what the local and source loaders do too.
 *
 * [rows] is the chapter's row as the database holds it when a load reads it by id; by default the
 * row the session was built from. A test that writes progress during the session passes its own.
 * [committer] is a relaxed mock unless a test watches what the view model asks of it. [notes] backs
 * the three page note interactors, which are the real ones. [getManga] follows no row by default;
 * a test of the entry's row passes the real interactor over its own repository. [excluded] are the
 * chapters whose scanlator the owner excluded: the scanlator filter leaves them out of the list.
 * A title of several entries passes [readerEntries] and one flat [chapters] list, each chapter
 * served by the entry its `mangaId` names; [downloadedChapters] and [incognitoPolicy] default to a
 * strict mock and to one answering [incognito] for every source.
 */
internal fun readerViewModel(
    savedState: SavedStateHandle,
    chapters: List<Chapter>,
    dispatcher: CoroutineDispatcher,
    sourceManager: SourceManager = readyRegistry(),
    resolver: PageLoaderResolver = resolverOf { PlainLoader() },
    incognito: Boolean = false,
    rows: (Long) -> Chapter? = { id -> chapters.firstOrNull { it.id == id } },
    committer: ProgressCommitter = mockk(relaxed = true),
    notes: BookmarkNoteRepository = FakeBookmarkNoteRepository(),
    getManga: GetManga = mockk<GetManga> {
        coEvery { await(MANGA_ID) } returns manga()
        every { subscribeOrNull(MANGA_ID) } returns emptyFlow()
    },
    excluded: Set<Long> = emptySet(),
    readerEntries: ReaderEntries = ReaderEntries { listOf(it) },
    downloadedChapters: DownloadedChapters = mockk<DownloadedChapters>(),
    incognitoPolicy: IncognitoPolicy = mockk<IncognitoPolicy> { every { isIncognito(any()) } returns incognito },
): ReaderViewModel {
    // Each entry serves the chapters whose mangaId is its id, so a title of several entries passes
    // one flat list. An entry with none answers an empty list.
    val getChapters = mockk<GetChaptersByMangaId> {
        coEvery { await(any<Long>(), true) } answers {
            chapters.filter { it.mangaId == firstArg<Long>() && it.id !in excluded }
        }
        coEvery { await(any<Long>(), false) } answers { chapters.filter { it.mangaId == firstArg<Long>() } }
    }
    val getChapter = mockk<GetChapter> {
        coEvery { await(any<Long>()) } answers { rows(firstArg()) }
    }
    return ReaderViewModel(
        savedState = savedState,
        sourceManager = sourceManager,
        getManga = getManga,
        getChaptersByMangaId = getChapters,
        getChapter = getChapter,
        getScanlatorPriority = mockk<GetScanlatorPriority>(),
        setMangaViewerFlags = mockk<SetMangaViewerFlags>(relaxed = true),
        toggleBookmark = mockk<ToggleBookmark>(),
        getBookmarkNotes = GetBookmarkNotes(notes),
        upsertBookmarkNote = UpsertBookmarkNote(notes),
        removeBookmarkNote = RemoveBookmarkNote(notes),
        pageLoaderResolver = resolver,
        downloadedChapters = downloadedChapters,
        basePreferences = BasePreferences(InMemoryPreferenceStore()),
        progressCommitterFactory = mockk<ProgressCommitter.Factory> {
            every { create(any()) } returns committer
        },
        incognitoPolicy = incognitoPolicy,
        readerEntries = readerEntries,
        readerPreferences = ReaderPreferences(InMemoryPreferenceStore()),
        preloadPolicy = mockk<PreloadPolicy> { every { compute() } returns PreloadPolicy.DEFAULT },
        animatedImagePolicy = mockk(),
        dispatchers = AppDispatchers(io = dispatcher, default = dispatcher, main = dispatcher),
        context = mockk<Context>(relaxed = true),
        memory = NoMemory,
    )
}

/** The handle a fresh open gets: the entry and the chapter the intent named, no page. */
internal fun openedOn(chapterId: Long, page: Int? = null): SavedStateHandle = SavedStateHandle(
    mapOf(ReaderIntents.KEY_MANGA_ID to MANGA_ID, ReaderIntents.KEY_CHAPTER_ID to chapterId),
).apply {
    // Set rather than put in the map: a map literal mixing Long and Int values boxes the page as a
    // Long, which no handle the platform restores would hold.
    page?.let { set(ReaderIntents.KEY_PAGE, it) }
}

/** A registry that has finished its first load and serves [source] for every id. */
internal fun readyRegistry(source: Source = mockk(relaxed = true)): SourceManager {
    val registry = mockk<SourceManager>()
    every { registry.isInitialized } returns MutableStateFlow(true)
    every { registry.getOrStub(any()) } returns source
    every { registry.get(any()) } returns source
    return registry
}

internal fun resolverOf(loader: (ReaderChapter) -> PageLoader): PageLoaderResolver = mockk {
    coEvery { resolve(any(), any(), any(), any()) } answers { loader(secondArg()) }
}

internal fun ReaderViewModel.chapter(id: Long): ReaderChapter =
    checkNotNull(state.value.session).chapters.first { it.chapter.id == id }

/**
 * [pages] plain pages, handed back after [beforePages] returns. Its splits are a [MutableSharedFlow]
 * nothing emits to, so a job collecting them stays active for as long as the chapter is loaded.
 */
internal class PlainLoader(
    private val pages: Int = PAGES,
    private val beforePages: suspend () -> Unit = {},
) : PageLoader {
    override val isLocal = true
    override val isRecycled = false
    override val splits: Flow<PageSplit> = MutableSharedFlow()

    override suspend fun getPages(): List<ReaderPage> {
        beforePages()
        return List(pages) { ReaderPage(it) }
    }

    override suspend fun loadPage(page: ReaderPage) = Unit
    override fun retryPage(page: ReaderPage) = Unit
    override fun recycle() = Unit

    companion object {
        const val PAGES = 5
    }
}

/**
 * The page notes table in memory, with its key: one note per chapter and page, an upsert replacing
 * the text in place. Subscribers see every write, as the database's query flows do.
 */
internal class FakeBookmarkNoteRepository(vararg seed: BookmarkNote) : BookmarkNoteRepository {

    private val rows = MutableStateFlow(seed.toList())
    private var nextId = seed.maxOfOrNull { it.id + 1 } ?: 1L

    val all: List<BookmarkNote> get() = rows.value

    override suspend fun getByChapterId(chapterId: Long): List<BookmarkNote> = byChapter(rows.value, chapterId)

    override fun subscribeByChapterId(chapterId: Long): Flow<List<BookmarkNote>> =
        rows.map { byChapter(it, chapterId) }

    override fun subscribeByMangaId(mangaId: Long): Flow<List<BookmarkNote>> = rows

    override suspend fun upsert(chapterId: Long, pageIndex: Long, note: String, now: Long) {
        rows.update { current ->
            val existing = current.firstOrNull { it.chapterId == chapterId && it.pageIndex == pageIndex }
            if (existing != null) {
                current.map { if (it === existing) it.copy(note = note) else it }
            } else {
                current + BookmarkNote(nextId++, chapterId, pageIndex, note, now)
            }
        }
    }

    override suspend fun remove(chapterId: Long, pageIndex: Long) {
        rows.update { current -> current.filterNot { it.chapterId == chapterId && it.pageIndex == pageIndex } }
    }

    private fun byChapter(all: List<BookmarkNote>, chapterId: Long) =
        all.filter { it.chapterId == chapterId }.sortedBy { it.pageIndex }
}

/** A stored note, for seeding [FakeBookmarkNoteRepository]. */
internal fun note(chapterId: Long, pageIndex: Long, text: String, id: Long = chapterId * 1000 + pageIndex) =
    BookmarkNote(id = id, chapterId = chapterId, pageIndex = pageIndex, note = text, dateAdded = 0L)

private object NoMemory : MemoryInfoProvider {
    override val maxHeapBytes: Long = 1_000_000_000L
    override val isLowRamDevice: Boolean = false
    override val usedHeapBytes: Long = 0L
}

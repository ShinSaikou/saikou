package com.shinsaikou.ui.migration.list

import com.shinsaikou.domain.manga.interactor.RefreshMangaFromSource
import com.shinsaikou.domain.preferences.SourcePreferences
import com.shinsaikou.migration.MigrateManga
import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceManager
import com.shinsaikou.source.StubSource
import com.shinsaikou.ui.migration.FakeCatalogueSource
import com.shinsaikou.ui.migration.list.MigratingManga.SearchResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import tachiyomi.domain.migration.models.MigrationFlag
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import java.io.IOException
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers

/**
 * The world the migration list's tests run in (phase 4 unit MB, design g7 final section 19): fake
 * sources, and the list's interactors as mockk fakes over two maps standing in for the database, the
 * rows by id and their chapters. Nothing reaches a database or a network.
 *
 * A search hit is added to a [FakeCatalogueSource] with [remoteEntry], which also says which chapter
 * numbers a chapter fetch of it stores. [refresh] and [migrateManga] record every call and can be
 * held on a [CompletableDeferred] or made to fail per entry.
 */
class MigrationListHarness(private val dispatcher: TestDispatcher) {

    data class RefreshCall(
        val sourceId: Long,
        val mangaId: Long,
        val fetchDetails: Boolean,
        val fetchChapters: Boolean,
        val manualFetch: Boolean,
    )

    data class MigrateCall(val current: Manga, val target: Manga, val replace: Boolean, val flags: Set<MigrationFlag>)

    val preferences = SourcePreferences(InMemoryPreferenceStore())
    val initialized = MutableStateFlow(true)
    val sources = mutableMapOf<Long, FakeCatalogueSource>()

    /** The database: every row by id, and each row's chapters. */
    val rows = mutableMapOf<Long, Manga>()
    val chapters = mutableMapOf<Long, List<Chapter>>()

    /** The chapter numbers a chapter fetch of (source id, url) stores. */
    private val remoteChapters = mutableMapOf<Pair<Long, String>, List<Double>>()

    val refreshCalls = mutableListOf<RefreshCall>()

    /** Rows whose fetch throws. */
    val refreshFailures = mutableSetOf<Long>()
    var refreshHold: CompletableDeferred<Unit>? = null

    val migrateCalls = mutableListOf<MigrateCall>()

    /** Old entries whose migration throws, and what it throws. */
    val migrateFailures = mutableMapOf<Long, Exception>()

    /** Called with the old entry's id just before its migration throws: another writer's change the write's guard sees. */
    var beforeFailure: ((Long) -> Unit)? = null
    var migrateHold: CompletableDeferred<Unit>? = null

    /** Called with the old entry's id after each write that succeeded, inside the write. */
    var onMigrated: ((Long) -> Unit)? = null

    /** The downloaded chapters a write of an old entry leaves behind, and every Delete them it was asked for. */
    val leftBehind = mutableMapOf<Long, List<Long>>()
    val deletedLeftBehind = mutableListOf<List<Long>>()

    private var nextId = 1000L

    val sourceManager = mockk<SourceManager> {
        every { isInitialized } returns initialized
        every { this@mockk.get(any()) } answers { sources[firstArg()] }
        every { getOrStub(any()) } answers { sources[firstArg()] ?: StubSource.unknown(firstArg()) }
    }

    val getManga = mockk<GetManga> {
        coEvery { await(any()) } answers { rows[firstArg()] }
    }

    val getChaptersByMangaId = mockk<GetChaptersByMangaId> {
        coEvery { await(any(), any()) } answers { chapters[firstArg()].orEmpty() }
    }

    /** `insertNetworkManga`: the row with that source and URL, or a new non-library one. */
    val networkToLocalManga = mockk<NetworkToLocalManga> {
        coEvery { this@mockk.invoke(any<Manga>()) } answers {
            val network = firstArg<Manga>()
            rows.values.firstOrNull { it.source == network.source && it.url == network.url }
                ?: network.copy(id = nextId++, favorite = false).also { rows[it.id] = it }
        }
    }

    val refresh = mockk<RefreshMangaFromSource> {
        coEvery { this@mockk.invoke(any(), any(), any(), any(), any()) } coAnswers {
            val source = arg<Source>(0)
            val manga = arg<Manga>(1)
            val fetchDetails = arg<Boolean>(2)
            val fetchChapters = arg<Boolean>(3)
            refreshCalls += RefreshCall(source.id, manga.id, fetchDetails, fetchChapters, arg(4))
            refreshHold?.await()
            if (manga.id in refreshFailures) throw IOException("refused")
            var row = rows[manga.id] ?: manga
            if (fetchDetails && row.thumbnailUrl == null) {
                row = row.copy(thumbnailUrl = FETCHED_COVER, description = "from the details")
                rows[row.id] = row
            }
            if (fetchChapters) {
                chapters[manga.id] = remoteChapters[source.id to manga.url].orEmpty()
                    .mapIndexed { index, number -> chapter(manga.id, index, number) }
            }
            RefreshMangaFromSource.Result(row, emptyList())
        }
    }

    /** What `getFavoriteIdsAsFlow` emits; the test sets it, as a write or a pull would change it. */
    val favoriteIds = MutableStateFlow<Set<Long>>(emptySet())

    val mangaRepository = mockk<MangaRepository> {
        every { getFavoriteIdsAsFlow() } returns favoriteIds
    }

    val migrateManga = mockk<MigrateManga> {
        coEvery { this@mockk.invoke(any(), any(), any(), any()) } coAnswers {
            val current = arg<Manga>(0)
            val target = arg<Manga>(1)
            val replace = arg<Boolean>(2)
            migrateCalls += MigrateCall(current, target, replace, arg(3))
            migrateHold?.await()
            migrateFailures[current.id]?.let {
                beforeFailure?.invoke(current.id)
                throw it
            }
            rows[target.id] = rows.getValue(target.id).copy(favorite = true)
            if (replace) rows[current.id] = rows.getValue(current.id).copy(favorite = false, dateAdded = 0)
            onMigrated?.invoke(current.id)
            MigrateManga.Result(
                leftBehindDownloads = leftBehind[current.id].orEmpty(),
                carried = MigrationFlag.DOWNLOADS in arg<Set<MigrationFlag>>(3),
            )
        }
        coEvery { deleteLeftBehindDownloads(any()) } coAnswers { deletedLeftBehind += arg<List<Long>>(0) }
    }

    fun source(id: Long, lang: String = "en"): FakeCatalogueSource =
        FakeCatalogueSource(id, name = "Source $id", lang = lang).also { sources[id] = it }

    /** A library entry on [sourceId], with its chapters stored. */
    fun libraryEntry(
        id: Long,
        title: String,
        sourceId: Long = OLD_SOURCE,
        url: String = "/old/$id",
        chapterNumbers: List<Double> = listOf(1.0, 2.0, 3.0),
    ): Manga {
        val manga = Manga.create().copy(
            id = id,
            source = sourceId,
            url = url,
            title = title,
            favorite = true,
            thumbnailUrl = "cover-$id",
            dateAdded = 5L,
        )
        rows[id] = manga
        chapters[id] = chapterNumbers.mapIndexed { index, number -> chapter(id, index, number) }
        return manga
    }

    /** A search on [source] for [query] finds this title, and a chapter fetch of it stores [chapterNumbers]. */
    fun remoteEntry(
        source: FakeCatalogueSource,
        query: String,
        title: String = query,
        url: String = "/remote/${source.id}/${title.lowercase().replace(' ', '-')}",
        cover: String? = "remote-cover",
        chapterNumbers: List<Double> = listOf(1.0, 2.0, 3.0, 4.0),
    ) {
        source.searchResults[query] = source.searchResults[query].orEmpty() +
            FakeCatalogueSource.entry(title, url = url, cover = cover)
        remoteChapters[source.id to url] = chapterNumbers
    }

    /** A row already in the database, not in the library unless [favorite]. */
    fun storedRow(
        id: Long,
        sourceId: Long,
        url: String,
        title: String,
        favorite: Boolean = false,
        chapterNumbers: List<Double> = listOf(1.0),
    ): Manga {
        val manga = Manga.create().copy(
            id = id,
            source = sourceId,
            url = url,
            title = title,
            favorite = favorite,
            thumbnailUrl = "cover-$id",
        )
        rows[id] = manga
        remoteChapters[sourceId to url] = chapterNumbers
        return manga
    }

    fun rowIdOf(sourceId: Long, url: String): Long =
        rows.values.single { it.source == sourceId && it.url == url }.id

    fun viewModel(
        mangaIds: List<Long>,
        extraSearchQuery: String? = null,
        restored: RestoredOutcomes? = null,
    ) = MigrationListViewModel(
        mangaIds = mangaIds,
        extraSearchQuery = extraSearchQuery,
        restored = restored,
        preferences = preferences,
        sourceManager = sourceManager,
        getManga = getManga,
        getChaptersByMangaId = getChaptersByMangaId,
        networkToLocalManga = networkToLocalManga,
        refreshMangaFromSource = refresh,
        migrateManga = migrateManga,
        mangaRepository = mangaRepository,
        dispatchers = AppDispatchers(dispatcher, dispatcher, dispatcher),
    )

    /**
     * Every event the list sends, collected in [scope] until it ends. The collector is unconfined, so
     * an event is in the list as soon as it is sent, and `advanceUntilIdle` does not stop before it.
     */
    fun events(viewModel: MigrationListViewModel, scope: CoroutineScope): List<MigrationListViewModel.Event> {
        val events = mutableListOf<MigrationListViewModel.Event>()
        scope.launch(UnconfinedTestDispatcher(dispatcher.scheduler)) { viewModel.events.collect { events += it } }
        return events
    }

    companion object {
        const val OLD_SOURCE = 1L
        const val FETCHED_COVER = "cover-from-details"

        fun chapter(mangaId: Long, index: Int, number: Double): Chapter = Chapter.create().copy(
            id = mangaId * 1000 + index,
            mangaId = mangaId,
            chapterNumber = number,
            url = "/chapter/$mangaId/$index",
            name = "Chapter $number",
        )

        fun MigrationListViewModel.result(mangaId: Long): SearchResult? =
            items.firstOrNull { it.manga.id == mangaId }?.searchResult?.value

        fun MigrationListViewModel.matchOf(mangaId: Long): Manga? = (result(mangaId) as? SearchResult.Success)?.manga
    }
}

package com.shinsaikou.ui.stats

import com.shinsaikou.domain.merged.model.MergedGroup
import com.shinsaikou.domain.merged.model.MergedGroupCounts
import com.shinsaikou.domain.merged.model.MergedMember
import com.shinsaikou.domain.merged.model.MergedTitle
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.downloads.DownloadManifest
import com.shinsaikou.libraryupdate.FlatLibraryUpdate
import com.shinsaikou.source.model.MangaStatus
import com.shinsaikou.source.model.UpdateStrategy
import com.shinsaikou.sources.local.LocalSource
import com.shinsaikou.tracking.FakeOAuthTracker
import com.shinsaikou.tracking.Tracker
import com.shinsaikou.tracking.TrackerManager
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
import tachiyomi.domain.history.interactor.GetTotalReadDuration
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import kotlin.time.TimeSource
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers
import kotlinx.coroutines.Dispatchers as KotlinxDispatchers

/**
 * The statistics screen's view model (phase 4 unit SS) over one fixture library, with every tile
 * pinned to one number.
 *
 * The library update tile is the real `FlatLibraryUpdate.buildQueue` over the same library and
 * Saikou's default restrictions, so the fixture has an entry in an excluded category, a fetch-once
 * entry with chapters and an entry outside the release window; upstream's own filter would count the
 * last two. The mean score has an entry tracked on two signed-in trackers of different
 * scales, an unscored row, a row on a signed-out tracker and a row of an entry not in the library.
 */
class StatsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val dispatchers = AppDispatchers(io = testDispatcher, default = testDispatcher, main = testDispatcher)
    private val libraryPreferences = LibraryPreferences(InMemoryPreferenceStore())

    private val getLibraryManga = mockk<GetLibraryManga>()
    private val getTotalReadDuration = mockk<GetTotalReadDuration>()
    private val trackRepository = mockk<TrackRepository>()
    private val downloadManifest = mockk<DownloadManifest>()
    private val fetchInterval = mockk<FetchInterval>()

    /** Ten-point scores as they are stored. */
    private val tenPoint = FakeOAuthTracker(id = 1L, loggedIn = true)

    /** A hundred-point service: its ten-point score is a tenth of the stored one. */
    private val hundredPoint = mockk<Tracker> {
        every { id } returns 2L
        every { isLoggedIn } returns true
        every { get10PointScore(any()) } answers { firstArg<Track>().score / 10.0 }
    }

    private val signedOut = FakeOAuthTracker(id = 3L, loggedIn = false)

    private var library: List<LibraryManga> = emptyList()
    private var tracks: List<Track> = emptyList()
    private var downloads: Map<Long, Long> = emptyMap()
    private var mergedTitles: List<MergedTitle> = emptyList()
    private var mergedGroups: List<MergedGroup> = emptyList()

    @BeforeEach
    fun setUp() {
        KotlinxDispatchers.setMain(testDispatcher)
        coEvery { getLibraryManga.await() } answers { library }
        coEvery { getTotalReadDuration.await() } returns READ_DURATION
        every { trackRepository.getTracksAsFlow() } answers { flowOf(tracks) }
        every { downloadManifest.subscribeCountsByManga() } answers { flowOf(downloads) }
        every { fetchInterval.getWindow(any(), any()) } returns WINDOW
        libraryPreferences.updateCategoriesExclude.set(setOf(EXCLUDED_CATEGORY.toString()))
    }

    @AfterEach
    fun tearDown() {
        KotlinxDispatchers.resetMain()
    }

    private val libraryUpdate = FlatLibraryUpdate(
        getLibraryManga = getLibraryManga,
        getManga = mockk(),
        getChaptersByMangaId = mockk(),
        sourceManager = mockk(),
        syncChaptersWithSource = mockk(),
        updateManga = mockk(),
        fetchInterval = fetchInterval,
        libraryPreferences = libraryPreferences,
        getMergedGroups = mockk { coEvery { await() } answers { mergedGroups } },
        reconcileMergedRead = mockk(relaxed = true),
        autoDownload = mockk(),
        dispatchers = dispatchers,
        timeSource = TimeSource.Monotonic,
    )

    private fun viewModel(trackers: List<Tracker> = listOf(tenPoint, hundredPoint, signedOut)) = StatsViewModel(
        getLibraryManga = getLibraryManga,
        getMergedGroups = mockk { every { subscribeTitles() } answers { flowOf(mergedTitles) } },
        getTotalReadDuration = getTotalReadDuration,
        trackRepository = trackRepository,
        trackerManager = TrackerManager(trackers.toSet()),
        downloadManifest = downloadManifest,
        libraryUpdate = libraryUpdate,
        fetchInterval = fetchInterval,
        dispatchers = dispatchers,
    )

    /** [main] and [other] as one title, with what the group's own query counts for it. */
    private fun merge(main: Long, other: Long, checkOther: Boolean = true, counts: MergedGroupCounts) {
        val group = MergedGroup(
            id = counts.groupId,
            mainMangaId = main,
            showAll = false,
            members = listOf(
                MergedMember(main, source = main, url = "/$main", position = 0, checkUpdates = true),
                MergedMember(other, source = other, url = "/$other", position = 1, checkUpdates = checkOther),
            ),
        )
        mergedGroups = mergedGroups + group
        mergedTitles = mergedTitles + MergedTitle(group, counts)
    }

    @Test
    fun `a merged title is one title and counts its chapters by number`() = runTest(testDispatcher) {
        seedFixture()
        downloads = mapOf(A to 10L, D to 4L)
        merge(A, D, counts = MergedGroupCounts(1L, total = 11, read = 9, bookmarked = 0, downloaded = 10))

        val state = viewModel().also { advanceUntilIdle() }.state.value as StatsScreenState.Success

        state.overview.libraryMangaCount shouldBe 7
        state.chapters.totalChapterCount shouldBe (10 + 5 + 3 + 4 + 6 + 0 + 8 + 2) - (10 + 4) + 11
        state.chapters.readChapterCount shouldBe (10 + 5 + 3 + 2 + 6 + 0 + 0 + 2) - (10 + 2) + 9
        state.chapters.downloadCount shouldBe 10
    }

    @Test
    fun `the update tile leaves out a member with Check for updates off`() = runTest(testDispatcher) {
        seedFixture()
        val before = (viewModel().also { advanceUntilIdle() }.state.value as StatsScreenState.Success)
            .titles.globalUpdateItemCount
        merge(A, F, checkOther = false, counts = MergedGroupCounts(1L, total = 10, read = 10, bookmarked = 0, downloaded = 0))

        val after = (viewModel().also { advanceUntilIdle() }.state.value as StatsScreenState.Success)
            .titles.globalUpdateItemCount

        after shouldBe before - 1
    }

    @Test
    fun `a merged title is one started title`() = runTest(testDispatcher) {
        seedFixture()
        merge(A, D, counts = MergedGroupCounts(1L, total = 11, read = 9, bookmarked = 0, downloaded = 0))

        val state = viewModel().also { advanceUntilIdle() }.state.value as StatsScreenState.Success

        // A, B, C, D, E and H are started; A and D are one title.
        state.titles.startedMangaCount shouldBe 5
    }

    @Test
    fun `a title whose copies overlap in some numbers counts the downloaded numbers once`() = runTest(testDispatcher) {
        seedFixture()
        downloads = mapOf(A to 3L, D to 4L)
        // Seven copies on disk, five distinct numbers.
        merge(A, D, counts = MergedGroupCounts(1L, total = 11, read = 9, bookmarked = 0, downloaded = 5))

        val state = viewModel().also { advanceUntilIdle() }.state.value as StatsScreenState.Success

        state.chapters.downloadCount shouldBe 5
    }

    @Test
    fun `a title the counts query has not reached leaves the download count as the manifest has it`() = runTest(testDispatcher) {
        seedFixture()
        downloads = mapOf(A to 3L, D to 4L)
        val group = MergedGroup(
            id = 1L,
            mainMangaId = A,
            showAll = false,
            members = listOf(A, D).mapIndexed { index, id ->
                MergedMember(id, source = id, url = "/$id", position = index.toLong(), checkUpdates = true)
            },
        )
        mergedTitles = listOf(MergedTitle(group, counts = null))

        val state = viewModel().also { advanceUntilIdle() }.state.value as StatsScreenState.Success

        state.chapters.downloadCount shouldBe 7
    }

    @Test
    fun `a title whose main entry is not in the library folds nothing`() = runTest(testDispatcher) {
        seedFixture()
        val before = (viewModel().also { advanceUntilIdle() }.state.value as StatsScreenState.Success)
        merge(NOT_IN_LIBRARY, A, counts = MergedGroupCounts(1L, total = 99, read = 99, bookmarked = 0, downloaded = 0))

        val after = (viewModel().also { advanceUntilIdle() }.state.value as StatsScreenState.Success)

        after.overview.libraryMangaCount shouldBe before.overview.libraryMangaCount
        after.chapters.totalChapterCount shouldBe before.chapters.totalChapterCount
    }

    /** The fixture: eight entries, as the comments on each say. */
    private fun seedFixture() {
        library = listOf(
            // In the update: ongoing, every chapter read, in the release window.
            item(A, totalChapters = 10, readCount = 10),
            // In the excluded category, otherwise as A.
            item(B, categories = listOf(EXCLUDED_CATEGORY), totalChapters = 5, readCount = 5),
            // Fetch once with chapters: the update skips it, whatever the restrictions.
            item(C, totalChapters = 3, readCount = 3, updateStrategy = UpdateStrategy.ONLY_FETCH_ONCE),
            // Completed with two unread: not completed here, and not updated (completed, has unread).
            item(D, totalChapters = 4, readCount = 2, status = MangaStatus.COMPLETED),
            // Completed and fully read, in no category, on the Local source.
            item(
                E,
                categories = listOf(0L),
                totalChapters = 6,
                readCount = 6,
                status = MangaStatus.COMPLETED,
                source = LocalSource.ID,
            ),
            // No chapters yet: the "not started" restriction needs chapters, so it is updated.
            item(F, categories = listOf(0L)),
            // Chapters, none read: skipped for its unread chapters, the first restriction that matches
            // (not started would skip it too).
            item(G, totalChapters = 8),
            // Fully read, but its next expected update is after the window's end.
            item(H, totalChapters = 2, readCount = 2, nextUpdate = WINDOW.second + 1),
        )
        tracks = listOf(
            // A: 8 on the ten-point tracker and 60 of 100, so its mean is (8 + 6) / 2 = 7.
            track(A, trackerId = 1L, score = 8.0),
            track(A, trackerId = 2L, score = 60.0),
            // B: tracked in an excluded category still counts; its mean is 4.
            track(B, trackerId = 1L, score = 4.0),
            // E: tracked, unscored, so it counts as tracked and not in the mean.
            track(E, trackerId = 1L, score = 0.0),
            // G: only on the signed-out tracker, so neither tracked nor scored.
            track(G, trackerId = 3L, score = 9.0),
            // Not in the library.
            track(NOT_IN_LIBRARY, trackerId = 1L, score = 10.0),
        )
        downloads = mapOf(A to 3L, B to 2L, NOT_IN_LIBRARY to 4L)
    }

    @Test
    fun `every tile over the fixture library`() = runTest(testDispatcher) {
        seedFixture()

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.state.value shouldBe StatsScreenState.Success(
            overview = StatsData.Overview(
                libraryMangaCount = 8,
                // E alone: D is completed with unread chapters.
                completedMangaCount = 1,
                totalReadDuration = READ_DURATION,
            ),
            titles = StatsData.Titles(
                // A and F. Upstream's filter would also count C (fetch once) and H (release period).
                globalUpdateItemCount = 2,
                // A, B, C, D, E and H.
                startedMangaCount = 6,
                localMangaCount = 1,
            ),
            chapters = StatsData.Chapters(
                totalChapterCount = 10 + 5 + 3 + 4 + 6 + 0 + 8 + 2,
                readChapterCount = 10 + 5 + 3 + 2 + 6 + 0 + 0 + 2,
                // Every manifest row, the entry no longer in the library included, as upstream counts.
                downloadCount = 9,
            ),
            trackers = StatsData.Trackers(
                // A, B and E.
                trackedTitleCount = 3,
                // The mean of the entries' means, A's 7 and B's 4.
                meanScore = 5.5,
                trackerCount = 2,
            ),
        )
    }

    @Test
    fun `the update tile follows the update's own settings`() = runTest(testDispatcher) {
        seedFixture()
        libraryPreferences.autoUpdateMangaRestrictions.set(emptySet())
        libraryPreferences.updateCategoriesExclude.set(emptySet())
        libraryPreferences.updateCategories.set(setOf(INCLUDED_CATEGORY.toString()))

        val viewModel = viewModel()
        advanceUntilIdle()

        // Only the included category (A, C, D, G and H), less C, which is fetched once.
        viewModel.state.value.success().titles.globalUpdateItemCount shouldBe 4
    }

    /**
     * An entry whose details were never fetched is updated whatever the restrictions, as upstream
     * v0.21.0 does, so the tile counts it: G again, never fetched, beside A and F.
     */
    @Test
    fun `the update tile counts a never-fetched entry the restrictions would skip`() = runTest(testDispatcher) {
        seedFixture()
        library = library.map { if (it.id == G) it.copy(manga = it.manga.copy(initialized = false)) else it }

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.state.value.success().titles.globalUpdateItemCount shouldBe 3
    }

    /**
     * Phase 5 unit SC5: the update tile is built from the list the screen read, where it used to
     * read the whole library a second time through `buildQueue`'s own read.
     */
    @Test
    fun `one screen load reads the library once`() = runTest(testDispatcher) {
        seedFixture()

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.state.value.success().titles.globalUpdateItemCount shouldBe 2
        coVerify(exactly = 1) { getLibraryManga.await() }
    }

    /**
     * The update tile's exact count over the fixture with A's row listed twice (R140 item 4): A
     * once, and F. B is in the excluded category and H's next update is past the window's end, so
     * neither counts; counted per row, A would make it 3. The library's own count is unchanged.
     */
    @Test
    fun `the update tile counts an entry listed twice once, and none excluded or outside the window`() =
        runTest(testDispatcher) {
            seedFixture()
            library = library + library.first { it.id == A }

            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.state.value.success().titles.globalUpdateItemCount shouldBe 2
            viewModel.state.value.success().overview.libraryMangaCount shouldBe 8
        }

    @Test
    fun `with no tracker signed in nothing is tracked and the mean score is not a number`() = runTest(testDispatcher) {
        seedFixture()

        val viewModel = viewModel(listOf(signedOut))
        advanceUntilIdle()

        viewModel.state.value.success().trackers.trackedTitleCount shouldBe 0
        viewModel.state.value.success().trackers.meanScore.isNaN() shouldBe true
        viewModel.state.value.success().trackers.trackerCount shouldBe 0
    }

    @Test
    fun `an empty library is all zeros`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        viewModel.state.value shouldBe StatsScreenState.Loading
        advanceUntilIdle()

        val state = viewModel.state.value.success()
        state.overview shouldBe StatsData.Overview(0, 0, READ_DURATION)
        state.titles shouldBe StatsData.Titles(0, 0, 0)
        state.chapters shouldBe StatsData.Chapters(0, 0, 0)
        state.trackers.trackedTitleCount shouldBe 0
        state.trackers.meanScore.isNaN() shouldBe true
        state.trackers.trackerCount shouldBe 2
    }

    private fun StatsScreenState.success(): StatsScreenState.Success {
        shouldBeInstanceOf<StatsScreenState.Success>()
        return this
    }

    private fun item(
        id: Long,
        categories: List<Long> = listOf(INCLUDED_CATEGORY),
        totalChapters: Long = 0L,
        readCount: Long = 0L,
        status: MangaStatus = MangaStatus.ONGOING,
        nextUpdate: Long = 0L,
        updateStrategy: UpdateStrategy = UpdateStrategy.ALWAYS_UPDATE,
        source: Long = 100L + id,
        initialized: Boolean = true,
    ) = LibraryManga(
        manga = Manga.create().copy(
            id = id,
            source = source,
            url = "/manga/$id",
            title = "Fixture $id",
            favorite = true,
            status = status.code.toLong(),
            nextUpdate = nextUpdate,
            updateStrategy = updateStrategy,
            initialized = initialized,
        ),
        categories = categories,
        totalChapters = totalChapters,
        readCount = readCount,
        bookmarkCount = 0L,
        latestUpload = 0L,
        chapterFetchedAt = 0L,
        lastRead = 0L,
    )

    private fun track(mangaId: Long, trackerId: Long, score: Double) = Track(
        id = mangaId * 10 + trackerId,
        mangaId = mangaId,
        trackerId = trackerId,
        remoteId = mangaId,
        libraryId = null,
        title = "Fixture $mangaId",
        lastChapterRead = 0.0,
        totalChapters = 0L,
        status = 1L,
        score = score,
        remoteUrl = "",
        startDate = 0L,
        finishDate = 0L,
        private = false,
    )

    private companion object {
        const val A = 1L
        const val B = 2L
        const val C = 3L
        const val D = 4L
        const val E = 5L
        const val F = 6L
        const val G = 7L
        const val H = 8L
        const val NOT_IN_LIBRARY = 99L

        const val INCLUDED_CATEGORY = 1L
        const val EXCLUDED_CATEGORY = 2L

        const val READ_DURATION = 5_430_000L
        val WINDOW = 0L to 1_000L
    }
}

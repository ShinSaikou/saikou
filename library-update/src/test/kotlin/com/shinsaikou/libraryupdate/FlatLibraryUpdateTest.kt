package com.shinsaikou.libraryupdate

import android.content.Context
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.chapter.interactor.SyncChaptersWithSource
import com.shinsaikou.domain.download.AutoDownload
import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.domain.manga.interactor.UpdateManga
import com.shinsaikou.domain.merged.interactor.ReconcileMergedRead
import com.shinsaikou.domain.merged.model.MergedGroup
import com.shinsaikou.domain.merged.model.MergedMember
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.LibraryUpdateRestrictions
import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceManager
import com.shinsaikou.source.SourceNotInstalledException
import com.shinsaikou.source.StubSource
import com.shinsaikou.source.model.ChapterInfo
import com.shinsaikou.source.model.ImageResult
import com.shinsaikou.source.model.MangaInfo
import com.shinsaikou.source.model.MangaStatus
import com.shinsaikou.source.model.MangaUpdate
import com.shinsaikou.source.model.PageInfo
import com.shinsaikou.source.model.UpdateStrategy
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.measureTime

/**
 * The pass's own rules, carried over from the sequential pass it replaced: what is skipped, what a
 * failure records, the progress it reports, the cancel, and the wait for the source registry. The
 * concurrency itself is `FlatLibraryUpdateConcurrencyTest`'s.
 *
 * Unless a test says otherwise, the source calls run on the test's scheduler, so time is virtual.
 */
class FlatLibraryUpdateTest {

    private lateinit var getLibraryManga: GetLibraryManga
    private lateinit var getManga: GetManga
    private lateinit var getChaptersByMangaId: GetChaptersByMangaId
    private lateinit var sourceManager: SourceManager
    private lateinit var syncChapters: SyncChaptersWithSource
    private lateinit var updateManga: UpdateManga
    private lateinit var fetchInterval: FetchInterval
    private lateinit var preferences: LibraryPreferences

    private val sources = mutableMapOf<Long, Source>()
    private val storedChapters = mutableMapOf<Long, List<Chapter>>()
    private val registryReady = MutableStateFlow(true)
    private val autoDownload = RecordingAutoDownload()
    private val reconcile = mockk<ReconcileMergedRead>(relaxed = true)
    private var mergedGroups: List<MergedGroup> = emptyList()

    @BeforeEach
    fun setUp() {
        getLibraryManga = mockk()
        getManga = mockk()
        getChaptersByMangaId = mockk()
        syncChapters = mockk()
        updateManga = mockk(relaxed = true)
        fetchInterval = mockk()
        preferences = LibraryPreferences(InMemoryPreferenceStore())

        sources.clear()
        storedChapters.clear()
        registryReady.value = true
        sourceManager = mockk()
        every { sourceManager.isInitialized } returns registryReady
        every { sourceManager.getOrStub(any()) } answers {
            val id = firstArg<Long>()
            sources[id] ?: StubSource.unknown(id)
        }
        every { sourceManager.get(any()) } answers { sources[firstArg<Long>()] }

        every { fetchInterval.getWindow(any(), any()) } returns WINDOW
        coEvery { getManga.await(any()) } answers { library.firstOrNull { it.id == firstArg<Long>() }?.manga }
        coEvery { getLibraryManga.await() } answers { library }
        coEvery { getChaptersByMangaId.await(any(), any()) } answers { storedChapters[firstArg<Long>()].orEmpty() }
        coEvery { syncChapters.await(any(), any(), any(), any()) } returns emptyList()
    }

    /** The pass as the graph builds it, with [io] for its source calls and the scheduler's clock. */
    private fun TestScope.pass(io: CoroutineDispatcher = StandardTestDispatcher(testScheduler)) = FlatLibraryUpdate(
        getLibraryManga = getLibraryManga,
        getManga = getManga,
        getChaptersByMangaId = getChaptersByMangaId,
        sourceManager = sourceManager,
        syncChaptersWithSource = syncChapters,
        updateManga = updateManga,
        fetchInterval = fetchInterval,
        libraryPreferences = preferences,
        getMergedGroups = mockk { coEvery { await() } answers { mergedGroups } },
        reconcileMergedRead = reconcile,
        autoDownload = autoDownload,
        dispatchers = Dispatchers(io = io, default = io, main = io),
        timeSource = testScheduler.timeSource,
    )

    private var library: List<LibraryManga> = emptyList()

    // region skip rules

    @Test
    fun `an entry that is only fetched once and already has chapters is skipped`() = runTest {
        seed(item(1L, totalChapters = 3, updateStrategy = UpdateStrategy.ONLY_FETCH_ONCE))

        run(automatic = true)

        source(1L).calls shouldBe 0
    }

    @Test
    fun `an entry that is only fetched once and has no chapters yet is refreshed`() = runTest {
        seed(item(1L, totalChapters = 0, updateStrategy = UpdateStrategy.ONLY_FETCH_ONCE))

        run(automatic = true)

        source(1L).calls shouldBe 1
    }

    @Test
    fun `a completed entry is skipped when the completed restriction is on`() = runTest {
        restrict(LibraryUpdateRestrictions.MANGA_NON_COMPLETED)
        seed(item(1L, status = MangaStatus.COMPLETED.code.toLong()))

        run(automatic = true)

        source(1L).calls shouldBe 0
    }

    @Test
    fun `an entry with unread chapters is skipped when the unread restriction is on`() = runTest {
        restrict(LibraryUpdateRestrictions.MANGA_HAS_UNREAD)
        seed(item(1L, totalChapters = 4, readCount = 1))

        run(automatic = true)

        source(1L).calls shouldBe 0
    }

    @Test
    fun `an entry that was never started is skipped when the started restriction is on`() = runTest {
        restrict(LibraryUpdateRestrictions.MANGA_NON_READ)
        seed(item(1L, totalChapters = 4, readCount = 0))

        run(automatic = true)

        source(1L).calls shouldBe 0
    }

    @Test
    fun `an entry outside its release window is skipped when that restriction is on`() = runTest {
        restrict(LibraryUpdateRestrictions.MANGA_OUTSIDE_RELEASE_PERIOD)
        seed(item(1L, nextUpdate = WINDOW.second + 1))

        run(automatic = true)

        source(1L).calls shouldBe 0
    }

    /** Updates shows these under its last update line; an automatic run used to only log them. */
    @Test
    fun `the outcome counts the entries each rule skipped`() = runTest {
        restrict(LibraryUpdateRestrictions.MANGA_NON_COMPLETED, LibraryUpdateRestrictions.MANGA_HAS_UNREAD)
        seed(
            item(1L, status = MangaStatus.COMPLETED.code.toLong()),
            item(2L, status = MangaStatus.COMPLETED.code.toLong()),
            item(3L, totalChapters = 4, readCount = 1),
            item(4L),
        )

        val outcome = run(automatic = true)

        outcome.skipped shouldBe mapOf(
            LibraryUpdateRestrictions.MANGA_NON_COMPLETED to 2,
            LibraryUpdateRestrictions.MANGA_HAS_UNREAD to 1,
        )
        source(4L).calls shouldBe 1
    }

    @Test
    fun `a manual run applies the fetch once rule and none of the restrictions`() = runTest {
        restrict(
            LibraryUpdateRestrictions.MANGA_NON_COMPLETED,
            LibraryUpdateRestrictions.MANGA_HAS_UNREAD,
            LibraryUpdateRestrictions.MANGA_NON_READ,
            LibraryUpdateRestrictions.MANGA_OUTSIDE_RELEASE_PERIOD,
        )
        seed(
            item(1L, status = MangaStatus.COMPLETED.code.toLong(), totalChapters = 4, readCount = 1),
            item(2L, totalChapters = 3, updateStrategy = UpdateStrategy.ONLY_FETCH_ONCE),
        )

        run(automatic = false)

        source(1L).calls shouldBe 1
        source(2L).calls shouldBe 0
    }

    /** A retry of failed entries, or the library selection's Update: the ids, whatever their categories. */
    @Test
    fun `a run over chosen entries queues those and no other, an excluded category's included`() = runTest {
        seed(item(1L), item(2L, categories = listOf(9L)), item(3L))
        preferences.updateCategoriesExclude.set(setOf("9"))

        val outcome = pass().run(
            categoryId = null,
            automatic = false,
            refreshMetadata = false,
            mangaIds = setOf(2L, 3L),
        ) {}

        source(1L).calls shouldBe 0
        source(2L).calls shouldBe 1
        source(3L).calls shouldBe 1
        outcome.checked shouldBe setOf(2L, 3L)
    }

    @Test
    fun `an entry in an excluded category is skipped`() = runTest {
        preferences.updateCategoriesExclude.set(setOf("7"))
        seed(item(1L, categories = listOf(7L)), item(2L, categories = listOf(8L)))

        run(automatic = true)

        source(1L).calls shouldBe 0
        source(2L).calls shouldBe 1
    }

    // endregion

    // region merged entries (R163)

    private fun groupOf(vararg members: Pair<Long, Boolean>) = MergedGroup(
        id = 1,
        mainMangaId = members.first().first,
        showAll = false,
        members = members.mapIndexed { index, (id, check) ->
            MergedMember(id, source = id, url = "/manga/$id", position = index.toLong(), checkUpdates = check)
        },
    )

    @Test
    fun `a member with check for updates off is left out of the run, as if it were not in the library`() = runTest {
        seed(item(1L), item(2L), item(3L))
        mergedGroups = listOf(groupOf(1L to true, 2L to false))

        val outcome = run(automatic = true)

        source(1L).calls shouldBe 1
        source(2L).calls shouldBe 0
        source(3L).calls shouldBe 1
        outcome.checked shouldBe setOf(1L, 3L)
    }

    @Test
    fun `a member with check for updates off stays out of a run over chosen entries`() = runTest {
        seed(item(1L), item(2L))
        mergedGroups = listOf(groupOf(1L to true, 2L to false))

        val outcome = pass().run(categoryId = null, automatic = false, refreshMetadata = false, mangaIds = setOf(1L, 2L)) {}

        source(2L).calls shouldBe 0
        outcome.checked shouldBe setOf(1L)
    }

    @Test
    fun `a member with check for updates on is updated like any entry`() = runTest {
        seed(item(1L), item(2L))
        mergedGroups = listOf(groupOf(1L to true, 2L to true))

        run(automatic = true)

        source(1L).calls shouldBe 1
        source(2L).calls shouldBe 1
    }

    @Test
    fun `with no group every entry is updated`() = runTest {
        seed(item(1L), item(2L))

        run(automatic = true)

        source(1L).calls shouldBe 1
        source(2L).calls shouldBe 1
    }

    @Test
    fun `a member whose refresh inserted chapters has its group reconciled`() = runTest {
        seed(item(1L), item(2L))
        coEvery { syncChapters.await(any(), match { it.id == 2L }, any(), any()) } returns listOf(chapter(21L, 2L))

        run(automatic = true)

        coVerify(exactly = 1) { reconcile.await(2L) }
        coVerify(exactly = 0) { reconcile.await(1L) }
    }

    @Test
    fun `an entry with no new chapters reconciles nothing`() = runTest {
        seed(item(1L))

        run(automatic = true)

        coVerify(exactly = 0) { reconcile.await(any<Long>()) }
    }

    @Test
    fun `a failing reconcile does not fail the entry, and its new chapters still go to auto download`() = runTest {
        seed(item(1L))
        coEvery { syncChapters.await(any(), any(), any(), any()) } returns listOf(chapter(11L, 1L))
        coEvery { reconcile.await(1L) } throws IllegalStateException("db busy")

        val outcome = run(automatic = true)

        outcome.failures.shouldBeEmpty()
        autoDownload.reported shouldHaveSize 1
    }

    // endregion

    @Test
    fun `a stub source is one failure and the run carries on`() = runTest {
        seed(item(1L), item(2L))
        sources.remove(1L)

        val outcome = run(automatic = true)

        outcome.failures shouldHaveSize 1
        outcome.failures.single().manga.id shouldBe 1L
        outcome.failures.single().error.shouldBeInstanceOf<SourceNotInstalledException>()
        source(2L).calls shouldBe 1
    }

    @Test
    fun `an exception from one source does not stop the next entry`() = runTest {
        seed(item(1L), item(2L))
        sources[1L] = FakeSource(1L) { error("boom") }

        val outcome = run(automatic = true)

        outcome.failures shouldHaveSize 1
        outcome.failures.single().manga.id shouldBe 1L
        source(2L).calls shouldBe 1
    }

    /**
     * A source's own network call times out with a [SocketTimeoutException], which the pass's
     * generic catch records like any other failure. The worker hands the failures to the store as
     * they are, so the store is where the timeout has to become the timeout key; before the shared
     * resolver it stored the exception's raw message instead.
     */
    @Test
    fun `a source whose request times out leaves the timeout key in the error store`(
        @TempDir filesDir: File,
    ) = runTest {
        seed(item(1L), item(2L))
        sources[1L] = FakeSource(1L) { throw SocketTimeoutException("Read timed out") }
        val context = mockk<Context>()
        every { context.filesDir } returns filesDir
        val store = LibraryUpdateErrorStore(context, Json)

        store.replace(run(automatic = true).failures)

        store.errors.value.map { it.mangaId } shouldBe listOf(1L)
        store.errors.value.single().key shouldBe ErrorKey.TIMEOUT
        store.errors.value.single().detail shouldBe null
        val reloaded = LibraryUpdateErrorStore(context, Json).also { it.load() }
        reloaded.errors.value.single().key shouldBe ErrorKey.TIMEOUT
    }

    @Test
    fun `an entry removed from the library between queue and fetch is never asked for`() = runTest {
        seed(item(1L), item(2L))
        val queued = library
        coEvery { getLibraryManga.await() } returns queued
        coEvery { getManga.await(1L) } returns null

        val outcome = run(automatic = true)

        source(1L).calls shouldBe 0
        source(2L).calls shouldBe 1
        outcome.failures.shouldBeEmpty()
    }

    @Test
    fun `an entry that lost its favourite flag is never asked for`() = runTest {
        seed(item(1L))
        coEvery { getManga.await(1L) } returns library.single().manga.copy(favorite = false)

        run(automatic = true)

        source(1L).calls shouldBe 0
    }

    @Test
    fun `the fetch window is computed once and handed to every sync`() = runTest {
        seed(item(1L), item(2L), item(3L))

        run(automatic = true)

        verify(exactly = 1) { fetchInterval.getWindow(any(), any()) }
        coVerify(exactly = 3) { syncChapters.await(any(), any(), any(), WINDOW) }
    }

    @Test
    fun `only entries with new chapters end up in updated`() = runTest {
        seed(item(1L), item(2L))
        coEvery { syncChapters.await(any(), match { it.id == 1L }, any(), any()) } returns listOf(chapter(11L, 1L))
        coEvery { syncChapters.await(any(), match { it.id == 2L }, any(), any()) } returns emptyList()

        val outcome = run(automatic = true)

        outcome.updated.keys shouldBe setOf(1L)
        outcome.updated.getValue(1L) shouldHaveSize 1
        preferences.newUpdatesCount.get() shouldBe 1
    }

    /**
     * Entries 2 and 3 share a source, which starts at one request in flight, so the second starts
     * only once the first has finished and the counts read as they did in the sequential pass.
     */
    @Test
    fun `progress counts only the entries that are not skipped`() = runTest {
        restrict(LibraryUpdateRestrictions.MANGA_NON_COMPLETED)
        seed(item(1L, status = MangaStatus.COMPLETED.code.toLong()), item(2L, source = 2L), item(3L, source = 2L))

        val seen = mutableListOf<FlatLibraryUpdate.Progress>()
        pass().run(categoryId = null, automatic = true) { seen += it }

        seen.map { it.done } shouldBe listOf(0, 1)
        seen.map { it.total } shouldBe listOf(2, 2)
        seen.map { it.current?.id } shouldBe listOf(2L, 3L)
    }

    /**
     * `ProgressCadence` finds the last report by count, the `total`-th call, so every entry the queue
     * holds is reported once as it starts, whatever then ends it: a fetch, a source that is not
     * installed, or an entry removed between the queue and its turn (phase 5 unit TG1).
     */
    @Test
    fun `progress is called once for each entry the queue holds, a stub source's and a removed one's included`() = runTest {
        seed(item(1L), item(2L), item(3L))
        sources.remove(2L)
        coEvery { getManga.await(3L) } returns null

        val seen = mutableListOf<FlatLibraryUpdate.Progress>()
        val outcome = pass().run(categoryId = null, automatic = true) { seen += it }

        seen.map { it.current?.id } shouldBe listOf(1L, 2L, 3L)
        seen.map { it.total } shouldBe listOf(3, 3, 3)
        source(1L).calls shouldBe 1
        source(3L).calls shouldBe 0
        outcome.failures.map { it.manga.id } shouldBe listOf(2L)
    }

    /** The error list replaces only these entries' rows, so a failed, updated or removed entry counts. */
    @Test
    fun `the outcome names every entry the run reached, failed or not`() = runTest {
        seed(item(1L), item(2L), item(3L))
        sources[1L] = FakeSource(1L) { error("boom") }
        coEvery { getManga.await(3L) } returns null

        val outcome = run(automatic = true)

        outcome.checked shouldBe setOf(1L, 2L, 3L)
    }

    @Test
    fun `a cancelled run does not name the entry it was still fetching`() = runTest {
        seed(item(1L), item(2L))
        val neverAnswers = CompletableDeferred<MangaUpdate>()
        sources[2L] = FakeSource(2L) { neverAnswers.await() }

        var outcome: FlatLibraryUpdate.Outcome? = null
        val job = launch { outcome = pass().run(categoryId = null, automatic = true) {} }
        runCurrent()
        job.cancelAndJoin()

        outcome?.cancelled shouldBe true
        outcome?.checked shouldBe setOf(1L)
    }

    @Test
    fun `cancelling the run returns a cancelled outcome without waiting for the source`() = runTest {
        seed(item(1L))
        val neverAnswers = CompletableDeferred<MangaUpdate>()
        sources[1L] = FakeSource(1L) { neverAnswers.await() }

        var outcome: FlatLibraryUpdate.Outcome? = null
        val job = launch { outcome = pass().run(categoryId = null, automatic = true) {} }
        // Not advanceUntilIdle: on virtual time that would run the watchdog out and end the entry.
        runCurrent()
        source(1L).calls shouldBe 1

        val elapsed = measureTime { job.cancelAndJoin() }

        outcome?.cancelled shouldBe true
        elapsed shouldBeLessThan 500.milliseconds
    }

    /**
     * The case above waits by suspending, which cancels cooperatively, so it passes whether or not
     * the fetch runs on the orphan fetch scope. A real source blocks a thread in I/O, which ignores
     * cancellation, so this one blocks: with the fetch moved inline the cancel either waits out the
     * latch or the run finishes uncancelled, and one of the two assertions fails (ruling R47). The
     * latch is bounded so that regression cannot hang the suite.
     */
    @Test
    fun `cancelling the run does not wait for a source that blocks its thread`() = runTest {
        seed(item(1L))
        val fetching = CountDownLatch(1)
        val release = CountDownLatch(1)
        sources[1L] = FakeSource(1L) {
            fetching.countDown()
            release.await(5, TimeUnit.SECONDS)
            MangaUpdate(null, emptyList())
        }

        try {
            var outcome: FlatLibraryUpdate.Outcome? = null
            val update = pass(io = kotlinx.coroutines.Dispatchers.IO)
            val job = launch { outcome = update.run(categoryId = null, automatic = true) {} }
            advanceUntilIdle()
            // The fetch thread is inside the source, not merely queued for it, when the cancel lands.
            fetching.await(5, TimeUnit.SECONDS) shouldBe true

            val elapsed = measureTime { job.cancelAndJoin() }

            outcome?.cancelled shouldBe true
            elapsed shouldBeLessThan 500.milliseconds
        } finally {
            release.countDown()
        }
    }

    /**
     * The watchdog's timeout is a [kotlinx.coroutines.CancellationException], and the pass catches
     * it before it rethrows cancellation, so one slow entry is a failure with the timeout key and the
     * run goes on (unit B's carry-over 2).
     */
    @Test
    fun `an entry that outlasts the watchdog is a failure and the run is not cancelled`() = runTest {
        seed(item(1L), item(2L))
        sources[1L] = FakeSource(1L) {
            delay(10.minutes)
            MangaUpdate(null, emptyList())
        }

        val outcome = run(automatic = true)

        outcome.cancelled shouldBe false
        outcome.failures.single().manga.id shouldBe 1L
        outcome.failures.single().error.shouldBeInstanceOf<TimeoutCancellationException>()
        ErrorKey.of(outcome.failures.single().error) shouldBe ErrorKey.TIMEOUT
        source(2L).calls shouldBe 1
    }

    /**
     * A source can raise a cancellation of its own, from a timeout inside it or a call it cancelled
     * itself, while the run still wants the answer. That is the entry's failure; read as the run's
     * cancellation it would leave the entry unreported, and the queue waiting for it.
     */
    @Test
    fun `a cancellation the source raises itself is that entry's failure and the run goes on`() = runTest {
        seed(item(1L), item(2L))
        val raised = kotlinx.coroutines.CancellationException("fixture: cancelled inside the source")
        sources[1L] = FakeSource(1L) { throw raised }

        val outcome = run(automatic = true)

        outcome.cancelled shouldBe false
        outcome.failures.single().error shouldBeSameInstanceAs raised
        source(2L).calls shouldBe 1
    }

    /**
     * The error store looks one cause deep, so the failure has to be the source's own throwable. A
     * failure thrown through `await` can come back as a copy wrapping the original whenever
     * kotlinx.coroutines recovers stack traces, which it does under the JVM's assertions, and this
     * one would then read as generic instead of as no network (unit B's carry-over 1).
     */
    @Test
    fun `a failure reaches the outcome as the source threw it`() = runTest {
        seed(item(1L))
        val thrown = IOException("fixture: wrapped", UnknownHostException("fixture host"))
        sources[1L] = FakeSource(1L) { throw thrown }

        val outcome = run(automatic = true)

        outcome.failures.single().error shouldBeSameInstanceAs thrown
        ErrorKey.of(outcome.failures.single().error) shouldBe ErrorKey.NO_NETWORK
    }

    // region details and stored chapters

    @Test
    fun `an entry whose details were never fetched skips every rule and is asked for its details`() = runTest {
        restrict(
            LibraryUpdateRestrictions.MANGA_NON_COMPLETED,
            LibraryUpdateRestrictions.MANGA_HAS_UNREAD,
            LibraryUpdateRestrictions.MANGA_NON_READ,
            LibraryUpdateRestrictions.MANGA_OUTSIDE_RELEASE_PERIOD,
        )
        seed(
            item(
                1L,
                initialized = false,
                status = MangaStatus.COMPLETED.code.toLong(),
                totalChapters = 4,
                readCount = 1,
                nextUpdate = WINDOW.second + 1,
                updateStrategy = UpdateStrategy.ONLY_FETCH_ONCE,
            ),
            item(2L, initialized = false, totalChapters = 4, readCount = 0),
            item(3L, status = MangaStatus.COMPLETED.code.toLong()),
        )

        run(automatic = true)

        source(1L).asked.single().fetchDetails shouldBe true
        source(2L).asked.single().fetchDetails shouldBe true
        source(3L).calls shouldBe 0
    }

    @Test
    fun `a manual run asks a never fetched entry for its details and leaves the others at chapters`() = runTest {
        seed(item(1L, initialized = false), item(2L))

        run(automatic = false)

        source(1L).asked.single().fetchDetails shouldBe true
        source(2L).asked.single().fetchDetails shouldBe false
    }

    @Test
    fun `the metadata setting asks every entry for details on an automatic run and none on a manual one`() = runTest {
        preferences.autoUpdateMetadata.set(true)
        seed(item(1L))

        run(automatic = true)
        run(automatic = false)

        source(1L).asked.map { it.fetchDetails } shouldBe listOf(true, false)
    }

    @Test
    fun `each entry is asked with its stored chapters in source order`() = runTest {
        seed(item(1L), item(2L))
        storedChapters[1L] = listOf(
            chapter(11L, 1L).copy(url = "/manga/1/1", name = "Chapter 1", sourceOrder = 1L, chapterNumber = 1.0),
            chapter(12L, 1L).copy(url = "/manga/1/2", name = "Chapter 2", sourceOrder = 0L, chapterNumber = 2.0),
        )

        run(automatic = true)

        val handed = source(1L).asked.single().chapters
        handed.map { it.url } shouldBe listOf("/manga/1/2", "/manga/1/1")
        handed.map { it.name } shouldBe listOf("Chapter 2", "Chapter 1")
        handed.map { it.chapterNumber } shouldBe listOf(2f, 1f)
        source(2L).asked.single().chapters.shouldBeEmpty()
        coVerify(exactly = 1) { getChaptersByMangaId.await(1L, false) }
    }

    // endregion

    // region the source registry

    /**
     * A run the system starts can start the process, and the registry answers a stub for every id
     * until its providers have loaded (phase 2 plan, section 1.6). Here the entry's source is
     * missing from the registry until the flag flips, as an extension's is on a cold start: a pass
     * that did not wait would record it as not installed.
     */
    @Test
    fun `the pass waits for the source registry before it builds its queue`() = runTest {
        seed(item(1L))
        val loaded = sources.remove(1L)!!
        registryReady.value = false

        var outcome: FlatLibraryUpdate.Outcome? = null
        val started = mutableListOf<Long>()
        val job = launch {
            outcome = pass().run(categoryId = null, automatic = true) { started += it.current!!.id }
        }
        advanceUntilIdle()

        outcome shouldBe null
        started.shouldBeEmpty()
        coVerify(exactly = 0) { getLibraryManga.await() }
        verify(exactly = 0) { sourceManager.getOrStub(any()) }

        sources[1L] = loaded
        registryReady.value = true
        job.join()

        outcome?.failures.shouldNotBeNull().shouldBeEmpty()
        outcome?.cancelled shouldBe false
        started shouldBe listOf(1L)
        source(1L).calls shouldBe 1
    }

    @Test
    fun `a run cancelled while it waits for the registry returns a cancelled outcome`() = runTest {
        seed(item(1L))
        registryReady.value = false

        var outcome: FlatLibraryUpdate.Outcome? = null
        val job = launch { outcome = pass().run(categoryId = null, automatic = true) {} }
        advanceUntilIdle()
        job.cancelAndJoin()

        outcome.shouldNotBeNull()
        outcome?.cancelled shouldBe true
        outcome?.failures.shouldNotBeNull().shouldBeEmpty()
        outcome?.updated shouldBe emptyMap()
        source(1L).calls shouldBe 0
    }

    // endregion

    // region new chapters to the download queue (phase 3 unit AD)

    @Test
    fun `each entry's new chapters go to the download rule unstarted and the queue starts once at the end`() = runTest {
        seed(item(1L), item(2L), item(3L))
        coEvery { syncChapters.await(any(), match { it.id == 1L }, any(), any()) } returns listOf(chapter(11L, 1L))
        coEvery { syncChapters.await(any(), match { it.id == 2L }, any(), any()) } returns emptyList()
        coEvery { syncChapters.await(any(), match { it.id == 3L }, any(), any()) } returns
            listOf(chapter(31L, 3L), chapter(32L, 3L))

        run(automatic = true)

        autoDownload.reported.map { (manga, chapters, autoStart) -> Triple(manga.id, chapters.map { it.id }, autoStart) } shouldBe
            listOf(Triple(1L, listOf(11L), false), Triple(3L, listOf(31L, 32L), false))
        autoDownload.starts shouldBe 1
    }

    @Test
    fun `a run whose new chapters the rule declines leaves the queue unstarted`() = runTest {
        seed(item(1L))
        coEvery { syncChapters.await(any(), any(), any(), any()) } returns listOf(chapter(11L, 1L))
        autoDownload.accept = false

        run(automatic = false)

        autoDownload.reported shouldHaveSize 1
        autoDownload.starts shouldBe 0
    }

    @Test
    fun `an entry with no new chapters is not reported`() = runTest {
        seed(item(1L))

        run(automatic = true)

        autoDownload.reported.shouldBeEmpty()
        autoDownload.starts shouldBe 0
    }

    @Test
    fun `a rule that throws is neither the entry's failure nor the run's`() = runTest {
        seed(item(1L), item(2L))
        coEvery { syncChapters.await(any(), any(), any(), any()) } answers {
            listOf(chapter(10L + secondArg<Manga>().id, secondArg<Manga>().id))
        }
        autoDownload.failure = IOException("fixture: the queue refused the write")

        val outcome = run(automatic = true)

        outcome.failures.shouldBeEmpty()
        outcome.updated.keys shouldBe setOf(1L, 2L)
        autoDownload.reported shouldHaveSize 2
    }

    @Test
    fun `a cancelled run does not start the queue for what it queued`() = runTest {
        seed(item(1L), item(2L))
        coEvery { syncChapters.await(any(), match { it.id == 1L }, any(), any()) } returns listOf(chapter(11L, 1L))
        val neverAnswers = CompletableDeferred<MangaUpdate>()
        sources[2L] = FakeSource(2L) { neverAnswers.await() }

        var outcome: FlatLibraryUpdate.Outcome? = null
        val job = launch { outcome = pass().run(categoryId = null, automatic = true) {} }
        runCurrent()
        autoDownload.reported shouldHaveSize 1
        source(2L).calls shouldBe 1

        job.cancelAndJoin()

        outcome?.cancelled shouldBe true
        autoDownload.starts shouldBe 0
    }

    // endregion

    // region helpers

    private suspend fun TestScope.run(automatic: Boolean) =
        pass().run(categoryId = null, automatic = automatic) {}

    private fun restrict(vararg restrictions: String) {
        preferences.autoUpdateMangaRestrictions.set(restrictions.toSet())
    }

    private fun seed(vararg items: LibraryManga) {
        library = items.toList()
        items.forEach { sources[it.manga.source] = FakeSource(it.manga.source) { MangaUpdate(null, emptyList()) } }
    }

    private fun source(id: Long) = sources.getValue(id) as FakeSource

    private fun item(
        id: Long,
        categories: List<Long> = listOf(1L),
        totalChapters: Long = 0L,
        readCount: Long = 0L,
        status: Long = MangaStatus.ONGOING.code.toLong(),
        nextUpdate: Long = 0L,
        updateStrategy: UpdateStrategy = UpdateStrategy.ALWAYS_UPDATE,
        // A library entry has usually been opened; false is one whose details were never fetched.
        initialized: Boolean = true,
        // One source per entry by default keeps the ordering rule out of the assertions.
        source: Long = id,
    ) = LibraryManga(
        manga = Manga.create().copy(
            id = id,
            source = source,
            url = "/manga/$id",
            title = "Fixture $id",
            favorite = true,
            status = status,
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

    private fun chapter(id: Long, mangaId: Long) = Chapter.create().copy(id = id, mangaId = mangaId)

    private class FakeSource(
        override val id: Long,
        private val answer: suspend () -> MangaUpdate,
    ) : Source {
        /** What one call was handed. */
        data class Asked(val manga: MangaInfo, val chapters: List<ChapterInfo>, val fetchDetails: Boolean)

        val asked = mutableListOf<Asked>()

        val calls: Int get() = asked.size

        override val name: String = "Fixture Source A"
        override val lang: String = "en"

        override suspend fun getMangaDetails(manga: MangaInfo): MangaInfo = manga
        override suspend fun getChapterList(manga: MangaInfo): List<ChapterInfo> = emptyList()
        override suspend fun getPageList(chapter: ChapterInfo): List<PageInfo> = emptyList()
        override suspend fun getImage(page: PageInfo): ImageResult = error("Unused by the update pass")

        override suspend fun getMangaUpdate(
            manga: MangaInfo,
            chapters: List<ChapterInfo>,
            fetchDetails: Boolean,
            fetchChapters: Boolean,
        ): MangaUpdate {
            synchronized(asked) { asked += Asked(manga, chapters, fetchDetails) }
            return answer()
        }
    }

    /**
     * Records what the pass hands the download rule. It queues everything unless [accept] is off,
     * and throws [failure] after recording when one is set.
     */
    private class RecordingAutoDownload : AutoDownload {
        val reported = mutableListOf<Triple<Manga, List<Chapter>, Boolean>>()
        var starts = 0
        var accept = true
        var failure: Exception? = null

        override suspend fun queueNewChapters(manga: Manga, chapters: List<Chapter>, autoStart: Boolean): Int {
            synchronized(reported) { reported += Triple(manga, chapters, autoStart) }
            failure?.let { throw it }
            return if (accept) chapters.size else 0
        }

        override suspend fun queuePulledChapters(chapterIds: List<Long>): Int = error("Unused by the update pass")

        override suspend fun startQueue() {
            starts++
        }

        override suspend fun downloadAhead(
            mangaId: Long,
            pageIndex: Int,
            pageCount: Int,
            nextChapterId: Long?,
        ): Int = error("Unused by the update pass")
    }

    private companion object {
        val WINDOW = 0L to 1_000L
    }

    // endregion
}


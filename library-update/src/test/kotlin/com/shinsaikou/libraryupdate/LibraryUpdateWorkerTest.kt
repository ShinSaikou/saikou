package com.shinsaikou.libraryupdate

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.ForegroundInfo
import androidx.work.ForegroundUpdater
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.ProgressUpdater
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.google.common.util.concurrent.ListenableFuture
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.chapter.interactor.SyncChaptersWithSource
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.LibraryUpdateRestrictions
import com.shinsaikou.domain.preferences.LibraryUpdateSkipCounts
import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceManager
import com.shinsaikou.source.model.MangaInfo
import com.shinsaikou.source.model.MangaUpdate
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeSource

/**
 * An automatic run the system starts while the app is in the background, where the platform
 * refuses a foreground service (phase 2 plan, section 1.6). The worker is built the way WorkManager
 * builds it; the updater stands in for the platform and refuses with the [IllegalStateException]
 * WorkManager reports.
 */
class LibraryUpdateWorkerTest {

    private val context = mockk<Context>(relaxed = true)
    private val update = mockk<FlatLibraryUpdate>()
    private val getManga = mockk<GetManga>()
    private val notifier = mockk<LibraryUpdateNotifier>(relaxed = true)
    private val errorStore = mockk<LibraryUpdateErrorStore>(relaxed = true)
    private val scheduler = mockk<LibraryUpdateScheduler>(relaxed = true)
    private val preferences = LibraryPreferences(InMemoryPreferenceStore()).apply {
        // The JVM reports API 0, where the worker checks wifi itself through the platform; no
        // device restriction keeps that check out of the test.
        autoUpdateDeviceRestrictions.set(emptySet())
    }

    @BeforeEach
    fun mockCancelIntent() {
        // The notification's cancel action; the platform stub returns null on the JVM, where the
        // worker declares it non-null.
        mockkStatic(PendingIntent::class)
        every { PendingIntent.getBroadcast(any(), any(), any(), any()) } returns mockk()
    }

    @AfterEach
    fun unmockCancelIntent() {
        unmockkStatic(PendingIntent::class)
    }

    @Test
    fun `an automatic run refused the foreground still updates, clears its progress and re-arms`() = runTest {
        coEvery { update.run(any(), any(), any(), any(), any()) } returns
            FlatLibraryUpdate.Outcome(updated = emptyMap(), failures = emptyList(), cancelled = false)
        val updater = RefusingForegroundUpdater()

        val result = build(updater).doWork()

        result shouldBe ListenableWorker.Result.success()
        updater.calls shouldBe 1
        coVerify(exactly = 1) { update.run(null, true, false, null, any()) }
        coVerify(exactly = 1) { errorStore.merge(emptyList(), emptySet(), emptySet(), any()) }
        preferences.lastUpdatedTimestamp.get() shouldBeGreaterThan 0L
        // The finally: the progress notification goes and the periodic request is armed again.
        verify(exactly = 1) { notifier.cancelProgress() }
        verify(exactly = 1) { scheduler.setup() }
    }

    /**
     * A category run reaches only its own entries, and used to replace the whole error list with its
     * failures, so the rows of every other entry went. Now the rows of the entries it checked are
     * replaced, an entry that updated fine loses its row, and the rest stay; a row whose entry left
     * the library goes.
     */
    @Test
    fun `a category run keeps the error rows of the entries it did not reach`(@TempDir filesDir: File) = runTest {
        val store = LibraryUpdateErrorStore(mockk<Context> { every { this@mockk.filesDir } returns filesDir }, Json)
        val raised = IOException("fixture: still unreachable")
        store.replace(
            listOf(1L, 2L, 3L, 4L).map { FlatLibraryUpdate.Failure(item(it).manga, IOException("fixture: earlier")) },
        )
        // Entry 4 left the library since that run.
        coEvery { getManga.await(any()) } answers { firstArg<Long>().takeIf { it != 4L }?.let { item(it).manga } }
        every { getManga.subscribeOrNull(any()) } answers {
            flowOf(firstArg<Long>().takeIf { it != 4L }?.let { item(it).manga })
        }
        coEvery { update.run(5L, false, false, null, any()) } returns FlatLibraryUpdate.Outcome(
            updated = emptyMap(),
            failures = listOf(FlatLibraryUpdate.Failure(item(3L).manga, raised)),
            cancelled = false,
            checked = setOf(2L, 3L),
        )
        val worker = build(
            RefusingForegroundUpdater(),
            LibraryUpdateWorker.WORK_NAME_MANUAL,
            store = store,
            input = workDataOf(LibraryUpdateWorker.KEY_CATEGORY to 5L),
        )

        worker.doWork()

        store.errors.value.map { it.mangaId } shouldBe listOf(1L, 3L)
        LibraryUpdateErrorStore(mockk<Context> { every { this@mockk.filesDir } returns filesDir }, Json)
            .also { it.load() }.errors.value.map { it.mangaId } shouldBe listOf(1L, 3L)
    }

    /**
     * Retry takes the entries at the front of the error list. One it passes over (fetch-once, with
     * its chapters) keeps its row, which used to keep its place too, so a front of such rows took
     * every Retry and the rows behind it were never reached. It now goes behind the rows the run was
     * not given, as a row whose entry failed again does.
     */
    @Test
    fun `a row the run was given but passed over goes behind the rows it was not given`(@TempDir filesDir: File) = runTest {
        val store = LibraryUpdateErrorStore(mockk<Context> { every { this@mockk.filesDir } returns filesDir }, Json)
        store.replace(
            listOf(1L, 2L, 3L, 4L).map { FlatLibraryUpdate.Failure(item(it).manga, IOException("fixture: earlier")) },
        )
        coEvery { getManga.await(any()) } answers { item(firstArg()).manga }
        every { getManga.subscribeOrNull(any()) } answers { flowOf(item(firstArg()).manga) }
        // Entry 1 is passed over, entry 2 updates and entry 3 fails again.
        coEvery { update.run(null, false, false, setOf(1L, 2L, 3L), any()) } returns FlatLibraryUpdate.Outcome(
            updated = emptyMap(),
            failures = listOf(FlatLibraryUpdate.Failure(item(3L).manga, IOException("fixture: again"))),
            cancelled = false,
            checked = setOf(2L, 3L),
        )
        val worker = build(
            RefusingForegroundUpdater(),
            LibraryUpdateWorker.WORK_NAME_MANUAL,
            store = store,
            input = workDataOf(LibraryUpdateWorker.KEY_MANGA_IDS to longArrayOf(1L, 2L, 3L)),
        )

        worker.doWork()

        store.errors.value.map { it.mangaId } shouldBe listOf(4L, 1L, 3L)
    }

    @Test
    fun `a run given entry ids hands them to the pass`() = runTest {
        coEvery { update.run(any(), any(), any(), any(), any()) } returns
            FlatLibraryUpdate.Outcome(updated = emptyMap(), failures = emptyList(), cancelled = false)

        build(
            RefusingForegroundUpdater(),
            LibraryUpdateWorker.WORK_NAME_MANUAL,
            input = workDataOf(LibraryUpdateWorker.KEY_MANGA_IDS to longArrayOf(4L, 7L)),
        ).doWork()

        coVerify(exactly = 1) { update.run(null, false, false, setOf(4L, 7L), any()) }
    }

    @Test
    fun `an automatic run stores its skip counts and a manual run leaves them`() = runTest {
        val skipped = mapOf(LibraryUpdateRestrictions.MANGA_NON_COMPLETED to 5, FlatLibraryUpdate.FETCH_ONCE to 2)
        fun outcome(skips: Map<String, Int>) =
            FlatLibraryUpdate.Outcome(updated = emptyMap(), failures = emptyList(), cancelled = false, skipped = skips)
        coEvery { update.run(any(), any(), any(), any(), any()) } returns outcome(skipped)

        build(RefusingForegroundUpdater()).doWork()
        LibraryUpdateSkipCounts.decode(preferences.lastAutoUpdateSkipped.get()) shouldBe skipped

        coEvery { update.run(any(), any(), any(), any(), any()) } returns outcome(emptyMap())
        build(RefusingForegroundUpdater(), LibraryUpdateWorker.WORK_NAME_MANUAL).doWork()
        LibraryUpdateSkipCounts.decode(preferences.lastAutoUpdateSkipped.get()) shouldBe skipped
    }

    /**
     * An automatic run that comes due while a manual one is going tries again later, as upstream's
     * does: two runs at once would ask the same sites with two budgets and the later one would
     * report over the first.
     */
    @Test
    fun `an automatic run that comes due during a manual run retries later and does nothing`() = runTest {
        coEvery { scheduler.isManualRunning() } returns true

        val result = build(RefusingForegroundUpdater()).doWork()

        result shouldBe ListenableWorker.Result.retry()
        coVerify(exactly = 0) { update.run(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { errorStore.merge(any(), any(), any(), any()) }
        preferences.lastUpdatedTimestamp.get() shouldBe 0L
    }

    /**
     * A start without extensions: the run would record every entry of an extension's source as not
     * installed. It waits for the next start instead, shows nothing meanwhile, and cancels a plain
     * progress notification a run cut off with the last process left.
     */
    @Test
    fun `a run in a start without extensions retries later and does nothing`() = runTest {
        every { scheduler.isPaused } returns true

        for (workName in listOf(LibraryUpdateWorker.WORK_NAME_AUTO, LibraryUpdateWorker.WORK_NAME_MANUAL)) {
            val updater = RefusingForegroundUpdater()
            build(updater, workName).doWork() shouldBe ListenableWorker.Result.retry()
            updater.calls shouldBe 0
        }
        coVerify(exactly = 0) { update.run(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { errorStore.merge(any(), any(), any(), any()) }
        verify(exactly = 2) { notifier.cancelProgress() }
        verify(exactly = 0) { scheduler.setup() }
        preferences.lastUpdatedTimestamp.get() shouldBe 0L
    }

    /**
     * The system brought WorkManager's foreground service back with the last process's progress.
     * WorkManager takes it down only for a run that entered the foreground, so the waiting run does.
     */
    @Test
    fun `a waiting run takes the foreground when a dead run's progress is still up`() = runTest {
        every { scheduler.isPaused } returns true
        every { notifier.isProgressShown() } returns true
        val updater = RefusingForegroundUpdater()

        build(updater).doWork() shouldBe ListenableWorker.Result.retry()

        updater.calls shouldBe 1
        coVerify(exactly = 0) { update.run(any(), any(), any(), any(), any()) }
    }

    /** The manual run is the one the check looks for, so it never waits on itself. */
    @Test
    fun `a manual run goes ahead whatever the manual check answers`() = runTest {
        coEvery { scheduler.isManualRunning() } returns true
        coEvery { update.run(any(), any(), any(), any(), any()) } returns
            FlatLibraryUpdate.Outcome(updated = emptyMap(), failures = emptyList(), cancelled = false)

        val result = build(RefusingForegroundUpdater(), LibraryUpdateWorker.WORK_NAME_MANUAL).doWork()

        result shouldBe ListenableWorker.Result.success()
        coVerify(exactly = 1) { update.run(null, false, false, null, any()) }
    }

    /**
     * The notification's Cancel names this run, so the receiver cancels it alone and not the waiting
     * periodic request, which carries the same tag.
     */
    @Test
    fun `the cancel action carries the run's own id`() = runTest {
        coEvery { update.run(any(), any(), any(), any(), any()) } returns
            FlatLibraryUpdate.Outcome(updated = emptyMap(), failures = emptyList(), cancelled = false)
        // The platform's Intent is a stub on the JVM, so the extras are read off the constructor mock.
        mockkConstructor(Intent::class)
        val extras = mutableListOf<Pair<String, String>>()
        every { anyConstructed<Intent>().putExtra(any<String>(), any<String>()) } answers {
            extras += firstArg<String>() to secondArg<String>()
            self as Intent
        }
        try {
            val worker = build(RefusingForegroundUpdater(), LibraryUpdateWorker.WORK_NAME_MANUAL)

            worker.doWork()

            extras.toSet() shouldBe setOf(LibraryUpdateCancelReceiver.EXTRA_RUN_ID to worker.id.toString())
        } finally {
            unmockkConstructor(Intent::class)
        }
    }

    /**
     * The owner taps Cancel while an entry is still being fetched. WorkManager cancels the Job that
     * runs doWork, and the pass returns what it gathered as a cancelled outcome, but inside a
     * `withContext` whose Job was cancelled from outside a returned value is dropped and the call
     * throws instead. The pass here is the real one on real threads, so the cancel reaches it through
     * the worker exactly as it does on a device; only its data and source are fakes.
     *
     * One source, so its entries run one at a time at the start (a limit of 1): the failing entry and
     * the updated one have both ended, and their results are recorded, before the third is fetched.
     */
    @Test
    fun `a run stopped mid-fetch still records its failures and announces its new chapters`() = runTest {
        val library = listOf(item(1L), item(2L), item(3L))
        val raised = IOException("fixture: the source is unreachable")
        val fetching = CountDownLatch(1)
        val release = CountDownLatch(1)
        val source = mockk<Source>()
        every { source.id } returns SOURCE
        coEvery { source.getMangaUpdate(any(), any(), any(), any()) } answers {
            when (firstArg<MangaInfo>().url) {
                "/manga/1" -> throw raised
                "/manga/2" -> MangaUpdate(null, emptyList())
                else -> {
                    // A real source blocks its thread in I/O; the latch is bounded so a regression
                    // cannot hang the suite.
                    fetching.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    MangaUpdate(null, emptyList())
                }
            }
        }
        val newChapter = Chapter.create().copy(id = 20L, mangaId = 2L)
        val syncChapters = mockk<SyncChaptersWithSource>()
        coEvery { syncChapters.await(any(), any(), any(), any()) } answers {
            if (secondArg<Manga>().id == 2L) listOf(newChapter) else emptyList()
        }
        coEvery { getManga.await(any()) } answers { library.firstOrNull { it.id == firstArg<Long>() }?.manga }
        every { getManga.subscribeOrNull(any()) } answers {
            flowOf(library.firstOrNull { it.id == firstArg<Long>() }?.manga)
        }
        val getLibraryManga = mockk<GetLibraryManga>()
        coEvery { getLibraryManga.await() } returns library
        val sourceManager = mockk<SourceManager>()
        every { sourceManager.isInitialized } returns MutableStateFlow(true)
        every { sourceManager.getOrStub(SOURCE) } returns source
        val io = kotlinx.coroutines.Dispatchers.IO
        val pass = FlatLibraryUpdate(
            getLibraryManga = getLibraryManga,
            getManga = getManga,
            getChaptersByMangaId = mockk { coEvery { await(any(), any()) } returns emptyList() },
            sourceManager = sourceManager,
            syncChaptersWithSource = syncChapters,
            updateManga = mockk(relaxed = true),
            fetchInterval = mockk { every { getWindow(any(), any()) } returns (0L to 1_000L) },
            libraryPreferences = preferences,
            getMergedGroups = mockk { coEvery { await() } returns emptyList() },
            reconcileMergedRead = mockk(relaxed = true),
            autoDownload = mockk(relaxed = true),
            dispatchers = Dispatchers(io = io, default = io, main = io),
            timeSource = TimeSource.Monotonic,
        )
        val worker = build(RefusingForegroundUpdater(), LibraryUpdateWorker.WORK_NAME_MANUAL, pass)

        try {
            var result: ListenableWorker.Result? = null
            val job = launch { result = worker.doWork() }
            advanceUntilIdle()
            // The third entry's thread is inside the source when the cancel lands.
            fetching.await(5, TimeUnit.SECONDS) shouldBe true

            job.cancelAndJoin()

            result shouldBe ListenableWorker.Result.success()
            coVerify(exactly = 1) {
                errorStore.merge(
                    match { it.size == 1 && it.single().manga.id == 1L && it.single().error === raised },
                    // The third entry was still being fetched, so its row from an earlier run stays.
                    setOf(1L, 2L),
                    emptySet(),
                    any(),
                )
            }
            verify(exactly = 1) { notifier.showNewChapters(match { it.size == 1 && it.keys.single().id == 2L }) }
            verify(exactly = 1) { notifier.showErrors(1) }
            verify(exactly = 1) { notifier.cancelProgress() }
        } finally {
            release.countDown()
        }
    }

    /**
     * The worker's progress lambda passes the work data and the notification through one cadence
     * (phase 5 unit SC5): twelve entries start, the clock still for the first ten and then a second
     * on, with none finished. The first, the one after the second passed and the last go out, the
     * same three to both. The eleventh carries the owner's edit, and its report names the entry by it
     * as the notification does (phase 5 unit ED).
     */
    @Test
    fun `the work data and the notification take the same reports through the cadence`() = runTest {
        val clock = TestTimeSource()
        val entries = (1L..12L).map { item(it).manga }
            .map { if (it.id == 11L) it.copy(titleOverride = "Edited 11") else it }
        coEvery { update.run(any(), any(), any(), any(), any()) } answers {
            val progress = arg<(FlatLibraryUpdate.Progress) -> Unit>(4)
            entries.forEachIndexed { index, manga ->
                if (index == 10) clock += 1.seconds
                progress(FlatLibraryUpdate.Progress(done = 0, total = entries.size, current = manga))
            }
            FlatLibraryUpdate.Outcome(updated = emptyMap(), failures = emptyList(), cancelled = false)
        }
        val written = mutableListOf<Data>()
        val progressUpdater = ProgressUpdater { _, _, data ->
            written += data
            // The worker discards the future setProgressAsync returns.
            mockk<ListenableFuture<Void>>(relaxed = true)
        }
        val shown = mutableListOf<FlatLibraryUpdate.Progress>()
        every { notifier.showProgress(capture(shown), any()) } returns Unit

        build(RefusingForegroundUpdater(), timeSource = clock, progressUpdater = progressUpdater).doWork()

        shown.map { it.current?.id } shouldBe listOf(1L, 11L, 12L)
        written.map { it.getString(LibraryUpdateWorker.KEY_PROGRESS_CURRENT) } shouldBe
            listOf("Fixture 1", "Edited 11", "Fixture 12")
        written.map { it.getInt(LibraryUpdateWorker.KEY_PROGRESS_TOTAL, -1) } shouldBe listOf(12, 12, 12)
        verify(exactly = 1) { notifier.cancelProgress() }
    }

    private fun build(
        updater: ForegroundUpdater,
        workName: String = LibraryUpdateWorker.WORK_NAME_AUTO,
        pass: FlatLibraryUpdate = update,
        timeSource: TimeSource = TimeSource.Monotonic,
        progressUpdater: ProgressUpdater? = null,
        store: LibraryUpdateErrorStore = errorStore,
        input: Data = Data.EMPTY,
    ): LibraryUpdateWorker =
        TestListenableWorkerBuilder.from(context, LibraryUpdateWorker::class.java)
            .setTags(listOf(LibraryUpdateWorker.TAG, workName))
            .setInputData(input)
            .setForegroundUpdater(updater)
            .apply { progressUpdater?.let(::setProgressUpdater) }
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                        LibraryUpdateWorker(
                            context = appContext,
                            params = workerParameters,
                            update = pass,
                            getManga = getManga,
                            notifier = notifier,
                            errorStore = store,
                            libraryPreferences = preferences,
                            scheduler = scheduler,
                            timeSource = timeSource,
                        )
                },
            )
            .build()

    private fun item(id: Long) = LibraryManga(
        manga = Manga.create().copy(
            id = id,
            source = SOURCE,
            url = "/manga/$id",
            title = "Fixture $id",
            favorite = true,
        ),
        categories = listOf(1L),
        totalChapters = 0L,
        readCount = 0L,
        bookmarkCount = 0L,
        latestUpload = 0L,
        chapterFetchedAt = 0L,
        lastRead = 0L,
    )

    /** What WorkManager raises when a background app asks for a foreground service on API 31 and above. */
    private class RefusingForegroundUpdater : ForegroundUpdater {
        var calls = 0
            private set

        override fun setForegroundAsync(context: Context, id: UUID, foregroundInfo: ForegroundInfo): ListenableFuture<Void> {
            calls++
            throw IllegalStateException("fixture: a foreground service is not allowed from the background")
        }
    }

    private companion object {
        const val SOURCE = 7L
    }
}

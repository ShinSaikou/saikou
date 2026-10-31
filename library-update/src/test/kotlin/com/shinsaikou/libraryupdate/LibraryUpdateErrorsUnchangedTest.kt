package com.shinsaikou.libraryupdate

import android.app.PendingIntent
import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.chapter.interactor.SyncChaptersWithSource
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceManager
import com.shinsaikou.source.StubSource
import com.shinsaikou.source.model.ChapterInfo
import com.shinsaikou.source.model.ImageResult
import com.shinsaikou.source.model.MangaInfo
import com.shinsaikou.source.model.MangaUpdate
import com.shinsaikou.source.model.PageInfo
import eu.kanade.tachiyomi.network.HttpException
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.NoChaptersException
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.time.TimeSource

/**
 * Done item 7 of the phase 2 plan's unit E: a run with failures leaves the error store, and so the
 * error groups built from it, and the error notification exactly as the sequential pass left them.
 *
 * The run is the worker's own, with the real pass on real threads and the real store, over eight
 * entries on five sources: a socket timeout, an HTTP 503, an entry with a new chapter, an entry with
 * no chapters, a source that is not installed, a plain exception, a network failure wrapped in an
 * `IOException`, and an HTTP 429. [SEQUENTIAL_FILE] is what `SequentialLibraryUpdate` at `bd95cd5`
 * wrote for the same library, taken with kotlinx.coroutines' debug mode off as on a device
 * (`evidence/phase2-flat-queue/golden-sequential.txt` in the design tree), with one change since:
 * the plain exception's row keeps its message where the sequential pass kept its class name (R162
 * item 8). The failures are in queue order, as the sequential pass met them, though here they finish
 * in any order.
 */
class LibraryUpdateErrorsUnchangedTest {

    private val context = mockk<Context>(relaxed = true)
    private val notifier = mockk<LibraryUpdateNotifier>(relaxed = true)
    private val scheduler = mockk<LibraryUpdateScheduler>(relaxed = true)
    private val preferences = LibraryPreferences(InMemoryPreferenceStore())

    private val sourceOf = mapOf(1L to 1L, 2L to 1L, 3L to 2L, 4L to 2L, 5L to 3L, 6L to 4L, 7L to 4L, 8L to 5L)
    private val library = sourceOf.map { (id, source) -> UpdateBench.entry(id, source) }
    private val answers: Map<Long, () -> MangaUpdate> = mapOf(
        1L to { throw SocketTimeoutException("Read timed out") },
        2L to { throw HttpException(503) },
        3L to { MangaUpdate(null, emptyList()) },
        4L to { throw NoChaptersException() },
        6L to { throw IllegalStateException("raw text from a source") },
        7L to { throw IOException("wrapped", UnknownHostException("fixture host")) },
        8L to { throw HttpException(429) },
    )

    @BeforeEach
    fun mockCancelIntent() {
        mockkStatic(PendingIntent::class)
        every { PendingIntent.getBroadcast(any(), any(), any(), any()) } returns mockk()
    }

    @AfterEach
    fun unmockCancelIntent() {
        unmockkStatic(PendingIntent::class)
    }

    @Test
    fun `a run with failures stores, groups and notifies them as the sequential pass did`(@TempDir filesDir: File) = runTest {
        val storeContext = mockk<Context> { every { this@mockk.filesDir } returns filesDir }
        val store = LibraryUpdateErrorStore(storeContext, Json)

        val result = worker(store).doWork()

        result shouldBe ListenableWorker.Result.success()
        store.file.readText().replace(Regex("\"at\":[0-9]+"), "\"at\":0") shouldBe SEQUENTIAL_FILE
        // The groups are built from these rows by source id (R42), so the same rows are the same groups.
        store.errors.value.groupBy({ it.sourceId }, { it.mangaId }) shouldBe
            mapOf(1L to listOf(1L, 2L), 2L to listOf(4L), 3L to listOf(5L), 4L to listOf(6L, 7L), 5L to listOf(8L))
        verify(exactly = 1) { notifier.showErrors(7) }
        verify(exactly = 1) {
            notifier.showNewChapters(match { it.keys.map(Manga::id) == listOf(3L) && it.values.single().single().id == 31L })
        }
        preferences.newUpdatesCount.get() shouldBe 1
    }

    private fun worker(store: LibraryUpdateErrorStore): LibraryUpdateWorker {
        val sources = (1L..5L).filter { it != NOT_INSTALLED }.associateWith { AnsweringSource(it) }
        val sourceManager = mockk<SourceManager> {
            every { isInitialized } returns MutableStateFlow(true)
            every { getOrStub(any()) } answers { sources[firstArg<Long>()] ?: StubSource.unknown(firstArg()) }
        }
        val getManga = mockk<GetManga> {
            coEvery { await(any<Long>()) } answers { library.firstOrNull { it.id == firstArg<Long>() }?.manga }
        }
        val update = FlatLibraryUpdate(
            getLibraryManga = mockk<GetLibraryManga> { coEvery { await() } returns library },
            getManga = getManga,
            getChaptersByMangaId = mockk { coEvery { await(any(), any()) } returns emptyList() },
            sourceManager = sourceManager,
            syncChaptersWithSource = mockk<SyncChaptersWithSource> {
                coEvery { await(any(), any(), any(), any()) } answers {
                    if (secondArg<Manga>().id == 3L) listOf(Chapter.create().copy(id = 31L, mangaId = 3L)) else emptyList()
                }
            },
            updateManga = mockk(relaxed = true),
            fetchInterval = mockk<FetchInterval> { every { getWindow(any(), any()) } returns (0L to 1_000L) },
            libraryPreferences = preferences,
            autoDownload = mockk(relaxed = true),
            dispatchers = Dispatchers(
                io = kotlinx.coroutines.Dispatchers.IO,
                default = kotlinx.coroutines.Dispatchers.Default,
                main = kotlinx.coroutines.Dispatchers.Default,
            ),
            timeSource = TimeSource.Monotonic,
        )
        return TestListenableWorkerBuilder.from(context, LibraryUpdateWorker::class.java)
            .setTags(listOf(LibraryUpdateWorker.TAG, LibraryUpdateWorker.WORK_NAME_MANUAL))
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                        LibraryUpdateWorker(
                            context = appContext,
                            params = workerParameters,
                            update = update,
                            getManga = getManga,
                            notifier = notifier,
                            errorStore = store,
                            libraryPreferences = preferences,
                            scheduler = scheduler,
                            timeSource = TimeSource.Monotonic,
                        )
                },
            )
            .build()
    }

    /** Answers each entry from [answers] by the id its url carries. */
    private inner class AnsweringSource(override val id: Long) : Source {
        override val name: String = "Fixture Source $id"
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
        ): MangaUpdate =
            answers.getValue(manga.url.removePrefix("/manga/").toLong())()
    }

    private companion object {
        const val NOT_INSTALLED = 3L

        const val SEQUENTIAL_FILE =
            """[{"mangaId":1,"title":"Fixture 1","sourceId":1,"error":"timeout","at":0},""" +
                """{"mangaId":2,"title":"Fixture 2","sourceId":1,"error":"http","detail":"503","at":0},""" +
                """{"mangaId":4,"title":"Fixture 4","sourceId":2,"error":"no_chapters","at":0},""" +
                """{"mangaId":5,"title":"Fixture 5","sourceId":3,"error":"source_not_installed","at":0},""" +
                """{"mangaId":6,"title":"Fixture 6","sourceId":4,"error":"generic",""" +
                """"detail":"raw text from a source","at":0},""" +
                """{"mangaId":7,"title":"Fixture 7","sourceId":4,"error":"no_network","at":0},""" +
                """{"mangaId":8,"title":"Fixture 8","sourceId":5,"error":"http","detail":"429","at":0}]"""
    }
}

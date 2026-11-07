package com.shinsaikou.migration

import app.cash.sqldelight.db.QueryResult
import com.shinsaikou.data.DatabaseTransactionRunner
import com.shinsaikou.data.chapter.ScanlatorPriorityRepositoryImpl
import com.shinsaikou.data.download.DownloadManifestRepositoryImpl
import com.shinsaikou.data.source.SourceAliasRepositoryImpl
import com.shinsaikou.data.sync.SyncChangeLogRepositoryImpl
import com.shinsaikou.data.sync.SyncStoreImpl
import com.shinsaikou.domain.chapter.ChapterReplacements
import com.shinsaikou.domain.chapter.interactor.GetExcludedScanlators
import com.shinsaikou.domain.chapter.interactor.GetScanlatorPriority
import com.shinsaikou.domain.chapter.interactor.SyncChaptersWithSource
import com.shinsaikou.domain.download.AutoDownload
import com.shinsaikou.domain.download.model.DownloadQueueEntry
import com.shinsaikou.domain.manga.interactor.UpdateManga
import com.shinsaikou.domain.merged.interactor.ReconcileMergedRead
import com.shinsaikou.domain.preferences.DownloadPreferences
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.ReaderPreferences
import com.shinsaikou.domain.preferences.SyncRestorePreferences
import com.shinsaikou.downloads.AutoDownloadRules
import com.shinsaikou.downloads.DownloadManifest
import com.shinsaikou.downloads.DownloadQueue
import com.shinsaikou.libraryupdate.FlatLibraryUpdate
import com.shinsaikou.migration.MigrationTestEnvironment.Companion.chapter
import com.shinsaikou.source.CatalogueSource
import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceManager
import com.shinsaikou.source.StubSource
import com.shinsaikou.source.model.ChapterInfo
import com.shinsaikou.source.model.ImageResult
import com.shinsaikou.source.model.MangaInfo
import com.shinsaikou.source.model.PageInfo
import com.shinsaikou.sync.BackendStatus
import com.shinsaikou.sync.MapPreferenceStore
import com.shinsaikou.sync.MergeEngine
import com.shinsaikou.sync.RemoteFile
import com.shinsaikou.sync.SyncBackend
import com.shinsaikou.sync.SyncContent
import com.shinsaikou.sync.SyncDigest
import com.shinsaikou.sync.SyncEngine
import com.shinsaikou.sync.SyncPreferences
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import tachiyomi.data.history.HistoryRepositoryImpl
import tachiyomi.domain.chapter.interactor.FilterChaptersForDownload
import tachiyomi.domain.migration.models.MigrationFlag
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import kotlin.time.TimeSource
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers
import kotlinx.coroutines.Dispatchers as CoroutineDispatchers

/**
 * A migration's target reaches the other device with every chapter (phase 4 unit NB, g8 test 2 and
 * challenge c5 change 7): device A migrates an entry onto a target of 100 chapters with the real
 * [MigrateManga] over the real schema and triggers, both devices sync through the real engine, and
 * device B's Updates tab then lists for the target exactly what A's does, and B's manual library
 * update against a source listing one chapter more queues that chapter alone. Before the fix B held
 * only the target's chapters with copied progress, listed every chapter its own update inserted, and
 * queued them all.
 */
class MigrateMangaSyncTest {

    @TempDir
    lateinit var tempDir: File

    private val environments = mutableListOf<MigrationTestEnvironment>()

    @AfterEach
    fun tearDown() {
        environments.forEach { it.close() }
    }

    private val backend = MemoryBackend()

    /** One device: a migration environment's database, its sync engine and its download rules. */
    private inner class Device(name: String) {
        val env = MigrationTestEnvironment(File(tempDir, name).apply { mkdirs() }).also { environments += it }
        val queue = RecordingQueue()
        val autoDownload: AutoDownload = rulesOver(env, queue)
        val store = SyncStoreImpl(env.handler, env.driver)
        private val engine = SyncEngine(
            store = store,
            changeLog = SyncChangeLogRepositoryImpl(env.handler),
            sourceAliases = SourceAliasRepositoryImpl(env.handler),
            preferences = SyncPreferences(MapPreferenceStore()),
            restorePreferences = MapPreferenceStore().let { store ->
                SyncRestorePreferences(store) { key, value -> store.getBoolean(key, false).set(value) }
            },
            merge = MergeEngine(),
            autoDownload = autoDownload,
            reconcileMergedRead = ReconcileMergedRead(mockk(), LibraryPreferences(InMemoryPreferenceStore())),
        )

        fun sync() = runBlocking { engine.run(backend) }.shouldNotBeNull()

        fun entryId(source: Long, url: String): Long =
            env.long("SELECT _id FROM mangas WHERE source = $source AND url = '$url'").shouldNotBeNull()

        /** The chapter urls of an entry the Updates tab lists here (`updatesView`). */
        fun updatesOf(mangaId: Long): List<String> = buildList {
            env.driver.executeQuery(null, "SELECT chapterUrl FROM updatesView WHERE mangaId = $mangaId ORDER BY chapterUrl", { cursor ->
                while (cursor.next().value) add(cursor.getString(0)!!)
                QueryResult.Unit
            }, 0).value
        }

        /** The urls of the chapters this device's rules queued, in order. */
        fun queuedUrls(): List<String> = queue.queued.map(::urlOf)

        private fun urlOf(chapterId: Long): String = buildList {
            env.driver.executeQuery(null, "SELECT url FROM chapters WHERE _id = $chapterId", { cursor ->
                if (cursor.next().value) add(cursor.getString(0)!!)
                QueryResult.Unit
            }, 0).value
        }.single()

        fun digest(): SyncDigest.Result = runBlocking { SyncDigest.of(store) }

        /** One chapter's history here, as (read at, time read), or null when it has none. */
        fun history(source: Long, mangaUrl: String, chapterUrl: String): Pair<Long, Long>? {
            val chapter = "SELECT H.%s FROM history H JOIN chapters C ON C._id = H.chapter_id " +
                "JOIN mangas M ON M._id = C.manga_id WHERE M.source = $source AND M.url = '$mangaUrl' AND C.url = '$chapterUrl'"
            val readAt = env.long(chapter.format("last_read")) ?: return null
            return readAt to env.long(chapter.format("time_read"))!!
        }

        /** A manual library update over this device's own repositories, the source listing [chapters]. */
        fun update(source: Long, chapters: List<ChapterInfo>): FlatLibraryUpdate.Outcome = runBlocking {
            val mangas = MangaRepositoryImpl(env.handler)
            val chapterRepository = ChapterRepositoryImpl(env.handler)
            val getChaptersByMangaId = GetChaptersByMangaId(chapterRepository)
            val fetchInterval = FetchInterval(getChaptersByMangaId)
            val updateManga = UpdateManga(mangas, fetchInterval)
            val libraryPreferences = LibraryPreferences(InMemoryPreferenceStore())
            val io = CoroutineDispatchers.Default
            FlatLibraryUpdate(
                getLibraryManga = GetLibraryManga(mangas),
                getManga = GetManga(mangas),
                getChaptersByMangaId = getChaptersByMangaId,
                sourceManager = OneSourceManager(ListingSource(source, chapters)),
                syncChaptersWithSource = SyncChaptersWithSource(
                    chapterRepository = chapterRepository,
                    shouldUpdateDbChapter = ShouldUpdateDbChapter(),
                    updateManga = updateManga,
                    getChaptersByMangaId = getChaptersByMangaId,
                    transactionRunner = DatabaseTransactionRunner(env.handler),
                    downloadManifestRepository = DownloadManifestRepositoryImpl(env.handler),
                    libraryPreferences = libraryPreferences,
                    getExcludedScanlators = GetExcludedScanlators(mangas),
                    historyRepository = HistoryRepositoryImpl(env.handler),
                    chapterReplacements = ChapterReplacements(
                        chapterRepository,
                        DatabaseTransactionRunner(env.handler),
                    ),
                ),
                updateManga = updateManga,
                fetchInterval = fetchInterval,
                libraryPreferences = libraryPreferences,
                getMergedGroups = mockk { coEvery { await() } returns emptyList() },
                reconcileMergedRead = mockk(relaxed = true),
                autoDownload = autoDownload,
                dispatchers = AppDispatchers(io = io, default = io, main = io),
                timeSource = TimeSource.Monotonic,
            ).run(categoryId = null, automatic = false) {}
        }
    }

    private fun rulesOver(env: MigrationTestEnvironment, queue: DownloadQueue): AutoDownloadRules {
        val chapters = ChapterRepositoryImpl(env.handler)
        val getChaptersByMangaId = GetChaptersByMangaId(chapters)
        val getManga = GetManga(MangaRepositoryImpl(env.handler))
        val downloadPreferences = DownloadPreferences(InMemoryPreferenceStore()).apply { downloadNewChapters.set(true) }
        return AutoDownloadRules(
            filterChaptersForDownload = FilterChaptersForDownload(
                getChaptersByMangaId,
                downloadPreferences,
                GetCategories(CategoryRepositoryImpl(env.handler)),
            ),
            getNextChapters = GetNextChapters(getChaptersByMangaId, getManga, mockk(), mockk { coEvery { await(any()) } returns null }, mockk()),
            getScanlatorPriority = GetScanlatorPriority(ScanlatorPriorityRepositoryImpl(env.handler)),
            getManga = getManga,
            chapterRepository = chapters,
            manifest = mockk<DownloadManifest>(relaxed = true),
            queue = queue,
            downloadPreferences = downloadPreferences,
            readerPreferences = ReaderPreferences(InMemoryPreferenceStore()),
            getMergedGroup = mockk { coEvery { await(any()) } returns null },
            getMergedChapters = mockk(),
        )
    }

    private fun listing(url: String, count: Int): List<ChapterInfo> = (count downTo 1).map {
        ChapterInfo(url = "$url/$it", name = "Chapter $it", chapterNumber = it.toFloat(), dateUpload = 1_000L * it)
    }

    @Test
    fun `a target migrated on one device arrives with every chapter, its Updates rows match, and a manual update there queues only the chapter new at the source`() {
        val a = Device("a")
        val b = Device("b")
        val (current, target) = runBlocking {
            val current = a.env.manga(OLD_SOURCE, "/old", "Same title") { it.copy(favorite = true, dateAdded = OLD_DATE_ADDED) }
            a.env.chapters(
                current.id,
                chapter("/old/1", 1.0, read = true, dateFetch = 111),
                chapter("/old/2", 2.0, read = true, bookmark = true, dateFetch = 222),
                chapter("/old/3", 3.0, dateFetch = 333),
            )
            current to a.env.manga(NEW_SOURCE, "/new", "Same title")
        }
        a.env.source(NEW_SOURCE, listing("/new", 100))
        a.env.source(OLD_SOURCE, emptyList())
        // The favourite clock is whole seconds and a tie keeps an entry in the library, so the old
        // entry's add is dated well before the migration that takes it out, as a real one is.
        a.env.driver.execute(null, "UPDATE mangas SET favorite_modified_at = $OLD_DATE_ADDED WHERE _id = ${current.id}", 0).value
        a.sync()
        b.sync()
        a.sync()

        runBlocking {
            a.env.migrateManga(current, target, replace = true, flags = setOf(MigrationFlag.CHAPTER, MigrationFlag.CATEGORY))
        }
        a.sync().segmentsPushed shouldBe 1
        b.sync().segmentsApplied shouldBe 1

        val targetOnA = a.entryId(NEW_SOURCE, "/new")
        val targetOnB = b.entryId(NEW_SOURCE, "/new")
        // Read before the update and asserted with what follows it, in one block (R29).
        val chaptersAfterPull = b.env.long("SELECT count(*) FROM chapters WHERE manga_id = $targetOnB")
        val favoriteAfterPull = b.env.long("SELECT favorite FROM mangas WHERE _id = $targetOnB")
        val updatesOnA = a.updatesOf(targetOnA)
        val updatesOnB = b.updatesOf(targetOnB)
        val digestsAfterPull = b.digest() to a.digest()
        val queuedByPull = b.queuedUrls()

        val outcome = b.update(NEW_SOURCE, listing("/new", 101))

        assertSoftly {
            chaptersAfterPull shouldBe 100L
            favoriteAfterPull shouldBe 1L
            // The three chapters of a matching number keep the old entry's earlier fetch dates and
            // are not listed; the other 97 are, on both devices.
            updatesOnA.size shouldBe 97
            updatesOnB shouldContainExactly updatesOnA
            digestsAfterPull.first shouldBe digestsAfterPull.second
            queuedByPull.shouldBeEmpty()
            outcome.failures.shouldBeEmpty()
            b.queuedUrls().size shouldBe 1
            b.queuedUrls() shouldContainExactly listOf("/new/101")
        }
    }

    /**
     * How sync sees the history a Migrate moves: the rows written on the target's chapters travel as
     * history upserts and arrive on the target there; the rows deleted from the old chapters do not
     * travel (a cleared history never does, Q6), so the other device keeps the old entry's history
     * until it is removed there.
     */
    @Test
    fun `the history a migration moves arrives on the target's chapters, and the other device keeps the old entry's`() {
        val a = Device("a")
        val b = Device("b")
        val (current, target) = runBlocking {
            val current = a.env.manga(OLD_SOURCE, "/old", "Same title") { it.copy(favorite = true, dateAdded = OLD_DATE_ADDED) }
            val chapters = a.env.chapters(current.id, chapter("/old/1", 1.0, read = true), chapter("/old/2", 2.0))
            a.env.execute("INSERT INTO history(chapter_id, last_read, time_read) VALUES (${chapters[1].id}, 2000, 60)")
            current to a.env.manga(NEW_SOURCE, "/new", "Same title")
        }
        a.env.source(NEW_SOURCE, listing("/new", 3))
        a.env.source(OLD_SOURCE, emptyList())
        a.env.driver.execute(null, "UPDATE mangas SET favorite_modified_at = $OLD_DATE_ADDED WHERE _id = ${current.id}", 0).value
        a.sync()
        b.sync()
        a.sync()
        val oldHistoryOnBBefore = b.history(OLD_SOURCE, "/old", "/old/2")

        runBlocking { a.env.migrateManga(current, target, replace = true, flags = setOf(MigrationFlag.CHAPTER)) }
        a.sync()
        b.sync()

        assertSoftly {
            oldHistoryOnBBefore shouldBe (2_000L to 60L)
            a.history(NEW_SOURCE, "/new", "/new/2") shouldBe (2_000L to 60L)
            a.history(OLD_SOURCE, "/old", "/old/2") shouldBe null
            b.history(NEW_SOURCE, "/new", "/new/2") shouldBe (2_000L to 60L)
            b.history(OLD_SOURCE, "/old", "/old/2") shouldBe (2_000L to 60L)
        }
    }

    /**
     * A source listing [chapters] for every entry. The environment's [MigrationTestEnvironment.FakeSource]
     * answers only what a migration asks, and a library update asks more.
     */
    private class ListingSource(override val id: Long, private val chapters: List<ChapterInfo>) : Source {
        override val name = "Fixture source $id"
        override val lang = "en"
        override suspend fun getMangaDetails(manga: MangaInfo) = manga.copy(initialized = true)
        override suspend fun getChapterList(manga: MangaInfo) = chapters
        override suspend fun getPageList(chapter: ChapterInfo): List<PageInfo> = error("not used")
        override suspend fun getImage(page: PageInfo): ImageResult = error("not used")
    }

    /** A registry that has loaded and holds [source] alone. */
    private class OneSourceManager(private val source: Source) : SourceManager {
        override val isInitialized: StateFlow<Boolean> = MutableStateFlow(true)
        override fun get(id: Long): Source? = source.takeIf { it.id == id }
        override fun getOrStub(id: Long): Source = get(id) ?: error("no other source here")
        override fun getCatalogueSources(): Flow<List<CatalogueSource>> = flowOf(emptyList())
        override fun getSources(): Flow<List<Source>> = flowOf(listOf(source))
        override fun getStubSources(): Flow<List<StubSource>> = emptyFlow()
    }

    /** The download queue's enqueues, in order; nothing is downloaded. */
    private class RecordingQueue : DownloadQueue {
        val queued = mutableListOf<Long>()
        override fun subscribe(): Flow<List<DownloadQueueEntry>> = emptyFlow()
        override val isRunning: StateFlow<Boolean> = MutableStateFlow(false)
        override val isPaused: StateFlow<Boolean> = MutableStateFlow(false)
        override suspend fun enqueue(manga: Manga, chapters: List<Chapter>, autoStart: Boolean) {
            queued += chapters.map { it.id }
        }
        override suspend fun start() = Unit
        override suspend fun remove(chapterIds: List<Long>) = error("not used")
        override suspend fun removeManga(mangaId: Long) = error("not used")
        override suspend fun reorder(orderedChapterIds: List<Long>) = error("not used")
        override suspend fun pause() = error("not used")
        override suspend fun clear() = error("not used")
    }

    /** The sync store both devices share, held in memory; every file is listed as soon as it is written. */
    private class MemoryBackend : SyncBackend {
        private val files = linkedMapOf<String, ByteArray>()
        override val id: String = "memory"
        override suspend fun probe() = BackendStatus(reachable = true, authenticated = true, message = null)
        override suspend fun list(prefix: String): List<RemoteFile> =
            files.filterKeys { it.startsWith(prefix) }.map { (path, bytes) -> RemoteFile(path, bytes.size.toLong(), 0L) }
        override suspend fun create(path: String, content: SyncContent) {
            files[path] = content.open().use { it.readBytes() }
        }
        override suspend fun <T> read(path: String, block: suspend (InputStream) -> T): T =
            ByteArrayInputStream(files[path] ?: throw FileNotFoundException(path)).use { block(it) }
        override suspend fun delete(path: String) {
            files.remove(path)
        }
    }

    private companion object {
        const val OLD_SOURCE = 7_001L
        const val NEW_SOURCE = 7_002L
        const val OLD_DATE_ADDED = 1_000L
    }
}

package com.shinsaikou.downloads

import android.content.Context
import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.core.metadata.comicinfo.ComicInfoXml
import com.shinsaikou.data.AndroidDatabaseHandler
import com.shinsaikou.data.DatabaseHandler
import com.shinsaikou.data.DatabaseTransactionRunner
import com.shinsaikou.data.download.DownloadManifestRepositoryImpl
import com.shinsaikou.data.download.DownloadQueueRepositoryImpl
import com.shinsaikou.domain.download.model.DownloadManifestEntry
import com.shinsaikou.domain.download.repository.DownloadManifestRepository
import com.shinsaikou.domain.preferences.DownloadPreferences
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceManager
import com.shinsaikou.source.StubSource
import com.shinsaikou.source.CatalogueSource
import com.shinsaikou.source.model.ChapterInfo
import com.shinsaikou.source.model.ImageResult
import com.shinsaikou.source.model.MangaInfo
import com.shinsaikou.source.model.PageInfo
import eu.kanade.tachiyomi.data.download.DownloadProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.track.TrackRepositoryImpl
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import java.io.File
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers as CoroutineDispatchers

/**
 * The engine against schema version 1 on the in-memory JVM driver, behind the real repositories
 * and the real transaction runner, with the files under a temporary directory and one fake source
 * that counts every request it serves.
 *
 * Every name here is synthetic; `CONTRIBUTING.md` binds test data too.
 */
abstract class DownloadTestBase {

    @TempDir
    lateinit var filesDir: File

    private lateinit var driver: JdbcSqliteDriver
    protected lateinit var handler: DatabaseHandler

    protected val preferenceStore = InMemoryPreferenceStore()
    protected val downloadPreferences = DownloadPreferences(preferenceStore)
    protected val libraryPreferences = LibraryPreferences(preferenceStore)

    /** One per test, as the graph has one: the engine and the adoption's copier share it. */
    protected val writeLock = DownloadWriteLock()

    protected val source = FakeSource(id = SOURCE_ID, pagesPerChapter = PAGES)
    protected val sourceManager = FakeSourceManager(source)

    protected val queueRepository by lazy { DownloadQueueRepositoryImpl(handler) }
    protected val realManifestRepository by lazy { DownloadManifestRepositoryImpl(handler) }
    protected val chapterRepository by lazy { ChapterRepositoryImpl(handler) }
    protected val mangaRepository by lazy { MangaRepositoryImpl(handler) }
    protected val categoryRepository by lazy { CategoryRepositoryImpl(handler) }
    protected val trackRepository by lazy { TrackRepositoryImpl(handler) }

    /** What the engine and the manifest see; a test may wrap the real repository to observe it. */
    protected open val manifestRepository: DownloadManifestRepository get() = realManifestRepository

    /** The free space the root reports: unknown, as the JVM's `StatFs` stub leaves it, unless a test sets it. */
    @Volatile
    protected var freeBytes: Long = -1

    protected val downloadRoot: DownloadRoot by lazy {
        val context = mockk<Context> { every { filesDir } returns this@DownloadTestBase.filesDir }
        val real = FilesDirDownloadRoot(context, libraryPreferences)
        object : DownloadRoot by real {
            override fun availableBytes(): Long = freeBytes
        }
    }

    @BeforeEach
    fun setUpDatabase() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), Database.Schema.synchronous())
        exec("PRAGMA foreign_keys = ON")
        val database = Database(
            driver = driver,
            chaptersAdapter = Chapters.Adapter(memoAdapter = MemoColumnAdapter),
            historyAdapter = History.Adapter(last_readAdapter = DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(
                genreAdapter = StringListColumnAdapter,
                update_strategyAdapter = UpdateStrategyColumnAdapter,
                memoAdapter = MemoColumnAdapter,
                genre_overrideAdapter = StringListColumnAdapter,
            ),
        )
        handler = AndroidDatabaseHandler(database, CoroutineDispatchers.Unconfined)
        downloadPreferences.parallelPageLimit.set(1)
    }

    @AfterEach
    fun tearDownDatabase() {
        driver.close()
    }

    protected fun dispatchers(dispatcher: CoroutineDispatcher) = Dispatchers(dispatcher, dispatcher, dispatcher)

    /** A new engine is a new worker: nothing but the database and the files carries over. */
    protected fun engine(dispatcher: CoroutineDispatcher) = DownloadEngine(
        queueRepository = queueRepository,
        manifestRepository = manifestRepository,
        transactionRunner = DatabaseTransactionRunner(handler),
        getManga = GetManga(mangaRepository),
        chapterRepository = chapterRepository,
        sourceManager = sourceManager,
        downloadRoot = downloadRoot,
        downloadPreferences = downloadPreferences,
        libraryPreferences = libraryPreferences,
        comicInfoWriter = comicInfoWriter(dispatcher),
        locator = locator(dispatcher),
        writeLock = writeLock,
        dispatchers = dispatchers(dispatcher),
    )

    protected fun comicInfoWriter(dispatcher: CoroutineDispatcher) =
        ChapterComicInfoWriter(categoryRepository, trackRepository, ComicInfoXml(), dispatchers(dispatcher))

    protected fun locator(dispatcher: CoroutineDispatcher) =
        DownloadLocator(downloadRoot, sourceManager, libraryPreferences, dispatchers(dispatcher))

    protected fun manifest(dispatcher: CoroutineDispatcher) =
        FolderDownloadManifest(manifestRepository, locator(dispatcher), dispatchers(dispatcher))

    protected fun exec(sql: String) {
        driver.execute(null, sql, 0).value
    }

    protected fun insertManga(id: Long, title: String = "Title $id", sourceId: Long = SOURCE_ID) = exec(
        """
        INSERT INTO mangas(_id, source, url, title, artist, author, description, genre, status, favorite,
            initialized, viewer, chapter_flags, cover_last_modified, date_added, memo)
        VALUES ($id, $sourceId, '/manga/$id', '$title', 'Artist $id', 'Author $id', 'About $id',
            'Genre A, Genre B', 1, 1, 1, 0, 0, 0, 0, CAST('{}' AS BLOB))
        """,
    )

    protected fun insertChapter(
        id: Long,
        mangaId: Long,
        name: String = "Chapter $id",
        url: String = "/ch/$id",
        scanlator: String? = null,
        number: Double = id.toDouble(),
    ) = exec(
        """
        INSERT INTO chapters(_id, manga_id, url, name, scanlator, read, bookmark, last_page_read,
            chapter_number, source_order, date_fetch, date_upload, memo)
        VALUES ($id, $mangaId, '$url', '$name', ${scanlator?.let { "'$it'" } ?: "NULL"},
            0, 0, 0, $number, 0, 0, 0, CAST('{}' AS BLOB))
        """,
    )

    protected suspend fun manga(id: Long): Manga = mangaRepository.getMangaById(id)

    protected suspend fun chapter(id: Long): Chapter = chapterRepository.getChapterById(id)!!

    protected suspend fun mangaDir(mangaId: Long): File =
        downloadRoot.mangaDir(source.name, manga(mangaId).title)

    protected suspend fun chapterDirName(chapterId: Long): String {
        val chapter = chapter(chapterId)
        return DownloadProvider.getChapterDirName(chapter.name, chapter.scanlator, chapter.url, false)
    }

    protected suspend fun finalDir(chapterId: Long): File =
        File(mangaDir(chapter(chapterId).mangaId), chapterDirName(chapterId))

    protected suspend fun tmpDir(chapterId: Long): File =
        File(mangaDir(chapter(chapterId).mangaId), chapterDirName(chapterId) + DownloadProvider.TMP_DIR_SUFFIX)

    protected fun manifestEntry(chapter: Chapter, manga: Manga, dirName: String) = DownloadManifestEntry(
        id = 0,
        chapterId = chapter.id,
        mangaId = manga.id,
        sourceId = manga.source,
        mangaTitle = manga.title,
        mangaUrl = manga.url,
        chapterUrl = chapter.url,
        chapterName = chapter.name,
        scanlator = chapter.scanlator,
        chapterDirName = dirName,
        pageCount = PAGES.toLong(),
        sizeBytes = 0,
        dateDownloaded = 0,
    )

    /**
     * Serves [pagesPerChapter] PNG pages for any chapter and counts every page-list and image
     * request, keyed by the page's image address. [onImage] runs before each image is served, with
     * the attempt number for that page, and may throw or suspend.
     */
    class FakeSource(override val id: Long, private val pagesPerChapter: Int) : Source {
        override val name = "Fixture Source"
        override val lang = "en"

        val pageListCalls = AtomicInteger()
        val imageCalls = ConcurrentHashMap<String, AtomicInteger>()

        @Volatile
        var onImage: suspend (page: PageInfo, attempt: Int) -> Unit = { _, _ -> }

        fun imageCallsFor(chapterUrl: String, index: Int): Int = imageCalls[imageUrl(chapterUrl, index)]?.get() ?: 0

        fun totalImageCalls(): Int = imageCalls.values.sumOf { it.get() }

        override suspend fun getMangaDetails(manga: MangaInfo): MangaInfo = manga

        override suspend fun getChapterList(manga: MangaInfo): List<ChapterInfo> = emptyList()

        override suspend fun getPageList(chapter: ChapterInfo): List<PageInfo> {
            pageListCalls.incrementAndGet()
            // Deliberately misindexed: the engine must number pages by list position.
            return List(pagesPerChapter) { PageInfo(index = 100 + it, imageUrl = imageUrl(chapter.url, it)) }
        }

        override suspend fun getImage(page: PageInfo): ImageResult {
            val attempt = imageCalls.getOrPut(page.imageUrl!!) { AtomicInteger() }.incrementAndGet()
            onImage(page, attempt)
            return ImageResult.ofBytes(pageBytes(page.imageUrl!!), "image/png", page.imageUrl)
        }

        override fun getChapterUrl(chapter: ChapterInfo): String = "https://example.invalid${chapter.url}"

        companion object {
            fun imageUrl(chapterUrl: String, index: Int) = "https://example.invalid$chapterUrl/$index.png"

            /** A PNG signature followed by the address, so every page's bytes are its own. */
            fun pageBytes(imageUrl: String): ByteArray =
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + imageUrl.toByteArray()
        }
    }

    /**
     * Serves [source] once [isInitialized] is true, and a stub for every id and no installed source
     * before that, as the app's registry does until its providers have loaded. Ready from the start
     * unless a test says otherwise.
     */
    class FakeSourceManager(private val source: Source) : SourceManager {
        override val isInitialized = MutableStateFlow(true)
        override fun get(id: Long): Source? = source.takeIf { isInitialized.value && it.id == id }
        override fun getOrStub(id: Long): Source = get(id) ?: StubSource.unknown(id)
        override fun getCatalogueSources(): Flow<List<CatalogueSource>> = flowOf(emptyList())
        override fun getSources(): Flow<List<Source>> = isInitialized.map { if (it) listOf(source) else emptyList() }
        override fun getStubSources(): Flow<List<StubSource>> = emptyFlow()
    }

    companion object {
        const val SOURCE_ID = 7L
        const val PAGES = 5
    }
}

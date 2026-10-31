package com.shinsaikou.reader.pipeline

import android.content.ContentResolver
import android.content.Context
import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.data.AndroidDatabaseHandler
import com.shinsaikou.data.download.DownloadManifestRepositoryImpl
import com.shinsaikou.domain.download.model.DownloadManifestEntry
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.ReaderPreferences
import com.shinsaikou.downloads.DownloadLocator
import com.shinsaikou.downloads.FilesDirDownloadRoot
import com.shinsaikou.downloads.LocatedDownloadedChapters
import com.shinsaikou.reader.cache.ChapterCache
import com.shinsaikou.reader.state.PreloadPolicy
import com.shinsaikou.source.CatalogueSource
import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceManager
import com.shinsaikou.source.SourceNotInstalledException
import com.shinsaikou.source.StubSource
import com.shinsaikou.source.model.ChapterInfo
import com.shinsaikou.source.model.ImageResult
import com.shinsaikou.source.model.MangaInfo
import com.shinsaikou.source.model.PageInfo
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.File
import java.io.FileNotFoundException
import java.util.Properties
import kotlinx.coroutines.Dispatchers as CoroutineDispatchers

/**
 * The resolver's downloaded branch against a real manifest row: the `:data` repository on the
 * in-memory JVM driver, `:downloads`' own [LocatedDownloadedChapters] and [DownloadLocator], and the
 * chapter's files under a temporary files directory laid out the way the engine lays them out.
 *
 * The case that matters is the first one. The entry's source is not installed, so every branch but
 * the downloaded one ends in [SourceNotInstalledException], and a page list read back from the
 * folder can only have come from disk. The second is phase 2 decision Q4: a row whose folder is gone
 * is not a download, and the chapter goes to its source like one that was never downloaded.
 *
 * Every name here is synthetic; `CONTRIBUTING.md` binds test data too.
 */
class PageLoaderResolverManifestTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var manifestRepository: DownloadManifestRepositoryImpl

    private val libraryPreferences = LibraryPreferences(InMemoryPreferenceStore())
    private val dispatchers = Dispatchers(
        io = CoroutineDispatchers.IO,
        default = CoroutineDispatchers.IO,
        main = CoroutineDispatchers.IO,
    )

    /** Opening an archive goes through the content resolver; the JVM has no libarchive behind it. */
    private val context = mockk<Context>(relaxed = true) {
        every { contentResolver } returns mockk<ContentResolver> {
            every { openFileDescriptor(any(), any()) } throws FileNotFoundException(ARCHIVE_OPENED)
        }
    }

    private val chapter = ReaderChapter(
        Chapter.create().copy(id = CHAPTER_ID, mangaId = MANGA_ID, url = "/chapter/1", name = CHAPTER_NAME),
    )

    @BeforeEach
    fun setUpDatabase() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), Database.Schema.synchronous())
        val database = Database(
            driver = driver,
            chaptersAdapter = Chapters.Adapter(memoAdapter = MemoColumnAdapter),
            historyAdapter = History.Adapter(last_readAdapter = DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(
                genreAdapter = StringListColumnAdapter,
                update_strategyAdapter = UpdateStrategyColumnAdapter,
                memoAdapter = MemoColumnAdapter,
            ),
        )
        val handler = AndroidDatabaseHandler(database, CoroutineDispatchers.Unconfined)
        manifestRepository = DownloadManifestRepositoryImpl(handler)
    }

    @AfterEach
    fun tearDownDatabase() {
        driver.close()
    }

    @Test
    fun `a chapter with a manifest row and its folder opens from disk while its source is not installed`() =
        runBlocking<Unit> {
            record(DIR_NAME)
            val folder = chapterFile(DIR_NAME).apply { mkdirs() }
            listOf("002.png", "001.png", "003.png").forEach { File(folder, it).writeBytes(page(it)) }
            File(folder, "ComicInfo.xml").writeText("<ComicInfo/>")

            val loader = resolver(installed = null).resolve(manga(), chapter, StubSource.unknown(SOURCE_ID)) {
                PreloadPolicy.DEFAULT
            }

            loader.shouldBeInstanceOf<DownloadedPageLoader>()
            val pages = loader.getPages()
            val bytes = pages.map { page -> checkNotNull(page.stream).invoke().use { it.readBytes().toList() } }
            bytes shouldContainExactly listOf("001.png", "002.png", "003.png").map { page(it).toList() }
            pages.map { it.status } shouldContainExactly List(3) { PageState.Ready }
        }

    @Test
    fun `a manifest row whose folder is missing falls through to the source`() = runBlocking<Unit> {
        record(DIR_NAME)

        val online = resolver(installed = source).resolve(manga(), chapter, source) { PreloadPolicy.DEFAULT }
        try {
            online.shouldBeInstanceOf<SourcePageLoader>()
        } finally {
            online.recycle()
        }

        // With nothing on disk and nothing installed, the chapter cannot open, and says why.
        val thrown = shouldThrow<SourceNotInstalledException> {
            resolver(installed = null).resolve(manga(), chapter, StubSource.unknown(SOURCE_ID)) {
                PreloadPolicy.DEFAULT
            }
        }
        thrown.sourceId shouldBe SOURCE_ID
    }

    /** The manifest decides, not a directory walk: a folder no row names is not a download. */
    @Test
    fun `a folder with no manifest row is not a download`() = runBlocking<Unit> {
        chapterFile(DIR_NAME).mkdirs()

        shouldThrow<SourceNotInstalledException> {
            resolver(installed = null).resolve(manga(), chapter, StubSource.unknown(SOURCE_ID)) {
                PreloadPolicy.DEFAULT
            }
        }
    }

    /** An adopted chapter can be a single archive; it is opened as one rather than listed as a folder. */
    @Test
    fun `a manifest row naming an archive opens the archive`() = runBlocking<Unit> {
        record(ARCHIVE_NAME)
        chapterFile(ARCHIVE_NAME).apply { parentFile.mkdirs() }.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04))

        val thrown = shouldThrow<FileNotFoundException> {
            resolver(installed = null).resolve(manga(), chapter, StubSource.unknown(SOURCE_ID)) {
                PreloadPolicy.DEFAULT
            }
        }
        thrown.message shouldBe ARCHIVE_OPENED
    }

    private val source: Source = object : Source {
        override val id = SOURCE_ID
        override val name = SOURCE_NAME
        override val lang = "en"
        override suspend fun getMangaDetails(manga: MangaInfo) = manga
        override suspend fun getChapterList(manga: MangaInfo) = emptyList<ChapterInfo>()
        override suspend fun getPageList(chapter: ChapterInfo) = emptyList<PageInfo>()
        override suspend fun getImage(page: PageInfo) = ImageResult.ofBytes(ByteArray(0), null)
    }

    private fun resolver(installed: Source?): PageLoaderResolver {
        val appContext = mockk<Context> { every { filesDir } returns this@PageLoaderResolverManifestTest.filesDir }
        val root = FilesDirDownloadRoot(appContext, libraryPreferences)
        val locator = DownloadLocator(root, SingleSourceManager(installed), libraryPreferences, dispatchers)
        return PageLoaderResolver(
            downloadedChapters = LocatedDownloadedChapters(manifestRepository, locator),
            localChapters = mockk(),
            chapterCache = mockk<ChapterCache>(relaxed = true),
            probe = mockk(relaxed = true),
            splitter = mockk(relaxed = true),
            readerPreferences = ReaderPreferences(InMemoryPreferenceStore()),
            dispatchers = dispatchers,
            context = context,
        )
    }

    /** Where the engine puts a chapter of [MANGA_TITLE] from [SOURCE_NAME]. */
    private fun chapterFile(name: String): File {
        val root = File(filesDir, FilesDirDownloadRoot.DOWNLOADS_DIR)
        val sourceDir = File(root, DownloadProvider.getSourceDirName(SOURCE_NAME, false))
        return File(File(sourceDir, DownloadProvider.getMangaDirName(MANGA_TITLE, false)), name)
    }

    private suspend fun record(dirName: String) = manifestRepository.recordDownload(
        DownloadManifestEntry(
            id = 0,
            chapterId = CHAPTER_ID,
            mangaId = MANGA_ID,
            sourceId = SOURCE_ID,
            mangaTitle = MANGA_TITLE,
            mangaUrl = "/manga/1",
            chapterUrl = chapter.chapter.url,
            chapterName = CHAPTER_NAME,
            scanlator = null,
            chapterDirName = dirName,
            pageCount = 3,
            sizeBytes = 0,
            dateDownloaded = 0,
        ),
    )

    private fun manga() =
        Manga.create().copy(id = MANGA_ID, source = SOURCE_ID, title = MANGA_TITLE, url = "/manga/1")

    private fun page(name: String): ByteArray =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + name.toByteArray()

    /** One source, or none: the engine's folder layout is found either way (`DownloadLocator`). */
    private class SingleSourceManager(private val source: Source?) : SourceManager {
        override val isInitialized: StateFlow<Boolean> = MutableStateFlow(true)
        override fun get(id: Long): Source? = source?.takeIf { it.id == id }
        override fun getOrStub(id: Long): Source = get(id) ?: StubSource.unknown(id)
        override fun getCatalogueSources(): Flow<List<CatalogueSource>> = flowOf(emptyList())
        override fun getSources(): Flow<List<Source>> = flowOf(listOfNotNull(source))
        override fun getStubSources(): Flow<List<StubSource>> = emptyFlow()
    }

    private companion object {
        const val SOURCE_ID = 7L
        const val SOURCE_NAME = "Fixture Source"
        const val MANGA_ID = 1L
        const val MANGA_TITLE = "Fixture Manga"
        const val CHAPTER_ID = 5L
        const val CHAPTER_NAME = "Chapter 1"
        const val DIR_NAME = "Chapter 1"
        const val ARCHIVE_NAME = "Chapter 1.cbz"
        const val ARCHIVE_OPENED = "the resolver opened the archive"
    }
}

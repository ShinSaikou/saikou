package com.shinsaikou.reader.pipeline

import android.content.Context
import com.hippo.unifile.UniFile
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.download.DownloadedChapters
import com.shinsaikou.domain.preferences.ReaderPreferences
import com.shinsaikou.reader.cache.ChapterCache
import com.shinsaikou.reader.state.PreloadPolicy
import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceIds
import com.shinsaikou.source.SourceNotInstalledException
import com.shinsaikou.source.StubSource
import com.shinsaikou.source.model.ChapterInfo
import com.shinsaikou.source.model.ImageResult
import com.shinsaikou.source.model.MangaInfo
import com.shinsaikou.source.model.PageInfo
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import com.shinsaikou.source.model.UpdateStrategy
import java.io.File
import kotlinx.coroutines.Dispatchers as CoroutineDispatchers

/**
 * The four-step resolution order of contract 07 section 4.1, with fakes for all five collaborators.
 * The downloaded branch against a real manifest row and real folders is
 * [PageLoaderResolverManifestTest].
 */
class PageLoaderResolverTest {

    /** A downloaded chapter's folder: the first branch opens it, so it has to exist. */
    @TempDir
    lateinit var downloadDir: File

    private val downloadedChapters = mockk<DownloadedChapters>()
    private val localChapters = mockk<LocalChapterResolver>()
    private val chapterCache = mockk<ChapterCache>(relaxed = true)
    private val splitter = mockk<TallStripSplitter>(relaxed = true)

    private val chapter = ReaderChapter(
        Chapter.create().copy(id = 5, mangaId = 1, url = "/c", name = "c"),
    )

    // ---- Step 1 ----

    @Test
    fun `a downloaded chapter gets the downloaded loader`() = runTest {
        coEvery { downloadedChapters.find(5) } returns downloadDir

        val loader = resolver().resolve(manga(source = 42), chapter, source(42)) { PreloadPolicy.DEFAULT }

        loader.shouldBeInstanceOf<DownloadedPageLoader>()
        loader.isLocal shouldBe true
    }

    @Test
    fun `a download wins over a local chapter`() = runTest {
        coEvery { downloadedChapters.find(5) } returns downloadDir
        val loader = resolver().resolve(
            manga(source = SourceIds.LOCAL),
            chapter,
            source(SourceIds.LOCAL),
        ) { PreloadPolicy.DEFAULT }
        loader.shouldBeInstanceOf<DownloadedPageLoader>()
    }

    // ---- Step 2 ----

    @Test
    fun `a local directory chapter gets the directory loader`() = runTest {
        coEvery { downloadedChapters.find(5) } returns null
        val manga = manga(source = SourceIds.LOCAL)
        coEvery { localChapters.resolve(manga, chapter.chapter) } returns
            LocalChapterFormat.Directory(mockk<UniFile>())

        val loader = resolver().resolve(manga, chapter, source(SourceIds.LOCAL)) { PreloadPolicy.DEFAULT }

        loader.shouldBeInstanceOf<DirectoryPageLoader>()
        loader.isLocal shouldBe true
    }

    @Test
    fun `a local chapter the local source cannot find is a programmer error`() = runTest {
        coEvery { downloadedChapters.find(5) } returns null
        val manga = manga(source = SourceIds.LOCAL)
        coEvery { localChapters.resolve(manga, chapter.chapter) } returns null

        shouldThrow<IllegalStateException> {
            resolver().resolve(manga, chapter, source(SourceIds.LOCAL)) { PreloadPolicy.DEFAULT }
        }
    }

    // ---- Step 3 ----

    @Test
    fun `a source that is not installed is reported as such`() = runTest {
        coEvery { downloadedChapters.find(5) } returns null
        val stub = StubSource.unknown(99)

        val thrown = shouldThrow<SourceNotInstalledException> {
            resolver().resolve(manga(source = 99), chapter, stub) { PreloadPolicy.DEFAULT }
        }
        thrown.sourceId shouldBe 99L
    }

    // ---- Step 4 ----

    @Test
    fun `everything else gets the online loader`() = runTest {
        coEvery { downloadedChapters.find(5) } returns null

        val loader = resolver().resolve(manga(source = 42), chapter, source(42)) { PreloadPolicy.DEFAULT }

        loader.shouldBeInstanceOf<SourcePageLoader>()
        loader.isLocal shouldBe false
        loader.isRecycled shouldBe false

        // The online loader parks a thread in its queue until it is recycled, exactly as upstream's does.
        loader.recycle()
        loader.isRecycled shouldBe true
    }

    private fun resolver() = PageLoaderResolver(
        downloadedChapters = downloadedChapters,
        localChapters = localChapters,
        chapterCache = chapterCache,
        probe = mockk(relaxed = true),
        splitter = splitter,
        readerPreferences = ReaderPreferences(InMemoryPreferenceStore()),
        // A real IO dispatcher, not a test one: SourcePageLoader's worker blocks in
        // PriorityBlockingQueue.take() inside runInterruptible, so an inline dispatcher would
        // block the test thread rather than a pool thread.
        dispatchers = Dispatchers(
            io = CoroutineDispatchers.IO,
            default = CoroutineDispatchers.IO,
            main = CoroutineDispatchers.IO,
        ),
        context = mockk<Context>(relaxed = true).also { every { it.applicationContext } returns it },
    )

    private fun source(id: Long): Source = object : Source {
        override val id = id
        override val name = "s"
        override val lang = "en"
        override suspend fun getMangaDetails(manga: MangaInfo) = manga
        override suspend fun getChapterList(manga: MangaInfo) = emptyList<ChapterInfo>()
        override suspend fun getPageList(chapter: ChapterInfo) = emptyList<PageInfo>()
        override suspend fun getImage(page: PageInfo) = ImageResult.ofBytes(ByteArray(0), null)
    }

    private fun manga(source: Long) = Manga(
        id = 1,
        source = source,
        favorite = true,
        lastUpdate = 0,
        nextUpdate = 0,
        fetchInterval = 0,
        dateAdded = 0,
        viewerFlags = 0,
        chapterFlags = 0,
        coverLastModified = 0,
        url = "/m",
        title = "t",
        artist = null,
        author = null,
        description = null,
        genre = null,
        status = 0,
        thumbnailUrl = null,
        updateStrategy = UpdateStrategy.ALWAYS_UPDATE,
        initialized = true,
        lastModifiedAt = 0,
        favoriteModifiedAt = null,
        version = 1,
        notes = "",
        memo = JsonObject(emptyMap()),
        manualPosition = 0,
        deletedAt = null,
        titleOverride = null,
        descriptionOverride = null,
    )
}

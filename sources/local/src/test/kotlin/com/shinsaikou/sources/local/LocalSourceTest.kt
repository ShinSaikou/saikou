package com.shinsaikou.sources.local

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import com.hippo.unifile.UniFile
import com.shinsaikou.core.metadata.comicinfo.ComicInfoXml
import com.shinsaikou.domain.preferences.StoragePreferences
import com.shinsaikou.domain.storage.StorageLocation
import com.shinsaikou.i18n.R
import com.shinsaikou.source.model.ChapterInfo
import com.shinsaikou.source.model.Filter
import com.shinsaikou.source.model.FilterList
import com.shinsaikou.source.model.MangaInfo
import com.shinsaikou.source.model.MangaStatus
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import tachiyomi.core.archive.ArchiveEntry
import tachiyomi.core.archive.ArchiveReader
import tachiyomi.core.archive.EpubReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE
import tachiyomi.core.metadata.comicinfo.ComicInfo
import tachiyomi.source.local.filter.OrderBy
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.io.LocalSourceFileSystem
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The Local source over a real temporary directory. Archives are stubbed through [ArchiveOpener]:
 * the real reader mmaps through `android.system.Os`, which no JVM test can do.
 */
class LocalSourceTest {

    @TempDir
    lateinit var externalFilesDir: File

    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var opener: RecordingArchiveOpener
    private lateinit var source: LocalSource
    private lateinit var comicInfoXml: ComicInfoXml

    @BeforeEach
    fun setUp() {
        // Only these two platform statics are reached, both from the .nomedia write and from
        // turning a cover file into a thumbnail url.
        mockkStatic(Uri::class)
        every { Uri.fromFile(any()) } answers { mockk(relaxed = true) }
        mockkStatic(MediaScannerConnection::class)
        every { MediaScannerConnection.scanFile(any(), any(), any(), any()) } just runs

        root = File(externalFilesDir, "local").apply { mkdirs() }

        context = mockk()
        every { context.getExternalFilesDir(null) } returns externalFilesDir
        every { context.getString(R.string.local_source) } returns "Local source"
        every { context.getString(R.string.local_filter_order_by) } returns "Order by"
        every { context.getString(R.string.title) } returns "Title"
        every { context.getString(R.string.date) } returns "Date"

        val fileSystem = LocalSourceFileSystem(
            context,
            LocalSourcePreferences(InMemoryPreferenceStore()),
            StorageLocation(StoragePreferences(InMemoryPreferenceStore())) { null },
        )
        comicInfoXml = ComicInfoXml()
        opener = RecordingArchiveOpener()
        source = LocalSource(
            context = context,
            fileSystem = fileSystem,
            coverManager = LocalCoverManager(context, fileSystem),
            archiveOpener = opener,
            json = Json,
            comicInfoXml = comicInfoXml,
        )
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    // region listing

    @Test
    fun `the listing keeps one entry per folder and drops hidden ones`() = runTest {
        mangaDir("Fixture Manga A")
        mangaDir("Fixture Manga B")
        mangaDir(".hidden")
        File(root, "loose.txt").writeText("not a manga")

        val page = source.getPopularManga(1)

        page.mangas.map { it.title } shouldBe listOf("Fixture Manga A", "Fixture Manga B")
        page.mangas.map { it.url } shouldBe listOf("Fixture Manga A", "Fixture Manga B")
        page.hasNextPage shouldBe false
    }

    @Test
    fun `a search matches the folder name, case insensitively`() = runTest {
        mangaDir("Fixture Manga A")
        mangaDir("Another Fixture")

        val page = source.getSearchManga(1, "fixture manga", FilterList(OrderBy.popular(context)))

        page.mangas.map { it.title } shouldBe listOf("Fixture Manga A")
    }

    @Test
    fun `the latest listing keeps only folders touched inside the threshold`() = runTest {
        val fresh = mangaDir("Fixture Manga A")
        val stale = mangaDir("Fixture Manga B")
        stale.setLastModified(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
        fresh.setLastModified(System.currentTimeMillis())

        val page = source.getLatestUpdates(1)

        page.mangas.map { it.title } shouldBe listOf("Fixture Manga A")
    }

    @Test
    fun `the sort direction of the filter decides the order`() = runTest {
        mangaDir("Fixture Manga A")
        mangaDir("Fixture Manga B")

        val descending = FilterList(
            Filter.Sort(
                name = "Order by",
                values = listOf("Title", "Date"),
                state = Filter.Sort.Selection(OrderBy.INDEX_TITLE, ascending = false),
            ),
        )
        val page = source.getSearchManga(1, "", descending)

        page.mangas.map { it.title } shouldBe listOf("Fixture Manga B", "Fixture Manga A")
    }

    // endregion

    // region chapters

    @Test
    fun `chapters come from folders and archives, newest name first`() = runTest {
        val manga = mangaDir("Fixture Manga A")
        chapterDir(manga, "Chapter 1", "01.png", "02.png")
        File(manga, "Chapter 2.cbz").writeText("stored")
        File(manga, "Chapter 10.cbz").writeText("stored")
        File(manga, ".nomedia").writeText("")

        val chapters = source.getChapterList(info("Fixture Manga A"))

        chapters.map { it.name } shouldBe listOf("Chapter 10", "Chapter 2", "Chapter 1")
        chapters.map { it.url } shouldBe listOf(
            "Fixture Manga A/Chapter 10.cbz",
            "Fixture Manga A/Chapter 2.cbz",
            "Fixture Manga A/Chapter 1",
        )
        chapters.map { it.chapterNumber } shouldBe listOf(10f, 2f, 1f)
    }

    @Test
    fun `a chapter level ComicInfo names and numbers the chapter`() = runTest {
        val manga = mangaDir("Fixture Manga A")
        File(manga, "Chapter 1.cbz").writeText("stored")
        opener.entries["Chapter 1.cbz"] = mapOf(
            COMIC_INFO_FILE to comicInfo(title = "The Opening", number = "1.5", translator = "Group A"),
        )

        val chapters = source.getChapterList(info("Fixture Manga A"))

        chapters.single().name shouldBe "The Opening"
        chapters.single().chapterNumber shouldBe 1.5f
        chapters.single().scanlator shouldBe "Group A"
    }

    // endregion

    // region details

    @Test
    fun `a top level ComicInfo fills the details and wins over the legacy json`() = runTest {
        val manga = mangaDir("Fixture Manga A")
        File(manga, COMIC_INFO_FILE).writeBytes(
            comicInfo(series = "From XML", writer = "Writer A", status = "Completed"),
        )
        File(manga, "details.json").writeText("""{"title":"From JSON","author":"Writer B"}""")

        val details = source.getMangaDetails(info("Fixture Manga A"))

        details.title shouldBe "From XML"
        details.author shouldBe "Writer A"
        details.status shouldBe MangaStatus.COMPLETED
        details.initialized shouldBe true
        File(manga, "details.json").exists() shouldBe true
    }

    @Test
    fun `a legacy json is applied, written back as ComicInfo and deleted`() = runTest {
        val manga = mangaDir("Fixture Manga A")
        File(manga, "details.json").writeText(
            """{"title":"From JSON","author":"Writer B","genre":["Action","Drama"],"status":2}""",
        )

        val details = source.getMangaDetails(info("Fixture Manga A"))

        details.title shouldBe "From JSON"
        details.author shouldBe "Writer B"
        details.genre shouldBe "Action, Drama"
        details.status shouldBe MangaStatus.COMPLETED
        File(manga, "details.json").exists() shouldBe false

        val written = File(manga, COMIC_INFO_FILE)
        written.exists() shouldBe true
        val decoded = written.inputStream().use(comicInfoXml::decode)
        decoded.series?.value shouldBe "From JSON"
        decoded.publishingStatus?.value shouldBe "Completed"
    }

    @Test
    fun `a ComicInfo found inside a chapter is copied to the manga folder`() = runTest {
        val manga = mangaDir("Fixture Manga A")
        File(manga, "Chapter 1.cbz").writeText("stored")
        opener.entries["Chapter 1.cbz"] = mapOf(COMIC_INFO_FILE to comicInfo(series = "From The Archive"))

        val details = source.getMangaDetails(info("Fixture Manga A"))

        details.title shouldBe "From The Archive"
        File(manga, COMIC_INFO_FILE).exists() shouldBe true
        File(manga, LocalSource.NO_XML_FILE).exists() shouldBe false
    }

    @Test
    fun `a folder with no metadata gains a noxml marker and is not searched twice`() = runTest {
        val manga = mangaDir("Fixture Manga A")
        File(manga, "Chapter 1.cbz").writeText("stored")
        // One image, so the first pass also writes the cover and the second pass needs nothing.
        opener.entries["Chapter 1.cbz"] = mapOf("01.png" to PNG_HEADER)

        source.getMangaDetails(info("Fixture Manga A"))

        File(manga, LocalSource.NO_XML_FILE).exists() shouldBe true
        File(manga, "cover.jpg").exists() shouldBe true
        val afterFirstPass = opener.archiveReads

        source.getMangaDetails(info("Fixture Manga A"))

        opener.archiveReads shouldBe afterFirstPass
    }

    // endregion

    // region pages and images

    @Test
    fun `page urls name the manga folder, the chapter entry and the image`() = runTest {
        val manga = mangaDir("Fixture Manga A")
        chapterDir(manga, "Chapter 1", "02.png", "10.png", "01.png")

        val pages = source.getPageList(chapter("Fixture Manga A/Chapter 1"))

        pages.map { it.url } shouldBe listOf(
            "Fixture Manga A/Chapter 1/01.png",
            "Fixture Manga A/Chapter 1/02.png",
            "Fixture Manga A/Chapter 1/10.png",
        )
        pages.map { it.index } shouldBe listOf(0, 1, 2)
    }

    @Test
    fun `pages of an archive come from its entries in natural order`() = runTest {
        val manga = mangaDir("Fixture Manga A")
        File(manga, "Chapter 1.cbz").writeText("stored")
        opener.entries["Chapter 1.cbz"] = mapOf(
            "10.png" to ByteArray(1),
            "01.png" to ByteArray(1),
            COMIC_INFO_FILE to ByteArray(1),
        )

        val pages = source.getPageList(chapter("Fixture Manga A/Chapter 1.cbz"))

        pages.map { it.url } shouldBe listOf(
            "Fixture Manga A/Chapter 1.cbz/01.png",
            "Fixture Manga A/Chapter 1.cbz/10.png",
        )
    }

    @Test
    fun `getImage on a directory page returns that file's bytes`() = runTest {
        val manga = mangaDir("Fixture Manga A")
        val chapterDirectory = chapterDir(manga, "Chapter 1", "01.png")
        File(chapterDirectory, "01.png").writeBytes(PNG_HEADER + byteArrayOf(1, 2, 3))

        val pages = source.getPageList(chapter("Fixture Manga A/Chapter 1"))
        val bytes = source.getImage(pages.single()).use { it.bytes() }

        bytes shouldBe PNG_HEADER + byteArrayOf(1, 2, 3)
    }

    // endregion

    // region covers

    @Test
    fun `a cover file is found whatever its case and extension`() = runTest {
        val manga = mangaDir("Fixture Manga A")
        File(manga, "Cover.PNG").writeBytes(PNG_HEADER)

        val page = source.getPopularManga(1)

        page.mangas.single().thumbnailUrl.shouldNotBeNull()
    }

    @Test
    fun `a missing cover is written from the first image of the first chapter`() = runTest {
        val manga = mangaDir("Fixture Manga A")
        val chapterDirectory = chapterDir(manga, "Chapter 1", "02.png", "01.png")
        File(chapterDirectory, "01.png").writeBytes(PNG_HEADER + byteArrayOf(7))

        source.getChapterList(info("Fixture Manga A"))

        val cover = File(manga, "cover.jpg")
        cover.exists() shouldBe true
        cover.readBytes() shouldBe PNG_HEADER + byteArrayOf(7)
        File(manga, ".nomedia").exists() shouldBe true
    }

    @Test
    fun `an empty root lists nothing`() = runTest {
        source.getPopularManga(1).mangas.shouldBeEmpty()
    }

    @Test
    fun `a manga folder with no chapters has no chapters`() = runTest {
        mangaDir("Fixture Manga A")

        source.getChapterList(info("Fixture Manga A")) shouldHaveSize 0
    }

    // endregion

    // region helpers

    private fun mangaDir(name: String): File = File(root, name).apply { mkdirs() }

    private fun chapterDir(mangaDir: File, name: String, vararg images: String): File =
        File(mangaDir, name).apply {
            mkdirs()
            images.forEach { File(this, it).writeBytes(PNG_HEADER) }
        }

    private fun info(url: String) = MangaInfo(url = url, title = url)

    private fun chapter(url: String) = ChapterInfo(url = url, name = url.substringAfterLast('/'))

    private fun comicInfo(
        series: String? = null,
        title: String? = null,
        number: String? = null,
        writer: String? = null,
        translator: String? = null,
        status: String? = null,
    ): ByteArray {
        val info = ComicInfo(
            title = title?.let { ComicInfo.Title(it) },
            series = series?.let { ComicInfo.Series(it) },
            number = number?.let { ComicInfo.Number(it) },
            summary = null,
            writer = writer?.let { ComicInfo.Writer(it) },
            penciller = null,
            inker = null,
            colorist = null,
            letterer = null,
            coverArtist = null,
            translator = translator?.let { ComicInfo.Translator(it) },
            genre = null,
            tags = null,
            web = null,
            publishingStatus = status?.let { ComicInfo.PublishingStatusTachiyomi(it) },
            categories = null,
            source = null,
        )
        return ByteArrayOutputStream().also { comicInfoXml.encode(info, it) }.toByteArray()
    }

    /** Serves archive entries out of a map, and counts how often an archive was opened. */
    private class RecordingArchiveOpener : ArchiveOpener {
        val entries = mutableMapOf<String, Map<String, ByteArray>>()
        var archiveReads = 0
            private set

        override fun <T> readArchive(file: UniFile, block: (ArchiveReader) -> T): T {
            archiveReads++
            val content = entries[file.name.orEmpty()].orEmpty()
            val reader = mockk<ArchiveReader>()
            every { reader.getInputStream(any()) } answers { content[firstArg<String>()]?.inputStream() }
            every { reader.useEntries<Any>(any()) } answers {
                val visit = firstArg<(Sequence<ArchiveEntry>) -> Any>()
                visit(content.keys.asSequence().map { ArchiveEntry(it, isFile = true) })
            }
            return block(reader)
        }

        override fun <T> readEpub(file: UniFile, block: (EpubReader) -> T): T =
            error("No epub fixture needs the reader")
    }

    private companion object {
        val PNG_HEADER = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
    }

    // endregion
}

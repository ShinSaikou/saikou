package com.shinsaikou.sources.local

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shinsaikou.SaikouApp
import com.shinsaikou.di.AppGraph
import com.shinsaikou.source.model.ChapterInfo
import com.shinsaikou.source.model.FilterList
import com.shinsaikou.source.model.MangaInfo
import com.shinsaikou.source.model.MangaStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import logcat.logcat
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The Local source on a device, through the application's own graph, over folders this test writes.
 *
 * `LocalSourceTest` on the JVM stubs the archive opener, because the real one mmaps through
 * `android.system.Os` and reads with native libarchive. So until this class nothing ran the archive
 * and epub halves of the source against the real opener, and nothing called [LocalSource.getPageList]
 * or [LocalSource.getImage] at all: the reader reads local pages through `:reader`'s own loaders.
 * `ReaderSmokeTest` lists a plain chapter folder and stops there.
 *
 * Every metadata source the Local source reads has a test here, and each test asserts the mark its
 * branch leaves, so a branch that stopped being reached fails by name rather than falling through
 * to the listing's own title:
 *
 * - [aTopLevelComicInfoIsTheMetadataAndAChapterFolderCarriesItsOwn]: a manga folder's
 *   `ComicInfo.xml` wins over a legacy JSON file beside it and removes a stale `.noxml` marker; a
 *   chapter folder's own `ComicInfo.xml` names the chapter.
 * - [anArchiveLendsTheFolderItsComicInfo]: a folder with no metadata of its own takes the
 *   `ComicInfo.xml` inside a chapter archive and keeps a copy; the archive's file names the chapter.
 * - [aLegacyJsonFileIsMigratedToComicInfo]: the JSON file is applied, written out as a
 *   `ComicInfo.xml` and deleted, and the next read goes through the migrated file.
 * - [anEpubNamesItsChapterAndAFolderWithNothingIsMarked]: an epub's package document names its
 *   chapter; a folder with no metadata anywhere gains `.noxml`, and the next read leaves it as is.
 *
 * Each also lists the pages of its chapter with [LocalSource.getPageList] and reads them back with
 * [LocalSource.getImage], so the directory, archive and epub forms each serve their bytes.
 *
 * The fixtures are written by the test process, for the reason `ReaderSmokeTest` gives: the source
 * cannot write its cover or its marker into a directory `adb push` created. Everything in them is
 * synthetic. So nothing here waits on an argument and nothing returns early: every test asserts on
 * every device, under strict mode or not.
 */
@RunWith(AndroidJUnit4::class)
class LocalSourceDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val graph: AppGraph get() = (context.applicationContext as SaikouApp).graph

    /** Every manga folder a test wrote, removed in [removeTheFixtures]. */
    private val written = mutableListOf<File>()

    /**
     * The source manager fills its registry from the providers asynchronously after the graph is
     * built, and until then answers every id, the Local source's included, with a stub. The first
     * test of a fresh process met exactly that, so each waits for the registry, bounded.
     */
    @Before
    fun awaitTheSourceRegistry() = runBlocking<Unit> {
        withTimeoutOrNull(REGISTRY_TIMEOUT_MILLIS) { graph.sourceManager.isInitialized.first { it } }
            ?: throw AssertionError(
                "the source manager never published its sources (after $REGISTRY_TIMEOUT_MILLIS ms)",
            )
    }

    @After
    fun removeTheFixtures() {
        written.forEach { it.deleteRecursively() }
    }

    @Test
    fun aTopLevelComicInfoIsTheMetadataAndAChapterFolderCarriesItsOwn() = runBlocking<Unit> {
        val folder = mangaFolder()
        File(folder, COMIC_INFO).writeText(
            comicInfo(
                "<Series>$SERIES_TOP</Series>",
                "<Summary>$SUMMARY</Summary>",
                "<Writer>$WRITER</Writer>",
                "<Penciller>$ARTIST</Penciller>",
                "<Genre>$GENRE</Genre>",
                STATUS_ONGOING,
            ),
        )
        // Both lose to the ComicInfo.xml beside them: the JSON is never read and the marker goes.
        val legacy = File(folder, LEGACY_JSON).apply { writeText(legacyJson(SERIES_LEGACY)) }
        val marker = File(folder, LocalSource.NO_XML_FILE).apply { writeText("") }

        val chapterFolder = File(folder, "Chapter 1").apply { mkdirs() }
        val pages = listOf(page(0xFF303030), page(0xFF505050))
        pages.forEachIndexed { index, bytes -> File(chapterFolder, "%03d.png".format(index + 1)).writeBytes(bytes) }
        File(chapterFolder, COMIC_INFO).writeText(
            comicInfo(
                "<Title>$CHAPTER_TITLE</Title>",
                "<Number>7</Number>",
                "<Translator>$GROUP</Translator>",
            ),
        )

        val details = source().getMangaDetails(listed(folder))
        assertEquals("the title is not the folder's ComicInfo.xml", SERIES_TOP, details.title)
        assertEquals(SUMMARY, details.description)
        assertEquals(WRITER, details.author)
        assertEquals(ARTIST, details.artist)
        assertEquals(GENRE, details.genre)
        assertEquals(MangaStatus.ONGOING, details.status)
        assertFalse("the .noxml marker survived a folder that has a ComicInfo.xml", marker.exists())
        assertTrue("the legacy JSON file was touched although ComicInfo.xml takes precedence", legacy.exists())

        val chapter = source().getChapterList(details).single()
        assertEquals("the chapter folder's ComicInfo.xml did not name the chapter", CHAPTER_TITLE, chapter.name)
        assertEquals(7f, chapter.chapterNumber, 0f)
        assertEquals(GROUP, chapter.scanlator)

        // The ComicInfo.xml in the folder is sniffed and left out; only the two images are pages.
        assertPagesServe(chapter, pages, listOf("001.png", "002.png"))
    }

    @Test
    fun anArchiveLendsTheFolderItsComicInfo() = runBlocking<Unit> {
        val folder = mangaFolder()
        val pages = listOf(page(0xFF602020), page(0xFF206020))
        zip(
            File(folder, "Chapter 2.cbz"),
            // Out of order on purpose: the page list is sorted by name, not taken in entry order.
            "002.png" to pages[1],
            COMIC_INFO to comicInfo(
                "<Series>$SERIES_ARCHIVE</Series>",
                "<Writer>$WRITER</Writer>",
                "<Title>$ARCHIVE_CHAPTER_TITLE</Title>",
                "<Number>2</Number>",
            ).toByteArray(),
            "001.png" to pages[0],
        )

        val details = source().getMangaDetails(listed(folder))
        assertEquals("the title is not the archive's ComicInfo.xml", SERIES_ARCHIVE, details.title)
        assertEquals(WRITER, details.author)
        assertTrue(
            "the archive's ComicInfo.xml was not copied into the manga folder",
            File(folder, COMIC_INFO).isFile,
        )
        assertFalse(
            "a folder that found metadata was marked as having none",
            File(folder, LocalSource.NO_XML_FILE).exists(),
        )

        val chapter = source().getChapterList(details).single()
        assertEquals("the archive's ComicInfo.xml did not name the chapter", ARCHIVE_CHAPTER_TITLE, chapter.name)
        assertEquals(2f, chapter.chapterNumber, 0f)

        assertPagesServe(chapter, pages, listOf("001.png", "002.png"))
    }

    @Test
    fun aLegacyJsonFileIsMigratedToComicInfo() = runBlocking<Unit> {
        val folder = mangaFolder()
        val legacy = File(folder, LEGACY_JSON).apply { writeText(legacyJson(SERIES_LEGACY)) }
        val chapterFolder = File(folder, "Chapter 3").apply { mkdirs() }
        val pages = listOf(page(0xFF202060))
        File(chapterFolder, "001.png").writeBytes(pages[0])

        val listing = listed(folder)
        val details = source().getMangaDetails(listing)
        assertLegacyDetails(details)
        assertFalse("the legacy JSON file was not removed after its migration", legacy.exists())
        assertTrue("the legacy JSON file was not written out as ComicInfo.xml", File(folder, COMIC_INFO).isFile)

        // The second read has no JSON left, so the same details can only come from the migrated file.
        assertLegacyDetails(source().getMangaDetails(listing))

        // A chapter folder with no ComicInfo.xml of its own keeps its folder name and parsed number.
        val chapter = source().getChapterList(details).single()
        assertEquals("Chapter 3", chapter.name)
        assertEquals(3f, chapter.chapterNumber, 0f)

        assertPagesServe(chapter, pages, listOf("001.png"))
    }

    @Test
    fun anEpubNamesItsChapterAndAFolderWithNothingIsMarked() = runBlocking<Unit> {
        val folder = mangaFolder()
        val first = page(0xFF206060)
        val second = page(0xFF606020)
        // The spine reads page-b before page-a, so the first page is b.png although a.png sorts
        // first by name and comes first in the archive.
        zip(
            File(folder, "Chapter 4.epub"),
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to CONTAINER_XML.toByteArray(),
            "OEBPS/content.opf" to PACKAGE_DOCUMENT.toByteArray(),
            "OEBPS/pages/page-a.xhtml" to xhtml("../images/a.png").toByteArray(),
            "OEBPS/pages/page-b.xhtml" to xhtml("../images/b.png").toByteArray(),
            "OEBPS/images/a.png" to second,
            "OEBPS/images/b.png" to first,
        )

        val listing = listed(folder)
        val details = source().getMangaDetails(listing)
        assertEquals("a folder with no metadata changed its title", listing.title, details.title)
        val marker = File(folder, LocalSource.NO_XML_FILE)
        assertTrue("a folder with no metadata anywhere was not marked", marker.isFile)
        val markedAt = marker.lastModified()

        // Marked, so the second read neither searches the chapters again nor rewrites the marker.
        assertEquals(listing.title, source().getMangaDetails(listing).title)
        assertTrue("the marker went away on the second read", marker.isFile)
        assertEquals("the marker was written again on the second read", markedAt, marker.lastModified())
        assertFalse("a ComicInfo.xml appeared from nowhere", File(folder, COMIC_INFO).exists())

        val chapter = source().getChapterList(details).single()
        assertEquals("the epub's dc:title did not name the chapter", EPUB_TITLE, chapter.name)
        assertEquals("the epub's dc:publisher is not the scanlator", EPUB_PUBLISHER, chapter.scanlator)
        assertEquals("the epub's dc:date is not the upload date", EPUB_DATE_MILLIS, chapter.dateUpload)

        assertPagesServe(chapter, listOf(first, second), listOf("OEBPS/images/b.png", "OEBPS/images/a.png"))
    }

    /**
     * The chapter's pages, in order, by the entry names [entries] gives, and each page's bytes as
     * [getImage][LocalSource.getImage] serves them. Compared byte for byte, because a page served
     * from the wrong entry is still a valid image.
     */
    private suspend fun assertPagesServe(chapter: ChapterInfo, expected: List<ByteArray>, entries: List<String>) {
        val pages = source().getPageList(chapter)
        assertEquals(
            "the page list of ${chapter.url} is not its images in reading order",
            entries.map { "${chapter.url}/$it" },
            pages.map { it.url },
        )
        assertEquals((0 until entries.size).toList(), pages.map { it.index })
        pages.forEachIndexed { index, page ->
            val served = source().getImage(page).use { it.bytes() }
            assertArrayEquals("page ${index + 1} of ${chapter.url} served other bytes", expected[index], served)
        }
        logcat(LOG_TAG, LogPriority.INFO) { "${chapter.url}: ${pages.size} page(s) listed and served" }
    }

    private fun assertLegacyDetails(details: MangaInfo) {
        assertEquals("the title is not the legacy JSON file's", SERIES_LEGACY, details.title)
        assertEquals(WRITER, details.author)
        assertEquals(ARTIST, details.artist)
        assertEquals(SUMMARY, details.description)
        assertEquals(GENRE, details.genre)
        assertEquals(MangaStatus.COMPLETED, details.status)
    }

    /**
     * The Local source as the update pass reaches it, through the source manager, and asserted to be
     * the one instance the graph holds, so a second copy wired somewhere else cannot stand in.
     */
    private fun source(): LocalSource {
        val resolved = graph.sourceManager.getOrStub(LocalSource.ID)
        assertSame(
            "the source manager does not resolve the Local source to the graph's instance",
            graph.localSource,
            resolved,
        )
        return graph.localSource
    }

    /**
     * A new manga folder under the directory the source falls back to when no tree URI is set,
     * named for this run so no earlier run's leftovers can be listed in its place.
     */
    private fun mangaFolder(): File {
        val root = File(checkNotNull(context.getExternalFilesDir(null)) { "no external files directory" }, LOCAL_ROOT)
        val folder = File(root, "$FIXTURE_PREFIX${System.nanoTime()}")
        assertTrue("could not create $folder", folder.mkdirs())
        written += folder
        return folder
    }

    /** The folder as the source lists it, which is also the check that its root is the app's own. */
    private suspend fun listed(folder: File): MangaInfo {
        val listing = source().getSearchManga(1, folder.name, FilterList.EMPTY)
        val info = listing.mangas.firstOrNull { it.url == folder.name }
        assertNotNull("the Local source did not list ${folder.name}; its root is not the app's own directory", info)
        return info!!
    }

    /** A real PNG in one colour: the source sniffs magic bytes, and each page must differ. */
    private fun page(color: Long): ByteArray {
        val bitmap = Bitmap.createBitmap(PAGE_WIDTH, PAGE_HEIGHT, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(color.toInt())
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun zip(target: File, vararg entries: Pair<String, ByteArray>) {
        ZipOutputStream(target.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    /**
     * A `ComicInfo.xml` as another tool writes one, by hand rather than through the application's
     * own encoder, so what is read is not only what Saikou would have written.
     */
    private fun comicInfo(vararg elements: String): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<ComicInfo xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\" " +
            "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n" +
            elements.joinToString("\n") + "\n</ComicInfo>\n"

    private fun legacyJson(title: String): String =
        "{\"title\":\"$title\",\"author\":\"$WRITER\",\"artist\":\"$ARTIST\"," +
            "\"description\":\"$SUMMARY\",\"genre\":[\"Synthetic Genre A\",\"Synthetic Genre B\"],\"status\":2}"

    private fun xhtml(image: String): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>page</title></head>" +
            "<body><img src=\"$image\" alt=\"\"/></body></html>\n"

    private companion object {
        const val LOG_TAG = "SaikouLocalSourceDevice"

        /** `LocalSourceFileSystem`'s default directory name under the app's external files. */
        const val LOCAL_ROOT = "local"

        const val FIXTURE_PREFIX = "saikou-local-source-"
        const val COMIC_INFO = "ComicInfo.xml"
        const val LEGACY_JSON = "details.json"

        const val SERIES_TOP = "Synthetic Series From ComicInfo"
        const val SERIES_ARCHIVE = "Synthetic Series From An Archive"
        const val SERIES_LEGACY = "Synthetic Series From Legacy JSON"
        const val SUMMARY = "A synthetic summary."
        const val WRITER = "Synthetic Writer"
        const val ARTIST = "Synthetic Artist"
        const val GENRE = "Synthetic Genre A, Synthetic Genre B"
        const val STATUS_ONGOING =
            "<ty:PublishingStatusTachiyomi xmlns:ty=\"http://www.w3.org/2001/XMLSchema\">Ongoing" +
                "</ty:PublishingStatusTachiyomi>"
        const val CHAPTER_TITLE = "Synthetic Chapter Title"
        const val ARCHIVE_CHAPTER_TITLE = "Synthetic Archive Chapter"
        const val GROUP = "Synthetic Group"

        const val EPUB_TITLE = "Synthetic Epub Chapter"
        const val EPUB_PUBLISHER = "Synthetic Publisher"

        /** 2024-01-02T03:04:05Z, the `dc:date` below, in the format the epub reader parses. */
        const val EPUB_DATE_MILLIS = 1_704_164_645_000L

        const val CONTAINER_XML =
            "<?xml version=\"1.0\"?>\n" +
                "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">" +
                "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" " +
                "media-type=\"application/oebps-package+xml\"/></rootfiles></container>\n"

        const val PACKAGE_DOCUMENT =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"id\">" +
                "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
                "<dc:identifier id=\"id\">synthetic-epub</dc:identifier>" +
                "<dc:title>$EPUB_TITLE</dc:title>" +
                "<dc:publisher>$EPUB_PUBLISHER</dc:publisher>" +
                "<dc:date>2024-01-02T03:04:05+0000</dc:date>" +
                "</metadata>" +
                "<manifest>" +
                "<item id=\"page-a\" href=\"pages/page-a.xhtml\" media-type=\"application/xhtml+xml\"/>" +
                "<item id=\"page-b\" href=\"pages/page-b.xhtml\" media-type=\"application/xhtml+xml\"/>" +
                "<item id=\"image-a\" href=\"images/a.png\" media-type=\"image/png\"/>" +
                "<item id=\"image-b\" href=\"images/b.png\" media-type=\"image/png\"/>" +
                "</manifest>" +
                "<spine><itemref idref=\"page-b\"/><itemref idref=\"page-a\"/></spine>" +
                "</package>\n"

        /** The providers publishing their first lists on a process the test runner just started. */
        const val REGISTRY_TIMEOUT_MILLIS = 30_000L

        const val PAGE_WIDTH = 48
        const val PAGE_HEIGHT = 64
    }
}

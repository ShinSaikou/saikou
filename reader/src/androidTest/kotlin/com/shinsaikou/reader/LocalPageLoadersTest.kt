package com.shinsaikou.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.preferences.ReaderPreferences
import com.shinsaikou.reader.cache.ChapterCache
import com.shinsaikou.reader.memory.AndroidDisplayInfoProvider
import com.shinsaikou.reader.pipeline.ArchivePageLoader
import com.shinsaikou.reader.pipeline.DirectoryPageLoader
import com.shinsaikou.reader.pipeline.EpubPageLoader
import com.shinsaikou.reader.pipeline.ImageProbe
import com.shinsaikou.reader.pipeline.PageLoader
import com.shinsaikou.reader.pipeline.PageState
import com.shinsaikou.reader.pipeline.PlatformBoundsReader
import com.shinsaikou.reader.pipeline.TallStripSplitter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import tachiyomi.core.archive.archiveReader
import tachiyomi.core.archive.epubReader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers as CoroutineDispatchers

/**
 * Instrumentation, not a unit test. Contract 07 part 2 section 9.2.
 *
 * Two of the three loaders read their chapter through [tachiyomi.core.archive.ArchiveReader], which
 * mmaps the file with `android.system.Os` and walks it with the native libarchive binding, and
 * every fixture page here is a real JPEG produced by `Bitmap.compress`. The mmap, the binding and
 * the encoder are all platform code with no unit test JVM equivalent, and `BitmapFactory` is how
 * the assertions tell one page from another.
 *
 * Every fixture is generated in the test, so nothing here skips: there is no owner-supplied input.
 */
@RunWith(AndroidJUnit4::class)
class LocalPageLoadersTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * The house shape for the reader's own tests, and enough here: the loaders hop to
     * `dispatchers.io` for the listing and the test awaits the hop through `runTest`.
     */
    private val dispatchers = Dispatchers(
        io = CoroutineDispatchers.IO,
        default = CoroutineDispatchers.IO,
        main = CoroutineDispatchers.IO,
    )

    /**
     * Real collaborators rather than stand-ins, because none of them is reached by the call under
     * test and all four are cheap to build: `getPages` only lists entries, while the probe, the
     * splitter, the cache and the preferences are consulted on the first `loadPage` of a page. The
     * cache in particular opens its `DiskLruCache` lazily, so constructing one touches no disk.
     */
    private val cache = ChapterCache(context, Json, dispatchers)
    private val probe = ImageProbe()
    private val splitter = TallStripSplitter(cache, PlatformBoundsReader(), AndroidDisplayInfoProvider(context))
    private val readerPreferences = ReaderPreferences(InMemoryPreferenceStore())

    private val loaders = mutableListOf<PageLoader>()
    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = File(context.cacheDir, "local-page-loaders-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        // The archive loaders hold an mmap over their fixture, so they are closed before it goes.
        loaders.forEach(PageLoader::recycle)
        loaders.clear()
        directory.deleteRecursively()
    }

    @Test
    fun directoryChapterListsItsImagesInNaturalOrder() = runTest {
        val chapterDir = File(directory, "chapter").apply { mkdirs() }
        PAGE_NUMBERS.forEach { number -> File(chapterDir, "$number.jpg").writeBytes(jpeg(number)) }
        File(chapterDir, "notes.txt").writeText(NON_IMAGE)
        File(chapterDir, "thumbnails").mkdirs()

        val loader = DirectoryPageLoader(
            dir = UniFile.fromFile(chapterDir)!!,
            chapterId = CHAPTER_ID,
            probe = probe,
            splitter = splitter,
            cache = cache,
            readerPreferences = readerPreferences,
            dispatchers = dispatchers,
        ).also { loaders += it }

        assertChapter(loader, loader.getPages())
    }

    @Test
    fun archiveChapterListsItsImagesInNaturalOrder() = runTest {
        val cbz = File(directory, "chapter.cbz")
        ZipOutputStream(cbz.outputStream()).use { zip ->
            ENTRY_ORDER.forEach { number -> zip.writeEntry("$number.jpg", jpeg(number)) }
            zip.writeEntry("ComicInfo.xml", NON_IMAGE.toByteArray())
        }

        val loader = ArchivePageLoader(
            reader = UniFile.fromFile(cbz)!!.archiveReader(context),
            chapterId = CHAPTER_ID,
            probe = probe,
            splitter = splitter,
            cache = cache,
            readerPreferences = readerPreferences,
            dispatchers = dispatchers,
        ).also { loaders += it }

        assertChapter(loader, loader.getPages())
    }

    @Test
    fun epubChapterListsItsImagesInSpineOrder() = runTest {
        val epub = File(directory, "chapter.epub")
        writeEpub(epub)

        val loader = EpubPageLoader(
            reader = UniFile.fromFile(epub)!!.epubReader(context),
            chapterId = CHAPTER_ID,
            probe = probe,
            splitter = splitter,
            cache = cache,
            readerPreferences = readerPreferences,
            dispatchers = dispatchers,
        ).also { loaders += it }

        assertChapter(loader, loader.getPages())
    }

    // ---- Assertions --------------------------------------------------------------------------

    /**
     * A [ReaderPage] keeps no entry name, so the only thing left to identify a page by is its
     * bytes: each fixture image is encoded at a width equal to the page number it was written
     * under, and decoding the bounds of every page's stream recovers the order the loader chose.
     */
    private fun assertChapter(loader: PageLoader, pages: List<ReaderPage>) {
        assertTrue("local chapters are local", loader.isLocal)
        assertEquals(PAGE_NUMBERS.indices.toList(), pages.map { it.index })
        assertEquals(List(pages.size) { PageState.Ready }, pages.map { it.status })
        assertEquals(PAGE_NUMBERS, pages.map { widthOf(it) })
    }

    private fun widthOf(page: ReaderPage): Int {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        page.stream!!.invoke().use { BitmapFactory.decodeStream(it, null, options) }
        return options.outWidth
    }

    // ---- Fixtures ----------------------------------------------------------------------------

    private fun jpeg(width: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, PAGE_HEIGHT, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream()
        try {
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bytes)
        } finally {
            bitmap.recycle()
        }
        return bytes.toByteArray()
    }

    private fun writeEpub(target: File) {
        ZipOutputStream(target.outputStream()).use { zip ->
            val mimetype = EPUB_MIMETYPE.toByteArray()
            // First and stored is the one structural rule an epub adds to a zip. The loader would
            // read the fixture either way, so it is built the way a real epub is rather than the
            // least this loader would accept.
            zip.putNextEntry(
                ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED
                    size = mimetype.size.toLong()
                    compressedSize = mimetype.size.toLong()
                    crc = CRC32().apply { update(mimetype) }.value
                },
            )
            zip.write(mimetype)
            zip.closeEntry()

            zip.writeEntry("META-INF/container.xml", CONTAINER_XML.toByteArray())
            zip.writeEntry("OEBPS/content.opf", packageDocument().toByteArray())
            // Stored in the reverse of the spine, so a loader that walked the archive rather than
            // reading the package document would come back with the pages backwards.
            ENTRY_ORDER.forEach { number ->
                zip.writeEntry("OEBPS/images/$number.jpg", jpeg(number))
                zip.writeEntry("OEBPS/text/$number.xhtml", pageDocument(number).toByteArray())
            }
        }
    }

    /** The manifest is in entry order and the spine in reading order, and the spine has to win. */
    private fun packageDocument(): String = buildString {
        append("""<?xml version="1.0"?><package version="3.0"><manifest>""")
        ENTRY_ORDER.forEach {
            append("""<item id="page$it" href="text/$it.xhtml" media-type="application/xhtml+xml"/>""")
        }
        append("</manifest><spine>")
        PAGE_NUMBERS.forEach { append("""<itemref idref="page$it"/>""") }
        append("</spine></package>")
    }

    private fun pageDocument(number: Int): String =
        """<html><body><img src="../images/$number.jpg"/></body></html>"""

    private fun ZipOutputStream.writeEntry(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    private companion object {
        /**
         * 1, 2, 10 is the case where natural order and lexicographic order disagree: sorted by
         * name the pages would read 1, 10, 2. This is also the expected page order of all three
         * chapters, and each number is the width its page's JPEG is encoded at.
         */
        val PAGE_NUMBERS = listOf(1, 2, 10)

        /** The order entries are written in: neither natural nor lexicographic, so sorting shows. */
        val ENTRY_ORDER = listOf(10, 1, 2)

        const val PAGE_HEIGHT = 16
        const val JPEG_QUALITY = 90
        const val CHAPTER_ID = 7L

        /** Long enough that the format sniffer reads a full header and still finds no image. */
        const val NON_IMAGE = "<ComicInfo><Title>a generated fixture, not an image</Title></ComicInfo>"

        const val EPUB_MIMETYPE = "application/epub+zip"

        const val CONTAINER_XML = """<?xml version="1.0"?><container version="1.0"><rootfiles>""" +
            """<rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>""" +
            """</rootfiles></container>"""
    }
}

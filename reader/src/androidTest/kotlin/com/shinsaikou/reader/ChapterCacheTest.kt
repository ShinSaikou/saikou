package com.shinsaikou.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jakewharton.disklrucache.DiskLruCache
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.reader.cache.ChapterCache
import com.shinsaikou.reader.cache.DiskKeys
import com.shinsaikou.source.model.PageInfo
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.chapter.model.Chapter
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers as CoroutineDispatchers

/**
 * Instrumentation, not a unit test. [ChapterCache] is a thin layer over `DiskLruCache`, and every
 * clause below is a fact about files rather than a return value: a committed entry is a temporary
 * file renamed over its `.0` name, a half-written one is that temporary file still sitting there,
 * eviction unlinks the least recently used entry, and a second open replays a journal the first
 * open wrote. The unit test JVM also has no `android.text.format.Formatter` and no
 * `android.util.Log`, both of which sit on paths exercised here.
 *
 * Nothing in this class skips on a missing fixture, because the only fixtures are byte arrays the
 * test writes itself.
 */
@RunWith(AndroidJUnit4::class)
class ChapterCacheTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** [ChapterCache] opens this path itself, so a test can only wipe it, never choose it. */
    private lateinit var cacheDirectory: File

    /**
     * A second directory for the raw `DiskLruCache` assertions. [ChapterCache.MAX_SIZE_BYTES] is
     * fixed at 160 MiB and is not a constructor parameter, so eviction and an explicit close are
     * only reachable against a cache the test opens with the same version and value count but a
     * limit measured in kibibytes.
     */
    private lateinit var scratchDirectory: File

    @Before
    fun setUp() {
        cacheDirectory = File(context.cacheDir, ChapterCache.DIRECTORY).apply {
            deleteRecursively()
            mkdirs()
        }
        scratchDirectory = File(context.cacheDir, SCRATCH_DIRECTORY).apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        cacheDirectory.deleteRecursively()
        scratchDirectory.deleteRecursively()
    }

    // ---- Whole pages ------------------------------------------------------------------------

    @Test
    fun putsAWholePageAndReadsTheSameBytesBack() {
        val cache = newCache()
        assertFalse(cache.isImageInCache(IMAGE_KEY))

        cache.putImageToCache(IMAGE_KEY, Buffer().write(PAGE_BYTES))

        assertTrue(cache.isImageInCache(IMAGE_KEY))
        val file = cache.getImageFile(IMAGE_KEY)
        // The flat layout the loader depends on: the image view streams this File directly and
        // never goes back through the cache object.
        assertEquals(File(cacheDirectory, DiskKeys.image(IMAGE_KEY) + ENTRY_SUFFIX), file)
        assertArrayEquals(PAGE_BYTES, file.readBytes())
    }

    @Test
    fun reportsAPageWhoseFileWentMissingAsAbsent() {
        val cache = newCache()
        cache.putImageToCache(IMAGE_KEY, Buffer().write(PAGE_BYTES))

        assertTrue(cache.getImageFile(IMAGE_KEY).delete())

        // A page can lose its file without the journal knowing, and the loader's reset to Queue
        // fires off this answer, which is why the check is the file and not only the journal.
        assertFalse(cache.isImageInCache(IMAGE_KEY))
    }

    // ---- Parts of a split page --------------------------------------------------------------

    @Test
    fun putsGetsAndRemovesTheBandsOfASplitPage() {
        val cache = newCache()
        cache.putImageToCache(IMAGE_KEY, Buffer().write(PAGE_BYTES))
        assertEquals(0, cache.partsInCache(IMAGE_KEY))

        repeat(3) { index -> cache.putPartToCache(IMAGE_KEY, index) { it.write(bandBytes(index)) } }

        assertEquals(3, cache.partsInCache(IMAGE_KEY))
        val band = cache.getPartFile(IMAGE_KEY, 1)
        assertEquals(File(cacheDirectory, DiskKeys.part(IMAGE_KEY, 1) + ENTRY_SUFFIX), band)
        assertArrayEquals(bandBytes(1), band.readBytes())

        cache.removeParts(IMAGE_KEY)

        // The parent is keyed without the part infix, so removing the bands must leave it alone.
        assertEquals(0, cache.partsInCache(IMAGE_KEY))
        assertTrue(cache.isImageInCache(IMAGE_KEY))
    }

    @Test
    fun countsOnlyTheRunOfBandsPresentFromZero() {
        val cache = newCache()
        cache.putPartToCache(IMAGE_KEY, 0) { it.write(bandBytes(0)) }
        cache.putPartToCache(IMAGE_KEY, 2) { it.write(bandBytes(2)) }

        // Band 1 is the hole an eviction opens. The run stops there even though band 2 is present.
        assertEquals(1, cache.partsInCache(IMAGE_KEY))

        cache.removeParts(IMAGE_KEY)

        // removeParts scans the directory rather than walking indices, so the band stranded past
        // the hole goes too.
        assertFalse(File(cacheDirectory, DiskKeys.part(IMAGE_KEY, 2) + ENTRY_SUFFIX).exists())
    }

    @Test
    fun aWriteThatFailsPartWayLeavesNoEntryBehind() {
        val cache = newCache()

        assertThrows(IOException::class.java) {
            cache.putPartToCache(IMAGE_KEY, 0) { stream ->
                stream.write(bandBytes(0))
                throw IOException("the body died mid band")
            }
        }

        // writeEntry aborts in a finally, and an abort unlinks the temporary file the editor was
        // filling, so a failed band is a re-split and never a truncated file the decoder trusts.
        assertEquals(0, cache.partsInCache(IMAGE_KEY))
        val leftovers = cacheDirectory.listFiles().orEmpty()
            .filter { it.name.startsWith(DiskKeys.image(IMAGE_KEY)) }
        assertEquals(emptyList<File>(), leftovers)
    }

    @Test
    fun aHalfWrittenEntryLeavesATemporaryFileAndDoesNotReadBackAsPresent() {
        val key = DiskKeys.image("$IMAGE_KEY#interrupted")
        openScratchCache().use { cache ->
            val editor = cache.edit(key)!!
            editor.newOutputStream(VALUE_INDEX).use { it.write(PAGE_BYTES) }

            // What a process killed mid-download leaves behind: the editor never committed, so
            // the bytes are still under the temporary name and the key is not readable.
            assertTrue(File(scratchDirectory, key + ENTRY_SUFFIX + ".tmp").exists())
            assertFalse(File(scratchDirectory, key + ENTRY_SUFFIX).exists())
            assertNull(cache.get(key))

            editor.abortUnlessCommitted()

            assertFalse(File(scratchDirectory, key + ENTRY_SUFFIX + ".tmp").exists())
        }
    }

    // ---- The page list ----------------------------------------------------------------------

    @Test
    fun roundTripsThePageListThroughJson() {
        val cache = newCache()
        val chapter = chapter()
        assertNull(cache.getPageListFromCache(chapter))

        val pages = listOf(
            PageInfo(index = 0, url = "$CHAPTER_URL/p/0", imageUrl = "$CHAPTER_URL/i/0.png"),
            PageInfo(index = 1, url = "$CHAPTER_URL/p/1", imageUrl = null),
            PageInfo(index = 2),
        )
        cache.putPageListToCache(chapter, pages)

        // PageInfo's equality covers exactly the three fields the payload carries; the progress
        // hook is per instance and is not meant to survive a cache round trip.
        assertEquals(pages, cache.getPageListFromCache(chapter))

        val payload = File(cacheDirectory, DiskKeys.pageList(chapter.mangaId, chapter.url) + ENTRY_SUFFIX)
        assertTrue(payload.readText().startsWith("["))
    }

    @Test
    fun returnsNullForAPageListThatNoLongerDecodes() {
        val cache = newCache()
        val chapter = chapter()
        cache.putPageListToCache(chapter, listOf(PageInfo(index = 0)))

        File(cacheDirectory, DiskKeys.pageList(chapter.mangaId, chapter.url) + ENTRY_SUFFIX)
            .writeText("not json")

        // A payload that no longer decodes is a slower chapter open, not a crash: the null sends
        // the loader back to the source.
        assertNull(cache.getPageListFromCache(chapter))
    }

    // ---- Eviction and reopening -------------------------------------------------------------

    @Test
    fun evictsTheLeastRecentlyUsedEntryPastTheSizeLimit() {
        openScratchCache().use { cache ->
            repeat(ENTRIES_AT_LIMIT) { index -> cache.putEntry(pageKey(index), entryBytes(index)) }
            assertEquals(LIMIT_BYTES, cache.size())

            // A read is a use. Without this refresh the victim below would be entry 0, which is
            // the defect getImageFile's diskCache.get exists to avoid.
            cache.get(pageKey(0))!!.close()
            cache.putEntry(pageKey(ENTRIES_AT_LIMIT), entryBytes(ENTRIES_AT_LIMIT))

            // The trim after a commit runs on the cache's cleanup thread; flush trims under the
            // lock, which is what makes the assertions below deterministic rather than timed.
            cache.flush()

            assertNull(cache.get(pageKey(1)))
            assertFalse(File(scratchDirectory, pageKey(1) + ENTRY_SUFFIX).exists())
            cache.get(pageKey(0)).use { assertNotNull(it) }
            assertEquals(LIMIT_BYTES, cache.size())
        }
    }

    @Test
    fun aSecondOpenOfTheSameDirectorySeesWhatSurvived() {
        openScratchCache().use { cache ->
            repeat(ENTRIES_AT_LIMIT) { index -> cache.putEntry(pageKey(index), entryBytes(index)) }
            cache.get(pageKey(0))!!.close()
            cache.putEntry(pageKey(ENTRIES_AT_LIMIT), entryBytes(ENTRIES_AT_LIMIT))
            cache.flush()
        }

        // The journal is the only thing carrying entries across the close, and replaying it is
        // what the next cold start of the process does.
        openScratchCache().use { reopened ->
            assertEquals(LIMIT_BYTES, reopened.size())
            assertNull(reopened.get(pageKey(1)))
            listOf(0, 2, 3, ENTRIES_AT_LIMIT).forEach { index ->
                reopened.get(pageKey(index))!!.use { snapshot ->
                    val bytes = snapshot.getInputStream(VALUE_INDEX).readBytes()
                    assertArrayEquals(entryBytes(index), bytes)
                }
            }
        }
    }

    @Test
    fun aSecondChapterCacheOnTheSameDirectoryReadsWhatTheFirstWrote() {
        val chapter = chapter()
        val pages = listOf(PageInfo(index = 0, url = "$CHAPTER_URL/p/0", imageUrl = null))
        newCache().apply {
            putImageToCache(IMAGE_KEY, Buffer().write(PAGE_BYTES))
            putPageListToCache(chapter, pages)
        }

        // Every commit flushes the journal, so a second instance over the same directory (the
        // app's next cold start) finds both entries without the first having closed anything.
        val reopened = newCache()

        assertTrue(reopened.isImageInCache(IMAGE_KEY))
        assertArrayEquals(PAGE_BYTES, reopened.getImageFile(IMAGE_KEY).readBytes())
        assertEquals(pages, reopened.getPageListFromCache(chapter))
    }

    @Test
    fun clearRemovesEveryEntryAndLeavesTheJournal() = runTest {
        val cache = newCache()
        cache.putImageToCache(IMAGE_KEY, Buffer().write(PAGE_BYTES))
        cache.putPartToCache(IMAGE_KEY, 0) { it.write(bandBytes(0)) }

        // Formatter.formatFileSize is the platform call the Data and storage screen shows, and it
        // is one of the reasons this class cannot run on the JVM.
        assertTrue(cache.readableSize().isNotEmpty())

        assertEquals(2, cache.clear())

        assertFalse(cache.isImageInCache(IMAGE_KEY))
        assertEquals(0, cache.partsInCache(IMAGE_KEY))
        assertTrue(File(cacheDirectory, "journal").exists())
    }

    // ---- Helpers ----------------------------------------------------------------------------

    private fun newCache() = ChapterCache(context, Json, dispatchers())

    /** Only `io` is ever reached: it is where the two suspend members hop. */
    private fun dispatchers() = Dispatchers(
        io = CoroutineDispatchers.IO,
        default = CoroutineDispatchers.Default,
        main = CoroutineDispatchers.Main,
    )

    private fun chapter() = Chapter.create().copy(id = 9, mangaId = 42, url = CHAPTER_URL)

    private fun openScratchCache(): DiskLruCache = DiskLruCache.open(
        scratchDirectory,
        ChapterCache.APP_VERSION,
        ChapterCache.VALUE_COUNT,
        LIMIT_BYTES,
    )

    /** `ChapterCache.writeEntry`'s sequence, against a limit small enough to overrun. */
    private fun DiskLruCache.putEntry(key: String, bytes: ByteArray) {
        val editor = edit(key) ?: throw IOException("cache entry busy")
        try {
            editor.newOutputStream(VALUE_INDEX).use { it.write(bytes) }
            flush()
            editor.commit()
        } finally {
            editor.abortUnlessCommitted()
        }
    }

    /** The scratch cache is keyed through [DiskKeys] too, so its keys stay legal ones. */
    private fun pageKey(index: Int) = DiskKeys.image("chapter-2-page-$index")

    private fun entryBytes(index: Int) = ByteArray(ENTRY_BYTES) { (index + 1).toByte() }

    private fun bandBytes(index: Int) = ByteArray(64) { (index + 1).toByte() }

    private companion object {
        const val SCRATCH_DIRECTORY = "chapter-cache-scratch"

        /** The loader's key shape, which is an image url or a page url plus its index. */
        const val IMAGE_KEY = "chapter-1-page-0"

        const val CHAPTER_URL = "/c/1"

        /** The only value index, because [ChapterCache.VALUE_COUNT] is 1. */
        const val VALUE_INDEX = 0

        /** An entry's file is its key plus its value index. */
        const val ENTRY_SUFFIX = ".$VALUE_INDEX"

        const val ENTRY_BYTES = 8 * 1024

        /** Four of those fill the scratch cache exactly, so the fifth write has to evict one. */
        const val ENTRIES_AT_LIMIT = 4

        val LIMIT_BYTES = ENTRIES_AT_LIMIT.toLong() * ENTRY_BYTES

        val PAGE_BYTES = ByteArray(1024) { it.toByte() }
    }
}

package com.shinsaikou.reader

import android.content.Context
import android.graphics.Bitmap
import com.shinsaikou.di.AppGraph
import com.shinsaikou.domain.manga.model.ReadingMode
import com.shinsaikou.domain.manga.model.withReadingMode
import com.shinsaikou.domain.source.toDomainChapter
import com.shinsaikou.domain.source.toDomainManga
import com.shinsaikou.domain.source.toMangaInfo
import com.shinsaikou.source.model.FilterList
import com.shinsaikou.sources.local.LocalSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.MangaUpdate
import java.io.File

/**
 * A Local source entry the reader can open, written by the test process and inserted through the
 * application's own graph. Shared by `ReaderSmokeTest`, `ReaderModeSwitchTest` and
 * `ReaderProcessDeathTest`.
 *
 * ### Why the fixture is built here rather than pushed
 *
 * `adb push` creates directories owned by the shell user, and the local source cannot write its
 * cover or its marker file into one, which fails the whole chapter listing and shows an entry with
 * no chapters and no error (recorded as carry-over 6). The test process runs as the application, so
 * a fixture it writes itself has the ownership the source needs, and it needs nothing from the
 * owner: every test built on it asserts on every machine rather than returning early.
 *
 * The directory carries a monotonic stamp in its name. A fixed name would collide with the rows a
 * previous run left in the database, where the chapter url is unique per entry, and the second run
 * would fail on an insert rather than on anything it meant to test.
 *
 * Each page is a flat PNG in [pageColor], a colour that differs per page index, so a test can tell
 * from a screenshot which page a viewer is showing.
 */
class ReaderFixture private constructor(
    private val graph: AppGraph,
    val directory: File,
    val mangaId: Long,
    /**
     * The flags the inserted row came with, so [writeReadingMode] changes the reading-mode bits and
     * leaves the rest of `viewer_flags` as the source wrote them. Reading them back off the row is
     * what `BenchFixtureSeeder` does too; assuming zero would work today and would silently clear
     * whatever a later insert path starts setting.
     */
    val viewerFlags: Long,
    /** The chapter rows, in the order the page counts were given. */
    val chapters: List<Chapter>,
) {

    /**
     * Writes [mode] onto the entry with the same `viewerFlags.withReadingMode(mode)` update
     * `BenchFixtureSeeder` performs, so the reader opens in the configuration an owner's library
     * holds (ruling R39).
     *
     * The return is not asserted on purpose. `MangaRepositoryImpl.update` returns true unless the
     * partial update throws, catching the exception into a logcat line, and an UPDATE that matched
     * no row returns true as well, so an assertion here would name a failure it cannot detect. What
     * proves the write is the reading mode the reader's session reports once it has opened.
     */
    fun writeReadingMode(mode: ReadingMode) {
        runBlocking {
            graph.mangaRepository.update(MangaUpdate(id = mangaId, viewerFlags = viewerFlags.withReadingMode(mode)))
        }
    }

    /**
     * The rows are soft deleted rather than removed: a hard delete is only available per source,
     * and running it over the local source would take an owner's own local entries with it. The
     * directory goes, so the next listing does not show the fixture.
     */
    fun remove() {
        directory.deleteRecursively()
        runBlocking { graph.mangaRepository.softDelete(mangaId, System.currentTimeMillis()) }
    }

    companion object {

        /** `LocalSourceFileSystem`'s default directory name under the app's external files. */
        private const val LOCAL_ROOT = "local"

        const val PAGE_WIDTH = 64
        const val PAGE_HEIGHT = 96

        /**
         * Five colours far apart on every channel, cycled by page index. A viewer that lands on the
         * wrong page shows a colour no tolerance in a screenshot comparison can mistake for the
         * right one.
         */
        private val PAGE_COLORS = intArrayOf(
            0xFFC03030.toInt(),
            0xFF30C030.toInt(),
            0xFF3030C0.toInt(),
            0xFFC0C030.toInt(),
            0xFFC030C0.toInt(),
        )

        fun pageColor(index: Int): Int = PAGE_COLORS[index % PAGE_COLORS.size]

        /** The chapter folder name the local source reads chapter [number] from, 1-based. */
        fun chapterName(number: Int): String = "Chapter $number"

        /**
         * Writes one chapter folder per entry of [pageCounts] under a new entry named [prefix] plus
         * a monotonic stamp, lists it through the local source, and inserts the entry and chapter
         * rows through the graph's repositories.
         */
        fun create(context: Context, graph: AppGraph, prefix: String, pageCounts: List<Int>): ReaderFixture {
            // Monotonic and distinct per call: a wall clock at millisecond resolution can hand two
            // methods of one class the same name, and the second would then collide with the rows
            // the first left behind on the chapter url's unique index.
            val entryName = "$prefix${System.nanoTime()}"
            val directory = File(localRoot(context), entryName)
            pageCounts.forEachIndexed { chapter, pages ->
                val chapterDirectory = File(directory, chapterName(chapter + 1))
                assertTrue("could not create the fixture directory", chapterDirectory.mkdirs())
                repeat(pages) { index -> writePage(File(chapterDirectory, "%03d.png".format(index + 1)), index) }
            }

            return runBlocking {
                val listed = graph.localSource.getSearchManga(1, entryName, FilterList.EMPTY)
                val info = listed.mangas.firstOrNull { it.title == entryName }
                assertNotNull(
                    "the local source did not list the fixture; its root is not the app's own directory",
                    info,
                )

                val stored = graph.mangaRepository.insertNetworkManga(
                    listOf(info!!.toDomainManga(LocalSource.ID)),
                ).single()

                val listedChapters = graph.localSource.getChapterList(stored.toMangaInfo())
                assertEquals(
                    "the local source read a different number of chapters",
                    pageCounts.size,
                    listedChapters.size,
                )

                val rows = graph.chapterRepository.addAll(listedChapters.map { it.toDomainChapter(stored.id) })
                assertEquals("the chapter rows were not written", pageCounts.size, rows.size)
                val ordered = List(pageCounts.size) { index ->
                    checkNotNull(rows.firstOrNull { it.name == chapterName(index + 1) }) {
                        "no chapter row is named ${chapterName(index + 1)}"
                    }
                }
                ReaderFixture(graph, directory, stored.id, stored.viewerFlags, ordered)
            }
        }

        /**
         * The directory the local source falls back to when no tree URI is configured. Resolved
         * rather than asked for, because the file system class is not a graph root; the listing
         * assertion in [create] is what catches a device whose root is configured somewhere else.
         */
        fun localRoot(context: Context): File =
            File(checkNotNull(context.getExternalFilesDir(null)) { "no external files directory" }, LOCAL_ROOT)

        /** A real PNG: the source sniffs magic bytes, and the pipeline decodes what it finds. */
        private fun writePage(target: File, index: Int) {
            val bitmap = Bitmap.createBitmap(PAGE_WIDTH, PAGE_HEIGHT, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(pageColor(index))
                target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally {
                bitmap.recycle()
            }
        }
    }
}

package com.shinsaikou.bench

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
import tachiyomi.domain.manga.model.MangaUpdate
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * The content the Phase 0 benchmarks read, built on the device that is about to be measured.
 *
 * Contract 01 section 7.5 measures a page turn and a webtoon scroll, and both need a chapter open.
 * Every earlier version of that took the chapter from the owner: a fixtures directory pushed over
 * adb and a steps file naming row ids. Neither can be in the repository, so on any machine but the
 * owner's the two benches skipped and Phase 0 published a gate with its reader rows missing.
 *
 * What is here instead is generated. It is deterministic, so two devices measure the same bytes and
 * their numbers compare; it is synthetic, so nothing about it belongs to anyone; and it is written
 * by the application's own process, so the local source can read it. `adb push` cannot be used for
 * this: it creates directories owned by the shell user, and the local source then fails to write
 * its cover into one and lists an entry with no chapters and no error (carry-over 6).
 *
 * ### The two entries
 *
 * [PAGED] is [PAGED_PAGES] JPEGs at [PAGED_WIDTH] x [PAGED_HEIGHT], the shape of a scanned page.
 * [STRIP] is [STRIP_PAGES] PNGs at [STRIP_WIDTH] x [STRIP_HEIGHT], tall enough that the splitter
 * has to band them: `SplitPlanner` splits above three times the width, and at 16 megapixels against
 * a 4 megapixel band budget each one becomes four parts. A webtoon bench over images the splitter
 * declines to touch would measure a code path the reader does not use on real strips.
 *
 * Both patterns are drawn from the pixel's own coordinates, so there is no random number generator
 * and no seed to record. They carry edges on purpose: a flat fill compresses to nothing and decodes
 * in no time, and a benchmark over it measures the reader's scaffolding rather than its pipeline.
 *
 * ### Idempotence
 *
 * Everything here is written once per device and reused. Images already on disk are left alone,
 * `insertNetworkManga` returns the existing row for a source and url it already holds, and a
 * chapter is inserted only when no row carries its url, because `chapters` has no unique index on
 * (manga_id, url) and a second insert would quietly double the chapter instead of failing.
 *
 * Nothing is deleted afterwards, which is the opposite of `ReaderSmokeTest`'s fixture. These rows
 * exist so the next run does not pay for them again, and the benchmark devices are the three AVDs
 * Phase 0 boots. They are never favorited, so they stay out of the library.
 */
object BenchFixtureSeeder {

    /** The ids a seeded entry was given, for whoever is about to open it. */
    data class Entry(val mangaId: Long, val chapterId: Long, val pages: Int)

    data class Seed(val paged: Entry, val strip: Entry)

    const val PAGED = "saikou-bench-paged"
    const val STRIP = "saikou-bench-strip"

    const val PAGED_PAGES = 60
    const val PAGED_WIDTH = 800
    const val PAGED_HEIGHT = 1200

    /** Written onto the entries by [seed]; the benchmark asserts the reader opened in them. */
    val PAGED_MODE = ReadingMode.LEFT_TO_RIGHT
    val STRIP_MODE = ReadingMode.WEBTOON

    const val STRIP_PAGES = 10
    const val STRIP_WIDTH = 800
    const val STRIP_HEIGHT = 20_000

    /**
     * Writes whatever is missing and returns the row ids of both entries. Safe to call on every
     * run: the second call on a device does no file writing and no inserting.
     */
    suspend fun seed(context: Context, graph: AppGraph): Seed = Seed(
        paged = seedEntry(context, graph, PAGED, PAGED_PAGES, "jpg", PAGED_MODE, ::writePagedPage),
        strip = seedEntry(context, graph, STRIP, STRIP_PAGES, "png", STRIP_MODE, ::writeStripPage),
    )

    private suspend fun seedEntry(
        context: Context,
        graph: AppGraph,
        title: String,
        pages: Int,
        extension: String,
        mode: ReadingMode,
        write: (target: File, index: Int) -> Unit,
    ): Entry {
        writeImages(context, title, pages, extension, write)

        val listed = graph.localSource.getSearchManga(1, title, FilterList.EMPTY)
        val info = checkNotNull(listed.mangas.firstOrNull { it.title == title }) {
            "the local source did not list $title; its root is not the application's own directory"
        }
        val manga = graph.mangaRepository
            .insertNetworkManga(listOf(info.toDomainManga(LocalSource.ID)))
            .single()

        // The reading mode is written onto the entry. The reader once took a mode from an intent
        // extra instead, and that path opened a reader which published the right state and drew
        // nothing (carry-over 12), which is worse than useless for a benchmark: the gestures would
        // be measured against a page that was never on screen. The extra is retired (ruling R39)
        // and this is the only way in. It is also what an owner's own library holds, so the two
        // viewers are benchmarked in the configuration they actually run in.
        graph.mangaRepository.update(
            MangaUpdate(id = manga.id, viewerFlags = manga.viewerFlags.withReadingMode(mode)),
        )

        val chapters = graph.localSource.getChapterList(manga.toMangaInfo())
        val chapter = chapters.singleOrNull()
            ?: error("$title has ${chapters.size} chapters where it should have one")

        // Read before write. addAll is a plain insert and the table has no unique index to stop it,
        // so seeding twice without this leaves two chapter rows with the same url and the benchmark
        // opens whichever one it was handed.
        val existing = graph.chapterRepository.getChapterByMangaId(manga.id)
            .firstOrNull { it.url == chapter.url }
        val chapterId = existing?.id
            ?: graph.chapterRepository.addAll(listOf(chapter.toDomainChapter(manga.id))).single().id

        return Entry(mangaId = manga.id, chapterId = chapterId, pages = pages)
    }

    /**
     * The chapter directory, filled to [pages] files. A file already there is left alone, so a
     * device that has been seeded before does no image work at all; a run interrupted part way
     * through finishes the rest on the next call.
     *
     * The file name is what orders the pages: the local source lists a directory's images by name,
     * and %03d keeps page 10 behind page 9.
     */
    private fun writeImages(
        context: Context,
        title: String,
        pages: Int,
        extension: String,
        write: (File, Int) -> Unit,
    ) {
        val chapter = File(File(localRoot(context), title), CHAPTER_NAME)
        check(chapter.isDirectory || chapter.mkdirs()) { "could not create $chapter" }
        for (index in 0..<pages) {
            val target = File(chapter, "%03d.%s".format(index + 1, extension))
            if (target.length() > 0) continue
            // Written beside the target and moved into place, so an interrupted run cannot leave a
            // truncated image that every later run then accepts as finished.
            val partial = File(chapter, target.name + ".part")
            write(partial, index)
            check(partial.renameTo(target)) { "could not put $target in place" }
        }
    }

    /**
     * The directory the local source falls back to when no tree URI is configured, resolved the
     * same way `ReaderSmokeTest` resolves it. The listing check in [seedEntry] is what catches a
     * device whose root is configured somewhere else.
     */
    private fun localRoot(context: Context): File =
        File(checkNotNull(context.getExternalFilesDir(null)) { "no external files directory" }, LOCAL_ROOT)

    // ---- the images -------------------------------------------------------------------------

    /**
     * Light paper, a coarse block structure and a scatter of dark strokes. JPEG keeps the strokes
     * as edges, which is the work a decoder actually does on a scanned page; a gradient would
     * compress to almost nothing and prove nothing about decoding one.
     */
    private fun pagedPixel(x: Int, y: Int, page: Int): Int {
        val block = ((x / 16) + (y / 16) + page) and 1
        val stroke = (x * 3 + y * 7 + page * 37) % 97 < 3
        val gray = PAPER - block * BLOCK_SHADE - if (stroke) STROKE_SHADE else 0
        return 0xFF shl 24 or (gray shl 16) or (gray shl 8) or gray
    }

    private fun writePagedPage(target: File, page: Int) {
        val pixels = IntArray(PAGED_WIDTH * PAGED_HEIGHT)
        for (y in 0..<PAGED_HEIGHT) {
            val row = y * PAGED_WIDTH
            for (x in 0..<PAGED_WIDTH) pixels[row + x] = pagedPixel(x, y, page)
        }
        val bitmap = Bitmap.createBitmap(PAGED_WIDTH, PAGED_HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            bitmap.setPixels(pixels, 0, PAGED_WIDTH, 0, 0, PAGED_WIDTH, PAGED_HEIGHT)
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * A strip, written chunk by chunk rather than through `Bitmap.compress`, because an
     * 800 x 20000 ARGB_8888 bitmap is 64 MB in one allocation and the 2 GB AVD the benchmarks have
     * to run on cannot make it. One scanline is held at a time; the splitter only sees the file.
     *
     * The pattern repeats every [STRIP_PERIOD] rows so deflate finds its matches inside a 32 KB
     * window and the file stays a few megabytes. The pixel count, which is what the reader pays
     * for, is unaffected by how well it compressed.
     */
    private fun writeStripPage(target: File, page: Int) {
        // The row depends only on its position within the repeat, so the whole image is
        // STRIP_PERIOD distinct scanlines written over and over. Computing each of 20000 rows
        // pixel by pixel instead costs 16 million iterations per file for the same bytes.
        val rows = Array(STRIP_PERIOD) { band ->
            ByteArray(1 + STRIP_WIDTH).also { row ->
                row[0] = 0 // filter type 0: a row's bytes are its pixels
                for (x in 0..<STRIP_WIDTH) row[1 + x] = stripGray(x, band, page).toByte()
            }
        }
        val idat = ByteArrayOutputStream()
        val deflater = Deflater(Deflater.BEST_SPEED)
        try {
            DeflaterOutputStream(idat, deflater).use { out ->
                for (y in 0..<STRIP_HEIGHT) out.write(rows[y % STRIP_PERIOD])
            }
        } finally {
            deflater.end()
        }
        writePng(target, idat.toByteArray())
    }

    private fun stripGray(x: Int, band: Int, page: Int): Int {
        val panel = ((x / 40) + (band / 20) + page) and 1
        val rule = band < 2 || x % 200 < 3
        return PAPER - panel * BLOCK_SHADE - if (rule) STROKE_SHADE else 0
    }

    private fun writePng(target: File, idat: ByteArray) {
        val header = ByteArrayOutputStream(IHDR_BYTES)
        DataOutputStream(header).use {
            it.writeInt(STRIP_WIDTH)
            it.writeInt(STRIP_HEIGHT)
            it.writeByte(BIT_DEPTH)
            it.writeByte(COLOR_TYPE_GRAYSCALE)
            it.writeByte(0) // deflate, the only compression method PNG defines
            it.writeByte(0) // adaptive filtering, the only filter method PNG defines
            it.writeByte(0) // not interlaced
        }
        DataOutputStream(BufferedOutputStream(target.outputStream())).use { out ->
            out.write(PNG_SIGNATURE)
            writeChunk(out, "IHDR", header.toByteArray())
            writeChunk(out, "IDAT", idat)
            writeChunk(out, "IEND", ByteArray(0))
        }
    }

    private fun writeChunk(out: DataOutputStream, type: String, data: ByteArray) {
        val name = type.toByteArray(Charsets.US_ASCII)
        out.writeInt(data.size)
        out.write(name)
        out.write(data)
        val crc = CRC32()
        crc.update(name)
        crc.update(data)
        out.writeInt(crc.value.toInt())
    }

    /** `LocalSourceFileSystem`'s default directory name under the application's external files. */
    private const val LOCAL_ROOT = "local"

    private const val CHAPTER_NAME = "Chapter 1"

    private const val PAPER = 0xE8
    private const val BLOCK_SHADE = 0x18
    private const val STROKE_SHADE = 0xC0
    private const val JPEG_QUALITY = 85

    /** Rows, and a multiple of the panel height in [stripGray] so the repeat is exact. */
    private const val STRIP_PERIOD = 40

    private val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    private const val IHDR_BYTES = 13
    private const val BIT_DEPTH = 8
    private const val COLOR_TYPE_GRAYSCALE = 0
}

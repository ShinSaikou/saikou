package com.shinsaikou.bench

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.shinsaikou.di.AppGraph
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import java.io.File

/**
 * The library the low RAM repro scrolls: [ENTRIES] favourites, each with a cover of its own, built
 * on the device that is about to be measured. Ruling R61.
 *
 * The repro replays a cover cache that grows while a large library scrolls, so what matters is that
 * every entry's cover is a different image the application has to decode, and that the grid reaches
 * it the way it reaches an owner's covers. Each entry is a row in `mangas` with a cover URL, and the
 * cover's bytes are written to the file [eu.kanade.tachiyomi.data.cache.CoverCache.getCoverFile]
 * names for that URL, which is where `MangaCoverFetcher` puts a favourite's cover after its first
 * download and where it looks before it touches the network. So the grid, the keyer, the fetcher,
 * the decoder and Coil's memory cache all run exactly as they do for a real library; only the one
 * download each cover would once have needed is skipped, because a gate device has nothing to
 * download from. The host is reserved and can never resolve, so a fetch that did happen would fail
 * rather than reach anyone.
 *
 * The files are written by the application's own process, like [BenchFixtureSeeder]'s, so they
 * have the ownership the fetcher needs (carry-over 6). Nothing is pushed.
 *
 * ### Idempotence
 *
 * A cover already on disk is left alone, and `insertNetworkManga` returns the existing row for a
 * source and url it already holds, so a second call writes no image and inserts nothing; it reads
 * the rows back and re-favourites any the library lost. Nothing is deleted afterwards: the rows
 * exist so the next run does not pay for them again, and they live only on the low RAM AVD, the
 * one device whose gate step runs this.
 *
 * ### The covers
 *
 * [COVER_WIDTH] x [COVER_HEIGHT] JPEGs, larger than a grid cell on any gate device, so each is
 * decoded down to the cell rather than up to it and costs the memory a real cover costs. Each is
 * drawn from its index: a hue of its own, stripes placed by the index, and the index itself in large
 * type, so no two share bytes and every one has edges for the decoder to work on.
 */
object LibraryCoverSeeder {

    /** The rows a seed produced, and how much of it this call had to write. */
    data class Library(val mangaIds: List<Long>, val coversWritten: Int, val refavourited: Int)

    /** R61 asks for at least 1,000. */
    const val ENTRIES = 1_000

    /** No provider serves this id, so every row under it belongs to this seeder alone. */
    const val SOURCE_ID = 9_000_000_000_000_000_003L

    /** Zero padded, so every title is the prefix and exactly [TITLE_DIGITS] digits. */
    const val TITLE_PREFIX = "Saikou low RAM entry "
    const val TITLE_DIGITS = 4

    const val COVER_WIDTH = 400
    const val COVER_HEIGHT = 600

    private const val URL_PREFIX = "/saikou-gate/lowram/"

    /** Reserved for documentation and examples, so it can never resolve to anything real. */
    private const val COVER_URL_PREFIX = "https://gate.invalid/saikou-lowram/cover/"

    /** Fixed, so the rows a second run reads back are the ones the first run wrote. */
    private const val DATE_ADDED = 1_758_585_600_000L

    private const val JPEG_QUALITY = 85
    private const val GOLDEN_ANGLE = 137.508f
    private const val STRIPES = 6

    fun title(index: Int): String = TITLE_PREFIX + index.toString().padStart(TITLE_DIGITS, '0')

    /**
     * Writes whatever is missing and returns the row ids. Safe to call on every run: the second call
     * on a device does no image work and no inserting.
     */
    suspend fun seed(graph: AppGraph): Library {
        val coversWritten = writeCovers(graph)

        val rows = graph.mangaRepository.insertNetworkManga(
            (0 until ENTRIES).map { index ->
                Manga.create().copy(
                    source = SOURCE_ID,
                    url = "$URL_PREFIX$index",
                    title = title(index),
                    thumbnailUrl = coverUrl(index),
                    favorite = true,
                    // Initialized, so nothing that opens one of these rows starts a fetch against a
                    // source that has no provider behind it.
                    initialized = true,
                    dateAdded = DATE_ADDED,
                )
            },
        )
        check(rows.size == ENTRIES) { "the seed stored ${rows.size} of $ENTRIES entries" }

        // A row that already existed comes back as it is, so one an earlier run or an owner took out
        // of the library would stay out. The return is not asserted: the repository answers true for
        // an update that matched nothing (see ReaderSmokeTest), and the library pass that counts the
        // titles on screen is what proves the entries are there.
        val lost = rows.filterNot { it.favorite }
        lost.forEach { graph.mangaRepository.update(MangaUpdate(id = it.id, favorite = true)) }

        return Library(mangaIds = rows.map { it.id }, coversWritten = coversWritten, refavourited = lost.size)
    }

    private fun coverUrl(index: Int): String = "$COVER_URL_PREFIX$index.jpg"

    /**
     * One file per entry at the path the fetcher reads. A file already there is left alone, and each
     * is written beside its target and moved into place, so an interrupted run cannot leave a
     * truncated cover that every later run then accepts as finished.
     */
    private fun writeCovers(graph: AppGraph): Int {
        val bitmap = Bitmap.createBitmap(COVER_WIDTH, COVER_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var written = 0
        try {
            for (index in 0 until ENTRIES) {
                val target = checkNotNull(graph.coverCache.getCoverFile(coverUrl(index)))
                if (target.length() > 0) continue
                target.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "could not create $it" } }
                drawCover(canvas, paint, index)
                val partial = File(target.parentFile, target.name + ".part")
                partial.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                check(partial.renameTo(target)) { "could not put $target in place" }
                written++
            }
        } finally {
            bitmap.recycle()
        }
        return written
    }

    private fun drawCover(canvas: Canvas, paint: Paint, index: Int) {
        val hue = (index * GOLDEN_ANGLE) % 360f
        canvas.drawColor(Color.HSVToColor(floatArrayOf(hue, 0.45f, 0.85f)))

        paint.style = Paint.Style.FILL
        paint.color = Color.HSVToColor(floatArrayOf((hue + 180f) % 360f, 0.6f, 0.35f))
        val band = COVER_HEIGHT / (STRIPES * 2f)
        for (stripe in 0 until STRIPES) {
            val top = band * (stripe * 2) + (index * 7 + stripe * 13) % band.toInt()
            val inset = ((index + stripe) * 11 % (COVER_WIDTH / 4)).toFloat()
            canvas.drawRect(inset, top, COVER_WIDTH - inset, top + band / 2, paint)
        }

        paint.color = Color.WHITE
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = COVER_WIDTH / 3f
        canvas.drawText(index.toString(), COVER_WIDTH / 2f, COVER_HEIGHT * 0.6f, paint)
    }
}

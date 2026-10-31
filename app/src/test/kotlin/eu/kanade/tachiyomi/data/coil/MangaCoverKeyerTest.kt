// Fresh: exercises the fresh keyers in the vendored coil package now that cover replacement depends on their key folding coverLastModified.
package eu.kanade.tachiyomi.data.coil

import coil3.request.Options
import eu.kanade.tachiyomi.data.cache.CoverCache
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import java.io.File

class MangaCoverKeyerTest {

    @TempDir
    lateinit var tempDir: File

    // Neither keyer reads the request options, so an untouched mock satisfies the Keyer signature.
    private val options = mockk<Options>()

    @Test
    fun `two MangaCover values that differ only in coverLastModified key differently through the companion function`() {
        val first = cover(lastModified = 1_000L)
        val second = cover(lastModified = 2_000L)

        val firstKey = MangaCoverKeyer.key(first)
        val secondKey = MangaCoverKeyer.key(second)

        firstKey shouldNotBe secondKey
    }

    @Test
    fun `two MangaCover values with the same fields key the same through the companion function`() {
        val first = cover(lastModified = 1_000L)
        val second = cover(lastModified = 1_000L)

        val firstKey = MangaCoverKeyer.key(first)
        val secondKey = MangaCoverKeyer.key(second)

        firstKey shouldBe secondKey
    }

    @Test
    fun `two Manga values that differ only in coverLastModified key differently through MangaKeyer`() {
        val keyer = MangaKeyer()
        val first = manga(coverLastModified = 1_000L)
        val second = manga(coverLastModified = 2_000L)

        val firstKey = keyer.key(first, options)
        val secondKey = keyer.key(second, options)

        firstKey shouldNotBe secondKey
    }

    @Test
    fun `two Manga values with the same fields key the same through MangaKeyer`() {
        val keyer = MangaKeyer()
        val first = manga(coverLastModified = 1_000L)
        val second = manga(coverLastModified = 1_000L)

        val firstKey = keyer.key(first, options)
        val secondKey = keyer.key(second, options)

        firstKey shouldBe secondKey
    }

    @Test
    fun `a MangaCover with an existing custom cover file keys differently from one without a custom cover file`() {
        val data = cover(lastModified = 1_000L)

        val withCustomCover = MangaCoverKeyer(coverCacheReturning(existingFile())).key(data, options)
        val withoutCustomCover = MangaCoverKeyer(coverCacheReturning(missingFile())).key(data, options)

        withCustomCover shouldNotBe withoutCustomCover
    }

    @Test
    fun `a custom cover key still changes when coverLastModified changes`() {
        val keyer = MangaCoverKeyer(coverCacheReturning(existingFile()))
        val first = cover(lastModified = 1_000L)
        val second = cover(lastModified = 2_000L)

        val firstKey = keyer.key(first, options)
        val secondKey = keyer.key(second, options)

        firstKey shouldNotBe secondKey
    }

    private fun cover(lastModified: Long) = MangaCover(
        mangaId = MANGA_ID,
        sourceId = SOURCE_ID,
        isMangaFavorite = false,
        url = COVER_URL,
        lastModified = lastModified,
    )

    private fun manga(coverLastModified: Long) = Manga.create().copy(
        id = MANGA_ID,
        source = SOURCE_ID,
        thumbnailUrl = COVER_URL,
        coverLastModified = coverLastModified,
    )

    private fun coverCacheReturning(customCoverFile: File): CoverCache = mockk<CoverCache> {
        every { getCustomCoverFile(any()) } returns customCoverFile
    }

    // The custom cover branch turns on File.exists, so the two cases are real files under the
    // per-test temp directory rather than a mocked File.
    private fun existingFile(): File = File(tempDir, "custom").apply { createNewFile() }

    private fun missingFile(): File = File(tempDir, "missing")

    private companion object {
        const val MANGA_ID = 1L
        const val SOURCE_ID = 1L
        const val COVER_URL = "https://example.invalid/cover.jpg"
    }
}

// Fresh: exercises the vendored SetMangaChapterFlags and SetMangaDefaultChapterFlags on the flag word (phase 4 unit VG).
package tachiyomi.domain.manga.interactor

import com.shinsaikou.domain.preferences.LibraryPreferences
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository

/**
 * The chapter flag word through every path that applies the chapter defaults (phase 4 unit VG,
 * challenge c2 X3): the open-time apply and "Reset" (`SetMangaDefaultChapterFlags.await`), "Also
 * apply to library" (`awaitAll`), and "Set as default" itself, which writes the six defaults and
 * then, with the box ticked, `awaitAll`. Each keeps the volume headers bit and any other bit
 * outside upstream's six masks, and still writes the six defaults exactly.
 */
class SetMangaChapterFlagsTest {

    private val written = mutableListOf<MangaUpdate>()
    private val favorites = mutableListOf<Manga>()

    private val repository = mockk<MangaRepository> {
        coEvery { update(any()) } coAnswers {
            written += firstArg<MangaUpdate>()
            true
        }
        coEvery { getFavorites() } coAnswers { favorites.toList() }
    }

    private val setFlags = SetMangaChapterFlags(repository)
    private val libraryPreferences = LibraryPreferences(InMemoryPreferenceStore())
    private val setDefaults = SetMangaDefaultChapterFlags(libraryPreferences, setFlags, GetFavorites(repository))

    /** Every upstream bit set one way, the volume bit, and an unassigned high bit a later unit might take. */
    private val entryFlags = Manga.CHAPTER_SORT_ASC or Manga.CHAPTER_SHOW_READ or Manga.CHAPTER_SHOW_DOWNLOADED or
        Manga.CHAPTER_SHOW_NOT_BOOKMARKED or Manga.CHAPTER_SORTING_ALPHABET or Manga.CHAPTER_DISPLAY_NUMBER or
        Manga.CHAPTER_GROUP_BY_VOLUME or LATER_BIT

    private fun manga(id: Long, flags: Long = entryFlags) = Manga.create().copy(id = id, chapterFlags = flags)

    /** The defaults every test applies: each of the six differs from [entryFlags]. */
    private fun storeDefaults() {
        libraryPreferences.filterChapterByRead.set(Manga.CHAPTER_SHOW_UNREAD)
        libraryPreferences.filterChapterByDownloaded.set(Manga.SHOW_ALL)
        libraryPreferences.filterChapterByBookmarked.set(Manga.CHAPTER_SHOW_BOOKMARKED)
        libraryPreferences.sortChapterBySourceOrNumber.set(Manga.CHAPTER_SORTING_NUMBER)
        libraryPreferences.sortChapterByAscendingOrDescending.set(Manga.CHAPTER_SORT_DESC)
        libraryPreferences.displayChapterByNameOrNumber.set(Manga.CHAPTER_DISPLAY_NAME)
    }

    private val defaultsWord = Manga.CHAPTER_SHOW_UNREAD or Manga.CHAPTER_SHOW_BOOKMARKED or
        Manga.CHAPTER_SORTING_NUMBER

    @Test
    fun `the six defaults onto an entry keep the volume bit and every other bit outside the six masks`() = runTest {
        storeDefaults()

        setDefaults.await(manga(1))

        written.single().id shouldBe 1L
        written.single().chapterFlags shouldBe (defaultsWord or Manga.CHAPTER_GROUP_BY_VOLUME or LATER_BIT)
    }

    @Test
    fun `an entry without the volume bit does not gain it from the defaults`() = runTest {
        storeDefaults()

        setDefaults.await(manga(1, flags = entryFlags and Manga.CHAPTER_GROUP_BY_VOLUME_MASK.inv()))

        written.single().chapterFlags shouldBe (defaultsWord or LATER_BIT)
        Manga.create().copy(chapterFlags = written.single().chapterFlags!!).groupByVolume shouldBe false
    }

    @Test
    fun `also apply to library keeps each entry's own bits outside the six masks`() = runTest {
        storeDefaults()
        favorites += manga(1)
        favorites += manga(2, flags = Manga.CHAPTER_SORT_ASC)

        setDefaults.awaitAll()

        written.associate { it.id to it.chapterFlags } shouldBe mapOf(
            1L to (defaultsWord or Manga.CHAPTER_GROUP_BY_VOLUME or LATER_BIT),
            2L to defaultsWord,
        )
    }

    @Test
    fun `set as default with the box ticked keeps the volume bit on the entry it was set from`() = runTest {
        val entry = manga(1)
        favorites += entry

        // MangaDetailViewModel.setAsDefaultChapterSettings(applyToLibrary = true): the six defaults
        // from this entry, then every favourite takes them.
        libraryPreferences.setChapterSettingsDefault(entry)
        setDefaults.awaitAll()

        written.single().chapterFlags shouldBe entryFlags
        Manga.create().copy(chapterFlags = written.single().chapterFlags!!).groupByVolume shouldBe true
    }

    @Test
    fun `the volume toggle writes its bit alone`() = runTest {
        val off = entryFlags and Manga.CHAPTER_GROUP_BY_VOLUME_MASK.inv()

        setFlags.awaitSetGroupByVolume(manga(1, flags = off), enabled = true)
        setFlags.awaitSetGroupByVolume(manga(1, flags = entryFlags), enabled = false)

        written.map { it.chapterFlags } shouldBe listOf(entryFlags, off)
        manga(1, flags = entryFlags).groupByVolume shouldBe true
        manga(1, flags = off).groupByVolume shouldBe false
    }

    @Test
    fun `the volume bit is outside every upstream mask and fits a backup's Int`() {
        (Manga.CHAPTER_GROUP_BY_VOLUME_MASK and Manga.CHAPTER_DEFAULTS_MASK) shouldBe 0L
        Manga.CHAPTER_DEFAULTS_MASK shouldBe 0x0010037FL
        Manga.CHAPTER_GROUP_BY_VOLUME.toInt().toLong() shouldBe Manga.CHAPTER_GROUP_BY_VOLUME
    }

    private companion object {
        /** No constant owns this bit; it stands for whatever a later unit adds. */
        const val LATER_BIT = 0x40000000L
    }
}

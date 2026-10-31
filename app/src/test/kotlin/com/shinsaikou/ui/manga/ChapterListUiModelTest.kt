package com.shinsaikou.ui.manga

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContainDuplicates
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * The chapter list's volume headers (phase 4 unit VG): a header where the volume changes, only with
 * the toggle on and a sort that keeps volumes together, and none over a chapter with no volume.
 */
class ChapterListUiModelTest {

    private fun chapter(id: Long, volume: Double?) = Chapter.create().copy(id = id, name = "Chapter $id", volume = volume)

    /** A readable shape of the list: H and the volume for a header, I and the id for a chapter. */
    private fun List<ChapterListUiModel>.shape(): List<String> = map { model ->
        when (model) {
            is ChapterListUiModel.Header -> "H ${model.volume}"
            is ChapterListUiModel.Item -> "I ${model.chapter.id}"
        }
    }

    private val runs = listOf(
        chapter(1, 1.0),
        chapter(2, 1.0),
        chapter(3, 2.0),
        chapter(4, 2.0),
        chapter(5, 3.0),
    )

    @Test
    fun `a header goes before each run of one volume`() {
        chapterListUiModels(runs, groupByVolume = true, sorting = Manga.CHAPTER_SORTING_NUMBER).shape() shouldContainExactly
            listOf("H 1.0", "I 1", "I 2", "H 2.0", "I 3", "I 4", "H 3.0", "I 5")
    }

    @Test
    fun `a descending list heads each run the same way, and volume 0 is a volume`() {
        val chapters = listOf(chapter(3, 1.0), chapter(2, 0.0), chapter(1, 0.0))

        chapterListUiModels(chapters, groupByVolume = true, sorting = Manga.CHAPTER_SORTING_SOURCE).shape() shouldContainExactly
            listOf("H 1.0", "I 3", "H 0.0", "I 2", "I 1")
    }

    /**
     * No "no volume" bucket (`phase4-start.md` section 5). A volume after chapters with none gets a
     * header again, even the volume before them, and its key stays unique.
     */
    @Test
    fun `a chapter with no volume sits under no header`() {
        val chapters = listOf(
            chapter(10, null),
            chapter(11, null),
            chapter(1, 1.0),
            chapter(12, null),
            chapter(2, 1.0),
            chapter(3, 2.0),
        )

        val models = chapterListUiModels(chapters, groupByVolume = true, sorting = Manga.CHAPTER_SORTING_NUMBER)

        models.shape() shouldContainExactly listOf("I 10", "I 11", "H 1.0", "I 1", "I 12", "H 1.0", "I 2", "H 2.0", "I 3")
        models.map { it.key }.shouldNotContainDuplicates()
    }

    @Test
    fun `a title with no volumes renders as before`() {
        val chapters = listOf(chapter(1, null), chapter(2, null))

        chapterListUiModels(chapters, groupByVolume = true, sorting = Manga.CHAPTER_SORTING_NUMBER).shape() shouldContainExactly
            listOf("I 1", "I 2")
    }

    @Test
    fun `with the toggle off the list has no headers`() {
        chapterListUiModels(runs, groupByVolume = false, sorting = Manga.CHAPTER_SORTING_NUMBER).shape() shouldContainExactly
            listOf("I 1", "I 2", "I 3", "I 4", "I 5")
    }

    @Test
    fun `a sort that does not keep volumes together has no headers`() {
        listOf(Manga.CHAPTER_SORTING_UPLOAD_DATE, Manga.CHAPTER_SORTING_ALPHABET).forEach { sorting ->
            chapterListUiModels(runs, groupByVolume = true, sorting = sorting).shape() shouldContainExactly
                listOf("I 1", "I 2", "I 3", "I 4", "I 5")
        }
    }

    @Test
    fun `header keys are apart from chapter ids, which keep theirs`() {
        val models = chapterListUiModels(runs, groupByVolume = true, sorting = Manga.CHAPTER_SORTING_NUMBER)

        models.map { it.key } shouldContainExactly
            listOf("volume-header-1", 1L, 2L, "volume-header-3", 3L, 4L, "volume-header-5", 5L)
    }
}

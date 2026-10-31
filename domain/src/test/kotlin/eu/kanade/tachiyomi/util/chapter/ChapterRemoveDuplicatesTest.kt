// Fresh: written for Saikou. Placed in the vendored package beside the helper it tests.
package eu.kanade.tachiyomi.util.chapter

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

/**
 * The pick among copies of one chapter number (phase 4 unit SC): the current chapter first, then
 * the best-ranked copy if any copy's group is ranked, else upstream's rule (the current chapter's
 * group, else the first copy). A stored 0 is the top rank, and a group with no row ranks last.
 *
 * Chapters 1 to 3 are Group A's numbers 1 to 3; 4 and 5 are Group B's copies of 2 and 3, and 6 is
 * Group C's copy of 3.
 */
class ChapterRemoveDuplicatesTest {

    private val chapters = listOf(
        chapter(id = 1, number = 1.0, scanlator = "Group A"),
        chapter(id = 2, number = 2.0, scanlator = "Group A"),
        chapter(id = 4, number = 2.0, scanlator = "Group B"),
        chapter(id = 3, number = 3.0, scanlator = "Group A"),
        chapter(id = 5, number = 3.0, scanlator = "Group B"),
        chapter(id = 6, number = 3.0, scanlator = "Group C"),
    )

    @Test
    fun `an unranked title keeps upstream's rule, the current chapter's group`() {
        chapters.removeDuplicates(chapters.first { it.id == 1L }, emptyMap()).ids() shouldBe listOf(1L, 2L, 3L)
    }

    @Test
    fun `an unranked title with no current chapter keeps the first copy of each number`() {
        chapters.removeDuplicates(null, emptyMap()).ids() shouldBe listOf(1L, 2L, 3L)
    }

    @Test
    fun `a ranked group's copy wins over the current chapter's group`() {
        val current = chapters.first { it.id == 1L }
        chapters.removeDuplicates(current, mapOf("Group B" to 0L)).ids() shouldBe listOf(1L, 4L, 5L)
    }

    @Test
    fun `the current chapter still wins its own number over a ranked copy`() {
        val current = chapters.first { it.id == 2L }
        chapters.removeDuplicates(current, mapOf("Group B" to 0L)).ids() shouldBe listOf(1L, 2L, 5L)
    }

    @Test
    fun `a stored 0 ranks first, ahead of a higher stored rank`() {
        val ranking = mapOf("Group C" to 1L, "Group B" to 0L)
        chapters.removeDuplicates(null, ranking).ids() shouldBe listOf(1L, 4L, 5L)
    }

    @Test
    fun `a group with no row ranks after every ranked group`() {
        // Group A, the current chapter's group, has no row; Group C does, so number 3 is Group C's.
        // Number 2 has no ranked copy, so upstream's rule keeps the current chapter's group there.
        val current = chapters.first { it.id == 1L }
        chapters.removeDuplicates(current, mapOf("Group C" to 5L)).ids() shouldBe listOf(1L, 2L, 6L)
    }

    @Test
    fun `a copy with no scanlator is never ranked`() {
        val withBlank = chapters + chapter(id = 7, number = 4.0, scanlator = null) +
            chapter(id = 8, number = 4.0, scanlator = "Group B")
        withBlank.removeDuplicates(null, mapOf("Group B" to 0L)).ids() shouldBe listOf(1L, 4L, 5L, 8L)
    }

    private fun List<Chapter>.ids() = map { it.id }

    private fun chapter(id: Long, number: Double, scanlator: String?) = Chapter.create().copy(
        id = id,
        mangaId = 1L,
        chapterNumber = number,
        scanlator = scanlator,
        name = "Chapter $id",
        url = "/chapter/$id",
    )
}

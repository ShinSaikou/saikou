package com.shinsaikou.domain.merged

import com.shinsaikou.domain.merged.model.MergedGroup
import com.shinsaikou.domain.merged.model.MergedMember
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

/** Section 4 of `design/plans/merged-entries.md`: order is the only rule. */
class MergedChaptersTest {

    private fun member(mangaId: Long, position: Long) =
        MergedMember(mangaId = mangaId, source = mangaId * 10, url = "/$mangaId", position = position, checkUpdates = true)

    private val group = MergedGroup(
        id = 1,
        mainMangaId = 2,
        showAll = false,
        members = listOf(member(1, position = 0), member(2, position = 1)),
    )
    private val allInstalled = setOf(10L, 20L)

    private fun chapter(id: Long, mangaId: Long, number: Double) =
        Chapter.create().copy(id = id, mangaId = mangaId, chapterNumber = number)

    private fun visible(vararg chapters: Chapter, of: MergedGroup = group, installed: Set<Long> = allInstalled) =
        MergedChapters.visible(chapters.toList(), of, installed).map { it.id }

    @Test
    fun `the first member in the order wins a number, whichever is main, and a gap falls through`() {
        visible(chapter(1, 1, 1.0), chapter(2, 2, 1.0), chapter(3, 2, 2.0)) shouldContainExactly listOf(1, 3)
    }

    @Test
    fun `an uninstalled source ranks last`() {
        visible(chapter(1, 1, 1.0), chapter(2, 2, 1.0), installed = setOf(20L)) shouldContainExactly listOf(2)
    }

    @Test
    fun `every row of the winning member stays`() {
        visible(chapter(1, 1, 1.0), chapter(2, 1, 1.0), chapter(3, 2, 1.0)) shouldContainExactly listOf(1, 2)
    }

    @Test
    fun `a number below zero is always kept`() {
        visible(chapter(1, 1, -1.0), chapter(2, 2, -1.0)) shouldContainExactly listOf(1, 2)
    }

    @Test
    fun `show every copy skips the pass`() {
        visible(chapter(1, 1, 1.0), chapter(2, 2, 1.0), of = group.copy(showAll = true)) shouldContainExactly listOf(1, 2)
    }

    @Test
    fun `after the downloaded filter, a lower copy stands in for the winner it removed`() {
        visible(chapter(2, 2, 1.0)) shouldContainExactly listOf(2)
    }
}

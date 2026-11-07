package com.shinsaikou.reader.state

import com.shinsaikou.domain.merged.MergedChapters
import com.shinsaikou.domain.merged.model.MergedGroup
import com.shinsaikou.domain.merged.model.MergedMember
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * [ReaderSession.build] for a title of two entries (R163): the chapter pass runs after the
 * downloaded filter, the tapped chapter always stays, and a hidden tapped copy takes its number
 * from the winner.
 *
 * Entry [A] is the main entry and ranks first, entry [B] second. A holds chapters 1 and 2; B holds
 * the same two numbers and a chapter 3 of its own.
 */
class ReaderSessionMergedTest {

    private val a = manga(id = A, source = 1L)
    private val b = manga(id = B, source = 2L)
    private val owners = mapOf(A to a, B to b)

    private val chapters = listOf(
        chapter(id = 1, number = 1.0, mangaId = A),
        chapter(id = 2, number = 2.0, mangaId = A),
        chapter(id = 11, number = 1.0, mangaId = B),
        chapter(id = 12, number = 2.0, mangaId = B),
        chapter(id = 13, number = 3.0, mangaId = B),
    )

    private val none = SessionFilters(skipRead = false, skipFiltered = false, skipDupe = false)

    private fun group(showAll: Boolean = false) = MergedGroup(
        id = 1,
        mainMangaId = A,
        showAll = showAll,
        members = listOf(
            MergedMember(A, source = 1L, url = "/a", position = 0, checkUpdates = true),
            MergedMember(B, source = 2L, url = "/b", position = 1, checkUpdates = true),
        ),
    )

    private fun pass(group: MergedGroup = group()): (List<Chapter>) -> List<Chapter> =
        { MergedChapters.visible(it, group, installedSources = setOf(1L, 2L)) }

    private fun ids(
        initial: Long,
        prefs: SessionFilters = none,
        downloaded: Set<Long> = emptySet(),
        main: Manga = a,
        all: List<Chapter> = chapters,
        chapterPass: (List<Chapter>) -> List<Chapter> = pass(),
        owners: Map<Long, Manga> = this.owners,
    ) = ReaderSession.build(main, all, initial, prefs, downloaded, emptyMap(), owners, chapterPass)
        .chapters.map { it.chapter.id }

    @Test
    fun `one copy of each number stays, from the first source, in number order`() {
        ids(initial = 1) shouldBe listOf(1L, 2L, 13L)
    }

    @Test
    fun `a tapped hidden copy stays and takes its number from the winner`() {
        // 11 is B's chapter 1; A's chapter 1 steps aside so the next chapter is not the same number again.
        ids(initial = 11) shouldBe listOf(11L, 2L, 13L)
    }

    @Test
    fun `a tapped hidden copy keeps every row of its own entry with that number`() {
        val twoRows = chapters + chapter(id = 14, number = 1.0, mangaId = B, scanlator = "other")

        ids(initial = 11, all = twoRows) shouldBe listOf(11L, 14L, 2L, 13L)
    }

    @Test
    fun `a tapped chapter the pass keeps leaves the rest as the pass left them`() {
        ids(initial = 13) shouldBe listOf(1L, 2L, 13L)
    }

    @Test
    fun `a tapped hidden copy with no recognised number stays beside the others`() {
        val unnumbered = listOf(
            chapter(id = 1, number = -1.0, mangaId = A),
            chapter(id = 11, number = -1.0, mangaId = B),
            chapter(id = 2, number = 2.0, mangaId = A),
        )

        ids(initial = 11, all = unnumbered) shouldBe listOf(1L, 11L, 2L)
    }

    @Test
    fun `downloaded only keeps a downloaded hidden copy and drops the winner that is not downloaded`() {
        ids(initial = 13, prefs = none.copy(downloadedOnly = true), downloaded = setOf(11L, 13L)) shouldBe
            listOf(11L, 13L)
    }

    @Test
    fun `downloaded only keeps the winner when both copies are downloaded`() {
        ids(initial = 13, prefs = none.copy(downloadedOnly = true), downloaded = setOf(1L, 11L, 13L)) shouldBe
            listOf(1L, 13L)
    }

    @Test
    fun `show every copy skips the pass`() {
        ids(initial = 1, chapterPass = pass(group(showAll = true))) shouldBe listOf(1L, 11L, 2L, 12L, 13L)
    }

    @Test
    fun `the default pass leaves a title of one entry whole`() {
        val only = ReaderSession.build(a, chapters.filter { it.mangaId == A }, 1, none, emptySet(), emptyMap())

        only.chapters.map { it.chapter.id } shouldBe listOf(1L, 2L)
    }

    @Test
    fun `several entries sort by number even when the main entry sorts by source order`() {
        val bySource = manga(id = A, source = 1L, chapterFlags = Manga.CHAPTER_SORTING_SOURCE)
        // Source order alone would put 13 first and 1 last.
        val all = listOf(
            chapter(id = 1, number = 1.0, mangaId = A, sourceOrder = 0),
            chapter(id = 13, number = 3.0, mangaId = B, sourceOrder = 9),
            chapter(id = 2, number = 2.0, mangaId = A, sourceOrder = 5),
        )

        ids(initial = 1, main = bySource, all = all, chapterPass = { it }, owners = mapOf(A to bySource, B to b)) shouldBe
            listOf(1L, 2L, 13L)
    }

    @Test
    fun `one entry sorting by source order still follows its source order`() {
        val bySource = manga(id = A, source = 1L, chapterFlags = Manga.CHAPTER_SORTING_SOURCE)
        val all = listOf(
            chapter(id = 1, number = 1.0, mangaId = A, sourceOrder = 0),
            chapter(id = 2, number = 2.0, mangaId = A, sourceOrder = 5),
        )

        ids(initial = 1, main = bySource, all = all, owners = mapOf(A to bySource)) shouldBe listOf(2L, 1L)
    }

    private companion object {
        const val A = 7L
        const val B = 8L
    }
}

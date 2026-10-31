package com.shinsaikou.reader.state

import com.shinsaikou.source.SourceIds
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

class ReaderSessionTest {

    private val all = listOf(
        chapter(id = 1, number = 1.0, read = true),
        chapter(id = 2, number = 2.0),
        chapter(id = 3, number = 3.0, bookmark = true),
        chapter(id = 4, number = 4.0),
    )

    private val none = SessionFilters(skipRead = false, skipFiltered = false, skipDupe = false)

    // ---- Step 1, tombstones ----

    @Test
    fun `a deleted chapter is not in the session`() {
        val withTombstone = all + chapter(id = 5, number = 5.0, deletedAt = 1_000L)
        ids(build(all = withTombstone, initial = 2)) shouldBe listOf(1L, 2L, 3L, 4L)
    }

    // ---- Step 2, skip read ----

    @Test
    fun `skip read drops read chapters`() {
        val session = build(initial = 2, prefs = none.copy(skipRead = true))
        ids(session) shouldBe listOf(2L, 3L, 4L)
    }

    @Test
    fun `skip read never drops the chapter that was tapped`() {
        val session = build(initial = 1, prefs = none.copy(skipRead = true))
        ids(session) shouldBe listOf(1L, 2L, 3L, 4L)
    }

    // ---- Step 3, the manga's own filters ----

    @Test
    fun `the unread filter drops read chapters`() {
        val session = build(
            manga = manga(chapterFlags = Manga.CHAPTER_SORTING_NUMBER or Manga.CHAPTER_SHOW_UNREAD),
            initial = 2,
            prefs = none.copy(skipFiltered = true),
        )
        ids(session) shouldBe listOf(2L, 3L, 4L)
    }

    @Test
    fun `the bookmarked filter keeps only bookmarks, and the tapped chapter`() {
        val session = build(
            manga = manga(chapterFlags = Manga.CHAPTER_SORTING_NUMBER or Manga.CHAPTER_SHOW_BOOKMARKED),
            initial = 2,
            prefs = none.copy(skipFiltered = true),
        )
        ids(session) shouldBe listOf(2L, 3L)
    }

    /**
     * Ruling R45 hid this filter until downloads existed; this is its sixth site. The set is what
     * `DownloadedChapters` answers, so a chapter whose folder is gone is already absent from it.
     */
    @Test
    fun `the downloaded filter keeps downloaded chapters, and the tapped chapter`() {
        val session = build(
            manga = manga(chapterFlags = Manga.CHAPTER_SORTING_NUMBER or Manga.CHAPTER_SHOW_DOWNLOADED),
            initial = 2,
            prefs = none.copy(skipFiltered = true),
            downloaded = setOf(3L, 4L),
        )
        ids(session) shouldBe listOf(2L, 3L, 4L)
    }

    @Test
    fun `the not downloaded filter drops downloaded chapters`() {
        val session = build(
            manga = manga(chapterFlags = Manga.CHAPTER_SORTING_NUMBER or Manga.CHAPTER_SHOW_NOT_DOWNLOADED),
            initial = 2,
            prefs = none.copy(skipFiltered = true),
            downloaded = setOf(3L, 4L),
        )
        ids(session) shouldBe listOf(1L, 2L)
    }

    @Test
    fun `the downloaded filter reads nothing unless skip filtered is on`() {
        val session = build(
            manga = manga(chapterFlags = Manga.CHAPTER_SORTING_NUMBER or Manga.CHAPTER_SHOW_DOWNLOADED),
            initial = 2,
            downloaded = emptySet(),
        )
        ids(session) shouldBe listOf(1L, 2L, 3L, 4L)
    }

    /** As the chapter list and the library count it: a Local chapter is on disk by definition. */
    @Test
    fun `every chapter of a local entry counts as downloaded`() {
        val local = manga(chapterFlags = Manga.CHAPTER_SORTING_NUMBER or Manga.CHAPTER_SHOW_DOWNLOADED)
            .copy(source = SourceIds.LOCAL)
        val session = build(manga = local, initial = 2, prefs = none.copy(skipFiltered = true))
        ids(session) shouldBe listOf(1L, 2L, 3L, 4L)
    }

    // ---- Step 4, the sort ----

    @Test
    fun `the session is sorted ascending whatever the manga's own sort direction is`() {
        val shuffled = listOf(all[3], all[0], all[2], all[1])
        val descending = manga(chapterFlags = Manga.CHAPTER_SORTING_NUMBER or Manga.CHAPTER_SORT_DESC)
        val ascending = manga(chapterFlags = Manga.CHAPTER_SORTING_NUMBER or Manga.CHAPTER_SORT_ASC)
        ids(build(manga = descending, all = shuffled, initial = 2)) shouldBe listOf(1L, 2L, 3L, 4L)
        ids(build(manga = ascending, all = shuffled, initial = 2)) shouldBe listOf(1L, 2L, 3L, 4L)
    }

    @Test
    fun `source order sorting reads oldest first, which is the reverse of the stored order`() {
        // getChapterSort inverts its flag on this branch: source order is the source's newest-first
        // listing, so the reader's ascending order is descending sourceOrder.
        val bySource = manga(chapterFlags = Manga.CHAPTER_SORTING_SOURCE)
        ids(build(manga = bySource, initial = 2)) shouldBe listOf(4L, 3L, 2L, 1L)
    }

    // ---- Step 5, duplicates ----

    @Test
    fun `skip dupe keeps one chapter per recognised number`() {
        val duplicated = all + chapter(id = 9, number = 2.0, sourceOrder = 9)
        val session = build(all = duplicated, initial = 2, prefs = none.copy(skipDupe = true))
        ids(session) shouldBe listOf(1L, 2L, 3L, 4L)
    }

    @Test
    fun `skip dupe keeps the chapter that was tapped over its duplicate`() {
        val duplicated = all + chapter(id = 9, number = 2.0, sourceOrder = 9)
        val session = build(all = duplicated, initial = 9, prefs = none.copy(skipDupe = true))
        ids(session) shouldBe listOf(1L, 9L, 3L, 4L)
    }

    @Test
    fun `skip dupe prefers the scanlator of the chapter that was tapped`() {
        val duplicated = listOf(
            chapter(id = 1, number = 1.0, scanlator = "a", sourceOrder = 1),
            chapter(id = 2, number = 2.0, scanlator = "a", sourceOrder = 2),
            chapter(id = 3, number = 2.0, scanlator = "b", sourceOrder = 3),
        )
        val session = build(all = duplicated, initial = 1, prefs = none.copy(skipDupe = true))
        ids(session) shouldBe listOf(1L, 2L)
    }

    @Test
    fun `skip dupe with a ranking keeps the ranked group's copy over the tapped chapter's group`() {
        val duplicated = listOf(
            chapter(id = 1, number = 1.0, scanlator = "a", sourceOrder = 1),
            chapter(id = 2, number = 2.0, scanlator = "a", sourceOrder = 2),
            chapter(id = 3, number = 2.0, scanlator = "b", sourceOrder = 3),
        )
        val session = build(
            all = duplicated,
            initial = 1,
            prefs = none.copy(skipDupe = true),
            ranking = mapOf("b" to 0L),
        )
        ids(session) shouldBe listOf(1L, 3L)
    }

    @Test
    fun `the ranking is not read without skip dupe`() {
        val duplicated = all + chapter(id = 9, number = 2.0, scanlator = "b", sourceOrder = 9)
        val session = build(all = duplicated, initial = 2, ranking = mapOf("b" to 0L))
        ids(session) shouldBe listOf(1L, 2L, 9L, 3L, 4L)
    }

    // ---- All three filters at once, the one rule upstream gets wrong ----

    @Test
    fun `the tapped chapter survives every filter at once`() {
        val duplicated = all + chapter(id = 9, number = 1.0, read = true, sourceOrder = 9)
        val session = build(
            manga = manga(chapterFlags = Manga.CHAPTER_SORTING_NUMBER or Manga.CHAPTER_SHOW_UNREAD),
            all = duplicated,
            initial = 9,
            prefs = SessionFilters(skipRead = true, skipFiltered = true, skipDupe = true),
        )
        ids(session) shouldBe listOf(9L, 2L, 3L, 4L)
    }

    @Test
    fun `a chapter that is not in the list is a programmer error`() {
        shouldThrow<IllegalStateException> { build(initial = 99) }
    }

    // ---- Navigation ----

    @Test
    fun `previous and next walk the session and stop at both ends`() {
        val session = build(initial = 1)
        val first = session.chapters.first()
        val last = session.chapters.last()
        session.previous(first) shouldBe null
        session.next(first) shouldBe session.chapters[1]
        session.next(last) shouldBe null
        session.previous(last) shouldBe session.chapters[2]
    }

    @Test
    fun `a chapter that is not in the session has no neighbours`() {
        val session = build(initial = 1)
        val stranger = waitingChapter(42)
        session.indexOf(stranger) shouldBe -1
        session.previous(stranger) shouldBe null
        session.next(stranger) shouldBe null
    }

    // ---- Delete after reading (phase 3 unit DR) ----

    @Test
    fun `read back names the chapter and the four before it, nearest first`() {
        val seven = (1L..7L).map { chapter(id = it) }
        val session = build(all = seven, initial = 1)
        session.readBack(session.chapterFor(6)) shouldBe listOf(6L, 5L, 4L, 3L, 2L)
    }

    @Test
    fun `read back near the start holds only the chapters there are`() {
        val session = build(initial = 1)
        session.readBack(session.chapterFor(2)) shouldBe listOf(2L, 1L)
        session.readBack(session.chapterFor(1)) shouldBe listOf(1L)
    }

    /**
     * Upstream counts slots over its reader's `chapterList`, so a chapter skip read or skip dupe left
     * out is not a slot. Opened on 2 with both on, the session is 2, 4, 5 and 6: from 6 the slots are
     * 6, 5, 4 and 2, and the read chapter 3 and the second chapter 4 are not among them.
     */
    @Test
    fun `read back counts only the session's chapters under skip read and skip duplicates`() {
        val listed = listOf(
            chapter(id = 1, number = 1.0, read = true),
            chapter(id = 2, number = 2.0),
            chapter(id = 3, number = 3.0, read = true),
            chapter(id = 4, number = 4.0, sourceOrder = 4),
            chapter(id = 40, number = 4.0, sourceOrder = 40),
            chapter(id = 5, number = 5.0),
            chapter(id = 6, number = 6.0),
        )
        val session = build(all = listed, initial = 2, prefs = none.copy(skipRead = true, skipDupe = true))

        ids(session) shouldBe listOf(2L, 4L, 5L, 6L)
        session.readBack(session.chapterFor(6)) shouldBe listOf(6L, 5L, 4L, 2L)
    }

    @Test
    fun `a chapter that is not in the session reads back nothing`() {
        build(initial = 1).readBack(waitingChapter(42)) shouldBe emptyList()
    }

    private fun build(
        manga: Manga = manga(),
        all: List<tachiyomi.domain.chapter.model.Chapter> = this.all,
        initial: Long,
        prefs: SessionFilters = none,
        downloaded: Set<Long> = emptySet(),
        ranking: Map<String, Long> = emptyMap(),
    ) = ReaderSession.build(manga, all, initial, prefs, downloaded, ranking)

    private fun ids(session: ReaderSession) = session.chapters.map { it.chapter.id }
}

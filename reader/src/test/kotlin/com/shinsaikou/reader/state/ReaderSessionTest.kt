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

    /** The app's downloaded only mode, which upstream's reader applies whatever the entry's filters. */
    @Test
    fun `downloaded only keeps downloaded chapters, and the tapped chapter`() {
        val session = build(initial = 2, prefs = none.copy(downloadedOnly = true), downloaded = setOf(3L, 4L))
        ids(session) shouldBe listOf(2L, 3L, 4L)
    }

    /**
     * Group a's copy of number 2 is the one the duplicate pass prefers, and only group b's is
     * downloaded. Upstream's order, duplicates first, drops b's copy and then a's, and the number is
     * gone; filtering first keeps b's.
     */
    @Test
    fun `downloaded only runs before the duplicate pass, so a number keeps its downloaded copy`() {
        val duplicated = listOf(
            chapter(id = 1, number = 1.0, scanlator = "a", sourceOrder = 1),
            chapter(id = 2, number = 2.0, scanlator = "a", sourceOrder = 2),
            chapter(id = 3, number = 2.0, scanlator = "b", sourceOrder = 3),
        )
        val session = build(
            all = duplicated,
            initial = 1,
            prefs = none.copy(skipDupe = true, downloadedOnly = true),
            downloaded = setOf(1L, 3L),
        )
        ids(session) shouldBe listOf(1L, 3L)
    }

    @Test
    fun `downloaded only leaves a local entry whole`() {
        val local = manga().copy(source = SourceIds.LOCAL)
        val session = build(manga = local, initial = 2, prefs = none.copy(downloadedOnly = true))
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

    // ---- Merged entries, each chapter owned by its own entry (R163) ----

    private val online = manga(id = 7L, source = 1L)
    private val local = manga(id = 20L, source = SourceIds.LOCAL)
    private val owners = mapOf(online.id to online, local.id to local)

    /** Chapters 1 and 2 belong to the Local entry, 3 and 4 to the online one. */
    private val merged = listOf(
        chapter(id = 1, mangaId = local.id),
        chapter(id = 2, mangaId = local.id),
        chapter(id = 3, mangaId = online.id),
        chapter(id = 4, mangaId = online.id),
    )

    @Test
    fun `ownerOf answers the entry the chapter belongs to`() {
        val session = ReaderSession(online, emptyList(), owners)

        session.ownerOf(chapter(id = 1, mangaId = local.id)) shouldBe local
        session.ownerOf(chapter(id = 3, mangaId = online.id)) shouldBe online
    }

    @Test
    fun `ownerOf falls back to the session's manga for an entry that is not in owners`() {
        val session = ReaderSession(online, emptyList(), owners)

        session.ownerOf(chapter(id = 9, mangaId = 99L)) shouldBe online
    }

    @Test
    fun `entryIds is every entry of the session`() {
        ReaderSession(online, emptyList(), owners).entryIds shouldBe setOf(online.id, local.id)
    }

    @Test
    fun `with the default owners the session has one entry, its manga`() {
        val session = ReaderSession(online, emptyList())

        session.entryIds shouldBe setOf(online.id)
        session.ownerOf(chapter(id = 1, mangaId = online.id)) shouldBe online
    }

    @Test
    fun `build keeps the owners it was given`() {
        val session = build(manga = online, all = merged, initial = 3, owners = owners)

        session.entryIds shouldBe setOf(online.id, local.id)
        session.ownerOf(merged[0]) shouldBe local
    }

    @Test
    fun `downloaded only keeps a local owner's chapters and drops the online owner's that are not downloaded`() {
        val session = build(
            manga = online,
            all = merged,
            initial = 1,
            prefs = none.copy(downloadedOnly = true),
            owners = owners,
        )
        ids(session) shouldBe listOf(1L, 2L)
    }

    @Test
    fun `downloaded only keeps the online owner's downloaded chapters beside the local owner's`() {
        val session = build(
            manga = online,
            all = merged,
            initial = 1,
            prefs = none.copy(downloadedOnly = true),
            downloaded = setOf(4L),
            owners = owners,
        )
        ids(session) shouldBe listOf(1L, 2L, 4L)
    }

    @Test
    fun `downloaded only keeps the tapped chapter of the online owner`() {
        val session = build(
            manga = online,
            all = merged,
            initial = 4,
            prefs = none.copy(downloadedOnly = true),
            owners = owners,
        )
        ids(session) shouldBe listOf(1L, 2L, 4L)
    }

    @Test
    fun `the downloaded filter counts a local owner's chapter as downloaded`() {
        val filtered = online.copy(chapterFlags = Manga.CHAPTER_SORTING_NUMBER or Manga.CHAPTER_SHOW_DOWNLOADED)
        val session = build(
            manga = filtered,
            all = merged,
            initial = 4,
            prefs = none.copy(skipFiltered = true),
            owners = mapOf(filtered.id to filtered, local.id to local),
        )
        // 3 is online and not downloaded, so it goes; 1 and 2 are Local; 4 is the tapped chapter.
        ids(session) shouldBe listOf(1L, 2L, 4L)
    }

    @Test
    fun `the not downloaded filter drops a local owner's chapters`() {
        val filtered = online.copy(chapterFlags = Manga.CHAPTER_SORTING_NUMBER or Manga.CHAPTER_SHOW_NOT_DOWNLOADED)
        val session = build(
            manga = filtered,
            all = merged,
            initial = 3,
            prefs = none.copy(skipFiltered = true),
            owners = mapOf(filtered.id to filtered, local.id to local),
        )
        ids(session) shouldBe listOf(3L, 4L)
    }

    private fun build(
        manga: Manga = manga(),
        all: List<tachiyomi.domain.chapter.model.Chapter> = this.all,
        initial: Long,
        prefs: SessionFilters = none,
        downloaded: Set<Long> = emptySet(),
        ranking: Map<String, Long> = emptyMap(),
        owners: Map<Long, Manga> = mapOf(manga.id to manga),
    ) = ReaderSession.build(manga, all, initial, prefs, downloaded, ranking, owners)

    private fun ids(session: ReaderSession) = session.chapters.map { it.chapter.id }
}

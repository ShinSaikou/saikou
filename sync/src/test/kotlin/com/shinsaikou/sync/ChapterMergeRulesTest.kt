package com.shinsaikou.sync

import com.shinsaikou.sync.MergeFixtures.chapter
import com.shinsaikou.sync.MergeFixtures.note
import com.shinsaikou.sync.MergeFixtures.track
import com.shinsaikou.sync.format.ChapterKey
import com.shinsaikou.sync.merge.ChapterRecord
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The chapter rows of Q6's table, and the row for one tracker bound on both sides, each written
 * from the row's wording and each tie checked both ways round.
 */
class ChapterMergeRulesTest {

    private val engine = MergeEngine()

    /** Merges both ways round and checks the two stored rows agree. */
    private fun both(local: ChapterRecord, remote: ChapterRecord) =
        engine.mergeChapter(local, remote).also { engine.mergeChapter(remote, local).value shouldBe it.value }

    // Row: Chapter read, bookmark | none | OR.

    @Test
    fun `read and bookmark are each the OR of the two sides`() {
        val read = chapter.copy(read = true)
        val bookmarked = chapter.copy(bookmark = true)

        val merged = both(read, bookmarked)

        merged.value.read shouldBe true
        merged.value.bookmark shouldBe true
        merged.fieldsFromRemote shouldBe setOf("bookmark")
    }

    @Test
    fun `an unread does not travel, which is the row's known limit`() {
        both(chapter.copy(read = false, version = 9L), chapter.copy(read = true)).value.read shouldBe true
    }

    // Row: Chapter last page read | none | max, with no reset to zero.

    @Test
    fun `the last page read is the max, even when both sides read the chapter`() {
        val merged = both(chapter.copy(read = true, lastPageRead = 12L), chapter.copy(read = true, lastPageRead = 30L))

        merged.value.lastPageRead shouldBe 30L
    }

    // Row: Chapter dateFetch | none | the earliest non-zero value.

    @Test
    fun `the fetch date is the earliest non-zero value`() {
        both(chapter.copy(dateFetch = 500L), chapter.copy(dateFetch = 300L)).value.dateFetch shouldBe 300L
        both(chapter.copy(dateFetch = 500L), chapter.copy(dateFetch = 0L)).value.dateFetch shouldBe 500L
        both(chapter, chapter).value.dateFetch shouldBe 0L
    }

    // Row: Chapter source values: name, scanlator, number, upload date, volume | version | higher
    // version; tie: encoding order.

    @Test
    fun `the higher version takes every source value`() {
        val remote = chapter.copy(
            version = 2L, name = "Renamed", scanlator = "group", chapterNumber = 1.5, dateUpload = 99L, volume = 3.0,
        )

        val merged = both(chapter.copy(version = 1L), remote)

        merged.value shouldBe remote
        merged.fieldsFromRemote shouldBe setOf("name", "scanlator", "chapterNumber", "dateUpload", "volume")
    }

    @Test
    fun `equal versions keep one side's source values whichever side is local`() {
        val a = chapter.copy(version = 4L, name = "A", volume = 1.0)
        val b = chapter.copy(version = 4L, name = "B", volume = null)

        val merged = both(a, b).value
        setOf(a.name to a.volume, b.name to b.volume).contains(merged.name to merged.volume) shouldBe true
    }

    @Test
    fun `page commits raise the version but never decide progress`() {
        val merged = both(chapter.copy(version = 60L, lastPageRead = 3L), chapter.copy(version = 1L, read = true))

        merged.value.read shouldBe true
        merged.value.lastPageRead shouldBe 3L
        merged.value.version shouldBe 60L
    }

    // Row: Chapter tombstone | deleted_at; restored_at | deleted while the newest deleted_at is newer
    // than the newest restored_at.

    @Test
    fun `a restore newer than the tombstone keeps the chapter`() {
        val deleted = chapter.copy(deletedAt = 100L)
        val restored = chapter.copy(restoredAt = 200L)

        val merged = both(deleted, restored).value

        merged.deletedAt shouldBe null
        merged.restoredAt shouldBe 200L
    }

    @Test
    fun `a tombstone newer than the restore deletes the chapter`() {
        val merged = both(chapter.copy(deletedAt = 300L), chapter.copy(restoredAt = 200L)).value

        merged.deletedAt shouldBe 300L
        merged.restoredAt shouldBe 200L
    }

    @Test
    fun `a tombstone and a restore at the same time leave the chapter live`() {
        both(chapter.copy(deletedAt = 200L), chapter.copy(restoredAt = 200L)).value.deletedAt shouldBe null
    }

    // Row: Bookmark notes | bookmark_notes_modified_at | the whole note set from the newer clock; tie:
    // union, and for one page the longer text, then encoding order.

    @Test
    fun `the newer notes clock takes the whole note set, so a removed note travels`() {
        val removed = chapter.copy(bookmarkNotes = emptyMap(), bookmarkNotesModifiedAt = 300L)
        val stale = chapter.copy(bookmarkNotes = mapOf(4L to note("x")), bookmarkNotesModifiedAt = 100L)

        both(stale, removed).value.bookmarkNotes shouldBe emptyMap()
    }

    @Test
    fun `equal notes clocks keep every page and the longer text for a page noted on both sides`() {
        val a = chapter.copy(
            bookmarkNotes = mapOf(1L to note("short"), 2L to note("two")),
            bookmarkNotesModifiedAt = 7L,
        )
        val b = chapter.copy(
            bookmarkNotes = mapOf(1L to note("much longer"), 3L to note("three")),
            bookmarkNotesModifiedAt = 7L,
        )

        both(a, b).value.bookmarkNotes shouldBe mapOf(1L to note("much longer"), 2L to note("two"), 3L to note("three"))
    }

    @Test
    fun `two notes of one length on one page keep one of them whichever side is local`() {
        val a = chapter.copy(bookmarkNotes = mapOf(1L to note("abc")), bookmarkNotesModifiedAt = 7L)
        val b = chapter.copy(bookmarkNotes = mapOf(1L to note("xyz")), bookmarkNotesModifiedAt = 7L)

        both(a, b)
    }

    @Test
    fun `a note survives the other side's page commits`() {
        val noted = chapter.copy(
            bookmarkNotes = mapOf(12L to note("see this")),
            bookmarkNotesModifiedAt = 50L,
            version = 1L,
        )
        val paged = chapter.copy(lastPageRead = 30L, version = 7L)

        val merged = both(paged, noted).value

        merged.bookmarkNotes shouldBe mapOf(12L to note("see this"))
        merged.lastPageRead shouldBe 30L
    }

    // Row: One tracker's fields, bound on both sides | none | lastChapterRead by max; every other
    // field, totalChapters and remoteUrl included, from the side with the higher lastChapterRead;
    // tie: encoding order.

    @Test
    fun `the tracker side that read further gives every field`() {
        val behind = track.copy(lastChapterRead = 3.0, score = 9.0, totalChapters = 10L, remoteUrl = "/a")
        val ahead = track.copy(lastChapterRead = 5.0, score = 7.0, totalChapters = 12L, remoteUrl = "/b", status = 2L)

        engine.mergeTrack(behind, ahead).value shouldBe ahead
        engine.mergeTrack(ahead, behind).value shouldBe ahead
        engine.mergeTrack(behind, ahead).fieldsFromRemote shouldBe
            setOf("lastChapterRead", "score", "totalChapters", "remoteUrl", "status")
    }

    @Test
    fun `equal tracker progress keeps one side's fields whichever side is local`() {
        val a = track.copy(lastChapterRead = 5.0, score = 8.0)
        val b = track.copy(lastChapterRead = 5.0, score = 7.0)

        engine.mergeTrack(a, b).value shouldBe engine.mergeTrack(b, a).value
    }

    @Test
    fun `two different chapters are never merged`() {
        shouldThrow<IllegalArgumentException> {
            engine.mergeChapter(chapter, chapter.copy(key = ChapterKey(chapter.key.manga, "/other")))
        }
    }
}

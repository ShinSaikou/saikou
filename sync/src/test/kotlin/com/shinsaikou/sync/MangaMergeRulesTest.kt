package com.shinsaikou.sync

import com.shinsaikou.source.model.UpdateStrategy
import com.shinsaikou.sync.MergeFixtures.manga
import com.shinsaikou.sync.MergeFixtures.track
import com.shinsaikou.sync.format.MangaKey
import com.shinsaikou.sync.merge.MangaRecord
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

/**
 * The manga rows of Q6's table in plans/phase3-start.md, one group of cases per row, each written
 * from the row's wording. A tie is always checked both ways round, because a rule that keeps the
 * local side on a tie passes any one-sided case.
 */
class MangaMergeRulesTest {

    private val engine = MergeEngine()

    /** Merges both ways round and checks the two stored rows agree. */
    private fun both(local: MangaRecord, remote: MangaRecord) =
        engine.mergeManga(local, remote).also { engine.mergeManga(remote, local).value shouldBe it.value }

    // Row: Manga favourite and date_added | favorite_modified_at | newer clock; tie: favourite OR,
    // date_added the earliest non-zero.

    @Test
    fun `the newer favourite clock takes the favourite and the add date, so a removal travels`() {
        val local = manga.copy(favorite = true, dateAdded = 100L, favoriteModifiedAt = 100L)
        val remote = manga.copy(favorite = false, dateAdded = 0L, favoriteModifiedAt = 200L)

        val merged = both(local, remote)

        merged.value.favorite shouldBe false
        merged.value.dateAdded shouldBe 0L
        merged.value.favoriteModifiedAt shouldBe 200L
        merged.fieldsFromRemote shouldBe setOf("favorite", "dateAdded")
    }

    @Test
    fun `a favourite clock tie keeps the entry in the library and names the favourite it pulled in`() {
        val local = manga.copy(favorite = false, dateAdded = 0L)
        val remote = manga.copy(favorite = true, dateAdded = 300L)

        val merged = both(local, remote)

        merged.value.favorite shouldBe true
        merged.value.dateAdded shouldBe 300L
        merged.fieldsFromRemote shouldBe setOf("favorite", "dateAdded")
    }

    @Test
    fun `a favourite clock tie keeps the earliest non-zero add date`() {
        val local = manga.copy(favorite = true, dateAdded = 500L, favoriteModifiedAt = 50L)
        val remote = manga.copy(favorite = true, dateAdded = 400L, favoriteModifiedAt = 50L)

        both(local, remote).value.dateAdded shouldBe 400L
        both(local, remote.copy(dateAdded = 0L)).value.dateAdded shouldBe 500L
    }

    // Row: Manga tombstone | deleted_at, restored_at | as chapters: deleted while the newest
    // deleted_at is newer than the newest restored_at (the coordinator's ruling on Q6's "as shipped").

    @Test
    fun `a restore newer than the tombstone keeps the entry live`() {
        val merged = both(manga.copy(deletedAt = 100L), manga.copy(restoredAt = 200L)).value

        merged.deletedAt shouldBe null
        merged.restoredAt shouldBe 200L
    }

    @Test
    fun `a tombstone newer than the restore stands, and both clocks are stored`() {
        val merged = both(manga.copy(deletedAt = 300L, restoredAt = 100L), manga.copy(restoredAt = 200L))

        merged.value.deletedAt shouldBe 300L
        merged.value.restoredAt shouldBe 200L
        both(manga.copy(deletedAt = 100L), manga.copy(deletedAt = 150L)).value.deletedAt shouldBe 150L
    }

    @Test
    fun `a tombstone and a restore at the same time leave the entry live`() {
        both(manga.copy(deletedAt = 200L), manga.copy(restoredAt = 200L)).value.deletedAt shouldBe null
    }

    @Test
    fun `the favourite clock no longer decides the tombstone, and the favourite still merges by it`() {
        val deleted = manga.copy(deletedAt = 100L, favoriteModifiedAt = 100L)
        val favouritedLater = manga.copy(favorite = true, favoriteModifiedAt = 200L)

        val merged = both(deleted, favouritedLater).value

        merged.deletedAt shouldBe 100L
        merged.favorite shouldBe true
        merged.favoriteModifiedAt shouldBe 200L
    }

    // Row: Manga source values, memo included | version | higher version; tie: encoding order. The
    // clause "an empty memo never replaces a non-empty one" is dropped by the coordinator's ruling.

    @Test
    fun `the higher version takes every source value`() {
        val local = manga.copy(version = 3L)
        val remote = manga.copy(
            version = 4L, title = "New", author = "A", artist = "B", description = "D",
            genre = listOf("x", "y"), status = 2L, thumbnailUrl = "/cover",
            updateStrategy = UpdateStrategy.ONLY_FETCH_ONCE,
            memo = JsonObject(mapOf("k" to JsonPrimitive(1))),
        )

        val merged = both(local, remote)

        merged.value shouldBe remote
        merged.fieldsFromRemote shouldBe setOf(
            "title", "author", "artist", "description", "genre", "status", "thumbnailUrl", "updateStrategy", "memo",
        )
    }

    @Test
    fun `equal versions keep the same source values whichever side is local`() {
        val a = manga.copy(version = 5L, title = "Alpha", author = "one")
        val b = manga.copy(version = 5L, title = "Beta", author = "two")

        val merged = both(a, b).value

        // One side's values as a whole, never a mix of the two.
        setOf(a.title to a.author, b.title to b.author) shouldContain (merged.title to merged.author)
    }

    @Test
    fun `the higher version takes the memo, an empty one included`() {
        val withMemo = manga.copy(version = 1L, memo = JsonObject(mapOf("k" to JsonPrimitive("v"))))
        val emptyNewer = manga.copy(version = 9L)

        both(withMemo, emptyNewer).value.memo shouldBe JsonObject(emptyMap())
        engine.mergeManga(withMemo, emptyNewer).fieldsFromRemote shouldContainAll setOf("memo")
        engine.mergeManga(emptyNewer, withMemo).fieldsFromRemote shouldBe emptySet()
    }

    @Test
    fun `equal versions keep one memo whichever side is local, an empty one included`() {
        val withMemo = manga.copy(version = 3L, memo = JsonObject(mapOf("k" to JsonPrimitive("v"))))
        val empty = manga.copy(version = 3L)

        val kept = both(withMemo, empty).value.memo

        setOf(withMemo.memo, empty.memo) shouldContain kept
    }

    @Test
    fun `two non-empty memos follow the version`() {
        val older = manga.copy(version = 1L, memo = JsonObject(mapOf("k" to JsonPrimitive("old"))))
        val newer = manga.copy(version = 2L, memo = JsonObject(mapOf("k" to JsonPrimitive("new"))))

        both(older, newer).value.memo shouldBe newer.memo
    }

    @Test
    fun `a higher version that brings no different value names no field`() {
        val merged = engine.mergeManga(manga.copy(version = 1L), manga.copy(version = 40L))

        merged.fieldsFromRemote shouldBe emptySet()
        merged.changed shouldBe true
        merged.value.version shouldBe 40L
    }

    // Rows: reading mode and chapter flags | display_modified_at; notes | notes_modified_at;
    // manual position | position_modified_at. Each: newer clock.

    @Test
    fun `reading mode and chapter flags follow the display clock, not the version`() {
        val edited = manga.copy(viewerFlags = 4L, chapterFlags = 0x100L, displayModifiedAt = 300L, version = 1L)
        val readMore = manga.copy(viewerFlags = 1L, chapterFlags = 0L, displayModifiedAt = 200L, version = 90L)

        val merged = both(readMore, edited)

        merged.value.viewerFlags shouldBe 4L
        merged.value.chapterFlags shouldBe 0x100L
        merged.value.displayModifiedAt shouldBe 300L
    }

    @Test
    fun `notes follow the notes clock`() {
        val edited = manga.copy(notes = "rewritten", notesModifiedAt = 300L, version = 1L)
        val refreshed = manga.copy(notes = "old", notesModifiedAt = 100L, version = 3L)

        both(refreshed, edited).value.notes shouldBe "rewritten"
        both(refreshed, edited.copy(notesModifiedAt = 50L)).value.notes shouldBe "old"
    }

    @Test
    fun `manual position follows the position clock`() {
        val moved = manga.copy(manualPosition = 7L, positionModifiedAt = 300L)
        val stale = manga.copy(manualPosition = 2L, positionModifiedAt = 100L, version = 50L)

        both(stale, moved).value.manualPosition shouldBe 7L
    }

    @Test
    fun `equal owner clocks keep one value whichever side is local`() {
        val a = manga.copy(notes = "a", notesModifiedAt = 10L, viewerFlags = 1L, displayModifiedAt = 10L)
        val b = manga.copy(notes = "b", notesModifiedAt = 10L, viewerFlags = 2L, displayModifiedAt = 10L)

        both(a, b)
    }

    // Row (phase 5 unit ED, R138 item 2): the title and description edit | details_modified_at |
    // newer clock, both overrides together; tie: encoding order. The source values under them keep
    // following the version.

    @Test
    fun `an edit beats a source refresh, and the refresh's source values still take the version`() {
        val edited = manga.copy(titleOverride = "Edited", descriptionOverride = "Edited text", detailsModifiedAt = 300L)
        val refreshed = manga.copy(title = "Renamed", description = "New text", version = 9L)

        val merged = both(edited, refreshed)

        merged.value.titleOverride shouldBe "Edited"
        merged.value.descriptionOverride shouldBe "Edited text"
        merged.value.detailsModifiedAt shouldBe 300L
        merged.value.title shouldBe "Renamed"
        merged.value.description shouldBe "New text"
        merged.fieldsFromRemote shouldBe setOf("title", "description")
    }

    @Test
    fun `the newer details clock takes both overrides, a revert to none included`() {
        val older = manga.copy(titleOverride = "Older", descriptionOverride = "Older text", detailsModifiedAt = 100L)
        val reverted = manga.copy(titleOverride = null, descriptionOverride = null, detailsModifiedAt = 200L)

        val merged = both(older, reverted)

        merged.value.titleOverride shouldBe null
        merged.value.descriptionOverride shouldBe null
        merged.value.detailsModifiedAt shouldBe 200L
        merged.fieldsFromRemote shouldBe setOf("titleOverride", "descriptionOverride")
    }

    @Test
    fun `a row never edited, as a restore or a format 1 line leaves it, loses to any edit`() {
        val edited = manga.copy(titleOverride = "Edited", detailsModifiedAt = 1L)

        both(manga, edited).value.titleOverride shouldBe "Edited"
    }

    @Test
    fun `equal details clocks keep one pair whichever side is local`() {
        val a = manga.copy(titleOverride = "a", descriptionOverride = null, detailsModifiedAt = 10L)
        val b = manga.copy(titleOverride = null, descriptionOverride = "b", detailsModifiedAt = 10L)

        // One side's pair whole, never a title from one and a description from the other.
        val merged = both(a, b).value
        (merged.titleOverride to merged.descriptionOverride) shouldBeIn listOf("a" to null, null to "b")
    }

    // Row: Manga categories | categories_modified_at | the whole set of Q7 keys from the newer clock;
    // tie: union.

    @Test
    fun `the newer categories clock takes its whole set, so a move travels`() {
        val moved = manga.copy(categories = setOf("finished"), categoriesModifiedAt = 300L, version = 1L)
        val read = manga.copy(categories = setOf("reading"), categoriesModifiedAt = 100L, version = 80L)

        val merged = engine.mergeManga(read, moved)

        merged.value.categories shouldBe setOf("finished")
        merged.fieldsFromRemote shouldContainAll setOf("categories")
    }

    @Test
    fun `equal categories clocks take the union`() {
        val a = manga.copy(categories = setOf("a", "b"), categoriesModifiedAt = 5L)
        val b = manga.copy(categories = setOf("c"), categoriesModifiedAt = 5L)

        both(a, b).value.categories shouldBe setOf("a", "b", "c")
    }

    @Test
    fun `categories compare as a set, so row order alone is no change`() {
        val merged = engine.mergeMangaCategories(linkedSetOf("a", "b"), linkedSetOf("b", "a"), 5L, 9L)

        merged.changed shouldBe false
        merged.fieldsFromRemote shouldBe emptySet()
    }

    // Row: Excluded scanlators | exclusions_modified_at | the whole set from the newer clock; tie: union.

    @Test
    fun `the newer exclusions clock takes its whole set, so an un-exclusion travels`() {
        val unexcluded = manga.copy(excludedScanlators = emptySet(), exclusionsModifiedAt = 300L)
        val stale = manga.copy(excludedScanlators = setOf("group"), exclusionsModifiedAt = 100L)

        both(stale, unexcluded).value.excludedScanlators shouldBe emptySet()
        both(stale, unexcluded.copy(exclusionsModifiedAt = 100L)).value.excludedScanlators shouldBe setOf("group")
    }

    // Row: Scanlator priority | priority_modified_at | the whole list from the newer clock; tie:
    // encoding order.

    @Test
    fun `the newer priority clock takes its whole ranking`() {
        val reordered = manga.copy(scanlatorPriority = mapOf("b" to 0L, "a" to 1L), priorityModifiedAt = 300L)
        val stale = manga.copy(scanlatorPriority = mapOf("a" to 0L, "b" to 1L, "c" to 2L), priorityModifiedAt = 100L)

        both(stale, reordered).value.scanlatorPriority shouldBe mapOf("b" to 0L, "a" to 1L)
    }

    @Test
    fun `equal priority clocks keep one ranking whichever side is local`() {
        val a = manga.copy(scanlatorPriority = mapOf("a" to 0L), priorityModifiedAt = 1L)
        val b = manga.copy(scanlatorPriority = mapOf("b" to 0L), priorityModifiedAt = 1L)

        val kept = both(a, b).value.scanlatorPriority
        (kept == a.scanlatorPriority || kept == b.scanlatorPriority) shouldBe true
    }

    // Row: Tracker set | trackers_modified_at | the whole set from the newer clock; tie: union.

    @Test
    fun `the newer trackers clock takes its whole set, so an unbind travels`() {
        val unbound = manga.copy(tracks = emptyMap(), trackersModifiedAt = 300L)
        val bound = manga.copy(tracks = mapOf(1L to track), trackersModifiedAt = 100L, version = 70L)

        both(bound, unbound).value.tracks shouldBe emptyMap()
    }

    @Test
    fun `equal trackers clocks keep every binding and merge one bound on both sides`() {
        val a = manga.copy(
            tracks = mapOf(1L to track.copy(lastChapterRead = 3.0), 2L to track),
            trackersModifiedAt = 5L,
        )
        val b = manga.copy(
            tracks = mapOf(1L to track.copy(lastChapterRead = 8.0, score = 9.0)),
            trackersModifiedAt = 5L,
        )

        val tracks = both(a, b).value.tracks

        tracks.keys shouldBe setOf(1L, 2L)
        tracks.getValue(1L).lastChapterRead shouldBe 8.0
        tracks.getValue(1L).score shouldBe 9.0
    }

    // Keys.

    @Test
    fun `two different manga are never merged`() {
        shouldThrow<IllegalArgumentException> {
            engine.mergeManga(manga, manga.copy(key = MangaKey(source = 2L, url = "/manga/1")))
        }
    }

    @Test
    fun `merging a row with itself changes nothing`() {
        val row = manga.copy(favorite = true, categories = setOf("a"), tracks = mapOf(1L to track))

        val merged = engine.mergeManga(row, row)

        merged.changed shouldBe false
        merged.fieldsFromRemote shouldBe emptySet()
    }
}

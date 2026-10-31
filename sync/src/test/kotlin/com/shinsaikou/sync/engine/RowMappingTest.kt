package com.shinsaikou.sync.engine

import com.shinsaikou.domain.sync.model.StoredCategory
import com.shinsaikou.domain.sync.model.StoredChapter
import com.shinsaikou.domain.sync.model.StoredHistory
import com.shinsaikou.domain.sync.model.StoredManga
import com.shinsaikou.domain.sync.model.StoredNote
import com.shinsaikou.domain.sync.model.StoredTrack
import com.shinsaikou.source.model.UpdateStrategy
import com.shinsaikou.sync.format.ChapterKey
import com.shinsaikou.sync.format.MangaKey
import com.shinsaikou.sync.format.NoteEntry
import com.shinsaikou.sync.format.PriorityEntry
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

/**
 * The three shapes of a synced row map into each other and back with nothing lost (phase 3 unit
 * E2, R81 carry-over 3): every value and clock is set to something no default equals, so a field
 * the mapping drops or swaps changes the round trip.
 */
class RowMappingTest {

    private val manga = StoredManga(
        id = 7,
        source = 11,
        url = "/series/1",
        favorite = true,
        dateAdded = 101,
        favoriteModifiedAt = 102,
        deletedAt = 103,
        restoredAt = 104,
        version = 105,
        title = "Title",
        author = "Author",
        artist = "Artist",
        description = "Description",
        genre = listOf("One", "Two"),
        status = 2,
        thumbnailUrl = "/cover.jpg",
        updateStrategy = UpdateStrategy.ONLY_FETCH_ONCE,
        memo = JsonObject(mapOf("k" to JsonPrimitive("v"))),
        viewerFlags = 106,
        chapterFlags = 107,
        displayModifiedAt = 108,
        notes = "Notes",
        notesModifiedAt = 109,
        manualPosition = 110,
        positionModifiedAt = 111,
        titleOverride = "Edited title",
        descriptionOverride = "Edited description",
        detailsModifiedAt = 116,
        categoriesModifiedAt = 112,
        exclusionsModifiedAt = 113,
        priorityModifiedAt = 114,
        trackersModifiedAt = 115,
    )

    private val track = StoredTrack(
        trackerId = 2,
        remoteId = 201,
        libraryId = 202,
        title = "Remote",
        lastChapterRead = 3.5,
        totalChapters = 203,
        status = 4,
        score = 8.5,
        remoteUrl = "/remote/201",
        startDate = 204,
        finishDate = 205,
        private = true,
    )

    private val chapter = StoredChapter(
        id = 31,
        mangaId = 7,
        url = "/series/1/chapter/1",
        read = true,
        bookmark = true,
        lastPageRead = 301,
        dateFetch = 302,
        version = 303,
        name = "Chapter 1",
        scanlator = "Group",
        chapterNumber = 1.5,
        dateUpload = 304,
        volume = 2.0,
        sourceOrder = 305,
        deletedAt = 306,
        restoredAt = 307,
        bookmarkNotesModifiedAt = 308,
    )

    @Test
    fun `a manga and its four collections travel as five lines and come back whole`() {
        val tracks = listOf(track, track.copy(trackerId = 1, remoteId = 101))
        val record = manga.toRecord(
            categories = setOf("b", "a"),
            excludedScanlators = setOf("Group"),
            scanlatorPriority = mapOf("Second" to 2L, "First" to 1L),
            tracks = tracks,
        )

        val mangaRow = record.toRow()
        val categoriesRow = record.categoriesRow()
        val exclusionsRow = record.excludedScanlatorsRow()
        val priorityRow = record.scanlatorPriorityRow()
        val tracksRow = record.tracksRow()

        mangaRow.updateStrategy shouldBe 1L
        categoriesRow.categoriesModifiedAt shouldBe 112L
        exclusionsRow.exclusionsModifiedAt shouldBe 113L
        priorityRow.priorities shouldBe listOf(PriorityEntry("First", 1), PriorityEntry("Second", 2))
        priorityRow.priorityModifiedAt shouldBe 114L
        tracksRow.tracks.map { it.trackerId } shouldBe listOf(1L, 2L)
        tracksRow.trackersModifiedAt shouldBe 115L

        val back = mangaRow.toRecord(record.key).copy(
            categories = categoriesRow.categories,
            categoriesModifiedAt = categoriesRow.categoriesModifiedAt,
            excludedScanlators = exclusionsRow.scanlators,
            exclusionsModifiedAt = exclusionsRow.exclusionsModifiedAt,
            scanlatorPriority = priorityRow.toRanking(),
            priorityModifiedAt = priorityRow.priorityModifiedAt,
            tracks = tracksRow.toRecords(),
            trackersModifiedAt = tracksRow.trackersModifiedAt,
        )
        back shouldBe record
        back.toStored(manga.id) shouldBe manga
        back.tracks.toStoredTracks().sortedBy { it.trackerId } shouldBe tracks.sortedBy { it.trackerId }
    }

    @Test
    fun `a manga line alone carries no collection and no collection clock`() {
        val record = manga.toRecord(categories = setOf("a"), tracks = listOf(track))

        val fromLine = record.toRow().toRecord(record.key)

        fromLine.categories shouldBe emptySet()
        fromLine.tracks shouldBe emptyMap()
        fromLine.categoriesModifiedAt shouldBe null
        fromLine.trackersModifiedAt shouldBe null
        fromLine.favoriteModifiedAt shouldBe 102L
        fromLine.restoredAt shouldBe 104L
    }

    @Test
    fun `update strategy travels as the column's code and an unknown code reads as the default`() {
        UpdateStrategy.entries.forEach { strategy ->
            updateStrategyOf(strategy.code) shouldBe strategy
        }
        UpdateStrategy.ALWAYS_UPDATE.code shouldBe 0L
        UpdateStrategy.ONLY_FETCH_ONCE.code shouldBe 1L
        updateStrategyOf(7) shouldBe UpdateStrategy.ALWAYS_UPDATE
    }

    @Test
    fun `a chapter and its notes travel as two lines, source order on the chapter's`() {
        val key = MangaKey(11, "/series/1")
        val notes = listOf(StoredNote(4, "four", 401), StoredNote(2, "two", 201))
        val record = chapter.toRecord(key, notes)

        val row = record.toRow(chapter.sourceOrder)
        val notesRow = record.notesRow()

        row.key shouldBe ChapterKey(key, chapter.url)
        row.sourceOrder shouldBe 305L
        notesRow.notes shouldBe listOf(NoteEntry(2, "two", 201), NoteEntry(4, "four", 401))
        notesRow.bookmarkNotesModifiedAt shouldBe 308L

        val back = row.toRecord(row.key).copy(
            bookmarkNotes = notesRow.toRecords(),
            bookmarkNotesModifiedAt = notesRow.bookmarkNotesModifiedAt,
        )
        back shouldBe record
        back.toStored(chapter.id, chapter.mangaId, row.sourceOrder) shouldBe chapter
        back.bookmarkNotes.toStoredNotes().sortedBy { it.pageIndex } shouldBe notes.sortedBy { it.pageIndex }
    }

    @Test
    fun `history's last read and time read are the record's read at and read duration`() {
        val key = ChapterKey(MangaKey(11, "/series/1"), "/series/1/chapter/1")
        val stored = StoredHistory(chapterId = 31, lastRead = 501, timeRead = 502)

        val record = stored.toRecord(key)
        record.readAt shouldBe 501L
        record.readDuration shouldBe 502L

        val row = record.toRow()
        row.lastRead shouldBe 501L
        row.timeRead shouldBe 502L
        row.toRecord(key).toStored(31) shouldBe stored
    }

    @Test
    fun `a category travels by its key with its parent's key`() {
        val stored = StoredCategory(
            id = 5,
            syncKey = "child",
            name = "Child",
            order = 3,
            flags = 4,
            parentKey = "parent",
            modifiedAt = 601,
            deletedAt = 602,
            restoredAt = 603,
        )

        val row = stored.toRecord().toRow()

        row.key shouldBe "child"
        row.parent shouldBe "parent"
        row.toRecord().toStored(5) shouldBe stored
    }
}

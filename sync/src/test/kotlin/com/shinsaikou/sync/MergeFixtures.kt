package com.shinsaikou.sync

import com.shinsaikou.source.model.UpdateStrategy
import com.shinsaikou.sync.format.ChapterKey
import com.shinsaikou.sync.format.MangaKey
import com.shinsaikou.sync.merge.BookmarkNoteRecord
import com.shinsaikou.sync.merge.CategoryRecord
import com.shinsaikou.sync.merge.ChapterRecord
import com.shinsaikou.sync.merge.HistoryRecord
import com.shinsaikou.sync.merge.MangaRecord
import com.shinsaikou.sync.merge.TrackRecord
import kotlinx.serialization.json.JsonObject

/** Rows with every value at its column default, for tests that set only what a rule reads. */
internal object MergeFixtures {
    val MANGA_KEY = MangaKey(source = 1L, url = "/manga/1")
    val CHAPTER_KEY = ChapterKey(MANGA_KEY, url = "/manga/1/chapter/1")
    const val CATEGORY_KEY = "category-key"
    val GROUP_MAIN = MangaKey(source = 1L, url = "/m/a")

    val manga = MangaRecord(
        key = MANGA_KEY,
        favorite = false,
        dateAdded = 0L,
        favoriteModifiedAt = null,
        deletedAt = null,
        restoredAt = null,
        version = 0L,
        title = "Title",
        author = null,
        artist = null,
        description = null,
        genre = null,
        status = 0L,
        thumbnailUrl = null,
        updateStrategy = UpdateStrategy.ALWAYS_UPDATE,
        memo = JsonObject(emptyMap()),
        viewerFlags = 0L,
        chapterFlags = 0L,
        displayModifiedAt = null,
        notes = "",
        notesModifiedAt = null,
        manualPosition = 0L,
        positionModifiedAt = null,
        titleOverride = null,
        descriptionOverride = null,
        authorOverride = null,
        artistOverride = null,
        statusOverride = null,
        genreOverride = null,
        detailsModifiedAt = null,
        categories = emptySet(),
        categoriesModifiedAt = null,
        excludedScanlators = emptySet(),
        exclusionsModifiedAt = null,
        scanlatorPriority = emptyMap(),
        priorityModifiedAt = null,
        tracks = emptyMap(),
        trackersModifiedAt = null,
    )

    val chapter = ChapterRecord(
        key = CHAPTER_KEY,
        read = false,
        readModifiedAt = 0L,
        bookmark = false,
        bookmarkModifiedAt = 0L,
        lastPageRead = 0L,
        dateFetch = 0L,
        version = 0L,
        name = "Chapter 1",
        scanlator = null,
        chapterNumber = 1.0,
        dateUpload = 0L,
        volume = null,
        deletedAt = null,
        restoredAt = null,
        bookmarkNotes = emptyMap(),
        bookmarkNotesModifiedAt = null,
    )

    val category = CategoryRecord(
        syncKey = CATEGORY_KEY,
        name = "Reading",
        order = 0L,
        flags = 0L,
        parentKey = null,
        modifiedAt = null,
        deletedAt = null,
        restoredAt = null,
    )

    val history = HistoryRecord(chapter = CHAPTER_KEY, readAt = null, readDuration = 0L)

    val track = TrackRecord(
        remoteId = 10L,
        libraryId = null,
        title = "Remote title",
        lastChapterRead = 0.0,
        totalChapters = 0L,
        status = 1L,
        score = 0.0,
        remoteUrl = "/remote/10",
        startDate = 0L,
        finishDate = 0L,
        private = false,
    )

    fun note(text: String, dateAdded: Long = 1L) = BookmarkNoteRecord(note = text, dateAdded = dateAdded)
}

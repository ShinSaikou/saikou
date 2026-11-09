package com.shinsaikou.sync.format

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * One row of every kind with every field set to a value that is not its default, and the same rows
 * with every nullable field null: the tombstones, the clocks and the optional values.
 */
internal object SampleRows {

    const val DEVICE = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"

    /** A source id above 2^53, which a JSON number read as a double would round. */
    val manga = MangaKey(source = 9_007_199_254_740_993L, url = "/series/42")
    val chapter = ChapterKey(manga, "/series/42/chapter/7")

    val full: List<SyncRow> = listOf(
        CategoryRow(
            key = "c3f1",
            name = "Reading",
            order = 2,
            flags = 64,
            parent = null,
            modifiedAt = 1_700_000_000_001,
            deletedAt = null,
            restoredAt = null,
        ),
        CategoryRow(
            key = "9ab0",
            name = "Dropped",
            order = 3,
            flags = 0,
            parent = "c3f1",
            modifiedAt = 1_700_000_000_002,
            deletedAt = 1_700_000_000_003,
            restoredAt = 1_700_000_000_004,
        ),
        MangaRow(
            key = manga,
            favorite = true,
            dateAdded = 1_600_000_000_000,
            favoriteModifiedAt = 1_600_000_000_001,
            deletedAt = 1_600_000_000_002,
            restoredAt = 1_600_000_000_018,
            version = 17,
            title = "A title with \"quotes\", a tab\tand a line\nbreak",
            author = "Author",
            artist = "Artist",
            description = "Unicode: é中文 😀",
            genre = listOf("Action", "Drama"),
            status = 2,
            thumbnailUrl = "https://example.invalid/cover.jpg",
            updateStrategy = 1,
            memo = buildJsonObject { put("key", JsonPrimitive("value")) },
            viewer = 3,
            chapterFlags = 0x100,
            displayModifiedAt = 1_600_000_000_003,
            notes = "My notes",
            notesModifiedAt = 1_600_000_000_004,
            manualPosition = 5,
            positionModifiedAt = 1_600_000_000_005,
            titleOverride = "Edited title",
            descriptionOverride = "Edited description",
            detailsModifiedAt = 1_600_000_000_006,
            authorOverride = "Edited author",
            artistOverride = "Edited artist",
            statusOverride = 5,
            genreOverride = listOf("Comedy", "Mystery"),
        ),
        MangaCategoriesRow(manga, setOf("c3f1", "9ab0"), categoriesModifiedAt = 1_600_000_000_006),
        ChapterRow(
            key = chapter,
            read = true,
            bookmark = true,
            lastPageRead = 12,
            dateFetch = 1_600_000_000_007,
            version = 4,
            name = "Chapter 7",
            scanlator = "Group",
            chapterNumber = 7.5,
            dateUpload = 1_600_000_000_008,
            volume = 0.0,
            sourceOrder = 3,
            deletedAt = 1_600_000_000_009,
            restoredAt = 1_600_000_000_010,
            readModifiedAt = 1_600_000_000_019,
            bookmarkModifiedAt = 1_600_000_000_020,
        ),
        ChapterRemovedRow(ChapterKey(manga, "/series/42/chapter/8"), removedAt = 1_600_000_000_011),
        HistoryRow(chapter, lastRead = 1_600_000_000_012, timeRead = 345_000),
        TracksRow(
            manga,
            tracks = listOf(
                TrackEntry(
                    trackerId = 2,
                    remoteId = 4_000_000_000L,
                    libraryId = 77,
                    title = "Remote title",
                    lastChapterRead = 6.5,
                    totalChapters = 40,
                    status = 1,
                    score = 8.5,
                    remoteUrl = "https://example.invalid/track/1",
                    startDate = 1_500_000_000_000,
                    finishDate = 1_500_000_000_001,
                    private = true,
                ),
            ),
            trackersModifiedAt = 1_600_000_000_013,
        ),
        ExcludedScanlatorsRow(manga, setOf("Other group"), exclusionsModifiedAt = 1_600_000_000_014),
        ScanlatorPriorityRow(
            manga,
            priorities = listOf(PriorityEntry("Group", 0), PriorityEntry("Other group", 1)),
            priorityModifiedAt = 1_600_000_000_015,
        ),
        BookmarkNotesRow(
            chapter,
            notes = listOf(NoteEntry(pageIndex = 3, note = "Panel", dateAdded = 1_600_000_000_016)),
            bookmarkNotesModifiedAt = 1_600_000_000_017,
        ),
        MergedGroupRow(
            main = manga,
            members = listOf(
                MergedMemberEntry(manga, position = 1, checkUpdates = true),
                MergedMemberEntry(MangaKey(source = 5, url = "/other/42"), position = 0, checkUpdates = false),
            ),
            showAll = true,
            modifiedAt = 1_600_000_000_018,
        ),
    )

    /** [full] with every nullable field null and every collection empty. */
    val nulls: List<SyncRow> = full.map { row ->
        when (row) {
            is CategoryRow -> row.copy(parent = null, modifiedAt = null, deletedAt = null, restoredAt = null)
            is MangaRow -> row.copy(
                favoriteModifiedAt = null,
                deletedAt = null,
                restoredAt = null,
                author = null,
                artist = null,
                description = null,
                genre = null,
                thumbnailUrl = null,
                memo = buildJsonObject { },
                displayModifiedAt = null,
                notesModifiedAt = null,
                positionModifiedAt = null,
                titleOverride = null,
                descriptionOverride = null,
                detailsModifiedAt = null,
            )
            is MangaCategoriesRow -> row.copy(categories = emptySet(), categoriesModifiedAt = null)
            is ChapterRow -> row.copy(scanlator = null, volume = null, deletedAt = null, restoredAt = null)
            is ChapterRemovedRow -> row
            is HistoryRow -> row.copy(lastRead = null)
            is TracksRow -> row.copy(tracks = row.tracks.map { it.copy(libraryId = null) }, trackersModifiedAt = null)
            is ExcludedScanlatorsRow -> row.copy(scanlators = emptySet(), exclusionsModifiedAt = null)
            is ScanlatorPriorityRow -> row.copy(priorities = emptyList(), priorityModifiedAt = null)
            is BookmarkNotesRow -> row.copy(notes = emptyList(), bookmarkNotesModifiedAt = null)
            is MergedGroupRow -> row.copy(members = emptyList())
        }
    }

    fun counts(rows: List<SyncRow>): Map<RowKind, Int> = rows.groupingBy { it.kind }.eachCount()

    fun segmentHeader(rows: List<SyncRow>, afterSeq: Long = 10, lastSeq: Long = 20) = SyncHeader(
        format = SyncFormat.CURRENT,
        deviceId = DEVICE,
        scope = SyncScope.Segment(afterSeq, lastSeq, rawRows = (lastSeq - afterSeq).toInt()),
        counts = counts(rows),
    )

    fun encode(header: SyncHeader, rows: List<SyncRow>): ByteArray {
        val bytes = ByteArrayOutputStream()
        SegmentWriter(bytes, header).use { writer -> rows.forEach(writer::write) }
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): Pair<SyncHeader, List<SyncRow>> =
        SegmentReader(ByteArrayInputStream(bytes)).use { reader ->
            reader.header to generateSequence { reader.next() }.toList()
        }
}

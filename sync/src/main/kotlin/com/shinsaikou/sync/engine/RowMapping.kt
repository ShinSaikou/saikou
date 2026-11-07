package com.shinsaikou.sync.engine

import com.shinsaikou.domain.sync.model.StoredCategory
import com.shinsaikou.domain.sync.model.StoredChapter
import com.shinsaikou.domain.sync.model.StoredHistory
import com.shinsaikou.domain.sync.model.StoredManga
import com.shinsaikou.domain.sync.model.StoredMergedGroup
import com.shinsaikou.domain.sync.model.StoredMergedMember
import com.shinsaikou.domain.sync.model.StoredNote
import com.shinsaikou.domain.sync.model.StoredTrack
import com.shinsaikou.source.model.UpdateStrategy
import com.shinsaikou.sync.format.BookmarkNotesRow
import com.shinsaikou.sync.format.CategoryRow
import com.shinsaikou.sync.format.ChapterKey
import com.shinsaikou.sync.format.ChapterRow
import com.shinsaikou.sync.format.ExcludedScanlatorsRow
import com.shinsaikou.sync.format.HistoryRow
import com.shinsaikou.sync.format.MangaCategoriesRow
import com.shinsaikou.sync.format.MangaKey
import com.shinsaikou.sync.format.MangaRow
import com.shinsaikou.sync.format.MergedGroupRow
import com.shinsaikou.sync.format.MergedMemberEntry
import com.shinsaikou.sync.format.NoteEntry
import com.shinsaikou.sync.format.PriorityEntry
import com.shinsaikou.sync.format.ScanlatorPriorityRow
import com.shinsaikou.sync.format.TrackEntry
import com.shinsaikou.sync.format.TracksRow
import com.shinsaikou.sync.merge.BookmarkNoteRecord
import com.shinsaikou.sync.merge.CategoryRecord
import com.shinsaikou.sync.merge.ChapterRecord
import com.shinsaikou.sync.merge.HistoryRecord
import com.shinsaikou.sync.merge.MangaRecord
import com.shinsaikou.sync.merge.MergedGroupRecord
import com.shinsaikou.sync.merge.MergedMemberRecord
import com.shinsaikou.sync.merge.TrackRecord

/*
 * The three shapes of a synced row, and the one mapping between each pair (phase 3 unit E2): the
 * database's rows as `SyncStore` hands them over (`Stored*`, with local ids), MergeEngine's records
 * (collections nested in their parent, keyed by natural key) and the wire's rows (each collection a
 * line of its own, R81 item 3). A push maps stored to record to wire; a pull maps wire to record,
 * merges, and maps the result back to stored. The system category has no sync key and so is never a
 * stored category: it never travels (Q7).
 */

// Database rows and merge records.

internal val StoredManga.key: MangaKey get() = MangaKey(source, url)

/** The title the conflict report names the entry by: the owner's edit over the source's, as screens show it. */
internal val StoredManga.displayTitle: String get() = titleOverride ?: title

/** [this] with the given collections; a pull leaves them empty and merges each from its own line. */
internal fun StoredManga.toRecord(
    categories: Set<String> = emptySet(),
    excludedScanlators: Set<String> = emptySet(),
    scanlatorPriority: Map<String, Long> = emptyMap(),
    tracks: List<StoredTrack> = emptyList(),
): MangaRecord = MangaRecord(
    key = key,
    favorite = favorite,
    dateAdded = dateAdded,
    favoriteModifiedAt = favoriteModifiedAt,
    deletedAt = deletedAt,
    restoredAt = restoredAt,
    version = version,
    title = title,
    author = author,
    artist = artist,
    description = description,
    genre = genre,
    status = status,
    thumbnailUrl = thumbnailUrl,
    updateStrategy = updateStrategy,
    memo = memo,
    viewerFlags = viewerFlags,
    chapterFlags = chapterFlags,
    displayModifiedAt = displayModifiedAt,
    notes = notes,
    notesModifiedAt = notesModifiedAt,
    manualPosition = manualPosition,
    positionModifiedAt = positionModifiedAt,
    titleOverride = titleOverride,
    descriptionOverride = descriptionOverride,
    detailsModifiedAt = detailsModifiedAt,
    categories = categories,
    categoriesModifiedAt = categoriesModifiedAt,
    excludedScanlators = excludedScanlators,
    exclusionsModifiedAt = exclusionsModifiedAt,
    scanlatorPriority = scanlatorPriority,
    priorityModifiedAt = priorityModifiedAt,
    tracks = tracks.toTrackRecords(),
    trackersModifiedAt = trackersModifiedAt,
)

/** The row's values and every clock; the collections are written through their own calls. */
internal fun MangaRecord.toStored(id: Long): StoredManga = StoredManga(
    id = id,
    source = key.source,
    url = key.url,
    favorite = favorite,
    dateAdded = dateAdded,
    favoriteModifiedAt = favoriteModifiedAt,
    deletedAt = deletedAt,
    restoredAt = restoredAt,
    version = version,
    title = title,
    author = author,
    artist = artist,
    description = description,
    genre = genre,
    status = status,
    thumbnailUrl = thumbnailUrl,
    updateStrategy = updateStrategy,
    memo = memo,
    viewerFlags = viewerFlags,
    chapterFlags = chapterFlags,
    displayModifiedAt = displayModifiedAt,
    notes = notes,
    notesModifiedAt = notesModifiedAt,
    manualPosition = manualPosition,
    positionModifiedAt = positionModifiedAt,
    titleOverride = titleOverride,
    descriptionOverride = descriptionOverride,
    detailsModifiedAt = detailsModifiedAt,
    categoriesModifiedAt = categoriesModifiedAt,
    exclusionsModifiedAt = exclusionsModifiedAt,
    priorityModifiedAt = priorityModifiedAt,
    trackersModifiedAt = trackersModifiedAt,
)

internal fun List<StoredTrack>.toTrackRecords(): Map<Long, TrackRecord> = associate { track ->
    track.trackerId to TrackRecord(
        remoteId = track.remoteId,
        libraryId = track.libraryId,
        title = track.title,
        lastChapterRead = track.lastChapterRead,
        totalChapters = track.totalChapters,
        status = track.status,
        score = track.score,
        remoteUrl = track.remoteUrl,
        startDate = track.startDate,
        finishDate = track.finishDate,
        private = track.private,
    )
}

internal fun Map<Long, TrackRecord>.toStoredTracks(): List<StoredTrack> = map { (trackerId, track) ->
    StoredTrack(
        trackerId = trackerId,
        remoteId = track.remoteId,
        libraryId = track.libraryId,
        title = track.title,
        lastChapterRead = track.lastChapterRead,
        totalChapters = track.totalChapters,
        status = track.status,
        score = track.score,
        remoteUrl = track.remoteUrl,
        startDate = track.startDate,
        finishDate = track.finishDate,
        private = track.private,
    )
}

/** [this] keyed under [manga], with the given notes; a pull merges the notes from their own line. */
internal fun StoredChapter.toRecord(manga: MangaKey, notes: List<StoredNote> = emptyList()): ChapterRecord =
    ChapterRecord(
        key = ChapterKey(manga, url),
        read = read,
        bookmark = bookmark,
        lastPageRead = lastPageRead,
        dateFetch = dateFetch,
        version = version,
        name = name,
        scanlator = scanlator,
        chapterNumber = chapterNumber,
        dateUpload = dateUpload,
        volume = volume,
        deletedAt = deletedAt,
        restoredAt = restoredAt,
        bookmarkNotes = notes.toNoteRecords(),
        bookmarkNotesModifiedAt = bookmarkNotesModifiedAt,
    )

/** [sourceOrder] is no record's: no rule merges it, and an insert takes the wire's (R81 item 11). */
internal fun ChapterRecord.toStored(id: Long, mangaId: Long, sourceOrder: Long): StoredChapter = StoredChapter(
    id = id,
    mangaId = mangaId,
    url = key.url,
    read = read,
    bookmark = bookmark,
    lastPageRead = lastPageRead,
    dateFetch = dateFetch,
    version = version,
    name = name,
    scanlator = scanlator,
    chapterNumber = chapterNumber,
    dateUpload = dateUpload,
    volume = volume,
    sourceOrder = sourceOrder,
    deletedAt = deletedAt,
    restoredAt = restoredAt,
    bookmarkNotesModifiedAt = bookmarkNotesModifiedAt,
)

internal fun List<StoredNote>.toNoteRecords(): Map<Long, BookmarkNoteRecord> =
    associate { it.pageIndex to BookmarkNoteRecord(note = it.note, dateAdded = it.dateAdded) }

internal fun Map<Long, BookmarkNoteRecord>.toStoredNotes(): List<StoredNote> =
    map { (page, note) -> StoredNote(pageIndex = page, note = note.note, dateAdded = note.dateAdded) }

internal fun StoredCategory.toRecord(): CategoryRecord = CategoryRecord(
    syncKey = syncKey,
    name = name,
    order = order,
    flags = flags,
    parentKey = parentKey,
    modifiedAt = modifiedAt,
    deletedAt = deletedAt,
    restoredAt = restoredAt,
)

internal fun CategoryRecord.toStored(id: Long): StoredCategory = StoredCategory(
    id = id,
    syncKey = syncKey,
    name = name,
    order = order,
    flags = flags,
    parentKey = parentKey,
    modifiedAt = modifiedAt,
    deletedAt = deletedAt,
    restoredAt = restoredAt,
)

internal fun StoredHistory.toRecord(chapter: ChapterKey): HistoryRecord =
    HistoryRecord(chapter = chapter, readAt = lastRead, readDuration = timeRead)

internal fun HistoryRecord.toStored(chapterId: Long): StoredHistory =
    StoredHistory(chapterId = chapterId, lastRead = readAt, timeRead = readDuration)

// Merge records and wire rows.

/** The `update_strategy` column's code, which the wire carries (R81 carry-over 3). */
internal val UpdateStrategy.code: Long get() = ordinal.toLong()

/** An unknown code reads as the default, as the database column's adapter reads it. */
internal fun updateStrategyOf(code: Long): UpdateStrategy =
    UpdateStrategy.entries.getOrElse(code.toInt()) { UpdateStrategy.ALWAYS_UPDATE }

internal fun MangaRecord.toRow(): MangaRow = MangaRow(
    key = key,
    favorite = favorite,
    dateAdded = dateAdded,
    favoriteModifiedAt = favoriteModifiedAt,
    deletedAt = deletedAt,
    restoredAt = restoredAt,
    version = version,
    title = title,
    author = author,
    artist = artist,
    description = description,
    genre = genre,
    status = status,
    thumbnailUrl = thumbnailUrl,
    updateStrategy = updateStrategy.code,
    memo = memo,
    viewer = viewerFlags,
    chapterFlags = chapterFlags,
    displayModifiedAt = displayModifiedAt,
    notes = notes,
    notesModifiedAt = notesModifiedAt,
    manualPosition = manualPosition,
    positionModifiedAt = positionModifiedAt,
    titleOverride = titleOverride,
    descriptionOverride = descriptionOverride,
    detailsModifiedAt = detailsModifiedAt,
)

internal fun MangaRecord.categoriesRow(): MangaCategoriesRow =
    MangaCategoriesRow(manga = key, categories = categories, categoriesModifiedAt = categoriesModifiedAt)

internal fun MangaRecord.excludedScanlatorsRow(): ExcludedScanlatorsRow =
    ExcludedScanlatorsRow(manga = key, scanlators = excludedScanlators, exclusionsModifiedAt = exclusionsModifiedAt)

/** The lowest ranking first, as the table orders it, then by scanlator. */
internal fun MangaRecord.scanlatorPriorityRow(): ScanlatorPriorityRow = ScanlatorPriorityRow(
    manga = key,
    priorities = scanlatorPriority.entries.sortedWith(compareBy({ it.value }, { it.key }))
        .map { PriorityEntry(scanlator = it.key, priority = it.value) },
    priorityModifiedAt = priorityModifiedAt,
)

internal fun MangaRecord.tracksRow(): TracksRow = TracksRow(
    manga = key,
    tracks = tracks.entries.sortedBy { it.key }.map { (trackerId, track) ->
        TrackEntry(
            trackerId = trackerId,
            remoteId = track.remoteId,
            libraryId = track.libraryId,
            title = track.title,
            lastChapterRead = track.lastChapterRead,
            totalChapters = track.totalChapters,
            status = track.status,
            score = track.score,
            remoteUrl = track.remoteUrl,
            startDate = track.startDate,
            finishDate = track.finishDate,
            private = track.private,
        )
    },
    trackersModifiedAt = trackersModifiedAt,
)

/**
 * The manga line under [key] (the line's own key with its source resolved), with no collections:
 * each collection arrives on a line of its own, and its clock here is whatever the caller copies in.
 */
internal fun MangaRow.toRecord(key: MangaKey): MangaRecord = MangaRecord(
    key = key,
    favorite = favorite,
    dateAdded = dateAdded,
    favoriteModifiedAt = favoriteModifiedAt,
    deletedAt = deletedAt,
    restoredAt = restoredAt,
    version = version,
    title = title,
    author = author,
    artist = artist,
    description = description,
    genre = genre,
    status = status,
    thumbnailUrl = thumbnailUrl,
    updateStrategy = updateStrategyOf(updateStrategy),
    memo = memo,
    viewerFlags = viewer,
    chapterFlags = chapterFlags,
    displayModifiedAt = displayModifiedAt,
    notes = notes,
    notesModifiedAt = notesModifiedAt,
    manualPosition = manualPosition,
    positionModifiedAt = positionModifiedAt,
    titleOverride = titleOverride,
    descriptionOverride = descriptionOverride,
    detailsModifiedAt = detailsModifiedAt,
    categories = emptySet(),
    categoriesModifiedAt = null,
    excludedScanlators = emptySet(),
    exclusionsModifiedAt = null,
    scanlatorPriority = emptyMap(),
    priorityModifiedAt = null,
    tracks = emptyMap(),
    trackersModifiedAt = null,
)

/** A later entry for one tracker replaces an earlier one; the writer never repeats one. */
internal fun TracksRow.toRecords(): Map<Long, TrackRecord> = tracks.associate { entry ->
    entry.trackerId to TrackRecord(
        remoteId = entry.remoteId,
        libraryId = entry.libraryId,
        title = entry.title,
        lastChapterRead = entry.lastChapterRead,
        totalChapters = entry.totalChapters,
        status = entry.status,
        score = entry.score,
        remoteUrl = entry.remoteUrl,
        startDate = entry.startDate,
        finishDate = entry.finishDate,
        private = entry.private,
    )
}

internal fun ScanlatorPriorityRow.toRanking(): Map<String, Long> = priorities.associate { it.scanlator to it.priority }

/** The order a [MergedGroupRecord] holds its members in. */
private val MEMBER_ORDER = compareBy<MergedMemberRecord>({ it.position }, { it.key.source }, { it.key.url })

internal val StoredMergedGroup.key: MangaKey get() = MangaKey(mainSource, mainUrl)

internal fun StoredMergedGroup.toRecord(): MergedGroupRecord = MergedGroupRecord(
    main = key,
    showAll = showAll,
    modifiedAt = modifiedAt,
    members = members.map { MergedMemberRecord(MangaKey(it.source, it.url), it.position, it.checkUpdates) }
        .sortedWith(MEMBER_ORDER),
)

internal fun MergedGroupRecord.toStored(id: Long): StoredMergedGroup = StoredMergedGroup(
    id = id,
    mainSource = main.source,
    mainUrl = main.url,
    showAll = showAll,
    modifiedAt = modifiedAt,
    members = members.map { StoredMergedMember(it.key.source, it.key.url, it.position, it.checkUpdates) },
)

internal fun MergedGroupRecord.toRow(): MergedGroupRow = MergedGroupRow(
    main = main,
    members = members.map { MergedMemberEntry(it.key, it.position, it.checkUpdates) },
    showAll = showAll,
    modifiedAt = modifiedAt,
)

/**
 * The line as this device holds it: each key through [resolve], since an alias can make one entry of
 * two the sender kept apart, and then the first of them is the member.
 */
internal fun MergedGroupRow.toRecord(resolve: (MangaKey) -> MangaKey): MergedGroupRecord = MergedGroupRecord(
    main = resolve(main),
    showAll = showAll,
    modifiedAt = modifiedAt,
    members = members.map { MergedMemberRecord(resolve(it.manga), it.position, it.checkUpdates) }
        .sortedWith(MEMBER_ORDER)
        .distinctBy { it.key },
)

internal fun ChapterRecord.toRow(sourceOrder: Long): ChapterRow = ChapterRow(
    key = key,
    read = read,
    bookmark = bookmark,
    lastPageRead = lastPageRead,
    dateFetch = dateFetch,
    version = version,
    name = name,
    scanlator = scanlator,
    chapterNumber = chapterNumber,
    dateUpload = dateUpload,
    volume = volume,
    sourceOrder = sourceOrder,
    deletedAt = deletedAt,
    restoredAt = restoredAt,
)

internal fun ChapterRecord.notesRow(): BookmarkNotesRow = BookmarkNotesRow(
    chapter = key,
    notes = bookmarkNotes.entries.sortedBy { it.key }
        .map { (page, note) -> NoteEntry(pageIndex = page, note = note.note, dateAdded = note.dateAdded) },
    bookmarkNotesModifiedAt = bookmarkNotesModifiedAt,
)

/** The chapter line under [key] with no notes, which arrive on a line of their own. */
internal fun ChapterRow.toRecord(key: ChapterKey): ChapterRecord = ChapterRecord(
    key = key,
    read = read,
    bookmark = bookmark,
    lastPageRead = lastPageRead,
    dateFetch = dateFetch,
    version = version,
    name = name,
    scanlator = scanlator,
    chapterNumber = chapterNumber,
    dateUpload = dateUpload,
    volume = volume,
    deletedAt = deletedAt,
    restoredAt = restoredAt,
    bookmarkNotes = emptyMap(),
    bookmarkNotesModifiedAt = null,
)

internal fun BookmarkNotesRow.toRecords(): Map<Long, BookmarkNoteRecord> =
    notes.associate { it.pageIndex to BookmarkNoteRecord(note = it.note, dateAdded = it.dateAdded) }

internal fun CategoryRecord.toRow(): CategoryRow = CategoryRow(
    key = syncKey,
    name = name,
    order = order,
    flags = flags,
    parent = parentKey,
    modifiedAt = modifiedAt,
    deletedAt = deletedAt,
    restoredAt = restoredAt,
)

internal fun CategoryRow.toRecord(): CategoryRecord = CategoryRecord(
    syncKey = key,
    name = name,
    order = order,
    flags = flags,
    parentKey = parent,
    modifiedAt = modifiedAt,
    deletedAt = deletedAt,
    restoredAt = restoredAt,
)

/** History's names differ by shape: the wire's `lastRead` and `timeRead` (R81 carry-over 3). */
internal fun HistoryRecord.toRow(): HistoryRow = HistoryRow(chapter = chapter, lastRead = readAt, timeRead = readDuration)

internal fun HistoryRow.toRecord(chapter: ChapterKey): HistoryRecord =
    HistoryRecord(chapter = chapter, readAt = lastRead, readDuration = timeRead)

package com.shinsaikou.backup.export

import com.shinsaikou.domain.chapter.model.BookmarkNote
import com.shinsaikou.domain.chapter.model.ScanlatorPriority
import com.shinsaikou.domain.manga.model.ReadingMode
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPageNote
import eu.kanade.tachiyomi.data.backup.models.BackupScanlatorPriority
import eu.kanade.tachiyomi.data.backup.models.BackupTracking
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.model.History
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.model.Track

/**
 * Domain rows to wire models. Every field upstream knows is written as upstream's creator writes it.
 * What the owner made in Saikou travels too (R165 item 3, amending contract 06 decision F10): the
 * edited details in TachiyomiSY's custom info (`customTitle`, `customAuthor`, `customArtist`,
 * `customDescription`, `customGenre` and `customStatus`), and the manual position, the scanlator
 * priority, the status edit, a chapter's volume and page notes and a category's parent in Saikou's
 * own fields (`BackupSaikou.kt`). Only the edits go in those fields; `title`, `author`, `artist`,
 * `genre` and `status` stay the source's. Sync's bookkeeping stays out: `deleted_at`, versions,
 * latches and clocks.
 */
internal fun Manga.toBackupManga(
    excludedScanlators: List<String>,
    scanlatorPriority: List<ScanlatorPriority> = emptyList(),
) = BackupManga(
    source = source,
    url = url,
    title = title,
    artist = artist,
    author = author,
    description = description,
    genre = genre.orEmpty(),
    status = status.toInt(),
    thumbnailUrl = thumbnailUrl,
    dateAdded = dateAdded,
    // The legacy field carries only the reading mode; the full flags travel in field 103.
    viewer = (viewerFlags and ReadingMode.MASK.toLong()).toInt(),
    viewer_flags = viewerFlags.toInt(),
    favorite = favorite,
    chapterFlags = chapterFlags.toInt(),
    updateStrategy = updateStrategy,
    lastModifiedAt = lastModifiedAt,
    favoriteModifiedAt = favoriteModifiedAt,
    excludedScanlators = excludedScanlators,
    version = version,
    notes = notes,
    initialized = initialized,
    memo = MemoColumnAdapter.encode(memo),
    // An edit to Unknown is 0, which TachiyomiSY reads as no edit; statusOverride carries it.
    customStatus = statusOverride?.toInt() ?: 0,
    customTitle = titleOverride,
    customArtist = artistOverride,
    customAuthor = authorOverride,
    customDescription = descriptionOverride,
    // Split from the column as genre is, by its column adapter.
    customGenre = genreOverride,
    manualPosition = manualPosition,
    scanlatorPriority = scanlatorPriority.map { BackupScanlatorPriority(it.scanlator, it.priority) },
    statusOverride = statusOverride?.toInt(),
)

internal fun Chapter.toBackupChapter(pageNotes: List<BookmarkNote> = emptyList()) = BackupChapter(
    url = url,
    name = name,
    scanlator = scanlator,
    read = read,
    bookmark = bookmark,
    lastPageRead = lastPageRead,
    dateFetch = dateFetch,
    dateUpload = dateUpload,
    chapterNumber = chapterNumber.toFloat(),
    sourceOrder = sourceOrder,
    lastModifiedAt = lastModifiedAt,
    version = version,
    memo = MemoColumnAdapter.encode(memo),
    volume = volume,
    pageNotes = pageNotes.map { BackupPageNote(pageIndex = it.pageIndex, note = it.note, dateAdded = it.dateAdded) },
)

internal fun Track.toBackupTracking() = BackupTracking(
    syncId = trackerId.toInt(),
    libraryId = libraryId ?: 0L,
    trackingUrl = remoteUrl,
    title = title,
    lastChapterRead = lastChapterRead.toFloat(),
    totalChapters = totalChapters.toInt(),
    score = score.toFloat(),
    status = status.toInt(),
    startedReadingDate = startDate,
    finishedReadingDate = finishDate,
    private = private,
    mediaId = remoteId,
)

/**
 * [place] is the category's place in the file's order, and [parentPlace] its parent's, if it has one;
 * see `BackupExporter.categoryPlaces`.
 */
internal fun Category.toBackupCategory(place: Long, parentPlace: Long?) = BackupCategory(
    name = name,
    order = place,
    id = id,
    flags = flags,
    parentOrder = parentPlace,
)

/** Rows with no read timestamp are dropped by the caller; this maps the rest. */
internal fun History.toBackupHistory(chapterUrl: String) = BackupHistory(
    url = chapterUrl,
    lastRead = readAt?.time ?: 0L,
    readDuration = readDuration,
)

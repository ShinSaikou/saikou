package com.shinsaikou.backup.export

import com.shinsaikou.domain.manga.model.ReadingMode
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupTracking
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.model.History
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.model.Track

/**
 * Domain rows to wire models. The file Saikou writes is a plain upstream file: the columns Saikou added
 * (`manual_position`, `deleted_at`, `volume`, the category parent) have no field in the format and
 * are sync entities rather than backup entities (contract 06 decision F10). `customTitle` and
 * `customDescription`, which the import reads from other readers' files, stay unset for the same
 * reason (R162 item 8).
 */
internal fun Manga.toBackupManga(excludedScanlators: List<String>) = BackupManga(
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
)

internal fun Chapter.toBackupChapter() = BackupChapter(
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

/** [place] is the category's place in the file's order; see `BackupExporter.categoryPlaces`. */
internal fun Category.toBackupCategory(place: Long) = BackupCategory(
    name = name,
    order = place,
    id = id,
    flags = flags,
)

/** Rows with no read timestamp are dropped by the caller; this maps the rest. */
internal fun History.toBackupHistory(chapterUrl: String) = BackupHistory(
    url = chapterUrl,
    lastRead = readAt?.time ?: 0L,
    readDuration = readDuration,
)

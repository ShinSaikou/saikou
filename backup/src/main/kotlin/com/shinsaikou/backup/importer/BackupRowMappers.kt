package com.shinsaikou.backup.importer

import com.shinsaikou.source.model.UpdateStrategy
import kotlinx.serialization.json.JsonObject
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.model.History
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.model.Track
import java.util.Date

/**
 * Row mappers for the four tables [BackupDatabaseWriter] reads. They are separate from the
 * `:data` repository mappers on purpose: the writer reads columns the repositories do not expose
 * (`is_syncing`, `deleted_at`, `volume`) because the merge rules need the stored value of every
 * column they leave alone.
 */
@Suppress("LongParameterList")
internal fun mapManga(
    id: Long,
    source: Long,
    url: String,
    artist: String?,
    author: String?,
    description: String?,
    genre: List<String>?,
    title: String,
    status: Long,
    thumbnailUrl: String?,
    favorite: Boolean,
    lastUpdate: Long?,
    nextUpdate: Long?,
    initialized: Boolean,
    viewerFlags: Long,
    chapterFlags: Long,
    coverLastModified: Long,
    dateAdded: Long,
    updateStrategy: UpdateStrategy,
    calculateInterval: Long,
    lastModifiedAt: Long,
    favoriteModifiedAt: Long?,
    version: Long,
    @Suppress("UNUSED_PARAMETER") isSyncing: Long,
    notes: String,
    memo: JsonObject,
    manualPosition: Long,
    deletedAt: Long?,
    @Suppress("UNUSED_PARAMETER") displayModifiedAt: Long?,
    @Suppress("UNUSED_PARAMETER") notesModifiedAt: Long?,
    @Suppress("UNUSED_PARAMETER") positionModifiedAt: Long?,
    @Suppress("UNUSED_PARAMETER") categoriesModifiedAt: Long?,
    @Suppress("UNUSED_PARAMETER") exclusionsModifiedAt: Long?,
    @Suppress("UNUSED_PARAMETER") priorityModifiedAt: Long?,
    @Suppress("UNUSED_PARAMETER") trackersModifiedAt: Long?,
    @Suppress("UNUSED_PARAMETER") restoredAt: Long?,
    // Schema version 3's overrides (phase 5 units SC4 and ED): read so the merged row is the stored
    // one. The file carries them at fields 800 and 804 (R162 item 8, R165 item 3), and a restore
    // fills one only where the row has none.
    titleOverride: String?,
    descriptionOverride: String?,
    @Suppress("UNUSED_PARAMETER") detailsModifiedAt: Long?,
    // Schema version 7's author, artist, status and genre overrides (R165 item 4): read so the merged
    // row is the stored one. No file carries them yet, and a restore leaves them as they are.
    authorOverride: String?,
    artistOverride: String?,
    statusOverride: Long?,
    genreOverride: List<String>?,
): Manga = Manga(
    id = id,
    source = source,
    favorite = favorite,
    lastUpdate = lastUpdate ?: 0L,
    nextUpdate = nextUpdate ?: 0L,
    fetchInterval = calculateInterval.toInt(),
    dateAdded = dateAdded,
    viewerFlags = viewerFlags,
    chapterFlags = chapterFlags,
    coverLastModified = coverLastModified,
    url = url,
    title = title,
    artist = artist,
    author = author,
    description = description,
    genre = genre,
    status = status,
    thumbnailUrl = thumbnailUrl,
    updateStrategy = updateStrategy,
    initialized = initialized,
    lastModifiedAt = lastModifiedAt,
    favoriteModifiedAt = favoriteModifiedAt,
    version = version,
    notes = notes,
    memo = memo,
    manualPosition = manualPosition,
    deletedAt = deletedAt,
    titleOverride = titleOverride,
    descriptionOverride = descriptionOverride,
    authorOverride = authorOverride,
    artistOverride = artistOverride,
    statusOverride = statusOverride,
    genreOverride = genreOverride,
)

@Suppress("LongParameterList")
internal fun mapChapter(
    id: Long,
    mangaId: Long,
    url: String,
    name: String,
    scanlator: String?,
    read: Boolean,
    bookmark: Boolean,
    lastPageRead: Long,
    chapterNumber: Double,
    sourceOrder: Long,
    dateFetch: Long,
    dateUpload: Long,
    lastModifiedAt: Long,
    version: Long,
    @Suppress("UNUSED_PARAMETER") isSyncing: Long,
    memo: JsonObject,
    volume: Double?,
    deletedAt: Long?,
    @Suppress("UNUSED_PARAMETER") bookmarkNotesModifiedAt: Long?,
    @Suppress("UNUSED_PARAMETER") restoredAt: Long?,
    @Suppress("UNUSED_PARAMETER") readModifiedAt: Long,
    @Suppress("UNUSED_PARAMETER") bookmarkModifiedAt: Long,
): Chapter = Chapter(
    id = id,
    mangaId = mangaId,
    read = read,
    bookmark = bookmark,
    lastPageRead = lastPageRead,
    dateFetch = dateFetch,
    sourceOrder = sourceOrder,
    url = url,
    name = name,
    dateUpload = dateUpload,
    chapterNumber = chapterNumber,
    scanlator = scanlator,
    lastModifiedAt = lastModifiedAt,
    version = version,
    memo = memo,
    volume = volume,
    deletedAt = deletedAt,
)

internal fun mapHistory(
    id: Long,
    chapterId: Long,
    lastRead: Date?,
    timeRead: Long,
): History = History(id = id, chapterId = chapterId, readAt = lastRead, readDuration = timeRead)

@Suppress("LongParameterList")
internal fun mapTrack(
    id: Long,
    mangaId: Long,
    syncId: Long,
    remoteId: Long,
    libraryId: Long?,
    title: String,
    lastChapterRead: Double,
    totalChapters: Long,
    status: Long,
    score: Double,
    remoteUrl: String,
    startDate: Long,
    finishDate: Long,
    private: Boolean,
): Track = Track(
    id = id,
    mangaId = mangaId,
    trackerId = syncId,
    remoteId = remoteId,
    libraryId = libraryId,
    title = title,
    lastChapterRead = lastChapterRead,
    totalChapters = totalChapters,
    status = status,
    score = score,
    remoteUrl = remoteUrl,
    startDate = startDate,
    finishDate = finishDate,
    private = private,
)

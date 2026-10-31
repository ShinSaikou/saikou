package com.shinsaikou.domain.library.interactor

import com.shinsaikou.domain.library.model.LibraryItem
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.model.Track

/** A library row for [id] titled [title], for the tracker filter and sort tests (phase 3 unit T3). */
internal fun libraryItem(id: Long, title: String) = LibraryItem(
    libraryManga = LibraryManga(
        manga = Manga.create().copy(id = id, source = 1L, title = title),
        categories = emptyList(),
        totalChapters = 10L,
        readCount = 0L,
        bookmarkCount = 0L,
        latestUpload = 0L,
        chapterFetchedAt = 0L,
        lastRead = 0L,
    ),
    downloadCount = 0L,
    unreadCount = 10L,
    isLocal = false,
    sourceLanguage = "en",
)

/** A track row of [mangaId] on [trackerId] with [score]. */
internal fun trackRow(mangaId: Long, trackerId: Long, score: Double = 0.0) = Track(
    id = mangaId * 100 + trackerId,
    mangaId = mangaId,
    trackerId = trackerId,
    remoteId = 1000 + mangaId,
    libraryId = null,
    title = "Entry $mangaId",
    lastChapterRead = 0.0,
    totalChapters = 0,
    status = 1,
    score = score,
    remoteUrl = "https://tracker.example.invalid/entry/$mangaId",
    startDate = 0,
    finishDate = 0,
    private = false,
)

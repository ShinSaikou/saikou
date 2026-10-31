package com.shinsaikou.domain.source

import com.shinsaikou.source.model.ChapterInfo
import com.shinsaikou.source.model.MangaInfo
import com.shinsaikou.source.model.MangaStatus
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * The bridge between the source contract's models and the domain models. Everything that crosses
 * the boundary goes through this file, so a rename on either side is a one-file change.
 */
fun MangaInfo.toDomainManga(sourceId: Long): Manga = Manga.create().copy(
    source = sourceId,
    url = url,
    title = title,
    artist = artist,
    author = author,
    description = description,
    genre = genres()?.takeIf { it.isNotEmpty() },
    status = status.code.toLong(),
    thumbnailUrl = thumbnailUrl,
    updateStrategy = updateStrategy,
    initialized = initialized,
    memo = memo,
)

/** The reverse direction, used to call Source.getMangaDetails and Source.getChapterList. */
fun Manga.toMangaInfo(): MangaInfo = MangaInfo(
    url = url,
    title = title,
    artist = artist,
    author = author,
    description = description,
    genre = genre?.joinToString(", "),
    status = MangaStatus.fromCode(status.toInt()),
    thumbnailUrl = thumbnailUrl,
    updateStrategy = updateStrategy,
    initialized = initialized,
    memo = memo,
)

/**
 * A fresh chapter row for [mangaId]. sourceOrder, dateFetch and volume are set by
 * SyncChaptersWithSource; ChapterInfo carries no volume field, so the volume is read from the name.
 */
fun ChapterInfo.toDomainChapter(mangaId: Long): Chapter = Chapter.create().copy(
    mangaId = mangaId,
    url = url,
    name = name,
    dateUpload = dateUpload,
    chapterNumber = chapterNumber.toDouble(),
    scanlator = scanlator?.ifBlank { null },
    memo = memo,
)

fun Chapter.toChapterInfo(): ChapterInfo = ChapterInfo(
    url = url,
    name = name,
    dateUpload = dateUpload,
    chapterNumber = chapterNumber.toFloat(),
    scanlator = scanlator,
    memo = memo,
)

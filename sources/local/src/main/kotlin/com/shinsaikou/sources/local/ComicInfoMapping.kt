package com.shinsaikou.sources.local

import com.shinsaikou.source.model.MangaInfo
import com.shinsaikou.source.model.MangaStatus
import tachiyomi.core.metadata.comicinfo.ComicInfo
import tachiyomi.core.metadata.comicinfo.ComicInfoPublishingStatus

/**
 * The `MangaInfo` half of upstream's `ComicInfo.kt`. It lives here rather than in `:core:metadata`
 * because the module graph gives that module no dependency on `:source-contract`
 * (ARCHITECTURE.md, carry-overs). The field mapping is upstream's, field for field.
 */

/** Upstream's `SManga.getComicInfo()`: the manga-level file the Local source writes. */
fun MangaInfo.toComicInfo(): ComicInfo = ComicInfo(
    series = ComicInfo.Series(title),
    summary = description?.let { ComicInfo.Summary(it) },
    writer = author?.let { ComicInfo.Writer(it) },
    penciller = artist?.let { ComicInfo.Penciller(it) },
    genre = genre?.let { ComicInfo.Genre(it) },
    publishingStatus = ComicInfo.PublishingStatusTachiyomi(
        ComicInfoPublishingStatus.toComicInfoValue(status.code.toLong()),
    ),
    title = null,
    number = null,
    web = null,
    translator = null,
    inker = null,
    colorist = null,
    letterer = null,
    coverArtist = null,
    tags = null,
    categories = null,
    source = null,
)

/**
 * Upstream's `SManga.copyFromComicInfo()`, returning a copy instead of mutating: genre is genre, tags
 * and categories joined and de-duplicated; artist is every credit but the writer, split on ", "
 * and de-duplicated; status goes through [ComicInfoPublishingStatus].
 */
fun MangaInfo.copyFromComicInfo(comicInfo: ComicInfo): MangaInfo {
    val genres = listOfNotNull(
        comicInfo.genre?.value,
        comicInfo.tags?.value,
        comicInfo.categories?.value,
    )
        .distinct()
        .joinToString(", ") { it.trim() }
        .takeIf { it.isNotEmpty() }

    val artists = listOfNotNull(
        comicInfo.penciller?.value,
        comicInfo.inker?.value,
        comicInfo.colorist?.value,
        comicInfo.letterer?.value,
        comicInfo.coverArtist?.value,
    )
        .flatMap { it.split(", ") }
        .distinct()
        .joinToString(", ") { it.trim() }
        .takeIf { it.isNotEmpty() }

    return copy(
        title = comicInfo.series?.value ?: title,
        author = comicInfo.writer?.value ?: author,
        description = comicInfo.summary?.value ?: description,
        genre = genres ?: genre,
        artist = artists ?: artist,
        status = MangaStatus.fromCode(ComicInfoPublishingStatus.toStatusCode(comicInfo.publishingStatus?.value)),
    )
}

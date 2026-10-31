package com.shinsaikou.downloads

import tachiyomi.core.metadata.comicinfo.ComicInfo
import tachiyomi.core.metadata.comicinfo.ComicInfoPublishingStatus
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * The chapter-level `ComicInfo.xml` a downloaded chapter carries: upstream's `getComicInfo(manga,
 * chapter, urls, categories, sourceName)`, field for field. Contract 06 section 3.4 leaves this
 * variant to `:downloads`; the manga-level one the Local source writes is `MangaInfo.toComicInfo`
 * in `:sources:local`.
 */
fun chapterComicInfo(
    manga: Manga,
    chapter: Chapter,
    urls: List<String>,
    categories: List<String>?,
    sourceName: String,
): ComicInfo = ComicInfo(
    title = ComicInfo.Title(chapter.name),
    series = ComicInfo.Series(manga.title),
    number = chapter.chapterNumber.takeIf { it >= 0 }?.let {
        if (it.rem(1) == 0.0) {
            ComicInfo.Number(it.toInt().toString())
        } else {
            ComicInfo.Number(it.toString())
        }
    },
    web = ComicInfo.Web(urls.joinToString(" ")),
    summary = manga.description?.let { ComicInfo.Summary(it) },
    writer = manga.author?.let { ComicInfo.Writer(it) },
    penciller = manga.artist?.let { ComicInfo.Penciller(it) },
    translator = chapter.scanlator?.let { ComicInfo.Translator(it) },
    genre = manga.genre?.let { ComicInfo.Genre(it.joinToString()) },
    publishingStatus = ComicInfo.PublishingStatusTachiyomi(
        ComicInfoPublishingStatus.toComicInfoValue(manga.status),
    ),
    categories = categories?.let { ComicInfo.CategoriesTachiyomi(it.joinToString()) },
    source = ComicInfo.SourceName(sourceName),
    inker = null,
    colorist = null,
    letterer = null,
    coverArtist = null,
    tags = null,
)

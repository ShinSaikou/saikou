package com.shinsaikou.data.download

import com.shinsaikou.domain.download.model.DownloadManifestEntry

/** The `downloads_manifest` columns in declaration order. */
fun mapDownloadManifestEntry(
    id: Long,
    chapterId: Long,
    mangaId: Long,
    sourceId: Long,
    mangaTitle: String,
    mangaUrl: String,
    chapterUrl: String,
    chapterName: String,
    scanlator: String?,
    chapterDirName: String,
    pageCount: Long,
    sizeBytes: Long,
    dateDownloaded: Long,
): DownloadManifestEntry = DownloadManifestEntry(
    id = id,
    chapterId = chapterId,
    mangaId = mangaId,
    sourceId = sourceId,
    mangaTitle = mangaTitle,
    mangaUrl = mangaUrl,
    chapterUrl = chapterUrl,
    chapterName = chapterName,
    scanlator = scanlator,
    chapterDirName = chapterDirName,
    pageCount = pageCount,
    sizeBytes = sizeBytes,
    dateDownloaded = dateDownloaded,
)

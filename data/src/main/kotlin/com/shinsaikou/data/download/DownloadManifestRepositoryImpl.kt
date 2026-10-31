package com.shinsaikou.data.download

import com.shinsaikou.data.DatabaseHandler
import com.shinsaikou.domain.download.model.DownloadManifestEntry
import com.shinsaikou.domain.download.repository.DownloadManifestRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DownloadManifestRepositoryImpl(
    private val handler: DatabaseHandler,
) : DownloadManifestRepository {

    override suspend fun getByChapterId(chapterId: Long): DownloadManifestEntry? =
        handler.awaitOneOrNull {
            downloads_manifestQueries.getByChapterId(chapterId, ::mapDownloadManifestEntry)
        }

    override suspend fun getByMangaId(mangaId: Long): List<DownloadManifestEntry> =
        handler.awaitList {
            downloads_manifestQueries.getByMangaId(mangaId, ::mapDownloadManifestEntry)
        }

    override suspend fun getAll(): List<DownloadManifestEntry> =
        handler.awaitList { downloads_manifestQueries.getAll(::mapDownloadManifestEntry) }

    override suspend fun getByChapterDirNames(chapterDirNames: Collection<String>): List<DownloadManifestEntry> =
        chapterDirNames.distinct().chunked(MAX_BOUND_NAMES).flatMap { names ->
            handler.awaitList { downloads_manifestQueries.getByChapterDirNames(names, ::mapDownloadManifestEntry) }
        }

    override fun subscribeByMangaId(mangaId: Long): Flow<List<DownloadManifestEntry>> =
        handler.subscribeToList {
            downloads_manifestQueries.getByMangaId(mangaId, ::mapDownloadManifestEntry)
        }

    override fun subscribeByChapterIds(chapterIds: Collection<Long>): Flow<List<DownloadManifestEntry>> =
        handler.subscribeToList {
            downloads_manifestQueries.getByChapterIds(chapterIds, ::mapDownloadManifestEntry)
        }

    override fun subscribeAll(): Flow<List<DownloadManifestEntry>> =
        handler.subscribeToList { downloads_manifestQueries.getAll(::mapDownloadManifestEntry) }

    override fun subscribeCountsByManga(): Flow<Map<Long, Long>> =
        handler.subscribeToList {
            downloads_manifestQueries.countsByManga { mangaId, count -> mangaId to count }
        }.map { it.toMap() }

    override suspend fun getOrphans(): List<DownloadManifestEntry> =
        handler.awaitList { downloads_manifestQueries.getOrphans(::mapDownloadManifestEntry) }

    override suspend fun getWithoutChapter(): List<DownloadManifestEntry> =
        handler.awaitList { downloads_manifestQueries.getWithoutChapter(::mapDownloadManifestEntry) }

    override suspend fun getTotalSizeBytes(): Long =
        handler.awaitOne { downloads_manifestQueries.getTotalSizeBytes() }

    override suspend fun recordDownload(entry: DownloadManifestEntry) {
        handler.await {
            downloads_manifestQueries.upsert(
                chapterId = entry.chapterId,
                mangaId = entry.mangaId,
                sourceId = entry.sourceId,
                mangaTitle = entry.mangaTitle,
                mangaUrl = entry.mangaUrl,
                chapterUrl = entry.chapterUrl,
                chapterName = entry.chapterName,
                scanlator = entry.scanlator,
                chapterDirName = entry.chapterDirName,
                pageCount = entry.pageCount,
                sizeBytes = entry.sizeBytes,
                now = entry.dateDownloaded,
            )
        }
    }

    override suspend fun repoint(
        fromChapterId: Long,
        toChapterId: Long,
        chapterUrl: String,
        chapterName: String,
        scanlator: String?,
    ) {
        handler.await {
            downloads_manifestQueries.repoint(
                toChapterId = toChapterId,
                chapterUrl = chapterUrl,
                chapterName = chapterName,
                scanlator = scanlator,
                fromChapterId = fromChapterId,
            )
        }
    }

    override suspend fun moveToChapter(fromChapterId: Long, entry: DownloadManifestEntry) {
        handler.await {
            downloads_manifestQueries.moveToChapter(
                toChapterId = entry.chapterId,
                mangaId = entry.mangaId,
                sourceId = entry.sourceId,
                mangaTitle = entry.mangaTitle,
                mangaUrl = entry.mangaUrl,
                chapterUrl = entry.chapterUrl,
                chapterName = entry.chapterName,
                scanlator = entry.scanlator,
                chapterDirName = entry.chapterDirName,
                fromChapterId = fromChapterId,
            )
        }
    }

    override suspend fun remove(chapterIds: List<Long>) {
        handler.await(inTransaction = true) {
            downloads_manifestQueries.deleteByChapterIds(chapterIds)
        }
    }
}

/** Names per statement: the 999 parameter bound that `subscribeByChapterIds` keeps to, kept here too. */
private const val MAX_BOUND_NAMES = 999

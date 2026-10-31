package com.shinsaikou.downloads

import com.shinsaikou.domain.download.DownloadedChapters
import com.shinsaikou.domain.download.model.DownloadManifestEntry
import com.shinsaikou.domain.download.repository.DownloadManifestRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * [DownloadedChapters] for the reader and the chapter list: a manifest row counts only while
 * [DownloadLocator] finds its folder or archive, the same test [FolderDownloadManifest] applies.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class LocatedDownloadedChapters(
    private val manifestRepository: DownloadManifestRepository,
    private val locator: DownloadLocator,
) : DownloadedChapters {

    override suspend fun find(chapterId: Long): File? =
        manifestRepository.getByChapterId(chapterId)?.let { locator.findChapterDir(it) }

    override fun subscribeByMangaId(mangaId: Long): Flow<Set<Long>> =
        manifestRepository.subscribeByMangaId(mangaId).located()

    override fun subscribeByChapterIds(chapterIds: Set<Long>): Flow<Set<Long>> =
        if (chapterIds.isEmpty()) flowOf(emptySet()) else manifestRepository.subscribeByChapterIds(chapterIds).located()

    override fun subscribeAll(): Flow<Set<Long>> = manifestRepository.subscribeAll().located()

    private fun Flow<List<DownloadManifestEntry>>.located(): Flow<Set<Long>> =
        map { entries ->
            entries.filter { locator.findChapterDir(it) != null }.mapTo(mutableSetOf()) { it.chapterId }
        }
            .distinctUntilChanged()
}

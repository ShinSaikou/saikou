package com.shinsaikou.downloads

import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.download.model.DownloadManifestEntry
import com.shinsaikou.domain.download.repository.DownloadManifestRepository
import com.shinsaikou.image.ImageUtil
import com.shinsaikou.source.model.PageInfo
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The manifest, read through [DownloadManifestRepository] and checked against the disk: a row whose
 * folder is missing counts as not downloaded (phase 2 decision Q4), and the sweep removes it.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class FolderDownloadManifest(
    private val manifestRepository: DownloadManifestRepository,
    private val locator: DownloadLocator,
    private val dispatchers: Dispatchers,
) : DownloadManifest {

    override suspend fun isDownloaded(chapterId: Long): Boolean = getChapterDir(chapterId) != null

    override fun subscribeCountsByManga(): Flow<Map<Long, Long>> = manifestRepository.subscribeCountsByManga()

    override suspend fun getEntry(chapterId: Long): DownloadManifestEntry? =
        manifestRepository.getByChapterId(chapterId)

    override suspend fun getChapterDir(chapterId: Long): File? =
        manifestRepository.getByChapterId(chapterId)?.let { locator.findChapterDir(it) }

    /**
     * The images of a chapter folder in file name order, which is page order for the `NNN.<ext>`
     * names the engine writes. Null for an archive: its pages are the reader's to open.
     */
    override suspend fun getPages(chapterId: Long): List<PageInfo>? {
        val dir = getChapterDir(chapterId) ?: return null
        return withContext(dispatchers.io) {
            if (!dir.isDirectory) return@withContext null
            dir.listFiles().orEmpty()
                .filter { it.isFile && ImageUtil.isImage(it.name) }
                .sortedBy { it.name }
                .mapIndexed { index, file -> PageInfo(index = index, imageUrl = file.toURI().toString()) }
        }
    }

    /**
     * Files first, then rows. A delete cut short leaves a row whose folder is gone, which the sweep
     * removes; the other order would leave files no row points at, which nothing would ever find.
     */
    override suspend fun delete(chapterIds: List<Long>) {
        if (chapterIds.isEmpty()) return
        val entries = chapterIds.mapNotNull { manifestRepository.getByChapterId(it) }
        entries.forEach { entry ->
            val dir = locator.findChapterDir(entry) ?: return@forEach
            withContext(dispatchers.io) { deleteWithEmptyParents(dir) }
        }
        manifestRepository.remove(chapterIds)
    }

    override suspend fun deleteManga(mangaId: Long) {
        delete(manifestRepository.getByMangaId(mangaId).map { it.chapterId })
    }

    override suspend fun getTotalSizeBytes(): Long = manifestRepository.getTotalSizeBytes()

    /** The chapter folder, then its manga and source folders if that left them empty. */
    private fun deleteWithEmptyParents(dir: File) {
        dir.deleteRecursively()
        val manga = dir.parentFile ?: return
        if (manga.list().isNullOrEmpty()) manga.delete()
        val source = manga.parentFile ?: return
        if (source.list().isNullOrEmpty()) source.delete()
    }
}

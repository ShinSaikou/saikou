package com.shinsaikou.downloads

import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.core.metadata.comicinfo.ComicInfoXml
import com.shinsaikou.domain.source.toChapterInfo
import com.shinsaikou.source.Source
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.withContext
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.repository.TrackRepository
import java.io.File

/**
 * Writes a chapter folder's `ComicInfo.xml` ([chapterComicInfo]) with the entry's categories and
 * every web address known for it, as upstream's downloader does. [DownloadEngine] calls it for every
 * chapter it downloads, and the adoption of upstream's downloads for a copied folder that has none.
 */
@Inject
@SingleIn(AppScope::class)
class ChapterComicInfoWriter(
    private val categoryRepository: CategoryRepository,
    private val trackRepository: TrackRepository,
    private val comicInfoXml: ComicInfoXml,
    private val dispatchers: Dispatchers,
) {

    suspend fun write(dir: File, manga: Manga, chapter: Chapter, source: Source) {
        val categories = categoryRepository.getCategoriesByMangaId(manga.id)
            .map { it.name.trim() }
            .takeUnless { it.isEmpty() }
        val urls = trackRepository.getTracksByMangaId(manga.id)
            .mapNotNull { track -> track.remoteUrl.takeUnless { it.isBlank() }?.trim() }
            .plus(listOfNotNull(source.getChapterUrl(chapter.toChapterInfo())?.trim()))
            .distinct()
        val info = chapterComicInfo(manga, chapter, urls, categories, source.name)
        withContext(dispatchers.io) {
            File(dir, COMIC_INFO_FILE).outputStream().use { comicInfoXml.encode(info, it) }
        }
    }
}

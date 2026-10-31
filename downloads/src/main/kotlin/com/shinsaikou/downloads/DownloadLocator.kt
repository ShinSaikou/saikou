package com.shinsaikou.downloads

import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.download.model.DownloadManifestEntry
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.source.SourceManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.download.DownloadProvider
import kotlinx.coroutines.withContext
import tachiyomi.domain.chapter.model.Chapter
import java.io.File

/**
 * Finds a chapter's folder on disk from what the manifest or the chapter row says about it.
 *
 * The manifest keeps the source id but not the source's name, and the source folder is named after
 * the name. A source that is not loaded, or that renamed itself since, would make a computed path
 * miss a folder that is there, and the sweep would then delete a row whose files still exist. So
 * the live source's folder is tried first and every other source folder after it, under both values
 * of [LibraryPreferences.disallowNonAsciiFilenames], before a folder counts as missing.
 */
@Inject
@SingleIn(AppScope::class)
class DownloadLocator(
    private val downloadRoot: DownloadRoot,
    private val sourceManager: SourceManager,
    private val libraryPreferences: LibraryPreferences,
    private val dispatchers: Dispatchers,
) {

    /** The folder (or archive) of a manifest row, or null when none of the candidates exists. */
    suspend fun findChapterDir(entry: DownloadManifestEntry): File? =
        find(entry.sourceId, entry.mangaTitle, listOf(entry.chapterDirName))

    /**
     * Whether [dir], a `<source>/<manga>/<chapter>` path under the root, is one [findChapterDir] may
     * return for [entry]: it tries every source folder, so the source's name does not decide it.
     */
    fun mayResolveTo(entry: DownloadManifestEntry, dir: File): Boolean =
        entry.chapterDirName == dir.name &&
            asciiSettings().any { DownloadProvider.getMangaDirName(entry.mangaTitle, it) == dir.parentFile?.name }

    /** Every `_tmp` folder [chapter] may have under [sourceId] and [mangaTitle]. */
    suspend fun findTmpDirs(sourceId: Long, mangaTitle: String, chapter: Chapter): List<File> {
        val names = asciiSettings().map {
            DownloadProvider.getChapterDirName(chapter.name, chapter.scanlator, chapter.url, it) +
                DownloadProvider.TMP_DIR_SUFFIX
        }.distinct()
        return withContext(dispatchers.io) {
            candidates(sourceId, mangaTitle, names).filter { it.exists() }.toList()
        }
    }

    private suspend fun find(sourceId: Long, mangaTitle: String, dirNames: List<String>): File? =
        withContext(dispatchers.io) {
            candidates(sourceId, mangaTitle, dirNames).firstOrNull { it.exists() }
        }

    private fun candidates(sourceId: Long, mangaTitle: String, dirNames: List<String>): Sequence<File> {
        val root = downloadRoot.root()
        val preferred = sourceManager.get(sourceId)?.name?.let { name ->
            asciiSettings().map { sourceDirIn(root, name, it) }
        }.orEmpty()
        val sourceDirs = (preferred + root.listFiles { file -> file.isDirectory }.orEmpty()).distinct()
        val mangaNames = asciiSettings().map { DownloadProvider.getMangaDirName(mangaTitle, it) }.distinct()
        return sourceDirs.asSequence().flatMap { sourceDir ->
            mangaNames.asSequence().flatMap { mangaName ->
                dirNames.asSequence().map { File(File(sourceDir, mangaName), it) }
            }
        }
    }

    /** The current setting first, so the common case is the first path tried. */
    private fun asciiSettings(): List<Boolean> {
        val current = libraryPreferences.disallowNonAsciiFilenames.get()
        return listOf(current, !current)
    }
}

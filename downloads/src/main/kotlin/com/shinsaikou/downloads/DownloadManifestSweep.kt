package com.shinsaikou.downloads

import com.shinsaikou.domain.download.repository.DownloadManifestRepository
import com.shinsaikou.domain.preferences.LibraryPreferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.download.DownloadProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.domain.chapter.repository.ChapterRepository

/**
 * Phase 2 decision Q7(b), run at app start and when [DownloadWorker] starts:
 *
 * 1. a row whose chapter row is gone is re-matched to a current chapter of the same manga whose
 *    [DownloadProvider] names, current or legacy, include the row's folder name, and repointed at it;
 * 2. a row whose folder is missing is deleted (decision Q4: such a row is not a download);
 * 3. a row that still matches nothing keeps its row and its files. The download settings screen
 *    runs [rematch] and then lists those through [DownloadManifestRepository.getWithoutChapter],
 *    both when it counts them and again when the owner confirms the one delete.
 *
 * A match must be unique, a chapter that already has a download counted too. Two chapters answering
 * to one folder name would put one chapter's pages under the other, which is worse than leaving the
 * row unmatched.
 *
 * Step 2 runs under [DownloadWriteLock]. A migration moves a chapter's folder and then its row under
 * that lock ([MigrationDownloads]); between the two the row names a folder that is gone, and a step 2
 * reading it then would delete the row of a download that is still there.
 */
@Inject
@SingleIn(AppScope::class)
class DownloadManifestSweep(
    private val manifestRepository: DownloadManifestRepository,
    private val chapterRepository: ChapterRepository,
    private val locator: DownloadLocator,
    private val libraryPreferences: LibraryPreferences,
    private val writeLock: DownloadWriteLock,
) {

    data class Result(val rematched: Int, val removed: Int, val unmatched: Int)

    private val lock = Mutex()

    suspend fun run(): Result = lock.withLock {
        val rematched = rematchRows()
        val removed = removeMissing()
        Result(rematched = rematched, removed = removed, unmatched = manifestRepository.getWithoutChapter().size)
    }

    /** Step 1 alone: rows only, no file I/O, so the settings screen can run it each time it counts. */
    suspend fun rematch(): Int = lock.withLock { rematchRows() }

    private suspend fun rematchRows(): Int {
        val disallowNonAscii = libraryPreferences.disallowNonAsciiFilenames.get()
        var rematched = 0
        manifestRepository.getWithoutChapter().groupBy { it.mangaId }.forEach { (mangaId, rows) ->
            val chapters = chapterRepository.getChapterByMangaId(mangaId)
            if (chapters.isEmpty()) return@forEach
            val taken = manifestRepository.getByMangaId(mangaId).mapTo(mutableSetOf()) { it.chapterId }
            val namesById = chapters.associate { chapter ->
                // Current and legacy names; the legacy set carries the other ASCII setting's name.
                chapter.id to DownloadProvider.getValidChapterDirNames(
                    chapter.name,
                    chapter.scanlator,
                    chapter.url,
                    disallowNonAscii,
                ).toSet()
            }
            rows.forEach { row ->
                // Unique among all the chapters before a downloaded one is passed over: the folder
                // could hold that one's pages as well.
                val match = chapters
                    .filter { row.chapterDirName in namesById.getValue(it.id) }
                    .singleOrNull()
                    ?.takeUnless { it.id in taken }
                    ?: return@forEach
                manifestRepository.repoint(
                    fromChapterId = row.chapterId,
                    toChapterId = match.id,
                    chapterUrl = match.url,
                    chapterName = match.name,
                    scanlator = match.scanlator,
                )
                taken += match.id
                rematched++
            }
        }
        return rematched
    }

    private suspend fun removeMissing(): Int = writeLock.withLock {
        val missing = manifestRepository.getAll()
            .filter { locator.findChapterDir(it) == null }
            .map { it.chapterId }
        if (missing.isNotEmpty()) manifestRepository.remove(missing)
        missing.size
    }
}

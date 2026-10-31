package com.shinsaikou.downloads

import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.download.model.DownloadManifestEntry
import com.shinsaikou.domain.download.repository.DownloadManifestRepository
import com.shinsaikou.domain.download.repository.DownloadQueueRepository
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.source.SourceManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.download.DownloadProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import java.io.File
import java.io.IOException

/**
 * A migration's downloaded chapters (unit migrate-downloads, fixable warning F1): each downloaded
 * chapter of the old entry is matched to a chapter of the new entry and its files follow it, so the
 * new entry reads offline without downloading again.
 *
 * A downloaded chapter is one whose manifest row [DownloadLocator] finds the folder or archive of
 * (phase 2 decision Q4); a row whose files are gone is neither moved nor counted. Its match is the
 * new entry's chapter of the same recognised number with no manifest row of its own, the one with
 * the same scanlator first, else the first in the source's order ([match]).
 *
 * On a move, the old entry's queue rows go first, so a chapter of it in flight stops before its
 * files are read. Then, under [DownloadWriteLock], which the download engine and the adoption's
 * copier hold per chapter and the sweep holds while it deletes rows whose folder is gone, each
 * matched chapter's folder is renamed to the path the new chapter would download to and then its
 * row is pointed at the new chapter, entry, source and title. A copy writes the files under
 * `<chapter>_tmp`, renames them to the final name and records a row of the new chapter's, as the
 * engine commits a download, and the old entry keeps its own. Last, a matched chapter of the new
 * entry still queued leaves the queue, since it is downloaded now.
 *
 * A chapter whose step fails stays where it was with its row unchanged, and the rest go on. The
 * final path is refused while another row may be found at it, whether its files are there or not,
 * as the engine and the copier refuse it; a folder there that no row names is a commit that died
 * before its row and is replaced.
 *
 * The rename comes before the row write, so a process death between the two leaves the files at
 * the new chapter's path with no row: the engine reuses them as that chapter's `_tmp` folder when it
 * is downloaded, and the sweep removes the old row, whose folder is gone.
 */
@Inject
@SingleIn(AppScope::class)
class MigrationDownloads(
    private val manifestRepository: DownloadManifestRepository,
    private val queueRepository: DownloadQueueRepository,
    private val chapterRepository: ChapterRepository,
    private val downloadQueue: DownloadQueue,
    private val downloadManifest: DownloadManifest,
    private val downloadRoot: DownloadRoot,
    private val locator: DownloadLocator,
    private val writeLock: DownloadWriteLock,
    private val sourceManager: SourceManager,
    private val libraryPreferences: LibraryPreferences,
    private val dispatchers: Dispatchers,
) {

    /**
     * [transferred] are the new entry's chapters that now have the files, and [leftBehind] the old
     * entry's downloaded chapters that did not follow: no match, or a step that failed.
     */
    data class Outcome(val transferred: List<Long>, val leftBehind: List<Long>) {
        companion object {
            val NONE = Outcome(emptyList(), emptyList())
        }
    }

    /** Moves, or with [move] false copies, the downloaded chapters of [current] onto [target]. */
    suspend fun transfer(current: Manga, target: Manga, move: Boolean): Outcome {
        if (move) {
            step("empty the queue of entry ${current.id}") { downloadQueue.removeManga(current.id) }
        }
        val outcome = writeLock.withLock { transferLocked(current, target, move) }
        if (outcome.transferred.isNotEmpty()) {
            step("empty the queue of entry ${target.id}'s downloaded chapters") {
                val transferred = outcome.transferred.toSet()
                val queued = queueRepository.getByMangaId(target.id).map { it.chapterId }.filter { it in transferred }
                if (queued.isNotEmpty()) downloadQueue.remove(queued)
            }
        }
        return outcome
    }

    /**
     * The downloaded chapters of entry [mangaId], the definition [transfer] moves and counts by: its
     * manifest rows whose folder or archive [DownloadLocator] finds. A Migrate that does not carry
     * the downloads reports these (unit migrate-kept-downloads). One indexed read, and one `exists`
     * per row.
     */
    suspend fun downloadedChapters(mangaId: Long): List<Long> = located(mangaId).map { (row, _) -> row.chapterId }

    /** Deletes the files and rows of [chapterIds], under the lock the moves take. */
    suspend fun delete(chapterIds: List<Long>) {
        if (chapterIds.isEmpty()) return
        writeLock.withLock { downloadManifest.delete(chapterIds) }
    }

    private suspend fun transferLocked(current: Manga, target: Manga, move: Boolean): Outcome {
        val located = located(current.id)
        if (located.isEmpty()) return Outcome.NONE

        val oldChapters = chapterRepository.getChapterByMangaId(current.id).associateBy { it.id }
        val taken = manifestRepository.getByMangaId(target.id).mapTo(HashSet()) { it.chapterId }
        val matches = match(
            downloaded = located.mapNotNull { (row, _) -> oldChapters[row.chapterId] },
            candidates = chapterRepository.getChapterByMangaId(target.id),
            taken = taken,
        )
        val targetSource = sourceManager.get(target.source)
        if (matches.isEmpty() || targetSource == null) return Outcome(emptyList(), located.map { it.first.chapterId })

        val disallowNonAscii = libraryPreferences.disallowNonAsciiFilenames.get()
        val mangaDir = mangaDirIn(downloadRoot.root(), targetSource.name, target.title, disallowNonAscii)
        fun destNameOf(chapter: Chapter, dir: File) =
            DownloadProvider.getChapterDirName(chapter.name, chapter.scanlator, chapter.url, disallowNonAscii) +
                if (dir.isFile && dir.extension.isNotEmpty()) ".${dir.extension}" else ""
        // The rows named like a folder this entry's chapters go to, of any entry, in one read, grouped
        // by name; the check below consults no other name. A name is nearly unique, so each check
        // reads a few. The map follows the moves below, so a later chapter sees an earlier one's.
        val destNames = located.mapNotNull { (row, dir) -> matches[row.chapterId]?.let { destNameOf(it, dir) } }
        val byDirName = manifestRepository.getByChapterDirNames(destNames).groupByTo(HashMap()) { it.chapterDirName }

        val transferred = mutableListOf<Long>()
        val leftBehind = mutableListOf<Long>()
        located.forEach { (row, dir) ->
            val chapter = matches[row.chapterId]
            if (chapter == null) {
                leftBehind += row.chapterId
                return@forEach
            }
            val destName = destNameOf(chapter, dir)
            val dest = File(mangaDir, destName)
            val entry = row.copy(
                id = 0,
                chapterId = chapter.id,
                mangaId = target.id,
                sourceId = target.source,
                mangaTitle = target.title,
                mangaUrl = target.url,
                chapterUrl = chapter.url,
                chapterName = chapter.name,
                scanlator = chapter.scanlator,
                chapterDirName = destName,
            )
            try {
                val held = byDirName[destName].orEmpty()
                    .any { it.chapterId != row.chapterId && it.chapterId != chapter.id && locator.mayResolveTo(it, dest) }
                if (held) throw IOException("The new chapter's folder belongs to another download")
                if (move) moveChapter(row, dir, entry, dest) else copyChapter(dir, entry, dest)
                if (move) byDirName[row.chapterDirName]?.removeAll { it.chapterId == row.chapterId }
                byDirName.getOrPut(destName) { mutableListOf() } += entry
                transferred += chapter.id
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN) {
                    "Migration could not ${if (move) "move" else "copy"} the download of chapter ${row.chapterId} " +
                        "onto chapter ${chapter.id}: ${e::class.simpleName}"
                }
                leftBehind += row.chapterId
            }
        }
        return Outcome(transferred, leftBehind)
    }

    /** The manifest rows of entry [mangaId] whose files [DownloadLocator] finds, with those files. */
    private suspend fun located(mangaId: Long): List<Pair<DownloadManifestEntry, File>> =
        manifestRepository.getByMangaId(mangaId).mapNotNull { row -> locator.findChapterDir(row)?.let { row to it } }

    /** The rename, then the row; a row write that fails puts the files back. */
    private suspend fun moveChapter(row: DownloadManifestEntry, dir: File, entry: DownloadManifestEntry, dest: File) {
        val samePath = withContext(dispatchers.io) { dir.canonicalFile == dest.canonicalFile }
        if (!samePath) {
            withContext(dispatchers.io) {
                // No row names it (checked above): a commit that died before its row.
                if (dest.exists()) dest.deleteRecursively()
                makeParent(dest)
                if (!dir.renameTo(dest)) throw IOException("The chapter could not be renamed")
            }
        }
        try {
            manifestRepository.moveToChapter(row.chapterId, entry)
        } catch (e: Exception) {
            if (!samePath) withContext(dispatchers.io) { dest.renameTo(dir) }
            throw e
        }
        if (!samePath) withContext(dispatchers.io) { deleteEmptyParents(dir) }
    }

    /** The copy under `_tmp`, the rename to the final name, then the new chapter's row. */
    private suspend fun copyChapter(dir: File, entry: DownloadManifestEntry, dest: File) {
        val tmp = File(dest.parentFile, dest.name + DownloadProvider.TMP_DIR_SUFFIX)
        withContext(dispatchers.io) {
            if (dir.canonicalFile == dest.canonicalFile) throw IOException("The copy would be the chapter itself")
            try {
                tmp.deleteRecursively()
                if (dest.exists()) dest.deleteRecursively()
                makeParent(dest)
                dir.copyRecursively(tmp)
                if (!tmp.renameTo(dest)) throw IOException("The chapter could not be renamed")
            } catch (e: Exception) {
                tmp.deleteRecursively()
                throw e
            }
        }
        try {
            manifestRepository.recordDownload(entry)
        } catch (e: Exception) {
            withContext(dispatchers.io) { dest.deleteRecursively() }
            throw e
        }
    }

    private fun makeParent(file: File) {
        val parent = file.parentFile ?: throw IOException("The chapter has no folder")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("The manga folder could not be created")
    }

    /** The old manga and source folders, if the move left them empty. */
    private fun deleteEmptyParents(dir: File) {
        val manga = dir.parentFile ?: return
        if (manga.list().isNullOrEmpty()) manga.delete()
        val source = manga.parentFile ?: return
        if (source != downloadRoot.root() && source.list().isNullOrEmpty()) source.delete()
    }

    private inline fun step(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Migration could not $what" }
        }
    }

    internal companion object {

        /**
         * The new entry's chapter for each of [downloaded], by id: one of [candidates] with the same
         * recognised number and not in [taken] (a chapter already downloaded), each given at most
         * once. A candidate with the same scanlator wins, so one group's download never takes the
         * chapter another download of that group needs; then the first in the source's order.
         */
        fun match(downloaded: List<Chapter>, candidates: List<Chapter>, taken: Set<Long>): Map<Long, Chapter> {
            val byNumber = candidates
                .filter { it.isRecognizedNumber && it.id !in taken }
                .sortedBy { it.sourceOrder }
                .groupBy { it.chapterNumber }
            val wanting = downloaded.filter { it.isRecognizedNumber }.sortedBy { it.sourceOrder }
            val used = HashSet<Long>()
            val matched = LinkedHashMap<Long, Chapter>()
            fun pick(old: Chapter, accept: (Chapter) -> Boolean) {
                val match = byNumber[old.chapterNumber]?.firstOrNull { it.id !in used && accept(it) } ?: return
                matched[old.id] = match
                used += match.id
            }
            wanting.forEach { old -> pick(old) { group(it) == group(old) } }
            wanting.filter { it.id !in matched }.forEach { old -> pick(old) { true } }
            return matched
        }

        /** A blank scanlator is no scanlator. */
        private fun group(chapter: Chapter): String? = chapter.scanlator?.trim()?.takeIf { it.isNotEmpty() }
    }
}

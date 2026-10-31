package com.shinsaikou.downloads.adoption

import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.database.TransactionRunner
import com.shinsaikou.domain.download.model.DownloadManifestEntry
import com.shinsaikou.domain.download.repository.DownloadManifestRepository
import com.shinsaikou.domain.download.repository.DownloadQueueRepository
import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.downloads.ChapterComicInfoWriter
import com.shinsaikou.downloads.DownloadLocator
import com.shinsaikou.downloads.DownloadRoot
import com.shinsaikou.downloads.DownloadWriteLock
import com.shinsaikou.downloads.mangaDirIn
import com.shinsaikou.image.ImageUtil
import com.shinsaikou.source.SourceManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.download.DownloadProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE
import java.io.File
import java.io.IOException
import kotlin.time.Clock

/**
 * Copies one matched chapter out of upstream's tree into the download root, in the order the download
 * engine writes one (phase 2 unit G's carry-over for unit J):
 *
 * 1. into `<chapter>_tmp` beside where the chapter will live, the images of the folder and then its
 *    `ComicInfo.xml`, or one written the way the engine writes it when the folder has none; or, for
 *    a `.cbz`, the archive's bytes, under `<name>.cbz_tmp`;
 * 2. the check: the copy holds as many images as the folder it came from, or the archive is the
 *    size the tree reports and opens as an archive with at least one page;
 * 3. the rename to the chapter's final name, which is the name it had in the tree;
 * 4. one transaction that re-reads the download queue and the chapter's manifest row, and writes
 *    the row.
 *
 * So a chapter cut short anywhere before step 4 has no manifest row, and its final name never holds
 * a partial copy: the reader, which asks the manifest first, never sees a half-copied chapter.
 *
 * All four steps, and the checks before them, run under [DownloadWriteLock], which the download
 * engine holds for each chapter it downloads, so the two never write one chapter at once.
 *
 * The tree is only ever read. Everything written, renamed or deleted here is under
 * [DownloadRoot.root], which is private to this app.
 */
@Inject
@SingleIn(AppScope::class)
class AdoptionCopier(
    private val downloadRoot: DownloadRoot,
    private val locator: DownloadLocator,
    private val manifestRepository: DownloadManifestRepository,
    private val queueRepository: DownloadQueueRepository,
    private val transactionRunner: TransactionRunner,
    private val comicInfoWriter: ChapterComicInfoWriter,
    private val writeLock: DownloadWriteLock,
    private val archives: ChapterArchives,
    private val sourceManager: SourceManager,
    private val libraryPreferences: LibraryPreferences,
    private val dispatchers: Dispatchers,
) {

    sealed interface Outcome {
        data object Copied : Outcome

        /** A download of the chapter whose files are there already exists; nothing was written. */
        data object Skipped : Outcome

        data class Failed(val failure: AdoptionFailure) : Outcome
    }

    /** Thrown inside [adopt] to end one chapter with a named reason; never leaves this class. */
    private class Refused(val reason: AdoptionFailureReason, val detail: String? = null) : Exception()

    suspend fun adopt(match: AdoptionWalker.Match): Outcome = writeLock.withLock { adoptLocked(match) }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun adoptLocked(match: AdoptionWalker.Match): Outcome {
        val chapter = match.chapter
        // A row whose files are gone is not a download (phase 2 decision Q4); the copy replaces it.
        val existing = manifestRepository.getByChapterId(chapter.id)
        if (existing != null && locator.findChapterDir(existing) != null) return Outcome.Skipped
        if (queueRepository.getByChapterId(chapter.id) != null) {
            return Outcome.Failed(AdoptionFailure(match.path, AdoptionFailureReason.QUEUED))
        }

        val disallowNonAscii = libraryPreferences.disallowNonAsciiFilenames.get()
        val mangaDir = mangaDirIn(downloadRoot.root(), match.sourceName, match.manga.title, disallowNonAscii)
        val name = match.node.name
        val finalFile = File(mangaDir, name)
        val tmpFile = File(mangaDir, name + DownloadProvider.TMP_DIR_SUFFIX)

        return try {
            prepare(match, finalFile, tmpFile)
            val pageCount = if (match.isArchive) copyArchive(match, tmpFile) else copyFolder(match, tmpFile)
            withContext(NonCancellable) { commit(match, existing, pageCount, tmpFile, finalFile) }
        } catch (e: Refused) {
            discard(tmpFile)
            Outcome.Failed(AdoptionFailure(match.path, e.reason, detail = e.detail))
        } catch (e: CancellationException) {
            // The partial copy stays under its _tmp name; the next run starts it again.
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.WARN) { "Adopting chapter ${chapter.id} failed: ${e::class.simpleName}" }
            discard(tmpFile)
            val key = ErrorKey.of(e)
            val detail = ErrorKey.detail(e)
            Outcome.Failed(AdoptionFailure(match.path, AdoptionFailureReason.ERROR, key, detail))
        }
    }

    /**
     * Clears what an earlier, interrupted run left for this chapter: its partial copy, and a final
     * copy that never got its manifest row.
     *
     * A final path another chapter's row may be found at is that download's, and is left alone,
     * whether its files are there or not: deleting them loses that download, and a copy put there
     * would be read as that chapter. Every row that names the folder counts, whichever manga it
     * belongs to, because folders are keyed by source and title and never by manga id: two library
     * entries of one source with one title share their chapter folders, and a chapter the source
     * delisted keeps its row and its files (unit H's carry-over 5), and gets them back if it is
     * listed again, while the walker, which reads live chapters only, sees its folder name as
     * another entry's.
     */
    private suspend fun prepare(match: AdoptionWalker.Match, finalFile: File, tmpFile: File) {
        // The rows named like the folder, of any entry: DownloadLocator.mayResolveTo matches the name
        // first, so no other row can hold it.
        val taken = manifestRepository.getByChapterDirNames(listOf(finalFile.name))
            .any { it.chapterId != match.chapter.id && locator.mayResolveTo(it, finalFile) }
        if (taken) throw Refused(AdoptionFailureReason.DESTINATION_IN_USE)
        withContext(dispatchers.io) {
            finalFile.deleteRecursively()
            tmpFile.deleteRecursively()
            if (!tmpFile.parentFile!!.isDirectory && !tmpFile.parentFile!!.mkdirs()) {
                throw IOException("The manga folder could not be created")
            }
        }
    }

    /** Returns the page count: the images of the folder, each of them now in [tmpDir]. */
    private suspend fun copyFolder(match: AdoptionWalker.Match, tmpDir: File): Int {
        val (images, comicInfo) = withContext(dispatchers.io) {
            val files = match.node.children().filter { !it.isDirectory && it.name.isNotBlank() }
            // In name order, which is page order, so a stopped copy holds the first pages.
            files.filter { isImage(it) }.sortedBy { it.name } to files.firstOrNull { it.name == COMIC_INFO_FILE }
        }
        if (images.isEmpty()) throw Refused(AdoptionFailureReason.NO_PAGES)

        withContext(dispatchers.io) {
            if (!tmpDir.mkdirs()) throw IOException("The chapter folder could not be created")
            images.forEach { image ->
                currentCoroutineContext().ensureActive()
                copy(image, File(tmpDir, safeName(image.name)))
            }
            comicInfo?.let { copy(it, File(tmpDir, COMIC_INFO_FILE)) }
        }
        if (comicInfo == null) {
            comicInfoWriter.write(tmpDir, match.manga, match.chapter, sourceManager.getOrStub(match.manga.source))
        }

        val copied = withContext(dispatchers.io) {
            tmpDir.listFiles().orEmpty().count { it.isFile && ImageUtil.isImage(it.name) { it.inputStream() } }
        }
        if (copied != images.size) {
            throw Refused(AdoptionFailureReason.PAGE_COUNT_MISMATCH, "${images.size}/$copied")
        }
        return images.size
    }

    /** Returns the page count of the copied archive, which is byte for byte the one in the tree. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun copyArchive(match: AdoptionWalker.Match, tmpFile: File): Int = withContext(dispatchers.io) {
        val copied = copy(match.node, tmpFile)
        val expected = match.node.length()
        if (expected > 0 && copied != expected) throw Refused(AdoptionFailureReason.SIZE_MISMATCH, "$expected/$copied")
        val pages = try {
            archives.pageCount(tmpFile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw Refused(AdoptionFailureReason.NOT_AN_ARCHIVE, e::class.simpleName)
        }
        if (pages == 0) throw Refused(AdoptionFailureReason.NO_PAGES)
        pages
    }

    /**
     * Steps 3 and 4. The transaction re-reads the queue as the engine's does: a chapter queued for
     * download while it was being copied is the engine's to write, so the copy is not recorded and
     * goes. It re-reads the chapter's manifest row too, and records only over the row [existing]
     * saw: a row that appeared or changed meanwhile, which the sweep's re-match writes without
     * [DownloadWriteLock], is a download of the chapter whose files are elsewhere, and the upsert
     * would point it at this copy and leave those files with no row. The copy goes then, unless that
     * row names the copy's own path.
     */
    private suspend fun commit(
        match: AdoptionWalker.Match,
        existing: DownloadManifestEntry?,
        pageCount: Int,
        tmpFile: File,
        finalFile: File,
    ): Outcome {
        val sizeBytes = withContext(dispatchers.io) {
            val size = if (tmpFile.isDirectory) tmpFile.listFiles().orEmpty().sumOf { it.length() } else tmpFile.length()
            if (!tmpFile.renameTo(finalFile)) throw IOException("The chapter could not be renamed")
            size
        }
        val manga = match.manga
        val chapter = match.chapter
        val entry = DownloadManifestEntry(
            id = 0,
            chapterId = chapter.id,
            mangaId = manga.id,
            sourceId = manga.source,
            mangaTitle = manga.title,
            mangaUrl = manga.url,
            chapterUrl = chapter.url,
            chapterName = chapter.name,
            scanlator = chapter.scanlator,
            chapterDirName = finalFile.name,
            pageCount = pageCount.toLong(),
            sizeBytes = sizeBytes,
            dateDownloaded = Clock.System.now().toEpochMilliseconds(),
        )
        val recorded = transactionRunner.inTransaction {
            val current = manifestRepository.getByChapterId(chapter.id)
            when {
                queueRepository.getByChapterId(chapter.id) != null -> Commit.Queued
                current != null && current != existing -> Commit.RecordedMeanwhile(current)
                else -> {
                    manifestRepository.recordDownload(entry)
                    Commit.Recorded
                }
            }
        }
        return when (recorded) {
            Commit.Recorded -> Outcome.Copied
            Commit.Queued -> {
                discard(finalFile)
                Outcome.Failed(AdoptionFailure(match.path, AdoptionFailureReason.QUEUED))
            }
            is Commit.RecordedMeanwhile -> {
                if (!locator.mayResolveTo(recorded.entry, finalFile)) discard(finalFile)
                Outcome.Skipped
            }
        }
    }

    private sealed interface Commit {
        data object Recorded : Commit

        data object Queued : Commit

        data class RecordedMeanwhile(val entry: DownloadManifestEntry) : Commit
    }

    /** Streams [from] into [to] and returns the bytes written. */
    private fun copy(from: AdoptionNode, to: File): Long =
        from.openStream().use { input -> to.outputStream().use { output -> input.copyTo(output) } }

    /** An image by its name, as the reader's folder loader decides, reading its first bytes only for a name that says nothing. */
    private fun isImage(node: AdoptionNode): Boolean = ImageUtil.isImage(node.name) { node.openStream() }

    /** A provider's display name is a file name, but it is checked before it becomes one here. */
    private fun safeName(name: String): String {
        if ('/' in name || name == "." || name == "..") throw IOException("An unusable file name")
        return name
    }

    private suspend fun discard(file: File) {
        withContext(NonCancellable + dispatchers.io) { file.deleteRecursively() }
    }
}

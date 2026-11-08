package com.shinsaikou.downloads

import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.chapter.model.PageListEmptyException
import com.shinsaikou.domain.database.TransactionRunner
import com.shinsaikou.domain.download.model.ChapterFolderInUseException
import com.shinsaikou.domain.download.model.DownloadManifestEntry
import com.shinsaikou.domain.download.model.DownloadQueueEntry
import com.shinsaikou.domain.download.model.DownloadState
import com.shinsaikou.domain.download.repository.DownloadManifestRepository
import com.shinsaikou.domain.download.repository.DownloadQueueRepository
import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.domain.preferences.DownloadPreferences
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.source.toChapterInfo
import com.shinsaikou.image.ImageUtil
import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceManager
import com.shinsaikou.source.SourceNotInstalledException
import com.shinsaikou.source.StubSource
import com.shinsaikou.source.model.PageInfo
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.network.HttpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.time.Clock

/**
 * Drains `download_queue` one chapter at a time, in `queue_order`. [DownloadWorker] hosts it; a
 * stopped worker is a cancelled [run], and the next [run] resumes from what the rows and the
 * chapter folders still hold (phase 2 decision Q4(a)(i)). With the Experimental switch
 * [DownloadPreferences.parallelSourceDownloads] on, it runs one chapter from each of several
 * sources at once instead ([runBySource]); this amends R65, which left
 * [DownloadPreferences.parallelSourceLimit] unread.
 *
 * Per chapter, and in this order:
 * 1. the page list from the source,
 * 2. every page through [Source.getImage], so the source's own client and rate limit carry it,
 *    written as `NNN.tmp` and renamed to `NNN.<ext>` inside `<chapter>_tmp`; a page already there
 *    under its final name is kept, and a `.tmp` left by a killed write is fetched again,
 * 3. the chapter's `ComicInfo.xml`,
 * 4. the rename of `<chapter>_tmp` to `<chapter>`,
 * 5. the manifest insert and the queue delete, in one transaction.
 *
 * With [DownloadPreferences.saveChaptersAsCBZ] on, read as each chapter starts, step 4 is instead
 * the folder's pages, in page order, and its `ComicInfo.xml` written as STORED zip entries into
 * `<chapter>.cbz_tmp`, synced, and renamed to `<chapter>.cbz`; the manifest row names the archive,
 * as it does for one adopted from upstream's downloads, and `<chapter>_tmp` goes once the row is
 * written. This amends R65 (`saveChaptersAsCBZ` is read) and builds R162 item 7. Off, the engine
 * writes folders exactly as before, and an entry may hold both kinds, since everything that finds a
 * download goes through its manifest row's name.
 *
 * The order is load bearing. `PageLoaderResolver` consults the manifest before anything else, so a
 * manifest row must never exist before its folder or archive has its final name.
 *
 * A run starts by deleting the `_tmp` folders no queue row will resume ([sweepLeftovers]).
 *
 * Each chapter is downloaded under [DownloadWriteLock], so a chapter the adoption of upstream's
 * downloads is copying is never written by both at once (R71). Several sources at once hold it
 * through [DownloadWriteLock.withFolderLock], which still keeps the adoption out for the whole
 * chapter. A chapter folder another chapter's manifest row may be found at is that download's, and
 * the chapter fails rather than take it over (ruling draft R-J7).
 */
@Inject
@SingleIn(AppScope::class)
class DownloadEngine(
    private val queueRepository: DownloadQueueRepository,
    private val manifestRepository: DownloadManifestRepository,
    private val transactionRunner: TransactionRunner,
    private val getManga: GetManga,
    private val chapterRepository: ChapterRepository,
    private val sourceManager: SourceManager,
    private val downloadRoot: DownloadRoot,
    private val downloadPreferences: DownloadPreferences,
    private val libraryPreferences: LibraryPreferences,
    private val comicInfoWriter: ChapterComicInfoWriter,
    private val locator: DownloadLocator,
    private val writeLock: DownloadWriteLock,
    private val dispatchers: Dispatchers,
) {

    /**
     * What the foreground notification shows: the chapter in flight, its pages, and [chaptersLeft],
     * the chapters the run still had to download when this one started, this one included.
     *
     * With several sources at once, [chaptersRunning] is the number of chapters whose pages are
     * downloading. Past one, the report is their sum ([ProgressBoard]): the pages of all of them and
     * the names and id of the one that started first. There [chaptersLeft] follows the queue: the
     * rows still queued or running as the queue stood when a chapter last started or ended, since a
     * count taken when each chapter started would not drop as another source's chapters finish.
     */
    data class Progress(
        val chapterId: Long,
        val mangaTitle: String,
        val chapterName: String,
        val pagesDone: Int,
        val pagesTotal: Int,
        val chaptersLeft: Int,
        val chaptersRunning: Int = 1,
    )

    /**
     * [storageFull]: the run stopped before a chapter because too little storage was free.
     * [interrupted]: it stopped at a chapter that failed for want of a network, put back in the queue,
     * for WorkManager to run again once its network constraint holds. Either way the rows stay queued.
     */
    data class Outcome(
        val completed: Int,
        val failed: Int,
        val storageFull: Boolean = false,
        val interrupted: Boolean = false,
    )

    private enum class ChapterResult { COMPLETED, FAILED, DROPPED, INTERRUPTED }

    private val runLock = Mutex()
    private val activeLock = Any()

    /** The chapters in flight by chapter id: one at a time, or one per source with the switch on. */
    private val active = HashMap<Long, Deferred<ChapterResult>>()

    /**
     * Network failures in a row per chapter, for [MAX_NETWORK_REQUEUES]. Kept for the life of the
     * process, which is where WorkManager's retries run; a process death only grants a few more.
     */
    private val networkFailures = ConcurrentHashMap<Long, Int>()

    /**
     * Downloads every active queue row, re-reading the queue after each chapter so rows enqueued,
     * removed or reordered meanwhile are honoured. A chapter that fails after its retries is left
     * in [DownloadState.ERROR] with its `_tmp` folder, and the run moves on. With less than
     * [MIN_FREE_BYTES] free before a chapter, the run ends there and the rest stay queued.
     *
     * A chapter that fails for want of a network ([isNetworkFailure]) would fail every chapter after
     * it the same way before WorkManager noticed the network had gone, so it goes back in the queue
     * and the run ends [Outcome.interrupted]. After [MAX_NETWORK_REQUEUES] such runs in a row it
     * fails like any other chapter, so one that never downloads does not hold the queue forever.
     */
    suspend fun run(onProgress: (Progress) -> Unit = {}): Outcome = runLock.withLock {
        // A source that has not loaded yet would read as not installed and fail every chapter.
        sourceManager.isInitialized.first { it }
        sweepLeftovers()

        var completed = 0
        val failed = mutableSetOf<Long>()
        while (true) {
            // Read before each chapter, so turning the switch on applies from the next chapter.
            if (downloadPreferences.parallelSourceDownloads.get()) {
                return@withLock runBySource(completed, failed, onProgress)
            }
            val waiting = queueRepository.getActive().filter { it.chapterId !in failed }
            val next = waiting.firstOrNull() ?: break
            if (withContext(dispatchers.io) { downloadRoot.isLowOnSpace() }) {
                logcat(LogPriority.WARN) { "Downloads stopped: less than $MIN_FREE_BYTES bytes free" }
                return@withLock Outcome(completed = completed, failed = failed.size, storageFull = true)
            }
            val result = runChapter(next, chaptersLeft = waiting.size, onProgress)
            if (result != ChapterResult.INTERRUPTED) networkFailures.remove(next.chapterId)
            when (result) {
                ChapterResult.COMPLETED -> completed++
                ChapterResult.FAILED -> failed += next.chapterId
                ChapterResult.DROPPED -> Unit
                ChapterResult.INTERRUPTED ->
                    return@withLock Outcome(completed = completed, failed = failed.size, interrupted = true)
            }
        }
        Outcome(completed = completed, failed = failed.size)
    }

    /**
     * Deletes the `_tmp` folders, and the `.cbz_tmp` files a copy from a folder or a chapter saved as
     * a CBZ file writes, that no queue row will resume: a chapter removed from the queue while its
     * folder could not be deleted, a chapter renamed since, a copy a stopped run left, an archive a
     * stopped run was writing (its pages are still in the `_tmp` folder, which the queue row keeps).
     * Only `<source>/<manga>/<name>_tmp` under the
     * root is looked at. The `_tmp` of every queue row is kept, a failed one's too, since its retry
     * reuses the pages, and so is any name a manifest row records. It runs under [DownloadWriteLock],
     * which every writer of the root holds per chapter, so none is being written.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun sweepLeftovers() {
        try {
            writeLock.withLock {
                val leftovers = withContext(dispatchers.io) { tmpEntries() }
                if (leftovers.isEmpty()) return@withLock
                val kept = queuedTmpPaths()
                val recorded = manifestRepository.getByChapterDirNames(leftovers.map { it.name }.distinct())
                val gone = leftovers.filter { file ->
                    tmpKey(file.parentFile!!.name, file.name) !in kept &&
                        recorded.none { locator.mayResolveTo(it, file) }
                }
                withContext(dispatchers.io) { gone.forEach { it.deleteRecursively() } }
                if (gone.isNotEmpty()) logcat(LogPriority.INFO) { "Deleted ${gone.size} unfinished chapter folders" }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Housekeeping; a failure in it must not hold the queue back.
            logcat(LogPriority.ERROR, e) { "The unfinished chapter sweep failed" }
        }
    }

    private fun tmpEntries(): List<File> =
        downloadRoot.root().listFiles { file -> file.isDirectory }.orEmpty().flatMap { sourceDir ->
            sourceDir.listFiles { file -> file.isDirectory }.orEmpty().flatMap { mangaDir ->
                mangaDir.listFiles { file -> file.name.endsWith(DownloadProvider.TMP_DIR_SUFFIX) }
                    .orEmpty().asList()
            }
        }

    /** Every `<manga>/<chapter>_tmp` a queue row's chapter may be written under, in any source folder. */
    private suspend fun queuedTmpPaths(): Set<String> {
        val rows = queueRepository.subscribeAll().first()
        if (rows.isEmpty()) return emptySet()
        val chapters = chapterRepository.getChaptersByIds(rows.map { it.chapterId })
        val titles = chapters.map { it.mangaId }.distinct().associateWith { getManga.await(it)?.title }
        return buildSet {
            chapters.forEach { chapter ->
                val title = titles[chapter.mangaId] ?: return@forEach
                for (ascii in listOf(false, true)) {
                    val name = DownloadProvider.getChapterDirName(chapter.name, chapter.scanlator, chapter.url, ascii)
                    val tmpName = name + DownloadProvider.TMP_DIR_SUFFIX
                    for (titleAscii in listOf(false, true)) {
                        add(tmpKey(DownloadProvider.getMangaDirName(title, titleAscii), tmpName))
                    }
                }
            }
        }
    }

    private fun tmpKey(mangaDirName: String, name: String) = "$mangaDirName/$name"

    /**
     * The run while [DownloadPreferences.parallelSourceDownloads] is on (Experimental; amends R65,
     * under which [DownloadPreferences.parallelSourceLimit] was never read). Up to that many sources
     * download at once, one chapter each. A source's chapters still go one after another in queue
     * order, so no source is asked for two chapters at a time, and the pages of each chapter still
     * run [DownloadPreferences.parallelPageLimit] at a time.
     *
     * The switch and the limit are read again at every chapter boundary, when a chapter ends and the
     * free places are filled, so a change applies from the next chapter to start; the switch turned
     * off mid-run leaves one chapter at a time until the run ends. The queue is read again there
     * too, so rows enqueued, removed or reordered meanwhile decide what starts next, as in [run].
     * Too little storage, or a chapter put back for want of a network, stops new chapters from
     * starting; the ones running finish, then the run ends as [run] would end there.
     */
    private suspend fun runBySource(
        completedBefore: Int,
        failed: MutableSet<Long>,
        onProgress: (Progress) -> Unit,
    ): Outcome = coroutineScope {
        var completed = completedBefore
        var storageFull = false
        var interrupted = false
        val board = ProgressBoard(onProgress)
        // Chapter id to its source id; null for a chapter whose entry is gone, which is only dropped.
        val running = HashMap<Long, Long?>()
        val sourceOfManga = HashMap<Long, Long?>()
        val finished = Channel<Pair<Long, ChapterResult>>(Channel.UNLIMITED)
        var ended: Long? = null
        while (true) {
            // Read at every boundary, a stopping run's too, so the count the notification shows is
            // the rows still queued or running, the ones just started included.
            val waiting = queueRepository.getActive().filter { it.chapterId !in failed }
            board.boundary(left = waiting.size, ended = ended)
            ended = null
            if (!storageFull && !interrupted) {
                val limit = sourceLimit()
                // A source is busy while one of its chapters runs. Only a source's first waiting row
                // may start, and only once the source is free, so its rows keep their queue order.
                val busy = running.values.toMutableSet()
                for (entry in waiting) {
                    if (running.size >= limit) break
                    if (entry.chapterId in running) continue
                    val sourceId = if (entry.mangaId in sourceOfManga) {
                        sourceOfManga[entry.mangaId]
                    } else {
                        getManga.await(entry.mangaId)?.source.also { sourceOfManga[entry.mangaId] = it }
                    }
                    if (!busy.add(sourceId)) continue
                    if (withContext(dispatchers.io) { downloadRoot.isLowOnSpace() }) {
                        logcat(LogPriority.WARN) { "Downloads stopped: less than $MIN_FREE_BYTES bytes free" }
                        storageFull = true
                        break
                    }
                    running[entry.chapterId] = sourceId
                    launch {
                        val result = runChapter(entry, chaptersLeft = waiting.size, board::report, parallel = true)
                        finished.send(entry.chapterId to result)
                    }
                }
            }
            if (running.isEmpty()) break
            val (chapterId, result) = finished.receive()
            running.remove(chapterId)
            // It leaves the board at the next boundary, with the count read after its commit.
            ended = chapterId
            if (result != ChapterResult.INTERRUPTED) networkFailures.remove(chapterId)
            when (result) {
                ChapterResult.COMPLETED -> completed++
                ChapterResult.FAILED -> failed += chapterId
                ChapterResult.DROPPED -> Unit
                ChapterResult.INTERRUPTED -> interrupted = true
            }
        }
        Outcome(completed = completed, failed = failed.size, storageFull = storageFull, interrupted = interrupted)
    }

    /** Sources at once for the next chapter to start; one with the switch turned off mid-run. */
    private fun sourceLimit(): Int =
        if (downloadPreferences.parallelSourceDownloads.get()) {
            downloadPreferences.parallelSourceLimit.get().coerceIn(1, MAX_PARALLEL_SOURCES)
        } else {
            1
        }

    /**
     * Stops the chapters in flight that are among [chapterIds], and returns once they have stopped,
     * so the caller can delete their `_tmp` folders without racing a page write. The others run on.
     */
    suspend fun cancel(chapterIds: Collection<Long>) {
        val jobs = synchronized(activeLock) { chapterIds.mapNotNull { active[it] } }
        jobs.forEach { it.cancelAndJoin() }
    }

    /**
     * One chapter under [DownloadWriteLock]: the whole lock one chapter at a time, as R71 has it, or,
     * with several sources at once ([parallel]), the hold that keeps the adoption out just the same
     * but lets engine chapters of other folder names run beside it.
     */
    private suspend fun runChapter(
        entry: DownloadQueueEntry,
        chaptersLeft: Int,
        onProgress: (Progress) -> Unit,
        parallel: Boolean = false,
    ): ChapterResult =
        coroutineScope {
            val job = async(start = CoroutineStart.LAZY) {
                if (parallel) {
                    writeLock.withFolderLock(folderNames(entry)) { downloadChapter(entry, chaptersLeft, onProgress) }
                } else {
                    writeLock.withLock { downloadChapter(entry, chaptersLeft, onProgress) }
                }
            }
            synchronized(activeLock) { active[entry.chapterId] = job }
            try {
                // The queue was read before the job was registered, so a removal in between found
                // nothing to cancel. A removal deletes the row before it cancels, so the row read
                // after registering is either gone, and the chapter is dropped, or still there, and
                // the removal's cancel will find the job.
                if (queueRepository.getByChapterId(entry.chapterId) == null) {
                    job.cancel()
                    return@coroutineScope ChapterResult.DROPPED
                }
                job.await()
            } catch (e: CancellationException) {
                // Either [cancel] stopped this chapter, and the run goes on, or the run itself is
                // being stopped, and ensureActive rethrows.
                currentCoroutineContext().ensureActive()
                ChapterResult.DROPPED
            } finally {
                synchronized(activeLock) { if (active[entry.chapterId] === job) active.remove(entry.chapterId) }
            }
        }

    /**
     * Every name the chapter's folder may have, under either value of
     * [LibraryPreferences.disallowNonAsciiFilenames] and with case folded, since a shared folder can
     * be on storage that ignores case. Two engine chapters whose names meet would write one `_tmp`
     * folder, or one would record a manifest row the other's R71 part 2 check had not seen, so they
     * do not run at once. Every manifest row a folder may resolve to has the folder's own name
     * ([DownloadLocator.mayResolveTo]), so the name alone decides it. A chapter no longer stored has
     * none; it is only dropped.
     */
    private suspend fun folderNames(entry: DownloadQueueEntry): List<String> {
        val chapter = chapterRepository.getChapterById(entry.chapterId) ?: return emptyList()
        return listOf(false, true).map { ascii ->
            DownloadProvider.getChapterDirName(chapter.name, chapter.scanlator, chapter.url, ascii)
                .lowercase(Locale.ROOT)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun downloadChapter(
        entry: DownloadQueueEntry,
        chaptersLeft: Int,
        onProgress: (Progress) -> Unit,
    ): ChapterResult {
        val manga = getManga.await(entry.mangaId)
        val chapter = chapterRepository.getChapterById(entry.chapterId)
        if (manga == null || chapter == null || chapter.deletedAt != null) {
            // The foreign keys cascade a hard delete; a soft-deleted chapter has nothing to fetch.
            queueRepository.remove(listOf(entry.chapterId))
            return ChapterResult.DROPPED
        }

        return try {
            val source = sourceManager.getOrStub(manga.source)
            if (source is StubSource) throw SourceNotInstalledException(manga.source)
            queueRepository.setState(entry.chapterId, DownloadState.DOWNLOADING, null, now())
            download(manga, chapter, source, chaptersLeft, onProgress)
        } catch (e: Throwable) {
            if (e is CancellationException && !currentCoroutineContext().isActive) {
                // A stopped worker leaves the row queued; its finished pages stay in the _tmp folder.
                withContext(NonCancellable) {
                    queueRepository.setState(entry.chapterId, DownloadState.QUEUED, null, now())
                }
                throw e
            }
            // Anything else, a CancellationException a source threw while this coroutine is still
            // active included, is the chapter's failure; rethrowing it would requeue it forever.
            logcat(LogPriority.WARN) { "Chapter ${entry.chapterId} failed to download: ${e::class.simpleName}" }
            val requeue = isNetworkFailure(e) &&
                networkFailures.merge(entry.chapterId, 1, Int::plus)!! <= MAX_NETWORK_REQUEUES
            if (requeue) {
                queueRepository.setState(entry.chapterId, DownloadState.QUEUED, null, now())
                return ChapterResult.INTERRUPTED
            }
            queueRepository.setState(entry.chapterId, DownloadState.ERROR, storedError(e), now())
            ChapterResult.FAILED
        }
    }

    /**
     * No network, no answer in time, or a connection lost mid-transfer, which is what a dropped
     * Wi-Fi throws. The failure, not the source, is to blame, and a run later can succeed.
     */
    private fun isNetworkFailure(e: Throwable): Boolean =
        ErrorKey.of(e) in NETWORK_FAILURES || e is SocketException || e.cause is SocketException

    private suspend fun download(
        manga: Manga,
        chapter: Chapter,
        source: Source,
        chaptersLeft: Int,
        onProgress: (Progress) -> Unit,
    ): ChapterResult {
        val disallowNonAscii = libraryPreferences.disallowNonAsciiFilenames.get()
        val mangaDir = mangaDirIn(downloadRoot.root(), source.name, manga.title, disallowNonAscii)
        val dirName = DownloadProvider.getChapterDirName(chapter.name, chapter.scanlator, chapter.url, disallowNonAscii)
        val finalDir = File(mangaDir, dirName)
        val tmpDir = File(mangaDir, dirName + DownloadProvider.TMP_DIR_SUFFIX)
        val archiveFile = File(mangaDir, dirName + ARCHIVE_SUFFIX)
        // Read per chapter, so a change applies from the next chapter on (R162 item 7).
        val archive = archiveFile.takeIf { downloadPreferences.saveChaptersAsCBZ.get() }

        val row = manifestRepository.getByChapterId(chapter.id)
        val downloaded = finalDir.exists() || (row?.chapterDirName == archiveFile.name && archiveFile.exists())
        if (downloaded && row != null) {
            // Already downloaded, as a folder or as the archive its row names, and queued again;
            // nothing to fetch.
            queueRepository.remove(listOf(chapter.id))
            return ChapterResult.DROPPED
        }
        if (heldByAnotherDownload(chapter, listOfNotNull(finalDir, archive))) {
            throw ChapterFolderInUseException(chapter.id)
        }
        withContext(dispatchers.io) { prepareTmpDir(finalDir, tmpDir, archive) }

        // Reading order is list order; a source's own indexing is not trusted.
        val pages = retrying { source.getPageList(chapter.toChapterInfo()) }
            .mapIndexed { index, page -> page.copy(index = index) }
        if (pages.isEmpty()) throw PageListEmptyException(chapter.id)

        val digits = max(MIN_PAGE_DIGITS, pages.size.toString().length)
        val prefixes = pages.map { "%0${digits}d".format(Locale.ENGLISH, it.number) }
        val present = withContext(dispatchers.io) { finishedPrefixes(tmpDir) }

        val progressLock = Mutex()
        var done = prefixes.count { it in present }
        queueRepository.setProgress(chapter.id, done.toLong(), pages.size.toLong())
        fun report() =
            onProgress(Progress(chapter.id, manga.displayTitle, chapter.name, done, pages.size, chaptersLeft))
        report()

        val semaphore = Semaphore(downloadPreferences.parallelPageLimit.get().coerceAtLeast(1))
        val failures = coroutineScope {
            pages.mapIndexed { i, page ->
                async {
                    if (prefixes[i] in present) return@async null
                    semaphore.withPermit {
                        try {
                            retrying { fetchPage(source, page, tmpDir, prefixes[i]) }
                            progressLock.withLock {
                                done++
                                queueRepository.setProgress(chapter.id, done.toLong(), pages.size.toLong())
                                report()
                            }
                            null
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            e
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        // Every page is attempted before the chapter fails, so a later run fetches only the rest.
        failures.firstOrNull()?.let { throw it }

        withContext(dispatchers.io) { verifyPages(tmpDir, prefixes.toSet()) }
        comicInfoWriter.write(tmpDir, manga, chapter, source)

        if (archive == null) {
            return withContext(NonCancellable) { commit(manga, chapter, pages.size, tmpDir, finalDir, dirName) }
        }
        val archiveTmp = archiveTmpOf(archive)
        writeArchive(tmpDir, archiveTmp)
        return withContext(NonCancellable) { commitArchive(manga, chapter, pages.size, tmpDir, archiveTmp, archive) }
    }

    /**
     * Steps 4 and 5. The transaction re-reads the queue row: a chapter cancelled after its last page
     * has no row left, so it is not recorded and its folder goes.
     */
    private suspend fun commit(
        manga: Manga,
        chapter: Chapter,
        pageCount: Int,
        tmpDir: File,
        finalDir: File,
        dirName: String,
    ): ChapterResult {
        val sizeBytes = withContext(dispatchers.io) {
            tmpDir.listFiles().orEmpty().sumOf { it.length() }.also {
                if (!tmpDir.renameTo(finalDir)) throw IOException("The chapter folder could not be renamed")
            }
        }
        if (!record(manga, chapter, pageCount, dirName, sizeBytes)) {
            withContext(dispatchers.io) { finalDir.deleteRecursively() }
            return ChapterResult.DROPPED
        }
        return ChapterResult.COMPLETED
    }

    /**
     * Writes [tmpDir]'s pages, in page order, and then its `ComicInfo.xml` into [archiveTmp] as a zip
     * of STORED entries, as upstream's archive step does: the pages are images already, and a stored
     * entry is read without inflating it. The file is synced before the rename makes it the chapter,
     * so a power cut cannot leave a short archive under the final name. A failure or a cancel deletes
     * [archiveTmp] and keeps the pages, which the next attempt reuses.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun writeArchive(tmpDir: File, archiveTmp: File) {
        try {
            withContext(dispatchers.io) {
                val files = tmpDir.listFiles().orEmpty()
                    .filter { it.isFile && it.name != COMIC_INFO_FILE && !it.name.endsWith(PAGE_TMP_SUFFIX) }
                    .sortedBy { it.name } +
                    File(tmpDir, COMIC_INFO_FILE)
                FileOutputStream(archiveTmp).use { output ->
                    // Closed after the sync, which needs the file still open, so the native
                    // Deflater every ZipOutputStream holds is released here, not by the collector.
                    ZipOutputStream(BufferedOutputStream(output)).use { zip ->
                        zip.setMethod(ZipOutputStream.STORED)
                        files.forEach { file ->
                            currentCoroutineContext().ensureActive()
                            zip.putStored(file)
                        }
                        zip.finish()
                        zip.flush()
                        output.fd.sync()
                    }
                }
            }
        } catch (e: Throwable) {
            withContext(NonCancellable + dispatchers.io) { archiveTmp.delete() }
            throw e
        }
    }

    /** A STORED entry needs its size and CRC before its bytes, so the file is read twice. */
    private fun ZipOutputStream.putStored(file: File) {
        val crc = CRC32()
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                crc.update(buffer, 0, read)
            }
        }
        val length = file.length()
        putNextEntry(
            ZipEntry(file.name).apply {
                method = ZipEntry.STORED
                size = length
                compressedSize = length
                this.crc = crc.value
                time = file.lastModified()
            },
        )
        file.inputStream().use { it.copyTo(this) }
        closeEntry()
    }

    /**
     * Steps 4 and 5 for an archive: the rename of [archiveTmp] to [archive], the transaction [commit]
     * runs, and only then the delete of [tmpDir].
     *
     * An archive already at [archive] has no row of its own (the chapter would have been dropped as
     * downloaded) and none of another chapter's ([heldByAnotherDownload]). It is a commit that died
     * before its row, or a `.cbz` a migration moved to this chapter's path before a process death
     * kept its row from following, and then it may be the only copy of the chapter. So it stays until
     * this point and the rename replaces it (a rename over a file replaces it in one step); a
     * download that failed before here left it as it was.
     *
     * A rename that fails leaves that archive alone. A transaction that fails deletes the new
     * archive, so no `.cbz` without a row is left, and keeps the pages, which hold the whole chapter,
     * for the next attempt. A chapter cancelled after its last page loses both, as [commit] deletes
     * its folder.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun commitArchive(
        manga: Manga,
        chapter: Chapter,
        pageCount: Int,
        tmpDir: File,
        archiveTmp: File,
        archive: File,
    ): ChapterResult {
        var renamed = false
        val recorded = try {
            val sizeBytes = withContext(dispatchers.io) {
                archiveTmp.length().also {
                    if (!archiveTmp.renameTo(archive)) throw IOException("The chapter archive could not be renamed")
                    renamed = true
                }
            }
            record(manga, chapter, pageCount, archive.name, sizeBytes)
        } catch (e: Throwable) {
            withContext(dispatchers.io) {
                archiveTmp.delete()
                if (renamed) archive.delete()
            }
            throw e
        }
        withContext(dispatchers.io) {
            if (!recorded) archive.delete()
            tmpDir.deleteRecursively()
        }
        return if (recorded) ChapterResult.COMPLETED else ChapterResult.DROPPED
    }

    /**
     * Step 5: the manifest row named [chapterDirName], in one transaction with the queue delete.
     * Returns false, and writes nothing, when the chapter's queue row is gone.
     */
    private suspend fun record(
        manga: Manga,
        chapter: Chapter,
        pageCount: Int,
        chapterDirName: String,
        sizeBytes: Long,
    ): Boolean {
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
            chapterDirName = chapterDirName,
            pageCount = pageCount.toLong(),
            sizeBytes = sizeBytes,
            dateDownloaded = now(),
        )
        return transactionRunner.inTransaction {
            if (queueRepository.getByChapterId(chapter.id) == null) {
                false
            } else {
                manifestRepository.recordDownload(entry)
                queueRepository.remove(listOf(chapter.id))
                true
            }
        }
    }

    /**
     * Whether a manifest row of another chapter may be found at [finalDir], whether its files are
     * there or not. Folders are keyed by source name and title, never by manga id, so two library
     * entries of one source with one title share their chapter folders, and a chapter the source
     * delisted keeps its row and its files. Taking such a folder over would reuse that download's
     * pages as this chapter's and leave its row pointing at this chapter's files; writing into it
     * while the files are gone would have that row read this chapter. Only the rows named like the
     * folder are read, since [DownloadLocator.mayResolveTo] matches the name first; until the
     * migrate-downloads follow-up each chapter read the whole manifest here. A chapter saved as an
     * archive checks the archive's name and the folder's, since [prepareTmpDir] reuses the folder.
     */
    private suspend fun heldByAnotherDownload(chapter: Chapter, paths: List<File>): Boolean =
        manifestRepository.getByChapterDirNames(paths.map { it.name })
            .any { row -> row.chapterId != chapter.id && paths.any { locator.mayResolveTo(row, it) } }

    /**
     * A final folder with no manifest row, of this chapter or any other, is a commit that died
     * between the rename and the transaction; it goes back to being the `_tmp` folder, so its pages
     * are reused. With [archive] set, a partial archive is a write that died and goes; an archive
     * already in place stays until [commitArchive] replaces it, since the `_tmp` folder may not hold
     * its pages.
     */
    private fun prepareTmpDir(finalDir: File, tmpDir: File, archive: File?) {
        if (archive != null) archiveTmpOf(archive).delete()
        if (finalDir.exists()) {
            if (tmpDir.exists()) finalDir.deleteRecursively() else finalDir.renameTo(tmpDir)
        }
        if (!tmpDir.isDirectory && !tmpDir.mkdirs()) {
            throw IOException("The chapter folder could not be created")
        }
    }

    /** Page prefixes already written under their final name. A `NNN.tmp` is a torn write and does not count. */
    private fun finishedPrefixes(tmpDir: File): Set<String> =
        tmpDir.list().orEmpty()
            .filterNot { it.endsWith(PAGE_TMP_SUFFIX) || it == COMIC_INFO_FILE }
            .mapTo(mutableSetOf()) { it.substringBefore('.') }

    private suspend fun fetchPage(source: Source, page: PageInfo, tmpDir: File, prefix: String) {
        val partial = File(tmpDir, prefix + PAGE_TMP_SUFFIX)
        source.getImage(page).use { result ->
            withContext(dispatchers.io) {
                partial.outputStream().use { output -> result.source().inputStream().copyTo(output) }
                val extension = ImageUtil.getExtensionFromMimeType(imageMime(result.contentType)) {
                    partial.inputStream()
                }
                if (!partial.renameTo(File(tmpDir, "$prefix.$extension"))) {
                    throw IOException("Page $prefix could not be renamed")
                }
            }
        }
    }

    /**
     * Drops files no page of the current list owns (a source that shortened its list since the
     * last attempt), then checks one image per page is there.
     */
    private fun verifyPages(tmpDir: File, prefixes: Set<String>) {
        tmpDir.listFiles().orEmpty()
            .filter { it.name != COMIC_INFO_FILE && it.name.substringBefore('.') !in prefixes }
            .forEach { it.deleteRecursively() }
        val written = finishedPrefixes(tmpDir)
        if (!written.containsAll(prefixes)) {
            throw IOException("${prefixes.size - written.intersect(prefixes).size} pages are missing")
        }
    }

    /**
     * Transient failures are retried three times, 2, 4 and 8 seconds apart, as upstream's downloader
     * does per page, or after the wait a 429 or 503 named in its `Retry-After` when that is longer,
     * up to [MAX_RETRY_AFTER_MS], as the library update honours it. A source that is not installed
     * will not become installed by waiting.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> retrying(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            val failure: Throwable = try {
                return block()
            } catch (e: TimeoutCancellationException) {
                // A source's own timeout is a transient failure, not this coroutine being stopped.
                IOException(e.message, e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SourceNotInstalledException) {
                throw e
            } catch (e: Throwable) {
                e
            }
            if (attempt >= MAX_RETRIES) throw failure
            val asked = retryAfterMs(failure)?.coerceAtMost(MAX_RETRY_AFTER_MS) ?: 0L
            delay(max(RETRY_BASE_DELAY_MS shl attempt, asked))
            attempt++
        }
    }

    /** The wait a 429 or 503 asked for, when the source's client built the failure from the response. */
    private fun retryAfterMs(e: Throwable): Long? =
        (e as? HttpException ?: e.cause as? HttpException)
            ?.takeIf { it.code in PUSHBACK_CODES }
            ?.pushback?.retryAfter?.inWholeMilliseconds

    /** Only an image type is a hint worth passing on; anything else is sniffed from the bytes. */
    private fun imageMime(contentType: String?): String? =
        contentType?.substringBefore(';')?.trim()?.takeIf { it.startsWith("image/") }

    /**
     * What a failed row keeps: a named failure whose text needs no detail as its [ErrorKey.id], so
     * the queue screen names it in the reader's language when it is shown (ruling R67 rule 3), and
     * anything else as its message, as [ErrorKey.message] cuts it for a snackbar and the library
     * update error list: a source's message can be a whole error page. An
     * [HttpException] keeps "HTTP error 503", since one column cannot hold the key and its status
     * code, and so does one wrapped in another failure, whose own message may not name the code. A
     * row written before this stored text and still reads as it was (R67 rule 5, no migration).
     */
    private fun storedError(e: Throwable): String? {
        val key = ErrorKey.of(e)
        if (key != ErrorKey.GENERIC && ErrorKey.detail(e) == null) return key.id
        ErrorKey.httpCode(e)?.let { return HttpException(it).message }
        return ErrorKey.message(e) ?: e::class.simpleName
    }

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    /** `<chapter>.cbz_tmp`, which [sweepLeftovers] deletes when a run starts, as it does a copy's. */
    private fun archiveTmpOf(archive: File): File =
        File(archive.parentFile, archive.name + DownloadProvider.TMP_DIR_SUFFIX)

    companion object {
        const val MAX_RETRIES = 3
        const val RETRY_BASE_DELAY_MS = 2_000L

        /** The longest `Retry-After` a page waits; a site naming more is asked again after this. */
        const val MAX_RETRY_AFTER_MS = 60_000L

        private val PUSHBACK_CODES = setOf(429, 503)

        /** Runs a chapter may end on a network failure, each time put back in the queue, before it fails. */
        const val MAX_NETWORK_REQUEUES = 3

        /** The top of upstream's range for [DownloadPreferences.parallelSourceLimit], 1 to 10. */
        const val MAX_PARALLEL_SOURCES = 10

        private val NETWORK_FAILURES = setOf(ErrorKey.NO_NETWORK, ErrorKey.TIMEOUT)

        private const val MIN_PAGE_DIGITS = 3
        private const val PAGE_TMP_SUFFIX = ".tmp"
        private const val ARCHIVE_SUFFIX = ".cbz"
    }
}

/**
 * The progress of the chapters running at once, sent as one report, so the foreground notification
 * shows them together. A chapter is on the board from its first report, once its page list is in,
 * until the boundary after it ends. Every report carries the chapters left as [boundary] last set
 * it from the queue, one chapter running or several. Synchronized, so the reports of several
 * chapters reach [onProgress] one at a time, which [DownloadProgressCadence] relies on.
 */
private class ProgressBoard(private val onProgress: (DownloadEngine.Progress) -> Unit) {

    /** In the order the chapters first reported, so the first is the one running longest. */
    private val running = LinkedHashMap<Long, DownloadEngine.Progress>()

    /** The rows still queued or running at the last boundary; set before any chapter starts. */
    private var left = 0

    @Synchronized
    fun report(progress: DownloadEngine.Progress) {
        running[progress.chapterId] = progress
        send()
    }

    /**
     * A chapter boundary: [ended], the chapter that just ended if one did, leaves the board, and
     * [left] is the queue's count now. The chapters still running are reported again when either
     * changed, so a drop to one chapter shows that chapter's own report with the new count.
     */
    @Synchronized
    fun boundary(left: Int, ended: Long?) {
        val removed = ended != null && running.remove(ended) != null
        val changed = left != this.left
        this.left = left
        if ((removed || changed) && running.isNotEmpty()) send()
    }

    private fun send() {
        val all = running.values
        val first = all.first()
        onProgress(
            if (all.size == 1) {
                first.copy(chaptersLeft = left)
            } else {
                first.copy(
                    pagesDone = all.sumOf { it.pagesDone },
                    pagesTotal = all.sumOf { it.pagesTotal },
                    chaptersLeft = left,
                    chaptersRunning = all.size,
                )
            },
        )
    }
}

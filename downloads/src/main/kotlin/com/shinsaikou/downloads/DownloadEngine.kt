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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
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
import java.io.File
import java.io.IOException
import java.net.SocketException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.time.Clock

/**
 * Drains `download_queue` one chapter at a time, in `queue_order`. [DownloadWorker] hosts it; a
 * stopped worker is a cancelled [run], and the next [run] resumes from what the rows and the
 * chapter folders still hold (phase 2 decision Q4(a)(i)).
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
 * The order is load bearing. `PageLoaderResolver` consults the manifest before anything else, so a
 * manifest row must never exist before its folder has its final name.
 *
 * A run starts by deleting the `_tmp` folders no queue row will resume ([sweepLeftovers]).
 *
 * Each chapter is downloaded under [DownloadWriteLock], so a chapter the adoption of upstream's
 * downloads is copying is never written by both at once. A chapter folder another chapter's manifest
 * row may be found at is that download's, and the chapter fails rather than take it over (ruling
 * draft R-J7).
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
     */
    data class Progress(
        val chapterId: Long,
        val mangaTitle: String,
        val chapterName: String,
        val pagesDone: Int,
        val pagesTotal: Int,
        val chaptersLeft: Int,
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
    private var active: Pair<Long, Deferred<ChapterResult>>? = null

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
     * Deletes the `_tmp` folders, and the `.cbz_tmp` files a copy from a folder writes, that no queue
     * row will resume: a chapter removed from the queue while its folder could not be deleted, a
     * chapter renamed since, a copy a stopped run left. Only `<source>/<manga>/<name>_tmp` under the
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
     * Stops the chapter in flight if it is one of [chapterIds], and returns once it has stopped, so
     * the caller can delete its `_tmp` folder without racing a page write.
     */
    suspend fun cancel(chapterIds: Collection<Long>) {
        val job = synchronized(activeLock) { active?.takeIf { it.first in chapterIds }?.second }
        job?.cancelAndJoin()
    }

    private suspend fun runChapter(
        entry: DownloadQueueEntry,
        chaptersLeft: Int,
        onProgress: (Progress) -> Unit,
    ): ChapterResult =
        coroutineScope {
            val job = async(start = CoroutineStart.LAZY) {
                writeLock.withLock { downloadChapter(entry, chaptersLeft, onProgress) }
            }
            synchronized(activeLock) { active = entry.chapterId to job }
            try {
                job.await()
            } catch (e: CancellationException) {
                // Either [cancel] stopped this chapter, and the run goes on, or the run itself is
                // being stopped, and ensureActive rethrows.
                currentCoroutineContext().ensureActive()
                ChapterResult.DROPPED
            } finally {
                synchronized(activeLock) { if (active?.second === job) active = null }
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

        if (finalDir.exists() && manifestRepository.getByChapterId(chapter.id) != null) {
            // Already downloaded and queued again; nothing to fetch.
            queueRepository.remove(listOf(chapter.id))
            return ChapterResult.DROPPED
        }
        if (heldByAnotherDownload(chapter, finalDir)) throw ChapterFolderInUseException(chapter.id)
        withContext(dispatchers.io) { prepareTmpDir(finalDir, tmpDir) }

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

        return withContext(NonCancellable) { commit(manga, chapter, pages.size, tmpDir, finalDir, dirName) }
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
            chapterDirName = dirName,
            pageCount = pageCount.toLong(),
            sizeBytes = sizeBytes,
            dateDownloaded = now(),
        )
        val recorded = transactionRunner.inTransaction {
            if (queueRepository.getByChapterId(chapter.id) == null) {
                false
            } else {
                manifestRepository.recordDownload(entry)
                queueRepository.remove(listOf(chapter.id))
                true
            }
        }
        if (!recorded) {
            withContext(dispatchers.io) { finalDir.deleteRecursively() }
            return ChapterResult.DROPPED
        }
        return ChapterResult.COMPLETED
    }

    /**
     * Whether a manifest row of another chapter may be found at [finalDir], whether its files are
     * there or not. Folders are keyed by source name and title, never by manga id, so two library
     * entries of one source with one title share their chapter folders, and a chapter the source
     * delisted keeps its row and its files. Taking such a folder over would reuse that download's
     * pages as this chapter's and leave its row pointing at this chapter's files; writing into it
     * while the files are gone would have that row read this chapter. Only the rows named like the
     * folder are read, since [DownloadLocator.mayResolveTo] matches the name first; until the
     * migrate-downloads follow-up each chapter read the whole manifest here.
     */
    private suspend fun heldByAnotherDownload(chapter: Chapter, finalDir: File): Boolean =
        manifestRepository.getByChapterDirNames(listOf(finalDir.name))
            .any { it.chapterId != chapter.id && locator.mayResolveTo(it, finalDir) }

    /**
     * A final folder with no manifest row, of this chapter or any other, is a commit that died
     * between the rename and the transaction; it goes back to being the `_tmp` folder, so its pages
     * are reused.
     */
    private fun prepareTmpDir(finalDir: File, tmpDir: File) {
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
     * anything else as its message, as before. An [HttpException][eu.kanade.tachiyomi.network.HttpException]
     * keeps "HTTP error 503", since one column cannot hold the key and its status code. A row
     * written before this stored text and still reads as it was (R67 rule 5, no migration).
     */
    private fun storedError(e: Throwable): String? {
        val key = ErrorKey.of(e)
        return if (key != ErrorKey.GENERIC && ErrorKey.detail(e) == null) key.id else e.message ?: e::class.simpleName
    }

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    companion object {
        const val MAX_RETRIES = 3
        const val RETRY_BASE_DELAY_MS = 2_000L

        /** The longest `Retry-After` a page waits; a site naming more is asked again after this. */
        const val MAX_RETRY_AFTER_MS = 60_000L

        private val PUSHBACK_CODES = setOf(429, 503)

        /** Runs a chapter may end on a network failure, each time put back in the queue, before it fails. */
        const val MAX_NETWORK_REQUEUES = 3

        private val NETWORK_FAILURES = setOf(ErrorKey.NO_NETWORK, ErrorKey.TIMEOUT)

        private const val MIN_PAGE_DIGITS = 3
        private const val PAGE_TMP_SUFFIX = ".tmp"
    }
}

package com.shinsaikou.downloads

import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.download.model.DownloadQueueEntry
import com.shinsaikou.domain.download.model.DownloadState
import com.shinsaikou.domain.download.repository.DownloadQueueRepository
import com.shinsaikou.source.SourceIds
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import kotlin.time.Clock

/**
 * The queue is the `download_queue` table; this class adds the controls over it and over the
 * worker that drains it. Nothing is held in memory that a process death would lose.
 *
 * A cancel removes the queue row first, then stops the chapter if it is the one in flight, then
 * deletes its `_tmp` folder. The engine's commit re-reads the row inside its transaction, so a
 * chapter cancelled after its last page is not recorded either.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class WorkManagerDownloadQueue(
    private val queueRepository: DownloadQueueRepository,
    private val manifest: DownloadManifest,
    private val chapterRepository: ChapterRepository,
    private val getManga: GetManga,
    private val locator: DownloadLocator,
    private val downloadRoot: DownloadRoot,
    private val engine: DownloadEngine,
    private val scheduler: DownloadScheduler,
    private val dispatchers: Dispatchers,
    appScope: CoroutineScope,
) : DownloadQueue {

    override fun subscribe(): Flow<List<DownloadQueueEntry>> = queueRepository.subscribeAll()

    /** A worker with nothing to do is [DownloadStatus.STORAGE_FULL] when that is why it stopped, or would. */
    override val status: StateFlow<DownloadStatus> =
        scheduler.status
            .map { if (it == DownloadStatus.IDLE && downloadRoot.isLowOnSpace()) DownloadStatus.STORAGE_FULL else it }
            .flowOn(dispatchers.io)
            .stateIn(appScope, SharingStarted.Eagerly, DownloadStatus.IDLE)

    override val isRunning: StateFlow<Boolean> =
        scheduler.isRunning.stateIn(appScope, SharingStarted.Eagerly, false)

    /** Rows waiting with no worker running. A row in error waits for [start], so it does not count. */
    override val isPaused: StateFlow<Boolean> =
        combine(isRunning, subscribe()) { running, rows -> !running && rows.any { it.state != DownloadState.ERROR } }
            .stateIn(appScope, SharingStarted.Eagerly, false)

    /**
     * Chapters already downloaded are skipped. One already queued or downloading is left as it is,
     * and one that failed is queued again, so Download on a failed chapter retries it.
     */
    override suspend fun enqueue(manga: Manga, chapters: List<Chapter>, autoStart: Boolean) {
        if (manga.source == SourceIds.LOCAL) return
        val now = Clock.System.now().toEpochMilliseconds()
        chapters.forEach { chapter ->
            if (!manifest.isDownloaded(chapter.id)) {
                queueRepository.enqueue(chapterId = chapter.id, mangaId = manga.id, now = now)
            }
        }
        if (autoStart) start()
    }

    override suspend fun remove(chapterIds: List<Long>) {
        if (chapterIds.isEmpty()) return
        val chapters = chapterRepository.getChaptersByIds(chapterIds)
        queueRepository.remove(chapterIds)
        engine.cancel(chapterIds)
        deleteTmpDirs(chapters)
    }

    override suspend fun removeManga(mangaId: Long) {
        remove(queueRepository.getByMangaId(mangaId).map { it.chapterId })
    }

    override suspend fun reorder(orderedChapterIds: List<Long>) {
        queueRepository.reorder(orderedChapterIds)
    }

    /** Chapters that failed are queued again, as upstream's start does, then the worker is started. */
    override suspend fun start() {
        val now = Clock.System.now().toEpochMilliseconds()
        subscribe().first()
            .filter { it.state == DownloadState.ERROR }
            .forEach { queueRepository.setState(it.chapterId, DownloadState.QUEUED, null, now) }
        scheduler.start()
    }

    override suspend fun pause() {
        scheduler.stop()
    }

    override suspend fun clear() {
        scheduler.stop()
        remove(subscribe().first().map { it.chapterId })
    }

    private suspend fun deleteTmpDirs(chapters: List<Chapter>) {
        chapters.groupBy { it.mangaId }.forEach { (mangaId, mangaChapters) ->
            val manga = getManga.await(mangaId) ?: return@forEach
            mangaChapters.forEach { chapter ->
                val dirs = locator.findTmpDirs(manga.source, manga.title, chapter)
                withContext(dispatchers.io) { dirs.forEach { it.deleteRecursively() } }
            }
        }
    }
}

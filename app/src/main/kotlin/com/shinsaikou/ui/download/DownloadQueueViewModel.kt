package com.shinsaikou.ui.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.download.model.DownloadQueueEntry
import com.shinsaikou.domain.download.model.DownloadState
import com.shinsaikou.downloads.DownloadQueue
import com.shinsaikou.downloads.DownloadStatus
import com.shinsaikou.presentation.core.viewmodel.StateViewModel
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.interactor.GetManga

/**
 * The download queue as a list: every row of `download_queue` in queue order, with its state and
 * page progress, and the four controls the queue offers. There is no reordering (phase 2 unit H).
 *
 * A queue row carries ids only, so the entry title and chapter name are looked up once per chapter
 * and kept: the engine rewrites a row on every page, and a lookup per write would be a query per
 * page downloaded.
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(DownloadQueueViewModel::class)
@Inject
class DownloadQueueViewModel(
    private val downloadQueue: DownloadQueue,
    private val chapterRepository: ChapterRepository,
    private val getManga: GetManga,
    private val dispatchers: Dispatchers,
) : StateViewModel<DownloadQueueViewModel.State>(State()) {

    data class State(
        val loading: Boolean = true,
        val rows: List<Row> = emptyList(),
        val status: DownloadStatus = DownloadStatus.IDLE,
    ) {
        val running: Boolean get() = status == DownloadStatus.RUNNING

        /** Running, or enqueued and waiting for its network: Pause applies, and Start would do nothing. */
        val started: Boolean
            get() = status != DownloadStatus.IDLE && status != DownloadStatus.STORAGE_FULL
    }

    /** One queued chapter. The names are null when the chapter or its entry is no longer stored. */
    data class Row(
        val chapterId: Long,
        val mangaTitle: String?,
        val chapterName: String?,
        val state: DownloadState,
        val downloadedPages: Long,
        val totalPages: Long,
        val errorMessage: String?,
    )

    private data class Names(val mangaTitle: String?, val chapterName: String?)

    private val names = mutableMapOf<Long, Names>()

    init {
        viewModelScope.launch {
            combine(downloadQueue.subscribe(), downloadQueue.status) { entries, status -> entries to status }
                .collect { (entries, status) ->
                    val rows = withContext(dispatchers.io) { rowsOf(entries) }
                    mutableState.update { State(loading = false, rows = rows, status = status) }
                }
        }
    }

    /** Starts the worker. Rows that failed are queued again first, as upstream's start does. */
    fun start() {
        viewModelScope.launch { downloadQueue.start() }
    }

    /**
     * Stops the worker, or drops its waiting request; the rows stay queued and the chapter in flight
     * keeps its finished pages.
     */
    fun pause() {
        viewModelScope.launch { downloadQueue.pause() }
    }

    /** Removes one row, stops it if it is the chapter in flight, and deletes its partial folder. */
    fun cancel(chapterId: Long) {
        viewModelScope.launch { downloadQueue.remove(listOf(chapterId)) }
    }

    /** Stops the worker and removes every row. Nothing already downloaded is touched. */
    fun clear() {
        viewModelScope.launch { downloadQueue.clear() }
    }

    private suspend fun rowsOf(entries: List<DownloadQueueEntry>): List<Row> {
        lookUp(entries.map { it.chapterId }.filterNot(names::containsKey))
        return entries.map { entry ->
            val known = names[entry.chapterId]
            Row(
                chapterId = entry.chapterId,
                mangaTitle = known?.mangaTitle,
                chapterName = known?.chapterName,
                state = entry.state,
                downloadedPages = entry.downloadedPages,
                totalPages = entry.totalPages,
                errorMessage = entry.errorMessage,
            )
        }
    }

    private suspend fun lookUp(chapterIds: List<Long>) {
        if (chapterIds.isEmpty()) return
        val chapters = chapterRepository.getChaptersByIds(chapterIds).associateBy { it.id }
        val titles = chapters.values.map { it.mangaId }.distinct()
            .associateWith { mangaId -> getManga.await(mangaId)?.displayTitle }
        chapterIds.forEach { id ->
            val chapter = chapters[id]
            names[id] = Names(mangaTitle = chapter?.let { titles[it.mangaId] }, chapterName = chapter?.name)
        }
    }
}

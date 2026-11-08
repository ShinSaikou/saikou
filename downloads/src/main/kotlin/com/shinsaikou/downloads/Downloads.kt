package com.shinsaikou.downloads

import com.shinsaikou.domain.download.model.DownloadManifestEntry
import com.shinsaikou.domain.download.model.DownloadQueueEntry
import com.shinsaikou.source.model.PageInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.File

/**
 * The download engine's public shape. [WorkManagerDownloadQueue], [FolderDownloadManifest] and
 * [FilesDirDownloadRoot] are the bindings; [DownloadWorker] hosts [DownloadEngine], which writes a
 * chapter into a `_tmp` folder, renames it (or archives it as a `.cbz`) and only then records it in
 * the manifest.
 */

/** The ordered list of chapters waiting to be downloaded, and the controls over it. */
interface DownloadQueue {
    /** ORDER BY queue_order; mirrors DownloadQueueRepository.subscribeAll in Phase 1. */
    fun subscribe(): Flow<List<DownloadQueueEntry>>

    val isRunning: StateFlow<Boolean>

    /** What the worker is doing, which the queue screen says. A queue that only knows [isRunning] says that. */
    val status: Flow<DownloadStatus>
        get() = isRunning.map { if (it) DownloadStatus.RUNNING else DownloadStatus.IDLE }

    val isPaused: StateFlow<Boolean>

    suspend fun enqueue(manga: Manga, chapters: List<Chapter>, autoStart: Boolean)

    suspend fun remove(chapterIds: List<Long>)

    suspend fun removeManga(mangaId: Long)

    suspend fun reorder(orderedChapterIds: List<Long>)

    suspend fun start()

    suspend fun pause()

    suspend fun clear()
}

/** Whether the download worker is running, and when it is not, what its request waits for. */
enum class DownloadStatus {
    /** No request, or none left unfinished. */
    IDLE,
    RUNNING,

    /** Enqueued under "only download over Wi-Fi". */
    WAITING_FOR_WIFI,

    /** Enqueued with any network allowed. */
    WAITING_FOR_NETWORK,

    /** Enqueued to run again after a run that ended on a network failure, once its backoff is over. */
    RETRYING,

    /** No request, and too little storage free for the queue's next chapter ([isLowOnSpace]). */
    STORAGE_FULL,

    /**
     * A request this process will not run: it started without extensions, from the crash screen,
     * and the worker leaves the queue to the next start ([DownloadWorker]).
     */
    WAITING_FOR_EXTENSIONS,
}

/** What is already on disk. Every read goes through the manifest, never through a directory walk. */
interface DownloadManifest {
    suspend fun isDownloaded(chapterId: Long): Boolean

    /** manga id to count, for the library download badge. Delegates to DownloadManifestRepository. */
    fun subscribeCountsByManga(): Flow<Map<Long, Long>>

    suspend fun getEntry(chapterId: Long): DownloadManifestEntry?

    /** Pages of a downloaded chapter, or null when it is not downloaded. */
    suspend fun getPages(chapterId: Long): List<PageInfo>?

    /**
     * The folder or `.cbz` a downloaded chapter lives in, or null when it has no manifest row or its
     * files are gone. A row whose folder is missing counts as not downloaded (phase 2 decision Q4).
     */
    suspend fun getChapterDir(chapterId: Long): File?

    suspend fun delete(chapterIds: List<Long>)

    suspend fun deleteManga(mangaId: Long)

    suspend fun getTotalSizeBytes(): Long
}

/**
 * Where downloaded files live: `File(context.filesDir, "downloads")`, private to the app and
 * excluded from backup and device transfer by the app's data extraction rules.
 */
interface DownloadRoot {
    fun root(): File

    fun sourceDir(sourceName: String): File

    fun mangaDir(sourceName: String, mangaTitle: String): File

    /** Bytes free on the volume the root is on, or -1 when that cannot be read. */
    fun availableBytes(): Long
}

/**
 * Upstream's floor: a chapter is not started, nor a chapter copied in, with less than this free.
 * The root is on internal storage with the database, which a full volume would stop writing too.
 */
const val MIN_FREE_BYTES = 200L * 1024 * 1024

/** Under [MIN_FREE_BYTES] free. A volume whose free space cannot be read is not called full. */
fun DownloadRoot.isLowOnSpace(): Boolean = availableBytes() in 0 until MIN_FREE_BYTES

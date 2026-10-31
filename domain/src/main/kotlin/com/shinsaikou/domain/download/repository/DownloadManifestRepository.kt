package com.shinsaikou.domain.download.repository

import com.shinsaikou.domain.download.model.DownloadManifestEntry
import kotlinx.coroutines.flow.Flow

interface DownloadManifestRepository {
    suspend fun getByChapterId(chapterId: Long): DownloadManifestEntry?

    suspend fun getByMangaId(mangaId: Long): List<DownloadManifestEntry>

    /** Every row, for the sweep that removes rows whose folder is missing. */
    suspend fun getAll(): List<DownloadManifestEntry>

    /**
     * The rows whose folder has one of [chapterDirNames] as its name, whichever entry they belong
     * to: the check a download, a copy from upstream's folder and a migration make before they take a
     * folder, since folders are keyed by source name and title, never by manga id. Any number of
     * names: the implementation splits them into statements under [subscribeByChapterIds]'s 999.
     */
    suspend fun getByChapterDirNames(chapterDirNames: Collection<String>): List<DownloadManifestEntry>

    fun subscribeByMangaId(mangaId: Long): Flow<List<DownloadManifestEntry>>

    /**
     * The rows of [chapterIds], again on every change: the Updates tab's download controls, which
     * need the chapters it shows and not every row.
     *
     * Bound: at most 999 ids. The ids are bound as one `IN` list, one parameter each, and SQLite
     * before 3.32 (API 26's) refuses a statement with more than 999 parameters. Nothing here splits
     * a larger set; the caller keeps under the bound. The one caller, `UpdatesViewModel`, passes the
     * ids of the rows it shows, and `GetUpdates` answers at most 500 rows (`limit = 500`).
     */
    fun subscribeByChapterIds(chapterIds: Collection<Long>): Flow<List<DownloadManifestEntry>>

    /** Every row, again on every change, for the Updates tab's downloaded filter. */
    fun subscribeAll(): Flow<List<DownloadManifestEntry>>

    /** manga_id to count, for the library download badge. */
    fun subscribeCountsByManga(): Flow<Map<Long, Long>>

    /** Rows whose manga is not a favourite. */
    suspend fun getOrphans(): List<DownloadManifestEntry>

    /**
     * Rows whose chapter row is gone or soft deleted. The download engine re-matches them to a
     * current chapter of the same manga (phase 2 decision Q7); what it cannot match stays here.
     */
    suspend fun getWithoutChapter(): List<DownloadManifestEntry>

    suspend fun getTotalSizeBytes(): Long

    suspend fun recordDownload(entry: DownloadManifestEntry)

    /**
     * Points the row of [fromChapterId] at [toChapterId] and copies that chapter's identity text.
     * The folder name is kept: it is the name the files are under.
     */
    suspend fun repoint(
        fromChapterId: Long,
        toChapterId: Long,
        chapterUrl: String,
        chapterName: String,
        scanlator: String?,
    )

    /**
     * Points the row of [fromChapterId] at another entry's chapter: every identity column, the
     * folder name included, takes [entry]'s value, and the page count, size and download date stay.
     * A migration that has just moved the files to [entry]'s folder calls it (unit migrate-downloads).
     */
    suspend fun moveToChapter(fromChapterId: Long, entry: DownloadManifestEntry)

    suspend fun remove(chapterIds: List<Long>)
}

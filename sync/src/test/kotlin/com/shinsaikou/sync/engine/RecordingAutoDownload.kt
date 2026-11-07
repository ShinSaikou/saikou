package com.shinsaikou.sync.engine

import com.shinsaikou.domain.download.AutoDownload
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * What a sync test device's engine hands to auto-download (phase 3 unit AD): each call's chapter
 * ids, and how many rows of [device] held the `is_syncing` latch at the moment of the call, which a
 * report made after the transaction committed finds at zero.
 */
internal class RecordingAutoDownload(private val device: SyncTestDevice) : AutoDownload {
    val pulled = mutableListOf<List<Long>>()
    val latchedAtReport = mutableListOf<Long>()

    override suspend fun queueNewChapters(manga: Manga, chapters: List<Chapter>, autoStart: Boolean): Int = 0

    override suspend fun queuePulledChapters(chapterIds: List<Long>): Int {
        pulled += chapterIds
        latchedAtReport += LATCHED_TABLES.sumOf { device.long("SELECT count(*) FROM $it WHERE is_syncing != 0")!! }
        return chapterIds.size
    }

    override suspend fun startQueue() = Unit

    override suspend fun downloadAhead(
        mangaId: Long,
        pageIndex: Int,
        pageCount: Int,
        nextChapterId: Long?,
    ): Int = 0

    private companion object {
        val LATCHED_TABLES = listOf("mangas", "chapters", "categories", "merged_groups")
    }
}

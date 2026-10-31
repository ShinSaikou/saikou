package com.shinsaikou.downloads

import com.shinsaikou.domain.download.model.DownloadManifestEntry
import com.shinsaikou.domain.download.repository.DownloadManifestRepository
import dev.zacsweers.metro.Inject

/**
 * One title's downloads on the storage screen's list (phase 4 unit ST, upstream issue #565): every
 * manifest row of [mangaId], titled from the manifest's copied title because the manga row may be
 * gone.
 */
data class OrphanTitle(
    val mangaId: Long,
    val title: String,
    val chapterIds: List<Long>,
    val sizeBytes: Long,
) {
    val chapterCount: Int
        get() = chapterIds.size
}

/**
 * Downloads of titles that are not in the library: the manifest's rows whose manga row is gone or
 * not a favourite. Removing a title from the library, a sync that removes it and clear database all
 * keep its downloads (R99: sync never deletes one), so this is where they are listed and deleted.
 */
@Inject
class OrphanDownloads(
    private val manifestRepository: DownloadManifestRepository,
    private val manifest: DownloadManifest,
) {

    /** Every such title, by title and then by id, each with its chapter count and summed size. */
    suspend fun list(): List<OrphanTitle> = group(manifestRepository.getOrphans())

    /**
     * Deletes the rows of [confirmed], the titles a confirmation named, that are still not in the
     * library now: a title added back meanwhile keeps its files, and a row downloaded after the
     * confirmation opened was never named, so it stays too. Files go before rows
     * ([DownloadManifest.delete]). Returns the chapter ids deleted.
     */
    suspend fun delete(confirmed: List<OrphanTitle>): List<Long> {
        val named = confirmed.flatMapTo(HashSet()) { it.chapterIds }
        if (named.isEmpty()) return emptyList()
        val ids = manifestRepository.getOrphans().map { it.chapterId }.filter { it in named }
        manifest.delete(ids)
        return ids
    }

    internal companion object {
        /**
         * Rows grouped by manga id. A title can differ between rows of one manga (the entry was
         * renamed between two downloads), so the newest download's title names the group.
         */
        fun group(rows: List<DownloadManifestEntry>): List<OrphanTitle> = rows
            .groupBy { it.mangaId }
            .map { (mangaId, entries) ->
                OrphanTitle(
                    mangaId = mangaId,
                    title = entries.maxBy { it.dateDownloaded }.mangaTitle,
                    chapterIds = entries.map { it.chapterId }.sorted(),
                    sizeBytes = entries.sumOf { it.sizeBytes },
                )
            }
            .sortedWith(compareBy<OrphanTitle, String>(String.CASE_INSENSITIVE_ORDER) { it.title }.thenBy { it.mangaId })
    }
}

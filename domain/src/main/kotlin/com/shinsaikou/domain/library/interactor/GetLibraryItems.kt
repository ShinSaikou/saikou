package com.shinsaikou.domain.library.interactor

import com.shinsaikou.domain.download.repository.DownloadManifestRepository
import com.shinsaikou.domain.library.model.LibraryItem
import com.shinsaikou.domain.merged.MergedLibrary
import com.shinsaikou.domain.merged.interactor.GetMergedGroups
import com.shinsaikou.domain.merged.model.MergedTitle
import com.shinsaikou.source.SourceIds
import com.shinsaikou.source.SourceManager
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import tachiyomi.domain.manga.interactor.GetLibraryManga

/**
 * The library screen's row source: favourites joined with download counts and source language, with
 * each merged title folded into its main entry. The fold happens here, above `libraryView`, because
 * the library update reads the same view and must go on seeing every member.
 */
@Inject
class GetLibraryItems(
    private val getLibraryManga: GetLibraryManga,
    private val downloadManifestRepository: DownloadManifestRepository,
    private val sourceManager: SourceManager,
    private val getMergedGroups: GetMergedGroups,
) {

    fun subscribe(): Flow<List<LibraryItem>> = combine(
        getLibraryManga.subscribe(),
        downloadManifestRepository.subscribeCountsByManga(),
        sourceManager.getCatalogueSources(),
        getMergedGroups.subscribeTitles(),
    ) { library, downloadCounts, sources, titles ->
        val languageBySourceId = sources.associate { it.id to it.lang }
        val items = library.map { libraryManga ->
            LibraryItem(
                libraryManga = libraryManga,
                downloadCount = downloadCounts[libraryManga.id] ?: 0L,
                unreadCount = libraryManga.unreadCount,
                isLocal = libraryManga.manga.source == SourceIds.LOCAL,
                sourceLanguage = languageBySourceId[libraryManga.manga.source].orEmpty(),
            )
        }
        if (titles.isEmpty()) items else MergedLibrary.folded(items, titles, LibraryItem::id, ::fold)
    }

    /** [MergedLibrary.fold]'s row, with what a Library row adds to it. */
    private fun fold(main: LibraryItem, members: List<LibraryItem>, title: MergedTitle): LibraryItem {
        val row = MergedLibrary.fold(main.libraryManga, members.map { it.libraryManga }, title)
        return main.copy(
            libraryManga = row,
            downloadCount = title.counts?.downloaded ?: main.downloadCount,
            unreadCount = row.unreadCount,
            isLocal = members.all { it.isLocal },
            members = members.map { it.manga },
        )
    }
}

package com.shinsaikou.domain.library.interactor

import com.shinsaikou.domain.download.repository.DownloadManifestRepository
import com.shinsaikou.domain.library.model.LibraryItem
import com.shinsaikou.source.SourceIds
import com.shinsaikou.source.SourceManager
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import tachiyomi.domain.manga.interactor.GetLibraryManga

/** The library screen's row source: favourites joined with download counts and source language. */
@Inject
class GetLibraryItems(
    private val getLibraryManga: GetLibraryManga,
    private val downloadManifestRepository: DownloadManifestRepository,
    private val sourceManager: SourceManager,
) {

    fun subscribe(): Flow<List<LibraryItem>> = combine(
        getLibraryManga.subscribe(),
        downloadManifestRepository.subscribeCountsByManga(),
        sourceManager.getCatalogueSources(),
    ) { library, downloadCounts, sources ->
        val languageBySourceId = sources.associate { it.id to it.lang }
        library.map { libraryManga ->
            LibraryItem(
                libraryManga = libraryManga,
                downloadCount = downloadCounts[libraryManga.id] ?: 0L,
                unreadCount = libraryManga.unreadCount,
                isLocal = libraryManga.manga.source == SourceIds.LOCAL,
                sourceLanguage = languageBySourceId[libraryManga.manga.source].orEmpty(),
            )
        }
    }
}

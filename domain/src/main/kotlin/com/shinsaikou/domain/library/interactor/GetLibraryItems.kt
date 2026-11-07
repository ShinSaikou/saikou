package com.shinsaikou.domain.library.interactor

import com.shinsaikou.domain.download.repository.DownloadManifestRepository
import com.shinsaikou.domain.library.model.LibraryItem
import com.shinsaikou.domain.merged.interactor.GetMergedGroups
import com.shinsaikou.domain.merged.model.MergedTitle
import com.shinsaikou.source.SourceIds
import com.shinsaikou.source.SourceManager
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import tachiyomi.domain.category.model.Category
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
        if (titles.isEmpty()) items else folded(items, titles)
    }

    /**
     * [items] with each title's main entry standing for it and the other members gone. A title
     * whose rows have not all reached this read yet folds what it can, and one left with fewer than
     * two rows hides nothing, so no entry can vanish between two queries.
     */
    private fun folded(items: List<LibraryItem>, titles: List<MergedTitle>): List<LibraryItem> {
        val byId = items.associateBy { it.id }
        val folded = HashMap<Long, LibraryItem>()
        val hidden = HashSet<Long>()
        titles.forEach { title ->
            val main = byId[title.group.mainMangaId] ?: return@forEach
            val members = title.group.members.mapNotNull { byId[it.mangaId] }
            if (members.size < 2) return@forEach
            folded[main.id] = fold(main, members, title)
            members.mapTo(hidden) { it.id }
            hidden -= main.id
        }
        return items.filterNot { it.id in hidden }.map { folded[it.id] ?: it }
    }

    /**
     * The counts are the group's own query, each number once. The dates are the latest of any
     * member, so a chapter from the second source moves the title in a date sort. A member in no
     * category has no tab to keep, so it reads as the default category only when every member does.
     */
    private fun fold(main: LibraryItem, members: List<LibraryItem>, title: MergedTitle): LibraryItem {
        val rows = members.map { it.libraryManga }
        val categories = rows.flatMap { it.categories }.distinct()
        val counts = title.counts
        val total = counts?.total ?: main.libraryManga.totalChapters
        val read = counts?.read ?: main.libraryManga.readCount
        return main.copy(
            libraryManga = main.libraryManga.copy(
                manga = main.manga.copy(lastUpdate = rows.maxOf { it.manga.lastUpdate }),
                categories = (categories - Category.UNCATEGORIZED_ID).ifEmpty { categories },
                totalChapters = total,
                readCount = read,
                bookmarkCount = counts?.bookmarked ?: main.libraryManga.bookmarkCount,
                latestUpload = rows.maxOf { it.latestUpload },
                chapterFetchedAt = rows.maxOf { it.chapterFetchedAt },
                lastRead = rows.maxOf { it.lastRead },
            ),
            downloadCount = counts?.downloaded ?: main.downloadCount,
            unreadCount = total - read,
            isLocal = members.all { it.isLocal },
            members = members.map { it.manga },
        )
    }
}

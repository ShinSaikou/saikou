package com.shinsaikou.domain.merged

import com.shinsaikou.domain.merged.model.MergedTitle
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryManga

/** How a list of the library folds its merged titles into one row each: the Library, and Stats. */
object MergedLibrary {

    /**
     * [items] with each title's main entry standing for it and the other members gone. A title
     * whose rows have not all reached this read yet folds what it can, and one left with fewer than
     * two rows hides nothing, so no entry can vanish between two queries.
     */
    fun <T> folded(
        items: List<T>,
        titles: List<MergedTitle>,
        id: (T) -> Long,
        fold: (main: T, members: List<T>, title: MergedTitle) -> T,
    ): List<T> {
        val byId = items.associateBy(id)
        val folded = HashMap<Long, T>()
        val hidden = HashSet<Long>()
        titles.forEach { title ->
            val main = byId[title.group.mainMangaId] ?: return@forEach
            val members = title.group.members.mapNotNull { byId[it.mangaId] }
            if (members.size < 2) return@forEach
            folded[id(main)] = fold(main, members, title)
            members.mapTo(hidden, id)
            hidden -= id(main)
        }
        return items.filterNot { id(it) in hidden }.map { folded[id(it)] ?: it }
    }

    /**
     * The counts are the group's own query, each number once. The dates are the latest of any
     * member, so a chapter from the second source moves the title in a date sort. A member in no
     * category has no tab to keep, so it reads as the default category only when every member does.
     */
    fun fold(main: LibraryManga, members: List<LibraryManga>, title: MergedTitle): LibraryManga {
        val categories = members.flatMap { it.categories }.distinct()
        val counts = title.counts
        return main.copy(
            manga = main.manga.copy(lastUpdate = members.maxOf { it.manga.lastUpdate }),
            categories = (categories - Category.UNCATEGORIZED_ID).ifEmpty { categories },
            totalChapters = counts?.total ?: main.totalChapters,
            readCount = counts?.read ?: main.readCount,
            bookmarkCount = counts?.bookmarked ?: main.bookmarkCount,
            latestUpload = members.maxOf { it.latestUpload },
            chapterFetchedAt = members.maxOf { it.chapterFetchedAt },
            lastRead = members.maxOf { it.lastRead },
        )
    }
}

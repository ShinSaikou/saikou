// Vendored from upstream v0.20.4: domain/src/main/java/tachiyomi/domain/manga/interactor/SetMangaChapterFlags.kt (modified: yes)
package tachiyomi.domain.manga.interactor

import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository

/**
 * Modified: the private `Long.setFlag` copy is gone; every call goes through [Manga.setFlag].
 * [awaitSetAllFlags] takes the entry and keeps every bit outside the six masks, and
 * [awaitSetGroupByVolume] is Saikou's (phase 4 unit VG).
 */
class SetMangaChapterFlags(
    private val mangaRepository: MangaRepository,
) {

    suspend fun awaitSetDownloadedFilter(manga: Manga, flag: Long): Boolean {
        return mangaRepository.update(
            MangaUpdate(
                id = manga.id,
                chapterFlags = manga.setFlag(flag, Manga.CHAPTER_DOWNLOADED_MASK),
            ),
        )
    }

    suspend fun awaitSetUnreadFilter(manga: Manga, flag: Long): Boolean {
        return mangaRepository.update(
            MangaUpdate(
                id = manga.id,
                chapterFlags = manga.setFlag(flag, Manga.CHAPTER_UNREAD_MASK),
            ),
        )
    }

    suspend fun awaitSetBookmarkFilter(manga: Manga, flag: Long): Boolean {
        return mangaRepository.update(
            MangaUpdate(
                id = manga.id,
                chapterFlags = manga.setFlag(flag, Manga.CHAPTER_BOOKMARKED_MASK),
            ),
        )
    }

    suspend fun awaitSetDisplayMode(manga: Manga, flag: Long): Boolean {
        return mangaRepository.update(
            MangaUpdate(
                id = manga.id,
                chapterFlags = manga.setFlag(flag, Manga.CHAPTER_DISPLAY_MASK),
            ),
        )
    }

    /** Saikou's volume headers, a bit outside upstream's masks (phase 4 unit VG). */
    suspend fun awaitSetGroupByVolume(manga: Manga, enabled: Boolean): Boolean {
        return mangaRepository.update(
            MangaUpdate(
                id = manga.id,
                chapterFlags = manga.setFlag(
                    if (enabled) Manga.CHAPTER_GROUP_BY_VOLUME else 0L,
                    Manga.CHAPTER_GROUP_BY_VOLUME_MASK,
                ),
            ),
        )
    }

    suspend fun awaitSetSortingModeOrFlipOrder(manga: Manga, flag: Long): Boolean {
        val newFlags = if (manga.sorting == flag) {
            // Just flip the order
            val orderFlag = if (manga.sortDescending()) Manga.CHAPTER_SORT_ASC else Manga.CHAPTER_SORT_DESC
            manga.setFlag(orderFlag, Manga.CHAPTER_SORT_DIR_MASK)
        } else {
            // Set new flag with ascending order
            manga.setFlag(flag, Manga.CHAPTER_SORTING_MASK)
                .setFlag(Manga.CHAPTER_SORT_ASC, Manga.CHAPTER_SORT_DIR_MASK)
        }
        return mangaRepository.update(
            MangaUpdate(
                id = manga.id,
                chapterFlags = newFlags,
            ),
        )
    }

    /**
     * The six chapter defaults onto [manga]. Upstream builds the word from zero; Saikou starts from the
     * entry's own word with the six masks cleared, so a bit outside them (the volume headers, or
     * any later one) survives "Set as default", "Reset" and "Also apply to library" (challenge c2
     * X3, phase 4 unit VG).
     */
    suspend fun awaitSetAllFlags(
        manga: Manga,
        unreadFilter: Long,
        downloadedFilter: Long,
        bookmarkedFilter: Long,
        sortingMode: Long,
        sortingDirection: Long,
        displayMode: Long,
    ): Boolean {
        return mangaRepository.update(
            MangaUpdate(
                id = manga.id,
                chapterFlags = (manga.chapterFlags and Manga.CHAPTER_DEFAULTS_MASK.inv())
                    .setFlag(unreadFilter, Manga.CHAPTER_UNREAD_MASK)
                    .setFlag(downloadedFilter, Manga.CHAPTER_DOWNLOADED_MASK)
                    .setFlag(bookmarkedFilter, Manga.CHAPTER_BOOKMARKED_MASK)
                    .setFlag(sortingMode, Manga.CHAPTER_SORTING_MASK)
                    .setFlag(sortingDirection, Manga.CHAPTER_SORT_DIR_MASK)
                    .setFlag(displayMode, Manga.CHAPTER_DISPLAY_MASK),
            ),
        )
    }
}

/** The same algebra [Manga.setFlag] uses, for the flag word [SetMangaChapterFlags.awaitSetAllFlags] builds. */
private fun Long.setFlag(flag: Long, mask: Long): Long = this and mask.inv() or (flag and mask)

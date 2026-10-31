// Vendored from upstream v0.20.4: domain/src/main/java/tachiyomi/domain/chapter/interactor/SetMangaDefaultChapterFlags.kt (modified: yes)
package tachiyomi.domain.chapter.interactor

import com.shinsaikou.domain.preferences.LibraryPreferences
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.SetMangaChapterFlags
import tachiyomi.domain.manga.model.Manga

/**
 * Modified: the entry itself goes to [SetMangaChapterFlags.awaitSetAllFlags], which keeps its bits
 * outside the six masks (phase 4 unit VG).
 */
class SetMangaDefaultChapterFlags(
    private val libraryPreferences: LibraryPreferences,
    private val setMangaChapterFlags: SetMangaChapterFlags,
    private val getFavorites: GetFavorites,
) {

    suspend fun await(manga: Manga) {
        withNonCancellableContext {
            with(libraryPreferences) {
                setMangaChapterFlags.awaitSetAllFlags(
                    manga = manga,
                    unreadFilter = filterChapterByRead.get(),
                    downloadedFilter = filterChapterByDownloaded.get(),
                    bookmarkedFilter = filterChapterByBookmarked.get(),
                    sortingMode = sortChapterBySourceOrNumber.get(),
                    sortingDirection = sortChapterByAscendingOrDescending.get(),
                    displayMode = displayChapterByNameOrNumber.get(),
                )
            }
        }
    }

    suspend fun awaitAll() {
        withNonCancellableContext {
            getFavorites.await().forEach { await(it) }
        }
    }
}

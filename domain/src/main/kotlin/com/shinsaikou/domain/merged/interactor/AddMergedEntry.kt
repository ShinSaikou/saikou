package com.shinsaikou.domain.merged.interactor

import com.shinsaikou.domain.manga.interactor.UpdateManga
import com.shinsaikou.domain.preferences.LibraryPreferences
import dev.zacsweers.metro.Inject
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

/**
 * The library add a merge needs, since a member is a favourite of its own: the favourite flag and
 * the named default category. No category is asked for: the merged title already shows in its
 * entries' tabs. An entry in the library is left alone, because the flag resets its date added.
 * An entry deleted on another device comes back first, as a migration target does, or it would be a
 * favourite no library shows.
 */
@Inject
class AddMergedEntry(
    private val updateManga: UpdateManga,
    private val mangaRepository: MangaRepository,
    private val getCategories: GetCategories,
    private val setMangaCategories: SetMangaCategories,
    private val libraryPreferences: LibraryPreferences,
) {
    suspend fun await(manga: Manga) {
        if (manga.favorite) return
        if (manga.deletedAt != null) mangaRepository.undelete(manga.id)
        updateManga.awaitUpdateFavorite(manga.id, true)
        val default = libraryPreferences.defaultCategory.get().toLong()
        if (default >= 0 && getCategories.await().any { it.id == default }) {
            setMangaCategories.await(manga.id, listOf(default))
        }
    }
}

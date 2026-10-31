package com.shinsaikou.domain.library.interactor

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.model.LibrarySort

/**
 * The alphabetical sort orders by the title the grid shows, the owner's edit over the source's
 * (phase 5 unit ED), so the library reads in order.
 */
class SortLibraryDisplayTitleTest {

    private val sortLibrary = SortLibrary()

    @Test
    fun `an edited entry sorts by its edit`() {
        val renamed = libraryItem(1, "Zulu").let {
            it.copy(libraryManga = it.libraryManga.copy(manga = it.manga.copy(titleOverride = "Alpha")))
        }
        val items = listOf(libraryItem(2, "Bravo"), renamed, libraryItem(3, "Charlie"))

        sortLibrary.apply(
            items = items,
            sort = LibrarySort(LibrarySort.Type.Alphabetical, LibrarySort.Direction.Ascending),
            seed = 0,
            trackerMeans = null,
        ).map { it.manga.displayTitle } shouldBe listOf("Alpha", "Bravo", "Charlie")
    }
}

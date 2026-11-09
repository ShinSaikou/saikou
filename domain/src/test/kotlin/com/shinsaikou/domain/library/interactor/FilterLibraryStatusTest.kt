package com.shinsaikou.domain.library.interactor

import com.shinsaikou.domain.library.model.LibraryFilters
import com.shinsaikou.domain.library.model.LibraryItem
import com.shinsaikou.domain.library.search.LibrarySearchMatcher
import com.shinsaikou.source.model.MangaStatus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.library.model.search.QueryNode

/**
 * The library's Completed filter reads the status the entry screen shows (R165 item 4): the owner's
 * edit over the source's, so an entry the owner marked completed counts as completed and one the
 * owner marked ongoing over a completed source does not.
 */
class FilterLibraryStatusTest {

    private val filterLibrary = FilterLibrary(LibrarySearchMatcher())

    // 1: completed at the source; 2: ongoing at the source, edited to completed; 3: completed at the
    // source, edited to ongoing; 4: ongoing, never edited.
    private val items = listOf(
        libraryItem(1L, "Title 1").withStatus(COMPLETED, null),
        libraryItem(2L, "Title 2").withStatus(ONGOING, COMPLETED),
        libraryItem(3L, "Title 3").withStatus(COMPLETED, ONGOING),
        libraryItem(4L, "Title 4").withStatus(ONGOING, null),
    )

    private fun shown(completed: TriState): List<Long> =
        filterLibrary.apply(items, LibraryFilters(completed = completed), QueryNode.from(""), emptyMap(), emptyMap())
            .map { it.id }

    @Test
    fun `the Completed filter keeps the entries whose shown status is completed`() {
        shown(TriState.ENABLED_IS) shouldBe listOf(1L, 2L)
    }

    @Test
    fun `its exclusion keeps the others`() {
        shown(TriState.ENABLED_NOT) shouldBe listOf(3L, 4L)
    }

    private fun LibraryItem.withStatus(status: Long, edit: Long?) =
        copy(libraryManga = libraryManga.copy(manga = manga.copy(status = status, statusOverride = edit)))

    private companion object {
        val COMPLETED = MangaStatus.COMPLETED.code.toLong()
        val ONGOING = MangaStatus.ONGOING.code.toLong()
    }
}

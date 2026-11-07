package com.shinsaikou.domain.merged

import com.shinsaikou.domain.merged.model.MergedGroup
import com.shinsaikou.domain.merged.model.MergedGroupCounts
import com.shinsaikou.domain.merged.model.MergedMember
import com.shinsaikou.domain.merged.model.MergedTitle
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga

/**
 * The fold the Library and Statistics share (R163), on its own: which rows stand and which go, and
 * the row a title becomes. `GetLibraryItemsMergedTest` pins the same through the Library.
 */
class MergedLibraryTest {

    private fun title(main: Long, vararg ids: Long, id: Long = 1, counts: MergedGroupCounts? = null) = MergedTitle(
        group = MergedGroup(
            id = id,
            mainMangaId = main,
            showAll = false,
            members = ids.mapIndexed { index, mangaId ->
                MergedMember(mangaId, source = mangaId, url = "/$mangaId", position = index.toLong(), checkUpdates = true)
            },
        ),
        counts = counts,
    )

    private fun row(
        id: Long,
        categories: List<Long> = listOf(0),
        total: Long = 5,
        read: Long = 1,
        bookmarks: Long = 0,
        lastUpdate: Long = 0,
        lastRead: Long = 0,
        fetchedAt: Long = id,
    ) = LibraryManga(
        manga = Manga.create().copy(id = id, lastUpdate = lastUpdate),
        categories = categories,
        totalChapters = total,
        readCount = read,
        bookmarkCount = bookmarks,
        latestUpload = id,
        chapterFetchedAt = fetchedAt,
        lastRead = lastRead,
    )

    private data class Entry(val id: Long, val label: String = "$id")

    /** Rows named by id, folded to "main+members" so the result shows who stood for whom. */
    private fun folded(ids: List<Long>, vararg titles: MergedTitle): List<String> =
        MergedLibrary.folded(ids.map { Entry(it) }, titles.toList(), Entry::id) { main, members, _ ->
            Entry(main.id, "${main.id}+${members.joinToString("+") { it.id.toString() }}")
        }.map { it.label }

    @Test
    fun `with no title every row stands as it is`() {
        folded(listOf(1, 2, 3)) shouldContainExactly listOf("1", "2", "3")
    }

    @Test
    fun `the main entry stands for the title in its own place and the other members go`() {
        folded(listOf(1, 2, 3, 4), title(3, 1, 3, 4)) shouldContainExactly listOf("2", "3+1+3+4")
    }

    @Test
    fun `the members are handed over in the group's order, not the list's`() {
        folded(listOf(1, 2, 3), title(1, 3, 2, 1)) shouldContainExactly listOf("1+3+2+1")
    }

    @Test
    fun `two titles each fold on their own`() {
        folded(listOf(1, 2, 3, 4, 5), title(1, 1, 2, id = 1), title(4, 3, 4, id = 2)) shouldContainExactly
            listOf("1+1+2", "4+3+4", "5")
    }

    @Test
    fun `a title with one member in the list hides nothing`() {
        folded(listOf(1, 3), title(1, 1, 2)) shouldContainExactly listOf("1", "3")
    }

    @Test
    fun `a title whose main entry is not in the list yet hides nothing`() {
        folded(listOf(2, 3), title(1, 1, 2, 3)) shouldContainExactly listOf("2", "3")
    }

    @Test
    fun `a title with no live member in the list hides nothing`() {
        folded(listOf(7, 8), title(1, 1, 2)) shouldContainExactly listOf("7", "8")
    }

    @Test
    fun `a main entry that names none of the members still stands for the ones present`() {
        // The main entry is in the list but names no member; the members that are there fold under it.
        folded(listOf(1, 2, 9), title(9, 1, 2)) shouldContainExactly listOf("9+1+2")
    }

    @Test
    fun `an empty list folds to an empty list`() {
        folded(emptyList(), title(1, 1, 2)) shouldBe emptyList()
    }

    // fold ---------------------------------------------------------------------------------------

    @Test
    fun `the counts are the group's when it has them`() {
        val counts = MergedGroupCounts(1, total = 9, read = 4, bookmarked = 2, downloaded = 3)

        val folded = MergedLibrary.fold(row(1), listOf(row(1), row(2)), title(1, 1, 2, counts = counts))

        folded.totalChapters shouldBe 9
        folded.readCount shouldBe 4
        folded.bookmarkCount shouldBe 2
        folded.unreadCount shouldBe 5
    }

    @Test
    fun `the main entry's own counts stand while the group has none`() {
        val folded = MergedLibrary.fold(
            row(1, total = 7, read = 3, bookmarks = 1),
            listOf(row(1, total = 7, read = 3, bookmarks = 1), row(2, total = 100, read = 100)),
            title(1, 1, 2),
        )

        folded.totalChapters shouldBe 7
        folded.readCount shouldBe 3
        folded.bookmarkCount shouldBe 1
    }

    @Test
    fun `the dates are the latest of any member and the row stays the main entry's`() {
        val folded = MergedLibrary.fold(
            row(1, lastUpdate = 10, lastRead = 7, fetchedAt = 3),
            listOf(row(1, lastUpdate = 10, lastRead = 7, fetchedAt = 3), row(2, lastUpdate = 20, lastRead = 5, fetchedAt = 9)),
            title(1, 1, 2),
        )

        folded.manga.id shouldBe 1
        folded.manga.lastUpdate shouldBe 20
        folded.lastRead shouldBe 7
        folded.chapterFetchedAt shouldBe 9
        folded.latestUpload shouldBe 2
    }

    @Test
    fun `categories are the union, less the default one while any member has a real one`() {
        val mixed = MergedLibrary.fold(
            row(1, categories = listOf(0)),
            listOf(row(1, categories = listOf(0)), row(2, categories = listOf(5, 6))),
            title(1, 1, 2),
        )
        val none = MergedLibrary.fold(
            row(1, categories = listOf(0)),
            listOf(row(1, categories = listOf(0)), row(2, categories = listOf(0))),
            title(1, 1, 2),
        )

        mixed.categories shouldContainExactly listOf(5L, 6L)
        none.categories shouldContainExactly listOf(0L)
    }

    @Test
    fun `a member shared by two categories is listed once`() {
        val folded = MergedLibrary.fold(
            row(1, categories = listOf(5)),
            listOf(row(1, categories = listOf(5)), row(2, categories = listOf(5))),
            title(1, 1, 2),
        )

        folded.categories shouldContainExactly listOf(5L)
    }
}

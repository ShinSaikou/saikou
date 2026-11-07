package com.shinsaikou.domain.library.interactor

import com.shinsaikou.domain.download.repository.DownloadManifestRepository
import com.shinsaikou.domain.library.model.LibraryItem
import com.shinsaikou.domain.merged.interactor.GetMergedGroups
import com.shinsaikou.domain.merged.model.MergedGroup
import com.shinsaikou.domain.merged.model.MergedGroupCounts
import com.shinsaikou.domain.merged.model.MergedMember
import com.shinsaikou.domain.merged.model.MergedTitle
import com.shinsaikou.domain.merged.repository.MergedGroupRepository
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.source.SourceIds
import com.shinsaikou.source.SourceManager
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.model.Manga

/** The Library's fold of a merged title: its main entry stands for it, and every member goes on existing. */
class GetLibraryItemsMergedTest {

    private fun row(
        id: Long,
        categories: List<Long> = listOf(0),
        total: Long = 5,
        read: Long = 1,
        source: Long = id,
        lastUpdate: Long = 0,
        lastRead: Long = 0,
    ) = LibraryManga(
        manga = Manga.create().copy(id = id, source = source, lastUpdate = lastUpdate),
        categories = categories,
        totalChapters = total,
        readCount = read,
        bookmarkCount = 0,
        latestUpload = id,
        chapterFetchedAt = id,
        lastRead = lastRead,
    )

    private fun group(mainId: Long, vararg ids: Long, id: Long = 1) = MergedGroup(
        id = id,
        mainMangaId = mainId,
        showAll = false,
        members = ids.mapIndexed { index, mangaId ->
            MergedMember(mangaId, source = mangaId, url = "/$mangaId", position = index.toLong(), checkUpdates = true)
        },
    )

    private fun counts(total: Long = 9, read: Long = 4, bookmarked: Long = 2, downloaded: Long = 3, groupId: Long = 1) =
        MergedGroupCounts(groupId, total, read, bookmarked, downloaded)

    private suspend fun items(library: List<LibraryManga>, titles: List<MergedTitle>): List<LibraryItem> {
        val getLibraryManga = mockk<GetLibraryManga> { every { subscribe() } returns flowOf(library) }
        val manifest = mockk<DownloadManifestRepository> {
            every { subscribeCountsByManga() } returns flowOf(library.associate { it.id to 1L })
        }
        val sources = mockk<SourceManager> { every { getCatalogueSources() } returns flowOf(emptyList()) }
        val groups = mockk<GetMergedGroups> { every { subscribeTitles() } returns flowOf(titles) }
        return GetLibraryItems(getLibraryManga, manifest, sources, groups).subscribe().first()
    }

    @Test
    fun `with no merged title every row is its own, untouched`() = runTest {
        val items = items(listOf(row(1), row(2)), emptyList())

        items.map { it.id } shouldContainExactly listOf(1L, 2L)
        items.first().unreadCount shouldBe 4
        items.first().downloadCount shouldBe 1
        items.first().members shouldBe emptyList()
    }

    @Test
    fun `the main entry stands for the title, the other members are hidden, and the rest stay in place`() = runTest {
        val items = items(listOf(row(1), row(2), row(3)), listOf(MergedTitle(group(2, 1, 2), counts())))

        items.map { it.id } shouldContainExactly listOf(2L, 3L)
        items.first().members.map { it.id } shouldContainExactly listOf(1L, 2L)
    }

    @Test
    fun `the counts are the group's, which is what the filters and sorts read`() = runTest {
        val item = items(listOf(row(1), row(2)), listOf(MergedTitle(group(1, 1, 2), counts()))).single()

        item.libraryManga.totalChapters shouldBe 9
        item.libraryManga.readCount shouldBe 4
        item.libraryManga.bookmarkCount shouldBe 2
        item.libraryManga.hasBookmarks shouldBe true
        item.unreadCount shouldBe 5
        item.downloadCount shouldBe 3
    }

    @Test
    fun `the dates are the latest of any member`() = runTest {
        val library = listOf(row(1, lastUpdate = 10, lastRead = 7), row(2, lastUpdate = 20, lastRead = 5))

        val item = items(library, listOf(MergedTitle(group(1, 1, 2), counts()))).single()

        item.manga.lastUpdate shouldBe 20
        item.libraryManga.lastRead shouldBe 7
        item.libraryManga.latestUpload shouldBe 2
    }

    @Test
    fun `a title shows in every category any member is in`() = runTest {
        val library = listOf(row(1, categories = listOf(5)), row(2, categories = listOf(5, 6)))

        val item = items(library, listOf(MergedTitle(group(1, 1, 2), counts()))).single()

        item.libraryManga.categories shouldContainExactly listOf(5L, 6L)
    }

    @Test
    fun `a member in no category adds no default tab, and a title of none stays there`() = runTest {
        val mixed = listOf(row(1, categories = listOf(5)), row(2, categories = listOf(0)))
        val none = listOf(row(1, categories = listOf(0)), row(2, categories = listOf(0)))
        val titles = listOf(MergedTitle(group(1, 1, 2), counts()))

        items(mixed, titles).single().libraryManga.categories shouldContainExactly listOf(5L)
        items(none, titles).single().libraryManga.categories shouldContainExactly listOf(0L)
    }

    @Test
    fun `a title is Local only while every member is`() = runTest {
        val titles = listOf(MergedTitle(group(1, 1, 2), counts()))
        val local = SourceIds.LOCAL

        items(listOf(row(1, source = local), row(2)), titles).single().isLocal shouldBe false
        items(listOf(row(1, source = local), row(2, source = local)), titles).single().isLocal shouldBe true
    }

    @Test
    fun `counts a tick behind the group fall back to the main entry's`() = runTest {
        val item = items(listOf(row(1, total = 5), row(2)), listOf(MergedTitle(group(1, 1, 2), null))).single()

        item.libraryManga.totalChapters shouldBe 5
        item.members.map { it.id } shouldContainExactly listOf(1L, 2L)
    }

    @Test
    fun `a group whose rows have not reached this read hides nothing`() = runTest {
        val items = items(listOf(row(1), row(3)), listOf(MergedTitle(group(1, 1, 2), counts())))

        items.map { it.id } shouldContainExactly listOf(1L, 3L)
        items.first().members shouldBe emptyList()
    }

    @Test
    fun `with the switch off no group query is subscribed to`() = runTest {
        val repository = mockk<MergedGroupRepository>()
        val preferences = LibraryPreferences(InMemoryPreferenceStore())

        GetMergedGroups(repository, preferences).subscribeTitles().first() shouldBe emptyList()

        verify(exactly = 0) { repository.subscribeLiveGroups() }
        verify(exactly = 0) { repository.subscribeGroupCounts() }
    }

    @Test
    fun `with the switch on and no group the chapter counts are not asked for`() = runTest {
        val repository = mockk<MergedGroupRepository> { every { subscribeLiveGroups() } returns flowOf(emptyList()) }
        val preferences = LibraryPreferences(InMemoryPreferenceStore()).apply { mergedEntries.set(true) }

        GetMergedGroups(repository, preferences).subscribeTitles().first() shouldBe emptyList()

        verify(exactly = 1) { repository.subscribeLiveGroups() }
        verify(exactly = 0) { repository.subscribeGroupCounts() }
    }
}

package com.shinsaikou.data.repository

import com.shinsaikou.data.merged.MergedGroupRepositoryImpl
import com.shinsaikou.domain.merged.model.MergedGroupCounts
import com.shinsaikou.source.SourceIds
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** `getGroupCounts` (merged_groups.sq): what the Library counts for a merged title. */
class MergedGroupCountsTest : RepositoryTest() {

    private val repository by lazy { MergedGroupRepositoryImpl(handler) }

    @BeforeEach
    fun seed() {
        insertManga(id = 1)
        insertManga(id = 2)
        insertManga(id = 3)
    }

    private fun chapter(
        id: Long,
        mangaId: Long,
        number: Double,
        read: Int = 0,
        bookmark: Int = 0,
        scanlator: String = "Group",
        deletedAt: Long? = null,
    ) = exec(
        """
        INSERT INTO chapters(_id, manga_id, url, name, scanlator, read, bookmark, last_page_read,
            chapter_number, source_order, date_fetch, date_upload, memo, deleted_at)
        VALUES ($id, $mangaId, '/c/$id', 'Chapter $id', '$scanlator', $read, $bookmark, 0, $number, $id, 0, 0,
            CAST('{}' AS BLOB), ${deletedAt ?: "NULL"})
        """,
    )

    private fun downloaded(chapterId: Long, mangaId: Long) = exec(
        """
        INSERT INTO downloads_manifest(chapter_id, manga_id, source_id, manga_title, manga_url, chapter_url,
            chapter_name, chapter_dir_name, page_count, date_downloaded)
        VALUES ($chapterId, $mangaId, 1, 'Title', '/m', '/c', 'Name', 'dir-$chapterId', 1, 0)
        """,
    )

    private suspend fun counts(): MergedGroupCounts? {
        val id = repository.getLiveGroup(1)?.id ?: return null
        return repository.subscribeGroupCounts().first()[id]
    }

    @Test
    fun `each number counts once, and a number is read, bookmarked or downloaded while any copy is`() = runTest {
        repository.merge(mainId = 1, mangaIds = listOf(1, 2))
        chapter(id = 10, mangaId = 1, number = 1.0, read = 1)
        chapter(id = 11, mangaId = 2, number = 1.0)
        chapter(id = 12, mangaId = 1, number = 2.0, bookmark = 1)
        chapter(id = 13, mangaId = 2, number = 2.0)
        chapter(id = 14, mangaId = 2, number = 3.0)
        downloaded(chapterId = 14, mangaId = 2)

        counts() shouldBe MergedGroupCounts(groupId = 1, total = 3, read = 1, bookmarked = 1, downloaded = 1)
    }

    @Test
    fun `two groups' rows of one number in one source count once, with the rest of the number`() = runTest {
        repository.merge(mainId = 1, mangaIds = listOf(1, 2))
        chapter(id = 10, mangaId = 1, number = 1.0, scanlator = "First")
        chapter(id = 11, mangaId = 1, number = 1.0, scanlator = "Second")
        chapter(id = 12, mangaId = 2, number = 1.0)

        counts()?.total shouldBe 1
    }

    @Test
    fun `a chapter with no recognised number is its own, however many there are`() = runTest {
        repository.merge(mainId = 1, mangaIds = listOf(1, 2))
        chapter(id = 10, mangaId = 1, number = -1.0)
        chapter(id = 11, mangaId = 2, number = -1.0, read = 1)
        chapter(id = 12, mangaId = 2, number = -1.0)

        counts()?.total shouldBe 3
        counts()?.read shouldBe 1
    }

    @Test
    fun `deleted chapters, excluded scanlators and entries outside the group are left out`() = runTest {
        repository.merge(mainId = 1, mangaIds = listOf(1, 2))
        chapter(id = 10, mangaId = 1, number = 1.0)
        chapter(id = 11, mangaId = 1, number = 2.0, deletedAt = 1_000)
        chapter(id = 12, mangaId = 2, number = 3.0, scanlator = "Excluded")
        chapter(id = 13, mangaId = 3, number = 4.0)
        exec("INSERT INTO excluded_scanlators(manga_id, scanlator) VALUES (2, 'Excluded')")

        counts()?.total shouldBe 1
    }

    @Test
    fun `a member that is no longer a favourite stops counting`() = runTest {
        repository.merge(mainId = 1, mangaIds = listOf(1, 2, 3))
        chapter(id = 10, mangaId = 1, number = 1.0)
        chapter(id = 11, mangaId = 3, number = 2.0)
        counts()?.total shouldBe 2

        exec("UPDATE mangas SET favorite = 0 WHERE _id = 3")

        counts()?.total shouldBe 1
    }

    @Test
    fun `a Local member's chapters are downloaded`() = runTest {
        exec("UPDATE mangas SET source = ${SourceIds.LOCAL} WHERE _id = 2")
        repository.merge(mainId = 1, mangaIds = listOf(1, 2))
        chapter(id = 10, mangaId = 1, number = 1.0)
        chapter(id = 11, mangaId = 2, number = 2.0)

        counts()?.downloaded shouldBe 1
    }

    @Test
    fun `a group with no chapters has no row`() = runTest {
        repository.merge(mainId = 1, mangaIds = listOf(1, 2))

        counts().shouldBeNull()
    }

    @Test
    fun `migrating a member puts the new entry in its place, and migrating the main entry keeps the group`() = runTest {
        repository.merge(mainId = 1, mangaIds = listOf(1, 2))

        repository.replaceMember(oldMangaId = 2, newMangaId = 3)
        repository.getLiveGroup(1)?.members?.map { it.mangaId } shouldBe listOf(1L, 3L)

        insertManga(id = 4)
        repository.replaceMember(oldMangaId = 1, newMangaId = 4)
        val group = repository.getLiveGroup(4)
        group?.mainMangaId shouldBe 4L
        group?.members?.map { it.mangaId } shouldBe listOf(4L, 3L)
        repository.getLiveGroup(1).shouldBeNull()
    }

    @Test
    fun `migrating onto another member of the group leaves one row for it`() = runTest {
        repository.merge(mainId = 1, mangaIds = listOf(1, 2, 3))

        repository.replaceMember(oldMangaId = 1, newMangaId = 2)

        val group = repository.getLiveGroup(2)
        group?.mainMangaId shouldBe 2L
        group?.members?.map { it.mangaId } shouldBe listOf(2L, 3L)
    }

    @Test
    fun `migrating onto the main of another group unmerges that group, and its other members do not join`() = runTest {
        insertManga(id = 4)
        repository.merge(mainId = 1, mangaIds = listOf(1, 2))
        repository.merge(mainId = 3, mangaIds = listOf(3, 4))

        repository.replaceMember(oldMangaId = 2, newMangaId = 3)

        val group = repository.getLiveGroup(1)
        group?.members?.map { it.mangaId } shouldBe listOf(1L, 3L)
        repository.getLiveGroup(4).shouldBeNull()
        scalar("SELECT count(*) FROM merged_members") shouldBe "2"
    }

    @Test
    fun `migrating the main onto the main of another group unmerges that group and runs nothing together`() = runTest {
        insertManga(id = 4)
        repository.merge(mainId = 1, mangaIds = listOf(1, 2))
        repository.merge(mainId = 3, mangaIds = listOf(3, 4))

        repository.replaceMember(oldMangaId = 1, newMangaId = 3)

        val group = repository.getLiveGroup(3)
        group?.mainMangaId shouldBe 3L
        group?.members?.map { it.mangaId } shouldBe listOf(3L, 2L)
        repository.getLiveGroup(4).shouldBeNull()
        repository.getLiveGroup(1).shouldBeNull()
    }

    @Test
    fun `migrating onto a plain member of a group that keeps two or more takes only that member out of it`() = runTest {
        insertManga(id = 4)
        insertManga(id = 5)
        repository.merge(mainId = 1, mangaIds = listOf(1, 2))
        repository.merge(mainId = 4, mangaIds = listOf(4, 3, 5))

        repository.replaceMember(oldMangaId = 2, newMangaId = 3)

        repository.getLiveGroup(1)?.members?.map { it.mangaId } shouldBe listOf(1L, 3L)
        val other = repository.getLiveGroup(4)
        other?.mainMangaId shouldBe 4L
        other?.members?.map { it.mangaId } shouldBe listOf(4L, 5L)
    }

    @Test
    fun `migrating onto a member of a group left with one member unmerges that group`() = runTest {
        insertManga(id = 4)
        repository.merge(mainId = 1, mangaIds = listOf(1, 2))
        repository.merge(mainId = 3, mangaIds = listOf(3, 4))

        repository.replaceMember(oldMangaId = 2, newMangaId = 4)

        repository.getLiveGroup(1)?.members?.map { it.mangaId } shouldBe listOf(1L, 4L)
        repository.getLiveGroup(3).shouldBeNull()
        scalar("SELECT count(*) FROM merged_members") shouldBe "2"
    }

    @Test
    fun `an entry in no group migrates with nothing written`() = runTest {
        repository.replaceMember(oldMangaId = 1, newMangaId = 2)

        scalar("SELECT count(*) FROM merged_members") shouldBe "0"
    }
}

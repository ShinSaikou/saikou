package com.shinsaikou.data.repository

import com.shinsaikou.data.merged.MergedGroupRepositoryImpl
import com.shinsaikou.domain.merged.model.MergedGroup
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.data.manga.MangaRepositoryImpl

/** Group rows name entries by key, so every read here goes through `mangas`. */
class MergedGroupRepositoryImplTest : RepositoryTest() {

    private val repository by lazy { MergedGroupRepositoryImpl(handler) }

    @BeforeEach
    fun seed() {
        (1L..3L).forEach { insertManga(id = it) }
    }

    private fun url(id: Long) = "https://example.invalid/manga/$id"

    private fun clearLog() = exec("DELETE FROM sync_change_log")

    private fun log(): String? = scalar(
        "SELECT group_concat(DISTINCT entity_type || ':' || entity_id) FROM sync_change_log",
    )

    private fun clock(groupId: Long): Long = scalar("SELECT modified_at FROM merged_groups WHERE _id = $groupId")!!.toLong()

    private fun rowCount(table: String) = scalar("SELECT count(*) FROM $table")

    private suspend fun group(mangaId: Long = 1): MergedGroup = repository.getLiveGroup(mangaId).shouldNotBeNull()

    @Test
    fun `any member's id finds the group, in chapter order, the main entry a member too`() = runTest {
        repository.merge(mainId = 2, mangaIds = listOf(1, 2))

        repository.getLiveGroup(1) shouldBe repository.getLiveGroup(2)
        group().mainMangaId shouldBe 2
        group().members.map { it.mangaId } shouldContainExactly listOf(1, 2)
        repository.getLiveGroup(3).shouldBeNull()
    }

    @Test
    fun `a group with fewer than two live members, or a dead main entry, is not live and hides nothing`() = runTest {
        repository.merge(mainId = 1, mangaIds = listOf(1, 2, 3))
        exec("UPDATE mangas SET favorite = 0 WHERE _id = 3")
        group().members.map { it.mangaId } shouldContainExactly listOf(1, 2)

        exec("UPDATE mangas SET deleted_at = 1000 WHERE _id = 2")
        repository.getLiveGroups().shouldBeEmpty()

        exec("UPDATE mangas SET deleted_at = NULL WHERE _id = 2")
        exec("UPDATE mangas SET favorite = 1 WHERE _id = 3")
        exec("UPDATE mangas SET favorite = 0 WHERE _id = 1")
        repository.getLiveGroup(2).shouldBeNull()
        rowCount("merged_members") shouldBe "3"
    }

    @Test
    fun `a member that has not arrived is inert, and a local reorder keeps it in the set`() = runTest {
        repository.merge(mainId = 1, mangaIds = listOf(1, 2))
        exec(
            """
            INSERT INTO merged_members(member_source, member_url, group_id, position)
            VALUES (1, '/not/here', 1, 9)
            """,
        )
        group().members shouldHaveSize 2

        repository.setOrder(group().id, listOf(2, 1))

        scalar("SELECT group_concat(member_url) FROM (SELECT member_url FROM merged_members ORDER BY position)") shouldBe
            "${url(2)},${url(1)},/not/here"
    }

    @Test
    fun `an unmerge keeps the row with a newer clock, and a new merge moves past it`() = runTest {
        repository.merge(mainId = 1, mangaIds = listOf(1, 2))
        val id = group().id
        val merged = clock(id)

        repository.unmerge(id)

        rowCount("merged_members") shouldBe "0"
        rowCount("merged_groups") shouldBe "1"
        (clock(id) > merged) shouldBe true
        val tombstone = clock(id)

        repository.merge(mainId = 1, mangaIds = listOf(1, 3))

        group().id shouldBe id
        (clock(id) > tombstone) shouldBe true
    }

    @Test
    fun `every local write logs its group once it changes something, and a no-op logs nothing`() = runTest {
        clearLog()
        repository.merge(mainId = 1, mangaIds = listOf(1, 2))
        val id = group().id
        log() shouldBe "merged_group:$id"

        clearLog()
        repository.setOrder(id, listOf(1, 2))
        repository.setShowAll(id, false)
        repository.setCheckUpdates(2, true)
        log().shouldBeNull()

        repository.setShowAll(id, true)
        log() shouldBe "merged_group:$id"
        clearLog()
        repository.setCheckUpdates(2, false)
        log() shouldBe "merged_group:$id"
        clearLog()
        repository.setOrder(id, listOf(2, 1))
        log() shouldBe "merged_group:$id"
    }

    @Test
    fun `changing the main entry empties the old row, carries the order and gives both one clock`() = runTest {
        repository.merge(mainId = 1, mangaIds = listOf(1, 2))
        val old = group().id
        repository.setShowAll(old, true)
        repository.setCheckUpdates(2, false)
        repository.setOrder(old, listOf(2, 1))
        clearLog()

        repository.setMain(old, 2)

        val moved = group()
        moved.mainMangaId shouldBe 2
        moved.showAll shouldBe true
        moved.members.map { it.mangaId } shouldContainExactly listOf(2, 1)
        moved.members.first().checkUpdates shouldBe false
        clock(old) shouldBe clock(moved.id)
        scalar("SELECT count(*) FROM merged_members WHERE group_id = $old") shouldBe "0"
        scalar("SELECT group_concat(DISTINCT entity_id) FROM sync_change_log WHERE entity_type = 'merged_group'")!!
            .split(",").map { it.toLong() }.toSet() shouldBe setOf(old, moved.id)
    }

    @Test
    fun `a member merged elsewhere moves with its check_updates`() = runTest {
        repository.merge(mainId = 1, mangaIds = listOf(1, 2))
        repository.setCheckUpdates(2, false)

        repository.merge(mainId = 3, mangaIds = listOf(3, 2))

        group(3).members.single { it.mangaId == 2L }.checkUpdates shouldBe false
        scalar("SELECT count(*) FROM merged_members WHERE member_url = '${url(2)}'") shouldBe "1"
    }

    @Test
    fun `the alias remap rewrites both tables, moves no clock and logs nothing`() = runTest {
        repository.merge(mainId = 1, mangaIds = listOf(1, 2))
        val id = group().id
        val before = clock(id)
        clearLog()

        MangaRepositoryImpl(handler).rewriteSourceId(oldSourceId = 1, newSourceId = 8) shouldBe 3

        scalar("SELECT main_source FROM merged_groups") shouldBe "8"
        scalar("SELECT group_concat(DISTINCT member_source) FROM merged_members") shouldBe "8"
        group().members shouldHaveSize 2
        clock(id) shouldBe before
        log().shouldBeNull()
    }

    @Test
    fun `an alias collision keeps the row being moved and takes the replaced group's members with it`() = runTest {
        exec("INSERT INTO merged_groups(_id, main_source, main_url, modified_at) VALUES (5, 8, '/a', 1)")
        exec("INSERT INTO merged_members(member_source, member_url, group_id, position) VALUES (8, '/gone', 5, 0)")
        exec("INSERT INTO merged_groups(_id, main_source, main_url, modified_at) VALUES (6, 1, '/a', 2)")
        exec("INSERT INTO merged_members(member_source, member_url, group_id, position) VALUES (1, '/kept', 6, 0)")

        MangaRepositoryImpl(handler).rewriteSourceId(oldSourceId = 1, newSourceId = 8)

        scalar("SELECT group_concat(_id) FROM merged_groups") shouldBe "6"
        scalar("SELECT group_concat(member_url) FROM merged_members") shouldBe "/kept"
    }
}

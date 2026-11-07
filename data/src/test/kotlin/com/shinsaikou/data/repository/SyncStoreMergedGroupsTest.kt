package com.shinsaikou.data.repository

import com.shinsaikou.data.merged.MergedGroupRepositoryImpl
import com.shinsaikou.data.sync.SyncStoreImpl
import com.shinsaikou.domain.sync.model.StoredMergedGroup
import com.shinsaikou.domain.sync.model.StoredMergedMember
import com.shinsaikou.domain.sync.model.SyncPushRequest
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The merged groups through [SyncStoreImpl] (R163, `design/plans/merged-entries.md` section 6) over
 * the real schema and triggers: what a push or a snapshot reads, tombstones included, and that an
 * apply writes a group whole, with its latch raised and lowered, so it logs nothing and moves no
 * clock of its own. Groups here are named by key with no `mangas` row behind them, as a line from
 * another device is.
 */
class SyncStoreMergedGroupsTest : RepositoryTest() {

    private val store by lazy { SyncStoreImpl(handler, driver) }

    private fun member(url: String, position: Long, checkUpdates: Boolean = true) =
        StoredMergedMember(source = 1, url = url, position = position, checkUpdates = checkUpdates)

    private fun group(main: String, clock: Long, vararg members: StoredMergedMember, showAll: Boolean = false) =
        StoredMergedGroup(
            id = 0,
            mainSource = 1,
            mainUrl = main,
            showAll = showAll,
            modifiedAt = clock,
            members = members.toList(),
        )

    private fun logged(): String? = scalar("SELECT group_concat(entity_type || ':' || entity_id) FROM sync_change_log")

    private fun latches(): String? = scalar("SELECT count(*) FROM merged_groups WHERE is_syncing = 1")

    @Test
    fun `an apply stores a group whole with its members and logs nothing`() = runTest {
        val id = store.apply { insertMergedGroup(group("/a", 500, member("/a", 0), member("/b", 1, checkUpdates = false), showAll = true)) }

        store.mergedGroups() shouldContainExactly listOf(
            group("/a", 500, member("/a", 0), member("/b", 1, checkUpdates = false), showAll = true).copy(id = id),
        )
        logged().shouldBeNull()
        latches() shouldBe "0"
    }

    @Test
    fun `an apply that rewrites a group replaces its members and clock and leaves no trigger a trace`() = runTest {
        val id = store.apply { insertMergedGroup(group("/a", 500, member("/a", 0), member("/b", 1))) }

        store.apply { updateMergedGroup(group("/a", 900, member("/a", 0), member("/c", 1)).copy(id = id)) }

        store.mergedGroups().single().let { stored ->
            stored.modifiedAt shouldBe 900
            stored.members.map { it.url } shouldContainExactly listOf("/a", "/c")
        }
        logged().shouldBeNull()
        latches() shouldBe "0"
    }

    @Test
    fun `an apply that empties a group leaves the tombstone, which a snapshot still reads`() = runTest {
        val id = store.apply { insertMergedGroup(group("/a", 500, member("/a", 0), member("/b", 1))) }

        store.apply { updateMergedGroup(group("/a", 600).copy(id = id)) }

        store.mergedGroups() shouldContainExactly listOf(group("/a", 600).copy(id = id))
        scalar("SELECT count(*) FROM merged_members") shouldBe "0"
    }

    @Test
    fun `taking a member from a group keeps the group's clock and logs nothing`() = runTest {
        store.apply { insertMergedGroup(group("/a", 500, member("/a", 0), member("/b", 1))) }

        store.apply { removeMergedMember(1, "/b") }

        store.mergedGroups().single().let { stored ->
            stored.modifiedAt shouldBe 500
            stored.members.map { it.url } shouldContainExactly listOf("/a")
        }
        logged().shouldBeNull()
        latches() shouldBe "0"
    }

    @Test
    fun `taking a member that no group holds does nothing`() = runTest {
        store.apply { removeMergedMember(1, "/nobody") }

        store.mergedGroups().shouldBeEmpty()
    }

    @Test
    fun `a group is found by its main entry and by any of its members`() = runTest {
        store.apply { insertMergedGroup(group("/a", 500, member("/a", 0), member("/b", 1))) }

        store.apply {
            mergedGroup(1, "/a").shouldNotBeNull().members.map { it.url } shouldContainExactly listOf("/a", "/b")
            mergedGroup(1, "/b").shouldBeNull()
            mergedGroupOfMember(1, "/b").shouldNotBeNull().mainUrl shouldBe "/a"
            mergedGroupOfMember(2, "/b").shouldBeNull()
        }
    }

    @Test
    fun `a push reads the groups its log rows name, and drops one that is gone`() = runTest {
        val first = store.apply { insertMergedGroup(group("/a", 500, member("/a", 0))) }
        val second = store.apply { insertMergedGroup(group("/b", 600)) }

        val rows = store.readForPush(SyncPushRequest(mergedGroupIds = setOf(first, second, 999)))

        rows.mergedGroups.keys shouldBe setOf(first, second)
        rows.mergedGroups.getValue(second).members.shouldBeEmpty()
        store.readForPush(SyncPushRequest()).mergedGroups shouldBe emptyMap()
    }

    @Test
    fun `groups written by the app and groups applied from a line read alike`() = runTest {
        insertManga(1)
        insertManga(2)
        val repository = MergedGroupRepositoryImpl(handler)
        repository.merge(mainId = 1, mangaIds = listOf(1, 2))
        repository.unmerge(scalar("SELECT _id FROM merged_groups")!!.toLong())

        store.mergedGroups().single().let {
            it.mainUrl shouldBe "https://example.invalid/manga/1"
            it.members.shouldBeEmpty()
        }
    }
}

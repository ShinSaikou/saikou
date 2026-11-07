package com.shinsaikou.data.repository

import app.cash.turbine.test
import com.shinsaikou.data.merged.MergedGroupRepositoryImpl
import com.shinsaikou.domain.database.TransactionRunner
import com.shinsaikou.domain.merged.interactor.CarryMergedTracks
import com.shinsaikou.domain.merged.interactor.GetMergedGroup
import com.shinsaikou.domain.merged.interactor.GetMergedGroups
import com.shinsaikou.domain.merged.interactor.MergeEntries
import com.shinsaikou.domain.merged.interactor.ReconcileMergedRead
import com.shinsaikou.domain.merged.interactor.RemoveMergedMember
import com.shinsaikou.domain.merged.interactor.SetMergedCheckUpdates
import com.shinsaikou.domain.merged.interactor.SetMergedMain
import com.shinsaikou.domain.merged.interactor.SetMergedOrder
import com.shinsaikou.domain.merged.interactor.SetMergedShowAll
import com.shinsaikou.domain.merged.interactor.UnmergeEntries
import com.shinsaikou.domain.merged.model.MergedGroup
import com.shinsaikou.domain.preferences.LibraryPreferences
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.data.track.TrackRepositoryImpl

/**
 * The merged entry interactors (R163) over the real repository and database: what each does to the
 * rows, the group it leaves live, and that every one of them stands aside while the Experimental
 * switch is off, with the rows left as they were. Entries 1 to 5 are favourites of one source; the
 * switch is on unless a test says otherwise.
 */
class MergedInteractorsTest : RepositoryTest() {

    private val repository by lazy { MergedGroupRepositoryImpl(handler) }
    private val preferences = LibraryPreferences(InMemoryPreferenceStore())
    private val getGroup by lazy { GetMergedGroup(repository, preferences) }
    private val reconcile by lazy { ReconcileMergedRead(repository, preferences) }
    private val carryTracks by lazy { CarryMergedTracks(TrackRepositoryImpl(handler)) }
    private val merge by lazy { MergeEntries(repository, getGroup, reconcile, carryTracks, preferences, InlineTransactions) }
    private val remove by lazy { RemoveMergedMember(repository, getGroup, carryTracks, InlineTransactions) }
    private val setMain by lazy { SetMergedMain(repository, getGroup, carryTracks, InlineTransactions) }
    private val setOrder by lazy { SetMergedOrder(repository, getGroup) }
    private val setShowAll by lazy { SetMergedShowAll(repository, getGroup) }
    private val setCheckUpdates by lazy { SetMergedCheckUpdates(repository, getGroup) }
    private val unmerge by lazy { UnmergeEntries(repository, getGroup) }

    private object InlineTransactions : TransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    }

    @BeforeEach
    fun seed() {
        (1L..5L).forEach { insertManga(id = it) }
        preferences.mergedEntries.set(true)
    }

    private fun url(id: Long) = "https://example.invalid/manga/$id"

    private suspend fun group(mangaId: Long): MergedGroup? = getGroup.await(mangaId)

    private suspend fun members(mangaId: Long): List<Long>? = group(mangaId)?.members?.map { it.mangaId }

    private fun clock(main: Long): Long =
        scalar("SELECT modified_at FROM merged_groups WHERE main_url = '${url(main)}'")!!.toLong()

    private fun groupRows(): Long = scalar("SELECT count(*) FROM merged_groups")!!.toLong()

    private fun memberRows(): Long = scalar("SELECT count(*) FROM merged_members")!!.toLong()

    private fun chapter(id: Long, mangaId: Long, number: Double, read: Int = 0, scanlator: String? = null) = exec(
        """
        INSERT INTO chapters(_id, manga_id, url, name, scanlator, read, bookmark, last_page_read, chapter_number,
            source_order, date_fetch, date_upload, memo)
        VALUES ($id, $mangaId, '/c/$id', 'Chapter $id', ${scanlator?.let { "'$it'" } ?: "NULL"}, $read, 0, 0, $number,
            $id, 0, 0, CAST('{}' AS BLOB))
        """,
    )

    private fun read(id: Long): String? = scalar("SELECT read FROM chapters WHERE _id = $id")

    /** Everything an interactor could write: both tables, the chapters' read flags and the change log. */
    private fun state(): String = scalar(
        """
        SELECT (SELECT group_concat(_id || ':' || main_url || ':' || show_all || ':' || modified_at, ';') FROM merged_groups) || '|' ||
            (SELECT group_concat(member_url || ':' || group_id || ':' || position || ':' || check_updates, ';')
                FROM (SELECT * FROM merged_members ORDER BY member_url)) || '|' ||
            (SELECT coalesce(group_concat(read), '') FROM (SELECT read FROM chapters ORDER BY _id)) || '|' ||
            (SELECT count(*) FROM sync_change_log)
        """,
    )!!

    // MergeEntries -------------------------------------------------------------------------------

    @Test
    fun `merging an entry that is in a group brings the whole group, in its place in the list`() = runTest {
        merge.await(mainId = 1, mangaIds = listOf(1, 2))

        merge.await(mainId = 3, mangaIds = listOf(3, 1))

        members(2) shouldContainExactly listOf(3, 1, 2)
        group(2)!!.mainMangaId shouldBe 3
        group(1) shouldBe group(3)
    }

    @Test
    fun `a group left behind is an unmerged row with a newer clock and no members`() = runTest {
        merge.await(mainId = 1, mangaIds = listOf(1, 2))
        val before = clock(1)

        merge.await(mainId = 3, mangaIds = listOf(3, 1))

        groupRows() shouldBe 2
        scalar("SELECT count(*) FROM merged_members m JOIN merged_groups g ON g._id = m.group_id WHERE g.main_url = '${url(1)}'") shouldBe "0"
        (clock(1) > before) shouldBe true
    }

    @Test
    fun `absorbing a group whose main entry is the new main keeps that row and unmerges nothing`() = runTest {
        merge.await(mainId = 1, mangaIds = listOf(1, 2))
        val id = group(1)!!.id

        merge.await(mainId = 1, mangaIds = listOf(1, 3))

        group(1)!!.id shouldBe id
        members(1) shouldContainExactly listOf(1, 2, 3)
        groupRows() shouldBe 1
    }

    @Test
    fun `two groups merged under one main entry bring every member, each group's order kept`() = runTest {
        merge.await(mainId = 1, mangaIds = listOf(1, 2))
        merge.await(mainId = 4, mangaIds = listOf(4, 5))

        merge.await(mainId = 4, mangaIds = listOf(1, 4))

        members(5) shouldContainExactly listOf(1, 2, 4, 5)
        group(2)!!.mainMangaId shouldBe 4
        memberRows() shouldBe 4
    }

    @Test
    fun `an entry named twice, or alone, merges nothing`() = runTest {
        merge.await(mainId = 1, mangaIds = listOf(1, 1))
        merge.await(mainId = 2, mangaIds = listOf(2))

        groupRows() shouldBe 0
        memberRows() shouldBe 0
    }

    @Test
    fun `a main entry that is not among the entries is refused and writes nothing`() = runTest {
        shouldThrow<IllegalArgumentException> { merge.await(mainId = 3, mangaIds = listOf(1, 2)) }

        groupRows() shouldBe 0
    }

    @Test
    fun `a merge raises every copy of a number any copy has read`() = runTest {
        chapter(10, mangaId = 1, number = 1.0, read = 1)
        chapter(11, mangaId = 2, number = 1.0)
        chapter(12, mangaId = 2, number = 2.0)

        merge.await(mainId = 1, mangaIds = listOf(1, 2))

        read(11) shouldBe "1"
        read(12) shouldBe "0"
    }

    // RemoveMergedMember -------------------------------------------------------------------------

    @Test
    fun `removing a member that is not the main entry leaves the group live with the rest`() = runTest {
        merge.await(mainId = 1, mangaIds = listOf(1, 2, 3))

        remove.await(2)

        members(1) shouldContainExactly listOf(1, 3)
        group(1)!!.mainMangaId shouldBe 1
        group(2).shouldBeNull()
        scalar("SELECT count(*) FROM merged_members WHERE member_url = '${url(2)}'") shouldBe "0"
    }

    @Test
    fun `removing the main entry hands the main to the first member left`() = runTest {
        merge.await(mainId = 3, mangaIds = listOf(1, 2, 3))

        remove.await(3)

        group(1)!!.mainMangaId shouldBe 1
        members(2) shouldContainExactly listOf(1, 2)
        group(3).shouldBeNull()
        // The old main's row stays as the tombstone, and the group that took over is not older than it.
        scalar("SELECT count(*) FROM merged_members m JOIN merged_groups g ON g._id = m.group_id WHERE g.main_url = '${url(3)}'") shouldBe "0"
        (clock(1) >= clock(3)) shouldBe true
    }

    @Test
    fun `removing one of the last two members takes both out, since one entry is no group`() = runTest {
        merge.await(mainId = 1, mangaIds = listOf(1, 2))
        val id = group(1)!!.id

        remove.await(2)

        group(1).shouldBeNull()
        group(2).shouldBeNull()
        memberRows() shouldBe 0
        scalar("SELECT _id FROM merged_groups") shouldBe id.toString()
    }

    @Test
    fun `removing the main entry of a pair unmerges the pair`() = runTest {
        merge.await(mainId = 1, mangaIds = listOf(1, 2))

        remove.await(1)

        memberRows() shouldBe 0
        group(2).shouldBeNull()
    }

    @Test
    fun `removing an entry that is in no group writes nothing`() = runTest {
        merge.await(mainId = 1, mangaIds = listOf(1, 2))
        val before = state()

        remove.await(4)

        state() shouldBe before
    }

    @Test
    fun `an unmerge also takes the members that have not arrived on this device`() = runTest {
        merge.await(mainId = 1, mangaIds = listOf(1, 2))
        exec("INSERT INTO merged_members(member_source, member_url, group_id, position) VALUES (1, '/not/here', 1, 9)")

        remove.await(2)

        memberRows() shouldBe 0
    }

    // SetMergedMain ------------------------------------------------------------------------------

    @Test
    fun `the repository asked for the main entry it has keeps the group as it is`() = runTest {
        merge.await(mainId = 1, mangaIds = listOf(1, 2))
        setShowAll.await(1, showAll = true)
        val before = state()

        repository.setMain(group(1)!!.id, 1)

        state() shouldBe before
        group(1)!!.showAll shouldBe true
    }

    @Test
    fun `an order naming an entry outside the group neither adds it nor takes it from its own group`() = runTest {
        merge.await(mainId = 1, mangaIds = listOf(1, 2))
        merge.await(mainId = 3, mangaIds = listOf(3, 4))

        setOrder.await(listOf(2, 5, 3, 1))

        members(1) shouldContainExactly listOf(2, 1)
        members(3) shouldContainExactly listOf(3, 4)
        group(5).shouldBeNull()
    }

    @Test
    fun `a new main entry empties the old row and sets the new one, both under one clock`() = runTest {
        merge.await(mainId = 1, mangaIds = listOf(1, 2, 3))
        exec("DELETE FROM sync_change_log")

        setMain.await(2)

        group(1)!!.mainMangaId shouldBe 2
        members(1) shouldContainExactly listOf(1, 2, 3)
        clock(1) shouldBe clock(2)
        scalar("SELECT count(*) FROM merged_members m JOIN merged_groups g ON g._id = m.group_id WHERE g.main_url = '${url(1)}'") shouldBe "0"
        scalar("SELECT count(DISTINCT entity_id) FROM sync_change_log WHERE entity_type = 'merged_group'") shouldBe "2"
    }

    @Test
    fun `changing the main entry back reuses the tombstone and moves both clocks on`() = runTest {
        merge.await(mainId = 1, mangaIds = listOf(1, 2))
        setMain.await(2)
        val between = clock(2)

        setMain.await(1)

        groupRows() shouldBe 2
        group(2)!!.mainMangaId shouldBe 1
        (clock(1) > between) shouldBe true
        clock(1) shouldBe clock(2)
    }

    @Test
    fun `making the main entry the main again, or an entry in no group the main, writes nothing`() = runTest {
        merge.await(mainId = 1, mangaIds = listOf(1, 2))
        val before = state()

        setMain.await(1)
        setMain.await(4)

        state() shouldBe before
    }

    // The group interactors by their effect ------------------------------------------------------

    @Test
    fun `order, flags and the unmerge act on the group of any member`() = runTest {
        merge.await(mainId = 1, mangaIds = listOf(1, 2, 3))

        setOrder.await(listOf(3, 1, 2))
        setShowAll.await(2, true)
        setCheckUpdates.await(3, false)

        members(2) shouldContainExactly listOf(3, 1, 2)
        group(1)!!.showAll shouldBe true
        group(1)!!.members.first().checkUpdates shouldBe false

        unmerge.await(2)

        group(1).shouldBeNull()
        memberRows() shouldBe 0
        scalar("SELECT show_all FROM merged_groups") shouldBe "0"
    }

    // The switch ---------------------------------------------------------------------------------

    @Test
    fun `with the switch off every lookup returns nothing and every writer leaves the rows as they were`() = runTest {
        merge.await(mainId = 1, mangaIds = listOf(1, 2, 3))
        chapter(10, mangaId = 1, number = 1.0, read = 1)
        chapter(11, mangaId = 2, number = 1.0)
        preferences.mergedEntries.set(false)
        val before = state()

        getGroup.await(1).shouldBeNull()
        getGroup.await(3).shouldBeNull()
        merge.await(mainId = 4, mangaIds = listOf(4, 5))
        merge.await(mainId = 4, mangaIds = listOf(4, 1))
        remove.await(3)
        remove.await(1)
        setMain.await(2)
        setOrder.await(listOf(3, 2, 1))
        setShowAll.await(1, true)
        setCheckUpdates.await(2, false)
        unmerge.await(1)
        reconcile.await(1)
        reconcile.await(setOf(1L), setOf(1L, 2L))

        state() shouldBe before
        read(11) shouldBe "0"
        // The rows are still there, live as far as the repository goes, for the sync that carries them.
        repository.getLiveGroup(1).shouldNotBeNull()
    }

    @Test
    fun `the same calls write once the switch is on`() = runTest {
        merge.await(mainId = 1, mangaIds = listOf(1, 2, 3))
        chapter(10, mangaId = 1, number = 1.0, read = 1)
        chapter(11, mangaId = 2, number = 1.0)

        reconcile.await(setOf(1L), emptySet())
        read(11) shouldBe "1"

        setShowAll.await(1, true)
        group(1)!!.showAll shouldBe true
    }

    @Test
    fun `the reconcile of a pull touches only the groups it names`() = runTest {
        merge.await(mainId = 1, mangaIds = listOf(1, 2))
        merge.await(mainId = 4, mangaIds = listOf(4, 5))
        chapter(10, mangaId = 1, number = 1.0, read = 1)
        chapter(11, mangaId = 2, number = 1.0)
        chapter(20, mangaId = 4, number = 1.0, read = 1)
        chapter(21, mangaId = 5, number = 1.0)

        reconcile.await(groupIds = emptySet(), mangaIds = setOf(5L))

        read(11) shouldBe "0"
        read(21) shouldBe "1"

        reconcile.await(groupIds = setOf(group(1)!!.id), mangaIds = emptySet())

        read(11) shouldBe "1"
    }

    @Test
    fun `GetMergedGroups follows the switch and the rows`() = runTest {
        preferences.mergedEntries.set(false)
        repository.merge(mainId = 1, mangaIds = listOf(1, 2))

        GetMergedGroups(repository, preferences).subscribe().test {
            awaitItem().shouldBeEmpty()

            preferences.mergedEntries.set(true)
            awaitItem().map { it.mainMangaId } shouldContainExactly listOf(1)

            repository.merge(mainId = 4, mangaIds = listOf(4, 5))
            awaitItem().map { it.mainMangaId }.sorted() shouldContainExactly listOf(1, 4)

            preferences.mergedEntries.set(false)
            awaitItem().shouldBeEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // Liveness -----------------------------------------------------------------------------------

    @Test
    fun `a group whose main entry is not a favourite is dead for every member, though the rows stay`() = runTest {
        merge.await(mainId = 1, mangaIds = listOf(1, 2, 3))

        exec("UPDATE mangas SET favorite = 0 WHERE _id = 1")

        listOf(1L, 2L, 3L).forEach { group(it).shouldBeNull() }
        memberRows() shouldBe 3
        repository.getLiveGroups().shouldBeEmpty()
    }

    @Test
    fun `a soft-deleted member leaves the group while two live members remain, and kills it below two`() = runTest {
        merge.await(mainId = 1, mangaIds = listOf(1, 2, 3))

        exec("UPDATE mangas SET deleted_at = 1000 WHERE _id = 3")
        members(1) shouldContainExactly listOf(1, 2)

        exec("UPDATE mangas SET deleted_at = 1000 WHERE _id = 2")
        group(1).shouldBeNull()

        // Re-added, the member is back in its place.
        exec("UPDATE mangas SET deleted_at = NULL WHERE _id = 2")
        members(1) shouldContainExactly listOf(1, 2)
    }

    @Test
    fun `a soft-deleted member has no group of its own, though its row still names one`() = runTest {
        merge.await(mainId = 1, mangaIds = listOf(1, 2, 3))

        exec("UPDATE mangas SET deleted_at = 1000 WHERE _id = 3")

        // Entry 3 is not in the library, so it is no member of the live group of 1 and 2.
        members(1) shouldContainExactly listOf(1, 2)
        group(3).shouldBeNull()
    }

    @Test
    fun `a member that left the library has no group of its own, though its row still names one`() = runTest {
        merge.await(mainId = 1, mangaIds = listOf(1, 2, 3))

        exec("UPDATE mangas SET favorite = 0 WHERE _id = 3")

        members(1) shouldContainExactly listOf(1, 2)
        group(3).shouldBeNull()
    }

    @Test
    fun `a soft-deleted main entry kills the group`() = runTest {
        merge.await(mainId = 1, mangaIds = listOf(1, 2, 3))

        exec("UPDATE mangas SET deleted_at = 1000 WHERE _id = 1")

        listOf(1L, 2L, 3L).forEach { group(it).shouldBeNull() }
    }

    @Test
    fun `a main entry with no member row of its own is no group, though two others are live`() = runTest {
        exec("INSERT INTO merged_groups(_id, main_source, main_url, modified_at) VALUES (7, 1, '${url(1)}', 100)")
        exec(
            """
            INSERT INTO merged_members(member_source, member_url, group_id, position)
            VALUES (1, '${url(2)}', 7, 0), (1, '${url(3)}', 7, 1)
            """,
        )

        listOf(1L, 2L, 3L).forEach { group(it).shouldBeNull() }
        repository.getLiveGroups().shouldBeEmpty()
    }

    @Test
    fun `a main entry whose row is not in the library yet leaves the group dead until it arrives`() = runTest {
        exec("INSERT INTO merged_groups(_id, main_source, main_url, modified_at) VALUES (7, 1, '/not/here', 100)")
        exec(
            """
            INSERT INTO merged_members(member_source, member_url, group_id, position)
            VALUES (1, '/not/here', 7, 0), (1, '${url(2)}', 7, 1), (1, '${url(3)}', 7, 2)
            """,
        )

        group(2).shouldBeNull()

        exec(
            """
            INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer,
                chapter_flags, cover_last_modified, date_added, memo)
            VALUES (9, 1, '/not/here', 'Title 9', 0, 1, 0, 0, 0, 0, 0, CAST('{}' AS BLOB))
            """,
        )

        members(2) shouldContainExactly listOf(9, 2, 3)
        group(2)!!.mainMangaId shouldBe 9
    }

    // ---- Tracks follow the main entry (R163) ----

    private fun track(mangaId: Long, trackerId: Long, remoteId: Long, read: Double = 0.0) = exec(
        """
        INSERT INTO manga_sync(manga_id, sync_id, remote_id, title, last_chapter_read, total_chapters, status, score,
            remote_url, start_date, finish_date)
        VALUES ($mangaId, $trackerId, $remoteId, 'T', $read, 0, 1, 0, '', 0, 0)
        """,
    )

    private fun tracksOf(mangaId: Long): String? = scalar(
        "SELECT group_concat(sync_id || ':' || remote_id || ':' || last_chapter_read) FROM " +
            "(SELECT * FROM manga_sync WHERE manga_id = $mangaId ORDER BY sync_id)",
    )

    @Test
    fun `a merge gives the main entry the tracks it lacks and keeps the member's own`() = runTest {
        track(2, trackerId = 7, remoteId = 70)

        merge.await(1, listOf(1, 2))

        tracksOf(1) shouldBe "7:70:0.0"
        tracksOf(2) shouldBe "7:70:0.0"
    }

    @Test
    fun `a merge leaves the main entry bound where it was, and moves a same-entry row that is further along`() = runTest {
        track(1, trackerId = 7, remoteId = 70, read = 3.0)
        track(2, trackerId = 7, remoteId = 99, read = 50.0)
        track(3, trackerId = 7, remoteId = 70, read = 9.0)

        merge.await(1, listOf(1, 2))
        tracksOf(1) shouldBe "7:70:3.0"

        merge.await(1, listOf(1, 3))
        tracksOf(1) shouldBe "7:70:9.0"
    }

    @Test
    fun `a new main entry takes the group's tracks, and a different remote entry is rebound to the group's`() = runTest {
        merge.await(1, listOf(1, 2))
        track(1, trackerId = 7, remoteId = 70, read = 4.0)
        track(2, trackerId = 7, remoteId = 80)
        track(2, trackerId = 8, remoteId = 81)

        setMain.await(2)

        tracksOf(2) shouldBe "7:70:4.0,8:81:0.0"
        tracksOf(1) shouldBe "7:70:4.0"
    }

    @Test
    fun `removing the main entry hands its tracks to the member that becomes main`() = runTest {
        merge.await(1, listOf(1, 2, 3))
        track(1, trackerId = 7, remoteId = 70, read = 4.0)

        remove.await(1)

        group(2)?.mainMangaId shouldBe 2
        tracksOf(2) shouldBe "7:70:4.0"
    }

    @Test
    fun `removing a member that is not the main entry moves no track`() = runTest {
        merge.await(1, listOf(1, 2, 3))
        track(1, trackerId = 7, remoteId = 70)

        remove.await(3)

        tracksOf(3).shouldBeNull()
        tracksOf(2).shouldBeNull()
    }
}

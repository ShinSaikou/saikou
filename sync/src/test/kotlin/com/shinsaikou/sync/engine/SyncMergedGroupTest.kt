package com.shinsaikou.sync.engine

import com.shinsaikou.domain.source.model.SourceAlias
import com.shinsaikou.sync.InMemorySyncBackend
import com.shinsaikou.sync.MergeEngine
import com.shinsaikou.sync.SyncDigest
import com.shinsaikou.sync.engine.SyncTestDevice.Companion.SOURCE
import com.shinsaikou.sync.format.MangaKey
import com.shinsaikou.sync.format.MergedGroupRow
import com.shinsaikou.sync.format.MergedMemberEntry
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * The `merged_group` line (R163, `design/plans/merged-entries.md` section 6) through the real engine
 * and store. The first test is why the line exists in this shape: naming entries a device does not
 * hold is no skip, since a skip would stop that device's compaction for good.
 */
class SyncMergedGroupTest {

    private val backend = InMemorySyncBackend()
    private val a = SyncTestDevice(backend, exported = true)
    private val b = SyncTestDevice(backend, exported = true)

    @AfterEach
    fun close() {
        a.close()
        b.close()
    }

    private fun key(id: Long) = MangaKey(SOURCE, "/series/$id")

    private fun line(main: Long, members: List<Long>, clock: Long) = MergedGroupRow(
        main = key(main),
        members = members.mapIndexed { position, id -> MergedMemberEntry(key(id), position.toLong(), true) },
        showAll = false,
        modifiedAt = clock,
    )

    private fun SyncTestDevice.apply(vararg rows: MergedGroupRow) =
        runBlocking { SegmentApply(store, aliases, MergeEngine()).apply(rows.toList()) }

    /** Each group's main url, clock and members, for comparing two devices. */
    private fun SyncTestDevice.groups() = rows(
        """
        SELECT G.main_url, G.modified_at, group_concat(M.member_url)
        FROM merged_groups G LEFT JOIN merged_members M ON M.group_id = G._id
        GROUP BY G._id ORDER BY G.main_url
        """,
        3,
    )

    private fun SyncTestDevice.mergeHere(mainId: Long, vararg mangaIds: Long) =
        runBlocking { mergedGroups.merge(mainId, mangaIds.toList()) }

    @Test
    fun `a line naming members this device does not hold is stored and is no skip`() {
        val result = b.apply(line(main = 7, members = listOf(7, 8), clock = 100))

        result.skipped shouldBe 0
        result.written shouldBe 1
        b.groups() shouldContainExactly listOf(listOf("/series/7", "100", "/series/7,/series/8"))
        b.long("SELECT count(*) FROM mangas") shouldBe 0
        b.logCount() shouldBe 0
        b.latchesRaised() shouldBe 0
    }

    @Test
    fun `a pulled line naming entries nobody holds leaves the device free to compact`() {
        a.exec("INSERT INTO merged_groups(main_source, main_url, modified_at) VALUES ($SOURCE, '/series/7', 100)")
        a.exec(
            """
            INSERT INTO merged_members(member_source, member_url, group_id, position)
            VALUES ($SOURCE, '/series/7', 1, 0), ($SOURCE, '/series/8', 1, 1)
            """,
        )
        a.sync()
        b.startReading(a.deviceId)

        val result = b.sync().shouldNotBeNull()

        result.rowsSkipped shouldBe 0
        b.preferences.skippedRows.get() shouldBe false
        b.groups() shouldBe a.groups()
    }

    @Test
    fun `a newer empty line unmerges and an older line loses to it`() {
        b.apply(line(main = 7, members = listOf(7, 8), clock = 100))

        b.apply(line(main = 7, members = emptyList(), clock = 200)).written shouldBe 1
        b.groups() shouldContainExactly listOf(listOf("/series/7", "200", null))

        b.apply(line(main = 7, members = listOf(7, 8), clock = 150)).written shouldBe 0
        b.groups() shouldContainExactly listOf(listOf("/series/7", "200", null))
    }

    @Test
    fun `a member claimed by two groups goes to the newer one, read in either order`() {
        val older = line(main = 1, members = listOf(1, 9), clock = 100)
        val newer = line(main = 2, members = listOf(2, 9), clock = 200)

        a.apply(older)
        a.apply(newer)
        b.apply(newer)
        b.apply(older)

        a.groups() shouldContainExactly listOf(
            listOf("/series/1", "100", "/series/1"),
            listOf("/series/2", "200", "/series/2,/series/9"),
        )
        b.groups() shouldBe a.groups()
    }

    @Test
    fun `at equal clocks a claim falls to the larger key, read in either order`() {
        val smaller = line(main = 1, members = listOf(1, 9), clock = 100)
        val larger = line(main = 2, members = listOf(2, 9), clock = 100)

        a.apply(smaller)
        a.apply(larger)
        b.apply(larger)
        b.apply(smaller)

        a.groups() shouldContainExactly listOf(
            listOf("/series/1", "100", "/series/1"),
            listOf("/series/2", "100", "/series/2,/series/9"),
        )
        b.groups() shouldBe a.groups()
    }

    @Test
    fun `changing the main entry lands the same way whichever line arrives first`() {
        val empty = line(main = 1, members = emptyList(), clock = 200)
        val moved = line(main = 2, members = listOf(1, 2), clock = 200)
        val before = line(main = 1, members = listOf(1, 2), clock = 100)

        a.apply(before)
        a.apply(empty)
        a.apply(moved)
        b.apply(before)
        b.apply(moved)
        b.apply(empty)

        a.groups() shouldContainExactly listOf(
            listOf("/series/1", "200", null),
            listOf("/series/2", "200", "/series/1,/series/2"),
        )
        b.groups() shouldBe a.groups()
    }

    @Test
    fun `at equal clocks a line with members beats an empty line, read in either order`() {
        val empty = line(main = 1, members = emptyList(), clock = 100)
        val held = line(main = 1, members = listOf(1, 2), clock = 100)

        a.apply(empty)
        a.apply(held)
        b.apply(held)
        b.apply(empty)

        a.groups() shouldContainExactly listOf(listOf("/series/1", "100", "/series/1,/series/2"))
        b.groups() shouldBe a.groups()
    }

    @Test
    fun `a line's keys resolve through this device's aliases`() {
        runBlocking { b.aliases.upsert(SourceAlias(oldSourceId = 999, newSourceId = SOURCE, dateAdded = 0)) }

        b.apply(
            MergedGroupRow(
                main = MangaKey(999, "/series/7"),
                members = listOf(MergedMemberEntry(MangaKey(999, "/series/7"), 0, true)),
                showAll = false,
                modifiedAt = 100,
            ),
        )

        b.long("SELECT main_source FROM merged_groups") shouldBe SOURCE
        b.long("SELECT member_source FROM merged_members") shouldBe SOURCE
    }

    @Test
    fun `a local merge travels as a segment, and the fingerprint sees it`() {
        a.addFavourite(1)
        a.addFavourite(2)
        a.sync()
        b.startReading(a.deviceId)
        b.sync()
        val before = runBlocking { SyncDigest.of(a.store) }
        runBlocking { SyncDigest.of(b.store) } shouldBe before

        a.mergeHere(1, 1, 2)
        a.sync()

        runBlocking { SyncDigest.of(a.store) } shouldNotBe before
        b.sync().shouldNotBeNull().rowsSkipped shouldBe 0
        b.groups() shouldBe a.groups()
        runBlocking { SyncDigest.of(b.store) } shouldBe runBlocking { SyncDigest.of(a.store) }
    }

    @Test
    fun `a full export carries every group`() {
        val exporter = SyncTestDevice(backend)
        val reader = SyncTestDevice(backend)
        try {
            exporter.addFavourite(1)
            exporter.addFavourite(2)
            exporter.mergeHere(1, 1, 2)
            exporter.sync()

            reader.sync().shouldNotBeNull().rowsSkipped shouldBe 0

            reader.groups() shouldBe exporter.groups()
            reader.groups().size shouldBe 1
        } finally {
            exporter.close()
            reader.close()
        }
    }

    @Test
    fun `a pull that touches a group raises its copies when the switch is on, and logs them`() {
        reconciledPull(on = true)

        b.long("SELECT read FROM chapters WHERE url = '/series/2/chapter/20'") shouldBe 1
        // Raised outside the latch, so it goes out as an ordinary change.
        b.preferences.lastPushedSeq.get() shouldNotBe 0L
    }

    @Test
    fun `a pull leaves the copies alone while the switch is off`() {
        reconciledPull(on = false)

        b.long("SELECT read FROM chapters WHERE url = '/series/2/chapter/20'") shouldBe 0
        b.groups().size shouldBe 1
    }

    /** A holds two merged entries with chapter 1 of each; it reads one, and [b] pulls that. */
    private fun reconciledPull(on: Boolean) {
        a.addFavourite(1)
        a.addFavourite(2)
        a.addChapter(10, 1)
        a.addChapter(20, 2)
        a.exec("UPDATE chapters SET chapter_number = 1")
        a.mergeHere(1, 1, 2)
        a.sync()
        b.startReading(a.deviceId)
        b.libraryPreferences.mergedEntries.set(on)
        b.sync()

        a.exec("UPDATE chapters SET read = 1 WHERE _id = 10")
        a.sync()
        b.sync()
    }
}

package com.shinsaikou.sync.engine

import com.shinsaikou.domain.source.model.SourceAlias
import com.shinsaikou.sync.InMemorySyncBackend
import com.shinsaikou.sync.MergeEngine
import com.shinsaikou.sync.SyncBackend
import com.shinsaikou.sync.SyncConflict
import com.shinsaikou.sync.SyncContent
import com.shinsaikou.sync.engine.SyncCompaction.Companion.DAY_MS
import com.shinsaikou.sync.engine.SyncTestDevice.Companion.SOURCE
import com.shinsaikou.sync.format.MangaKey
import com.shinsaikou.sync.format.MangaRow
import com.shinsaikou.sync.format.MergedGroupRow
import com.shinsaikou.sync.format.MergedMemberEntry
import com.shinsaikou.sync.format.RemoteName
import com.shinsaikou.sync.format.RowKind
import com.shinsaikou.sync.format.SyncLayout
import com.shinsaikou.sync.format.SyncRow
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.manga.model.MangaUpdate
import java.io.IOException
import java.io.InputStream

/**
 * The merged group line (R163, `design/plans/merged-entries.md` section 6) on its way between
 * devices, through the real engine and store: carried by a snapshot and by a full export that is
 * stopped and resumed, split from the entries it names, cut at a segment boundary, reported as a
 * conflict, read from an older file, and resolved through the source aliases.
 * [SyncMergedGroupTest] holds the first cases of the line and [SyncMergedGroupOrderTest] its order
 * laws.
 */
class SyncMergedGroupTransportTest {

    private var now = 0L
    private val backend = InMemorySyncBackend(clock = { now })
    private val devices = mutableListOf<SyncTestDevice>()

    private fun device(
        backend: InMemorySyncBackend = this.backend,
        exported: Boolean = false,
        remote: SyncBackend = backend,
    ): SyncTestDevice = SyncTestDevice(backend, exported = exported, remote = remote).also { devices += it }

    @AfterEach
    fun close() {
        devices.forEach { it.close() }
    }

    private fun key(id: Long) = MangaKey(SOURCE, "/series/$id")

    private fun SyncTestDevice.mergeHere(mainId: Long, vararg mangaIds: Long) =
        runBlocking { mergedGroups.merge(mainId, mangaIds.toList()) }

    private fun SyncTestDevice.groupId(mainId: Long): Long = long("SELECT _id FROM merged_groups WHERE main_url = '/series/$mainId'")!!

    /** Each group's main url, clock, flag and members in order, for comparing two devices. */
    private fun SyncTestDevice.groups() = rows(
        """
        SELECT G.main_url, G.modified_at, G.show_all,
            (SELECT group_concat(member_url || ':' || position || ':' || check_updates, ',')
                FROM (SELECT * FROM merged_members WHERE group_id = G._id ORDER BY position, member_url))
        FROM merged_groups G ORDER BY G.main_url
        """,
        4,
    )

    private fun line(main: Long, members: List<Long>, clock: Long) = MergedGroupRow(
        main = key(main),
        members = members.mapIndexed { position, id -> MergedMemberEntry(key(id), position.toLong(), true) },
        showAll = false,
        modifiedAt = clock,
    )

    private fun SyncTestDevice.apply(vararg rows: MergedGroupRow): ApplyResult =
        runBlocking { SegmentApply(store, aliases, MergeEngine()).apply(rows.toList()) }

    /** A edits and syncs [count] times, one segment each. */
    private fun SyncTestDevice.pushSegments(count: Int, from: Long) {
        repeat(count) { i ->
            addFavourite(from + i)
            sync().shouldNotBeNull().segmentsPushed shouldBe 1
        }
    }

    private fun groupLines(rows: List<SyncRow>) = rows.filterIsInstance<MergedGroupRow>()

    // Snapshots ----------------------------------------------------------------------------------

    @Test
    fun `a snapshot carries every group, the unmerge's tombstone too, and a reader that applies it lands on the same rows`() {
        val a = device()
        val c = device()
        (1L..4L).forEach(a::addFavourite)
        a.mergeHere(1, 1, 2)
        a.mergeHere(3, 3, 4)
        runBlocking { a.mergedGroups.unmerge(a.groupId(1)) }
        a.sync()
        a.pushSegments(3, from = 100)
        now = 14 * DAY_MS
        a.pushSegments(1, from = 200)
        val snapshot = backend.paths().mapNotNull { SyncLayout.parse(it) as? RemoteName.Snapshot }.single()

        val lines = groupLines(backend.rowFile(snapshot.path).second)

        lines.map { it.main } shouldContainExactly listOf(key(1), key(3))
        lines[0].members.shouldBeEmpty()
        lines[0].modifiedAt shouldBe a.long("SELECT modified_at FROM merged_groups WHERE main_url = '/series/1'")
        lines[1].members.map { it.manga } shouldContainExactly listOf(key(3), key(4))

        // A reader that cannot chain to the segments gets the groups from the snapshot alone.
        now = 15 * DAY_MS
        a.pushSegments(1, from = 300)
        val hidden = backend.paths().mapNotNull { SyncLayout.parse(it) as? RemoteName.Segment }
            .filter { it.deviceId == a.deviceId }.sortedBy { it.lastSeq }[1]
        backend.hide(hidden.path)

        val result = c.sync().shouldNotBeNull()

        result.snapshotApplied shouldBe true
        result.rowsSkipped shouldBe 0
        c.groups() shouldBe a.groups()
        c.library() shouldBe a.library()
        c.groups().first()[3] shouldBe null
    }

    // Exports ------------------------------------------------------------------------------------

    /** [delegate], part [failing] failing to write once, so the export stops with the parts before it saved. */
    private class FailingPart(private val delegate: InMemorySyncBackend, var failing: Int?) : SyncBackend by delegate {
        override suspend fun create(path: String, content: SyncContent) {
            val part = (SyncLayout.parse(path) as? RemoteName.ExportPart)?.part
            if (part != null && part == failing) {
                failing = null
                throw IOException("Connection reset writing $path")
            }
            delegate.create(path, content)
        }
    }

    private fun parts(of: SyncTestDevice) = backend.paths().mapNotNull { SyncLayout.parse(it) as? RemoteName.ExportPart }
        .filter { it.deviceId == of.deviceId }.sortedBy { it.part }

    @Test
    fun `an export stopped after part 1 and resumed carries each group once, in part 1, with the edit made meanwhile`() = runTest {
        val a = device()
        a.bulkLibrary(entries = ENTRIES, chapters = CHAPTERS)
        // The main entry and one member are in part 1; the other member is in the last part.
        a.mergeHere(1, 1, 2, ENTRIES.toLong())
        val failing = FailingPart(backend, failing = 2)

        shouldThrow<IOException> { a.engine.run(failing) }

        a.preferences.exportProgress.get().shouldNotBeNull().parts shouldBe 1
        // Edited while the export waits: it is in the log after the export's seq, so a segment carries it.
        runBlocking { a.mergedGroups.setShowAll(a.groupId(1), true) }
        a.engine.run(failing).shouldNotBeNull().exported shouldBe true

        val written = parts(a)
        (written.size >= 3) shouldBe true
        written.map { groupLines(backend.rowFile(it.path).second).size } shouldBe listOf(1) + List(written.size - 1) { 0 }
        groupLines(backend.rowFile(written.first().path).second).single().members.map { it.manga } shouldContainExactly
            listOf(key(1), key(2), key(ENTRIES.toLong()))

        val reader = device()
        reader.sync().shouldNotBeNull().rowsSkipped shouldBe 0
        reader.groups() shouldBe a.groups()
        reader.long("SELECT show_all FROM merged_groups") shouldBe 1
        reader.library() shouldBe a.library()
    }

    @Test
    fun `a reader applying an export part by part holds the group from part 1, and it goes live as its members arrive`() {
        val a = device()
        a.bulkLibrary(entries = ENTRIES, chapters = CHAPTERS)
        // Members in part 1, in a later part, and in the last part.
        a.mergeHere(1, 1, 5, ENTRIES.toLong())
        a.sync().shouldNotBeNull().exported shouldBe true
        val written = parts(a)
        (written.size >= 3) shouldBe true
        val reader = device()

        fun applyPart(number: Int): ApplyResult = runBlocking {
            SegmentApply(reader.store, reader.aliases, MergeEngine()).apply(backend.rowFile(written[number - 1].path).second)
        }

        applyPart(1).skipped shouldBe 0
        // The line is stored with all three members; only the main entry is here, so the group is dead.
        reader.groups().single()[3]!!.split(",").map { it.substringBefore(':') } shouldContainExactly
            listOf("/series/1", "/series/5", "/series/$ENTRIES")
        runBlocking { reader.mergedGroups.getLiveGroups() }.shouldBeEmpty()

        for (number in 2..written.size) applyPart(number).skipped shouldBe 0

        val live = runBlocking { reader.mergedGroups.getLiveGroups() }.single()
        live.members.map { it.url } shouldContainExactly listOf("/series/1", "/series/5", "/series/$ENTRIES")
        reader.groups() shouldBe a.groups()
    }

    // Lines for entries the device does not hold -------------------------------------------------

    @Test
    fun `a member joins its group on the device once its own row arrives, with nothing skipped on the way`() {
        val a = device(exported = true)
        val b = device(exported = true)
        a.exec("INSERT INTO merged_groups(main_source, main_url, modified_at) VALUES ($SOURCE, '/series/7', 100)")
        a.exec(
            """
            INSERT INTO merged_members(member_source, member_url, group_id, position)
            VALUES ($SOURCE, '/series/7', 1, 0), ($SOURCE, '/series/8', 1, 1)
            """,
        )
        a.sync()
        b.startReading(a.deviceId)

        b.sync().shouldNotBeNull().rowsSkipped shouldBe 0

        b.groups().single()[3] shouldBe "/series/7:0:1,/series/8:1:1"
        runBlocking { b.mergedGroups.getLiveGroups() }.shouldBeEmpty()

        a.addFavourite(7)
        a.addFavourite(8)
        a.sync()
        b.sync().shouldNotBeNull().rowsSkipped shouldBe 0

        b.preferences.skippedRows.get() shouldBe false
        val live = runBlocking { b.mergedGroups.getLiveGroups() }.single()
        live.members.map { it.mangaId } shouldContainExactly listOf(b.mangaId(SOURCE, "/series/7")!!, b.mangaId(SOURCE, "/series/8")!!)
        live.mainMangaId shouldBe b.mangaId(SOURCE, "/series/7")
    }

    // Conflicts ----------------------------------------------------------------------------------

    /** Sets a clock under the latch, so the value's own trigger does not move it again. */
    private fun SyncTestDevice.setGroupClock(id: Long, value: Long) {
        exec("UPDATE merged_groups SET is_syncing = 1 WHERE _id = $id")
        exec("UPDATE merged_groups SET modified_at = $value WHERE _id = $id")
        exec("UPDATE merged_groups SET is_syncing = 0 WHERE _id = $id")
    }

    @Test
    fun `a newer group line that replaces this device's own edit is a conflict, named by the main entry's title`() {
        val a = device()
        val b = device()
        a.addFavourite(1)
        a.addFavourite(2)
        a.mergeHere(1, 1, 2)
        a.sync()
        b.sync()
        a.sync()

        b.exec("UPDATE merged_members SET check_updates = 0 WHERE member_url = '/series/2'")
        b.setGroupClock(b.groupId(1), 1_000)
        runBlocking { a.mergedGroups.setShowAll(a.groupId(1), true) }
        a.setGroupClock(a.groupId(1), 5_000)
        a.sync()

        val result = b.sync().shouldNotBeNull()

        result.conflicts shouldContainExactly listOf(
            SyncConflict(kind = RowKind.MERGED_GROUP, title = "Title 1", chapter = null, fields = setOf("showAll", "members")),
        )
        b.groups() shouldBe a.groups()
    }

    @Test
    fun `a group line this device did not change is never a conflict, however much it takes`() {
        val a = device()
        val b = device()
        a.addFavourite(1)
        a.addFavourite(2)
        a.mergeHere(1, 1, 2)
        a.sync()
        b.sync()
        a.sync()
        runBlocking { a.mergedGroups.setShowAll(a.groupId(1), true) }
        a.sync()

        val result = b.sync().shouldNotBeNull()

        result.conflicts.shouldBeEmpty()
        result.routineMerges shouldBe 1
        b.groups() shouldBe a.groups()
    }

    // The segment's boundary ---------------------------------------------------------------------

    private fun chapters(mangaId: Long, count: Int, device: SyncTestDevice) = device.exec(
        """
        WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < $count)
        INSERT INTO chapters(manga_id, url, name, read, bookmark, last_page_read, chapter_number,
            source_order, date_fetch, date_upload, memo)
        SELECT $mangaId, '/series/$mangaId/chapter/' || i, 'Chapter ' || i, 0, 0, 0, i, i, 0, 0, CAST('{}' AS BLOB) FROM n
        """,
    )

    @Test
    fun `a group line whose rows precede a full batch's cut goes in the first segment, the member's entry in the next`() {
        val a = device(exported = true)
        val b = device(exported = true)
        // Entry 2 is browsed, with 20 chapters, and enters the library after the merge.
        a.exec(
            """
            INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer, chapter_flags,
                cover_last_modified, date_added, memo)
            VALUES (2, $SOURCE, '/series/2', 'Title 2', 0, 0, 1, 0, 0, 0, 0, CAST('{}' AS BLOB))
            """,
        )
        chapters(2, 20, a)
        a.addFavourite(1)
        chapters(1, 4_989, a)
        a.mergeHere(1, 1, 2)
        runBlocking { MangaRepositoryImpl(a.handler).update(MangaUpdate(id = 2, favorite = true)) } shouldBe true
        // The batch of 5,000 rows ends inside the add of entry 2.
        a.latestSeq() shouldBe 4_990 + MERGE_LOG_ROWS + 21
        a.string("SELECT entity_type || ' ' || entity_id FROM sync_change_log WHERE seq = 5000")!!.substringBefore(' ') shouldBe "chapter"

        a.sync().shouldNotBeNull().segmentsPushed shouldBe 2

        val segments = backend.paths().mapNotNull { SyncLayout.parse(it) as? RemoteName.Segment }
            .filter { it.deviceId == a.deviceId }.sortedBy { it.lastSeq }
        val first = backend.rowFile(segments[0].path).second
        val second = backend.rowFile(segments[1].path).second
        groupLines(first).single().members.map { it.manga } shouldContainExactly listOf(key(1), key(2))
        first.filterIsInstance<MangaRow>().map { it.key } shouldContainExactly listOf(key(1))
        groupLines(second).shouldBeEmpty()
        second.filterIsInstance<MangaRow>().map { it.key } shouldContainExactly listOf(key(2))

        b.startReading(a.deviceId)
        b.sync().shouldNotBeNull().rowsSkipped shouldBe 0
        b.groups() shouldBe a.groups()
        runBlocking { b.mergedGroups.getLiveGroups() }.single().members shouldHaveSize 2
    }

    // Older files --------------------------------------------------------------------------------

    @Test
    fun `a format 2 file, which has no group line, leaves every group as it is`() {
        val b = device(exported = true)
        b.apply(line(main = 7, members = listOf(7, 8), clock = 100))
        val before = b.groups()
        val foreign = "11111111-2222-3333-4444-555555555555"
        val path = SyncLayout.segmentPath(foreign, 0, 1)
        runBlocking {
            backend.create(
                path,
                SyncContent(
                    rawFile(
                        """{"format":2,"deviceId":"$foreign","scope":{"kind":"segment","afterSeq":0,"lastSeq":1,"rawRows":1},"counts":{"category":1}}""",
                        """{"entity":"category","key":"c3f1","name":"Reading","order":2,"flags":64,"parent":null,"modifiedAt":1700000000001,"deletedAt":null,"restoredAt":null}""",
                    ),
                ),
            )
        }
        b.startReading(foreign)

        val result = b.sync().shouldNotBeNull()

        result.rowsSkipped shouldBe 0
        result.rowsWritten shouldBe 1
        b.string("SELECT name FROM categories WHERE _id > 0") shouldBe "Reading"
        b.groups() shouldBe before
    }

    // Aliases ------------------------------------------------------------------------------------

    private fun SyncTestDevice.aliased(old: Long) =
        runBlocking { aliases.upsert(SourceAlias(oldSourceId = old, newSourceId = SOURCE, dateAdded = 0)) }

    @Test
    fun `members that an alias makes one entry collapse into one member, the lowest position's`() {
        val b = device(exported = true)
        b.aliased(999)

        val result = b.apply(
            MergedGroupRow(
                main = key(7),
                members = listOf(
                    MergedMemberEntry(MangaKey(999, "/series/7"), position = 2, checkUpdates = false),
                    MergedMemberEntry(key(7), position = 0, checkUpdates = true),
                    MergedMemberEntry(key(8), position = 1, checkUpdates = true),
                ),
                showAll = false,
                modifiedAt = 100,
            ),
        )

        result.skipped shouldBe 0
        b.groups().single()[3] shouldBe "/series/7:0:1,/series/8:1:1"
        b.long("SELECT count(*) FROM merged_members") shouldBe 2
    }

    @Test
    fun `two lines whose main entries an alias makes one are one group, the newer clock's, in either order`() {
        val older = MergedGroupRow(MangaKey(999, "/series/7"), listOf(MergedMemberEntry(MangaKey(999, "/series/7"), 0, true)), false, 100)
        val newer = line(main = 7, members = listOf(7, 8), clock = 200)
        val b = device(exported = true)
        val c = device(exported = true)
        b.aliased(999)
        c.aliased(999)

        b.apply(older)
        b.apply(newer)
        c.apply(newer)
        c.apply(older)

        b.groups() shouldBe listOf(listOf("/series/7", "200", "0", "/series/7:0:1,/series/8:1:1"))
        c.groups() shouldBe b.groups()
        b.long("SELECT count(*) FROM merged_groups") shouldBe 1
    }

    // Reconcile after a stopped pull -------------------------------------------------------------

    /** [delegate], reading [failing] failing, so a pull stops with the files before it applied. */
    private class FailingRead(private val delegate: InMemorySyncBackend) : SyncBackend by delegate {
        var failing: String? = null

        override suspend fun <T> read(path: String, block: suspend (InputStream) -> T): T {
            if (path == failing) throw IOException("Connection reset reading $path")
            return delegate.read(path, block)
        }
    }

    @Test
    fun `a pull stopped after a segment that touched a group still raises that group's copies`() {
        val a = device()
        val failingRemote = FailingRead(backend)
        val b = device(remote = failingRemote)
        a.addFavourite(1)
        a.addFavourite(2)
        a.addChapter(10, 1)
        a.addChapter(20, 2)
        a.exec("UPDATE chapters SET chapter_number = 1")
        a.mergeHere(1, 1, 2)
        a.sync()
        b.libraryPreferences.mergedEntries.set(true)
        b.sync().shouldNotBeNull()
        a.exec("UPDATE chapters SET read = 1 WHERE _id = 10")
        a.sync().shouldNotBeNull().segmentsPushed shouldBe 1
        a.addFavourite(3)
        a.sync().shouldNotBeNull().segmentsPushed shouldBe 1
        val last = backend.paths().mapNotNull { SyncLayout.parse(it) as? RemoteName.Segment }
            .filter { it.deviceId == a.deviceId }.maxBy { it.lastSeq }
        failingRemote.failing = last.path

        shouldThrow<IOException> { b.sync() }

        // The first segment was applied and committed; the second never arrived.
        b.long("SELECT read FROM chapters WHERE url = '/series/1/chapter/10'") shouldBe 1
        b.mangaId(SOURCE, "/series/3") shouldBe null
        b.long("SELECT read FROM chapters WHERE url = '/series/2/chapter/20'") shouldBe 1
    }

    private companion object {
        const val ENTRIES = 12
        const val CHAPTERS = 999

        /** The change log rows of `mergedGroups.merge(1, 1, 2)` on two entries: the group, then each member. */
        const val MERGE_LOG_ROWS = 3
    }
}

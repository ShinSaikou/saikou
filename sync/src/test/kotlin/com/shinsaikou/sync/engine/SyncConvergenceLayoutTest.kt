package com.shinsaikou.sync.engine

import com.shinsaikou.sync.InMemorySyncBackend
import com.shinsaikou.sync.SyncContent
import com.shinsaikou.sync.format.NewerFormatException
import com.shinsaikou.sync.format.RemoteName
import com.shinsaikou.sync.format.SyncLayout
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * The convergence suite (phase 3 unit E2), part two: the cases that turn on the remote layout and
 * on a device's own history. Three devices applying the same files in different orders; a restore
 * after sync; a device that clears its data and joins again under a new id; a listing that hides a
 * segment; two devices compacting at once and then deleting; and a segment from a newer format.
 */
class SyncConvergenceLayoutTest {

    @TempDir
    lateinit var dir: File

    private val backend = InMemorySyncBackend()
    private val devices = mutableListOf<SyncTestDevice>()

    private fun device(): SyncTestDevice = SyncTestDevice(backend).also(devices::add)

    @AfterEach
    fun close() {
        devices.forEach { it.close() }
    }

    /** Runs [block] with the listing showing only what [visible] accepts, as a lagging listing would. */
    private fun <T> listingOnly(visible: (String) -> Boolean, block: () -> T): T {
        val hidden = backend.paths().filterNot(visible)
        hidden.forEach(backend::hide)
        try {
            return block()
        } finally {
            hidden.forEach(backend::unhide)
        }
    }

    private fun SyncTestDevice.filesOnly(): (String) -> Boolean = { it.startsWith(SyncLayout.devicePrefix(deviceId)) }

    private fun SyncTestDevice.segments(): List<RemoteName.Segment> =
        backend.paths().mapNotNull { SyncLayout.parse(it) as? RemoteName.Segment }.filter { it.deviceId == deviceId }

    private fun assertAllEqual(vararg all: SyncTestDevice) {
        all.drop(1).forEach { it.library() shouldBe all.first().library() }
        all.forEach { it.latchesRaised() shouldBe 0 }
    }

    @Test
    fun `three devices apply the same files in different orders and reach the same rows`() {
        val (a, b, c) = List(3) { device() }
        a.buildLibrary()
        listOf(a, b, c, a, b).forEach { it.sync() }
        assertAllEqual(a, b, c)

        // Each writer edits the same rows, and pushes seeing only its own files.
        a.exec("UPDATE chapters SET read = 1 WHERE url = '/series/1/chapter/11'")
        a.exec("UPDATE mangas SET notes = 'from A' WHERE url = '/series/1'")
        a.exec("DELETE FROM mangas_categories")
        a.exec("INSERT INTO bookmark_notes(chapter_id, page_index, note, date_added) VALUES (10, 1, 'A', 1)")
        a.exec("UPDATE manga_sync SET last_chapter_read = 3")
        b.exec("UPDATE chapters SET bookmark = 1, last_page_read = 9 WHERE url = '/series/1/chapter/11'")
        b.exec("UPDATE mangas SET notes = 'from B' WHERE url = '/series/1'")
        b.exec("UPDATE categories SET name = 'Top' WHERE name = 'Parent'")
        b.exec("INSERT INTO excluded_scanlators(manga_id, scanlator) VALUES (1, 'Group X')")
        b.exec("INSERT INTO bookmark_notes(chapter_id, page_index, note, date_added) SELECT _id, 1, 'Bee', 2 FROM chapters WHERE url = '/series/1/chapter/10'")
        b.exec("UPDATE manga_sync SET last_chapter_read = 5")
        c.exec("UPDATE chapters SET last_page_read = 4 WHERE url = '/series/1/chapter/11'")
        c.exec("UPDATE mangas SET notes = 'from C', manual_position = 7 WHERE url = '/series/1'")
        c.exec("INSERT INTO mangas_categories(manga_id, category_id) SELECT 1, _id FROM categories WHERE name = 'Parent'")
        c.exec("UPDATE history SET last_read = 99000, time_read = 500")
        c.exec("UPDATE chapters SET deleted_at = max(strftime('%s', 'now') * 1000, coalesce(restored_at, 0) + 1) WHERE url = '/series/1/chapter/10'")
        listOf(a, b, c).forEach { writer -> listingOnly(writer.filesOnly()) { writer.sync().shouldNotBeNull().segmentsPushed shouldBe 1 } }

        // Three readers join and read the writers one at a time, each in its own order.
        val orders = listOf(listOf(a, b, c), listOf(c, b, a), listOf(b, c, a))
        val readers = orders.map { order ->
            device().also { reader -> order.forEach { writer -> listingOnly(writer.filesOnly()) { reader.sync() } } }
        }
        assertAllEqual(*readers.toTypedArray())

        // The writers, syncing in the open, reach the same rows.
        repeat(2) { listOf(a, b, c).forEach { it.sync() } }
        assertAllEqual(readers.first(), a, b, c)
        a.rows("SELECT read, bookmark, last_page_read FROM chapters WHERE url = '/series/1/chapter/11'", 3) shouldBe
            listOf(listOf("1", "1", "9"))
        a.long("SELECT max(last_chapter_read) FROM manga_sync") shouldBe 5
    }

    @Test
    fun `a restore after sync, with an undelete and a re-created category, ends the same on both devices`() {
        val (a, b) = List(2) { device() }
        val bytes = syncBackup(entries = 12, chapters = 4).bytes()
        a.restore(bytes, File(dir, "a1").apply { mkdirs() })
        b.restore(bytes, File(dir, "b1").apply { mkdirs() })
        listOf(a, b, a).forEach { it.sync() }
        assertAllEqual(a, b)

        // A removes an entry and deletes a category; B applies both.
        a.exec("UPDATE mangas SET deleted_at = max(strftime('%s', 'now') * 1000, coalesce(restored_at, 0) + 1), favorite = 0 WHERE url = '/series/3'")
        val completed = a.long("SELECT _id FROM categories WHERE name = 'Completed'")!!
        a.exec("UPDATE categories SET deleted_at = max(strftime('%s', 'now') * 1000, coalesce(restored_at, 0) + 1) WHERE _id = $completed")
        a.exec("DELETE FROM mangas_categories WHERE category_id = $completed")
        a.exec("UPDATE chapters SET read = 0 WHERE url = '/series/5/chapter/1'")
        listOf(a, b).forEach { it.sync() }
        b.long("SELECT deleted_at IS NOT NULL FROM mangas WHERE url = '/series/3'") shouldBe 1

        // B restores the same backup again, which undeletes the entry and re-creates the category.
        b.restore(bytes, File(dir, "b2").apply { mkdirs() })
        b.long("SELECT deleted_at IS NULL FROM mangas WHERE url = '/series/3'") shouldBe 1
        b.long("SELECT deleted_at IS NULL FROM categories WHERE name = 'Completed'") shouldBe 1
        b.restorePreferences.restoredSinceSync.get() shouldBe true

        val afterRestore = b.sync().shouldNotBeNull()
        afterRestore.exported shouldBe true
        a.sync()
        b.sync()

        assertAllEqual(a, b)
        b.restorePreferences.restoredSinceSync.get() shouldBe false
        // A restore is the owner's latest word (R87): its undelete stamps a restore clock past A's
        // tombstone, so the entry is live and a favourite on both. The re-created category is a
        // real re-creation with a new restore clock, so it is live on both, and so are the backup's
        // memberships to it (entries 1 and 7), whose category clocks the restore moved past A's drop.
        for (device in listOf(a, b)) {
            device.long("SELECT deleted_at IS NULL AND favorite = 1 FROM mangas WHERE url = '/series/3'") shouldBe 1
            device.long("SELECT deleted_at IS NULL FROM categories WHERE name = 'Completed'") shouldBe 1
            device.long(
                "SELECT count(*) FROM mangas_categories MC JOIN categories C ON C._id = MC.category_id WHERE C.name = 'Completed'",
            ) shouldBe 2
            device.long("SELECT read FROM chapters WHERE url = '/series/5/chapter/1'") shouldBe 1
        }
        listOf(a, b).forEach { it.sync().shouldNotBeNull().rowsWritten shouldBe 0 }
    }

    /**
     * Phase 5 unit SC2: a restore inserts its chapters under the latch, so the change log holds no
     * row for any of them. Both devices have pushed before the restore, so the restoring device's
     * next push would be a segment of its log; the restore flag makes it a full export instead,
     * which reads the library rather than the log (R86), and the other device receives every chapter.
     */
    @Test
    fun `a restore's chapters, logged nowhere, reach a device that did not restore`() {
        val (a, b) = List(2) { device() }
        listOf(a, b, a).forEach { it.sync() }

        a.restore(syncBackup(entries = 12, chapters = 4).bytes(), File(dir, "a1").apply { mkdirs() })
        a.long("SELECT count(*) FROM chapters") shouldBe 48
        a.long("SELECT count(*) FROM sync_change_log WHERE entity_type = 'chapter'") shouldBe 0
        a.restorePreferences.restoredSinceSync.get() shouldBe true

        a.sync().shouldNotBeNull().exported shouldBe true
        b.sync()

        b.long("SELECT count(*) FROM chapters") shouldBe 48
        assertAllEqual(a, b)
        listOf(a, b).forEach { it.sync().shouldNotBeNull().rowsWritten shouldBe 0 }
    }

    /**
     * Phase 5 unit SC2: B already holds the entries and has read further than the backup it then
     * restores. The restore only moves read state, bookmarks and the page forward, under the latch,
     * so B keeps its progress; when B joins A, which read further on another chapter, both end with
     * both devices' progress.
     */
    @Test
    fun `a restore onto a device that already reads further keeps its progress, and the join merges both`() {
        val (a, b) = List(2) { device() }
        val bytes = syncBackup(entries = 12, chapters = 4).bytes()
        a.restore(bytes, File(dir, "a1").apply { mkdirs() })
        a.sync()
        a.exec("UPDATE chapters SET last_page_read = 30, bookmark = 1 WHERE url = '/series/5/chapter/4'")
        a.sync()

        b.restore(bytes, File(dir, "b1").apply { mkdirs() })
        // The file has chapter 3 of entry 2 unread, on page 2, with no bookmark.
        b.exec("UPDATE chapters SET read = 1, last_page_read = 40, bookmark = 1 WHERE url = '/series/2/chapter/3'")
        val progress = "SELECT read, last_page_read, bookmark FROM chapters WHERE url = '/series/2/chapter/3'"
        val ahead = b.rows(progress, 3)
        ahead shouldBe listOf(listOf("1", "40", "1"))

        b.restore(bytes, File(dir, "b2").apply { mkdirs() })
        b.rows(progress, 3) shouldBe ahead

        listOf(b, a, b).forEach { it.sync() }

        assertAllEqual(a, b)
        for (device in listOf(a, b)) {
            device.rows(progress, 3) shouldBe listOf(listOf("1", "40", "1"))
            device.rows(
                "SELECT read, last_page_read, bookmark FROM chapters WHERE url = '/series/5/chapter/4'",
                3,
            ) shouldBe listOf(listOf("0", "30", "1"))
        }
        listOf(a, b).forEach { it.sync().shouldNotBeNull().rowsWritten shouldBe 0 }
    }

    @Test
    fun `a device that clears its data joins again under a new id and changes nothing on the other`() {
        val (a, b) = List(2) { device() }
        a.buildLibrary()
        listOf(a, b, a).forEach { it.sync() }
        b.exec("UPDATE chapters SET bookmark = 1 WHERE url = '/series/1/chapter/11'")
        listOf(b, a).forEach { it.sync() }
        val oldId = b.deviceId
        val before = a.library()

        val cleared = device()
        val first = cleared.sync().shouldNotBeNull()

        cleared.deviceId shouldNotBe oldId
        // The old id's export and segment read as another device's.
        first.exportsApplied shouldBe 2
        cleared.library() shouldBe before
        val back = a.sync().shouldNotBeNull()
        back.exportsApplied shouldBe 1
        back.rowsWritten shouldBe 0
        a.library() shouldBe before
        // Nothing deletes the old id's files: a device deletes only its own.
        backend.paths().any { it.startsWith(SyncLayout.devicePrefix(oldId)) } shouldBe true
        assertAllEqual(a, cleared)
    }

    @Test
    fun `a reader whose listing hides a segment stops at the gap and catches up on its next run`() {
        val (a, b) = List(2) { device() }
        a.buildLibrary()
        listOf(a, b).forEach { it.sync() }
        a.exec("UPDATE chapters SET last_page_read = 1 WHERE _id = 11")
        a.sync()
        a.exec("UPDATE mangas SET notes = 'second' WHERE _id = 1")
        a.sync()
        a.exec("UPDATE chapters SET read = 1 WHERE _id = 11")
        a.sync()
        val (first, second, third) = a.segments().sortedBy { it.lastSeq }
        backend.hide(second.path)

        val stopped = b.sync().shouldNotBeNull()

        stopped.segmentsApplied shouldBe 1
        stopped.stoppedAtGap shouldBe true
        b.preferences.pullCursors.get()[a.deviceId] shouldBe first.lastSeq
        b.rows("SELECT read, last_page_read FROM chapters WHERE url = '/series/1/chapter/11'", 2) shouldBe listOf(listOf("0", "1"))
        b.string("SELECT notes FROM mangas WHERE url = '/series/1'") shouldBe "owner notes"

        backend.unhide(second.path)
        val caughtUp = b.sync().shouldNotBeNull()

        caughtUp.segmentsApplied shouldBe 2
        caughtUp.stoppedAtGap shouldBe false
        b.preferences.pullCursors.get()[a.deviceId] shouldBe third.lastSeq
        assertAllEqual(a, b)
    }

    @Test
    fun `two devices compacting at once each write a snapshot, delete only their own files, and a new device joins from them`() {
        val (a, b) = List(2) { device() }
        a.buildLibrary()
        listOf(a, b, a).forEach { it.sync() }
        repeat(3) { round ->
            a.exec("UPDATE chapters SET last_page_read = ${round + 1} WHERE url = '/series/1/chapter/11'")
            b.exec("UPDATE mangas SET manual_position = ${round + 10} WHERE url = '/series/1'")
            listOf(a, b).forEach { it.sync() }
        }
        a.sync()

        // Two weeks on, a new file moves the server's time (compaction reads it from the listing),
        // and both compact in the same window: B's listing does not show A's snapshot yet.
        backend.clock = { 15 * DAY }
        a.exec("UPDATE chapters SET read = 1 WHERE url = '/series/1/chapter/11'")
        a.sync().shouldNotBeNull().compacted shouldBe true
        val aSnapshot = SyncLayout.snapshotPath(1, a.deviceId)
        val bRun = listingOnly({ it != aSnapshot }) { b.sync().shouldNotBeNull() }
        bRun.compacted shouldBe true
        backend.paths().mapNotNull { SyncLayout.parse(it) as? RemoteName.Snapshot }.map { it.generation to it.deviceId }
            .toSet() shouldBe setOf(1L to a.deviceId, 1L to b.deviceId)

        // A week after that, a new file moves the server's time on, and each deletes what both cover.
        backend.clock = { 23 * DAY }
        a.exec("UPDATE chapters SET bookmark = 1 WHERE url = '/series/1/chapter/11'")
        val beforeA = backend.paths()
        a.sync().shouldNotBeNull().filesDeleted shouldNotBe 0
        (beforeA - backend.paths().toSet()).forEach { it.startsWith(SyncLayout.devicePrefix(a.deviceId)) shouldBe true }
        val beforeB = backend.paths()
        b.sync().shouldNotBeNull().filesDeleted shouldNotBe 0
        (beforeB - backend.paths().toSet()).forEach { it.startsWith(SyncLayout.devicePrefix(b.deviceId)) shouldBe true }
        // Only snapshots and A's newest segment are left of the two devices' history.
        val left = backend.paths().mapNotNull { SyncLayout.parse(it) }
        left.filterIsInstance<RemoteName.Snapshot>() shouldHaveSize 2
        left.filterIsInstance<RemoteName.Done>() shouldHaveSize 0
        left.filterIsInstance<RemoteName.Segment>() shouldHaveSize 1

        // A device joining now can only start from a snapshot, then A's newest segment.
        val joined = device()
        val join = joined.sync().shouldNotBeNull()
        join.snapshotApplied shouldBe true
        join.segmentsApplied shouldBe 1
        b.sync()
        assertAllEqual(a, b, joined)
    }

    @Test
    fun `a segment from a newer format stops every run at it, and nothing after it is skipped or pushed`() {
        val (a, b) = List(2) { device() }
        a.buildLibrary()
        listOf(a, b, a).forEach { it.sync() }
        a.exec("UPDATE chapters SET read = 1 WHERE _id = 11")
        a.sync()
        // A later build of A writes the next range in format 3, then an ordinary segment after it.
        val after = a.preferences.lastPushedSeq.get()
        runBlocking { backend.create(SyncLayout.segmentPath(a.deviceId, after, after + 1), SyncContent(newerFormat(a.deviceId, after))) }
        b.exec("UPDATE mangas SET notes = 'waiting on B' WHERE url = '/series/1'")
        val bLog = b.logCount()
        val bPushed = b.preferences.lastPushedSeq.get()

        repeat(2) {
            shouldThrow<NewerFormatException> { b.sync() }.found shouldBe 5
            // The readable segment before it applied; the cursor stops before the newer one.
            b.preferences.pullCursors.get()[a.deviceId] shouldBe after
            b.long("SELECT read FROM chapters WHERE url = '/series/1/chapter/11'") shouldBe 1
            b.preferences.lastPushedSeq.get() shouldBe bPushed
            b.logCount() shouldBe bLog
        }
        b.latchesRaised() shouldBe 0
    }

    private fun newerFormat(deviceId: String, afterSeq: Long): ByteArray {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).bufferedWriter().use { writer ->
            writer.write(
                """{"format":5,"deviceId":"$deviceId","scope":{"kind":"segment","afterSeq":$afterSeq,""" +
                    """"lastSeq":${afterSeq + 1},"rawRows":1},"counts":{"manga_v2":1}}""",
            )
            writer.write("\n")
            writer.write("""{"entity":"manga_v2","key":{"source":1,"url":"/series/9"}}""")
            writer.write("\n")
        }
        return bytes.toByteArray()
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}

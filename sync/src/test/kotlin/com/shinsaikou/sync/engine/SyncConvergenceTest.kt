package com.shinsaikou.sync.engine

import com.shinsaikou.domain.category.CategorySyncKey
import com.shinsaikou.sync.InMemorySyncBackend
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * The convergence suite (phase 3 unit E2), part one: PLAN's four cases (race, rename, delete,
 * fresh-device categories) and the plan's single-row cases, each over two or three real databases
 * on E1's in-memory backend. Every device starts empty and makes its full export on its first run,
 * as a device does; the edits are plain SQL, so every trigger fires as it does for the app.
 *
 * Every case ends with the devices equal, by [library], and with no latch left raised.
 */
class SyncConvergenceTest {

    private val backend = InMemorySyncBackend()
    private val a = SyncTestDevice(backend)
    private val b = SyncTestDevice(backend)

    @AfterEach
    fun close() {
        a.close()
        b.close()
    }

    /** A builds [buildLibrary] and both devices hold it: A exports, B applies it and exports. */
    private fun bothHoldTheLibrary() {
        a.buildLibrary()
        a.sync()
        b.sync()
        a.sync()
        b.library() shouldBe a.library()
    }

    /** Each device syncs in turn until both have seen each other's latest files. */
    private fun converge() {
        a.sync()
        b.sync()
        a.sync()
    }

    private fun assertConverged() {
        a.library() shouldBe b.library()
        a.latchesRaised() shouldBe 0
        b.latchesRaised() shouldBe 0
    }

    private fun SyncTestDevice.value(sql: String): Long? = long(sql)

    private companion object {
        const val NOW = "strftime('%s', 'now') * 1000"
        const val CH11 = "/series/1/chapter/11"
    }

    @Test
    fun `race, a read on one device and an offline bookmark on the other both survive`() {
        bothHoldTheLibrary()
        // Chapter 11: A reads it to the end; B, offline, bookmarks it and leaves a note on page 2.
        a.exec("UPDATE chapters SET read = 1, last_page_read = 20 WHERE url = '$CH11'")
        b.exec("UPDATE chapters SET bookmark = 1, last_page_read = 6 WHERE url = '$CH11'")
        b.exec("INSERT INTO bookmark_notes(chapter_id, page_index, note, date_added) VALUES ((SELECT _id FROM chapters WHERE url = '$CH11'), 2, 'offline', 9000)")

        converge()

        assertConverged()
        for (device in listOf(a, b)) {
            device.rows("SELECT read, bookmark, last_page_read FROM chapters WHERE url = '$CH11'", 3) shouldBe
                listOf(listOf("1", "1", "20"))
            device.value("SELECT count(*) FROM bookmark_notes WHERE chapter_id = (SELECT _id FROM chapters WHERE url = '$CH11') AND note = 'offline'") shouldBe 1
        }
    }

    @Test
    fun `rename, a chapter renamed by its source and a renamed category never duplicate`() {
        bothHoldTheLibrary()
        // A's library update renames chapter 11 (the version moves); the owner renames a category on B.
        a.exec("UPDATE chapters SET name = 'Chapter 11: The title' WHERE url = '$CH11'")
        b.exec("UPDATE categories SET name = 'Now reading' WHERE name = 'Reading'")

        converge()

        assertConverged()
        for (device in listOf(a, b)) {
            device.value("SELECT count(*) FROM chapters WHERE url = '/series/1/chapter/11'") shouldBe 1
            device.value("SELECT count(*) FROM chapters") shouldBe 2
            device.string("SELECT name FROM chapters WHERE url = '/series/1/chapter/11'") shouldBe "Chapter 11: The title"
            device.rows("SELECT name, sync_key FROM categories WHERE _id > 0 ORDER BY name", 2) shouldBe listOf(
                listOf("Now reading", CategorySyncKey.of("Reading")),
                listOf("Parent", CategorySyncKey.of("Parent")),
            )
            // The membership follows the key through the rename.
            device.value(
                "SELECT count(*) FROM mangas_categories MC JOIN categories C ON C._id = MC.category_id WHERE C.name = 'Now reading'",
            ) shouldBe 1
        }
    }

    @Test
    fun `delete, a chapter one device dropped stays deleted though the other read it meanwhile`() {
        bothHoldTheLibrary()
        // B's library update drops chapter 11; A, not yet updated, reads it. A syncs first, so B's
        // pull meets A's read of a chapter B no longer holds.
        b.exec("DELETE FROM chapters WHERE url = '/series/1/chapter/11'")
        a.exec("UPDATE chapters SET read = 1 WHERE url = '$CH11'")
        a.sync()
        b.sync()
        a.sync()

        assertConverged()
        for (device in listOf(a, b)) {
            device.rows("SELECT read, deleted_at IS NOT NULL FROM chapters WHERE url = '/series/1/chapter/11'", 2) shouldBe
                listOf(listOf("1", "1"))
        }
        // A third run on each changes nothing.
        listOf(a, b).forEach { it.sync().shouldNotBeNull().rowsWritten shouldBe 0 }
    }

    @Test
    fun `delete, a manga removed from the library on one device stays removed though the other read it`() {
        bothHoldTheLibrary()
        a.exec("UPDATE mangas SET deleted_at = max(strftime('%s', 'now') * 1000, coalesce(restored_at, 0) + 1), favorite = 0 WHERE url = '/series/1'")
        b.exec("UPDATE chapters SET read = 1 WHERE url = '$CH11'")

        converge()

        assertConverged()
        for (device in listOf(a, b)) {
            device.rows("SELECT favorite, deleted_at IS NOT NULL FROM mangas WHERE url = '/series/1'", 2) shouldBe listOf(listOf("0", "1"))
            device.value("SELECT read FROM chapters WHERE url = '$CH11'") shouldBe 1
        }
    }

    @Test
    fun `delete, a chapter dropped before a device's first export comes back until its next drop, which travels`() {
        // Two devices that hold one library and have never synced, as two restored from one backup.
        a.buildLibrary()
        b.buildLibrary()
        // B's library update drops chapter 11, and B syncs first: its full export, which prunes the
        // log row naming the drop, goes out before A has exported anything B could meet it in.
        b.exec("DELETE FROM chapters WHERE url = '$CH11'")
        b.sync().shouldNotBeNull().exported shouldBe true
        a.sync()
        b.sync()

        // The documented limit: the chapter is back on B, live, as on A.
        assertConverged()
        b.value("SELECT deleted_at IS NULL FROM chapters WHERE url = '/series/1/chapter/11'") shouldBe 1

        // B's next library update drops it again; now the drop is in a segment and travels.
        b.exec("DELETE FROM chapters WHERE url = '/series/1/chapter/11'")
        b.sync().shouldNotBeNull().segmentsPushed shouldBe 1
        a.sync()
        a.value("SELECT deleted_at IS NOT NULL FROM chapters WHERE url = '/series/1/chapter/11'") shouldBe 1
        b.value("SELECT count(*) FROM chapters WHERE url = '/series/1/chapter/11'") shouldBe 0
    }

    @Test
    fun `fresh-device categories, a device joining gets them renamed and deleted, one system category, and changes nothing`() {
        a.buildLibrary()
        a.addCategory(3, "Old name")
        a.addCategory(4, "Gone")
        a.exec("INSERT INTO mangas_categories(manga_id, category_id) VALUES (1, 4)")
        a.sync()
        a.exec("UPDATE categories SET name = 'New name' WHERE _id = 3")
        // DeleteCategory: the tombstone, then the memberships.
        a.exec("UPDATE categories SET deleted_at = max(strftime('%s', 'now') * 1000, coalesce(restored_at, 0) + 1) WHERE _id = 4")
        a.exec("DELETE FROM mangas_categories WHERE category_id = 4")
        a.sync()
        val before = a.library()

        SyncTestDevice(backend).use { fresh ->
            val first = fresh.sync().shouldNotBeNull()

            first.exported shouldBe true
            fresh.library() shouldBe before
            fresh.rows("SELECT _id, name, sync_key FROM categories WHERE sync_key IS NULL", 3) shouldBe
                listOf(listOf("0", "", null))
            fresh.value("SELECT count(*) FROM categories WHERE name = 'Old name'") shouldBe 0
            fresh.value("SELECT deleted_at IS NOT NULL FROM categories WHERE name = 'Gone'") shouldBe 1
            fresh.value("SELECT count(*) FROM mangas_categories MC JOIN categories C ON C._id = MC.category_id WHERE C.name = 'Gone'") shouldBe 0
            fresh.latchesRaised() shouldBe 0

            // The fresh device's first push changed nothing on A.
            val back = a.sync().shouldNotBeNull()
            back.exportsApplied shouldBe 1
            back.rowsWritten shouldBe 0
            a.library() shouldBe before
        }
    }

    @Test
    fun `the system category never travels, keeps each device's own settings, and is never duplicated`() {
        bothHoldTheLibrary()
        // Each device sorts its uncategorised shelf its own way; manga 2 sits in no category.
        a.exec("UPDATE categories SET flags = 64, sort = -1 WHERE _id = 0")
        b.exec("UPDATE categories SET flags = 8 WHERE _id = 0")
        a.addFavourite(2)

        converge()

        assertConverged()
        a.rows("SELECT _id, name, flags FROM categories WHERE sync_key IS NULL", 3) shouldBe listOf(listOf("0", "", "64"))
        b.rows("SELECT _id, name, flags FROM categories WHERE sync_key IS NULL", 3) shouldBe listOf(listOf("0", "", "8"))
        for (device in listOf(a, b)) {
            device.value("SELECT count(*) FROM categories WHERE name = ''") shouldBe 1
            device.value(
                "SELECT count(*) FROM mangas_categories MC JOIN mangas M ON M._id = MC.manga_id WHERE M.url = '/series/2'",
            ) shouldBe 0
        }
    }

    @Test
    fun `a pull followed by a push pushes no row back, and a third run on either side writes nothing`() {
        bothHoldTheLibrary()
        a.exec("UPDATE chapters SET read = 1 WHERE url = '$CH11'")
        a.exec("UPDATE mangas SET notes = 'changed', manual_position = 9 WHERE url = '/series/1'")
        a.exec("INSERT INTO excluded_scanlators(manga_id, scanlator) VALUES (1, 'Group Z')")
        a.sync()
        val pushedBefore = b.preferences.lastPushedSeq.get()

        val pull = b.sync().shouldNotBeNull()

        pull.rowsWritten shouldBe 3
        pull.segmentsPushed shouldBe 0
        b.logCount() shouldBe 0
        b.preferences.lastPushedSeq.get() shouldBe pushedBefore
        assertConverged()
        for (device in listOf(a, b)) {
            val again = device.sync().shouldNotBeNull()
            again.rowsWritten shouldBe 0
            again.segmentsPushed shouldBe 0
        }
    }

    @Test
    fun `the favourite clock stored after a sync is the merged one on both devices`() {
        bothHoldTheLibrary()
        // A removes manga 1 from the library and B adds manga 2, each stamping its own favourite clock.
        a.exec("UPDATE mangas SET favorite = 0 WHERE url = '/series/1'")
        b.exec(
            """
            INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer, chapter_flags,
                cover_last_modified, date_added, memo)
            VALUES (2, ${SyncTestDevice.SOURCE}, '/series/2', 'Title 2', 0, 0, 0, 0, 0, 0, 0, CAST('{}' AS BLOB))
            """,
        )
        b.exec("UPDATE mangas SET favorite = 1 WHERE _id = 2")
        // Clocks well apart from the apply's own time, which the unguarded favourite trigger would stamp.
        a.exec("UPDATE mangas SET favorite_modified_at = 5000 WHERE url = '/series/1'")
        b.exec("UPDATE mangas SET favorite_modified_at = 7000 WHERE _id = 2")

        converge()

        assertConverged()
        for (device in listOf(a, b)) {
            device.rows("SELECT url, favorite, favorite_modified_at FROM mangas ORDER BY url", 3) shouldBe listOf(
                listOf("/series/1", "0", "5000"),
                listOf("/series/2", "1", "7000"),
            )
        }
    }

    @Test
    fun `a manga read from Browse and then added to the library arrives with its progress and history`() {
        bothHoldTheLibrary()
        // A browses manga 2 (not in the library, so nothing logs), reads two chapters, then adds it.
        a.exec(
            """
            INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer, chapter_flags,
                cover_last_modified, date_added, memo)
            VALUES (2, ${SyncTestDevice.SOURCE}, '/series/2', 'Browsed', 0, 0, 0, 0, 0, 0, 0, CAST('{}' AS BLOB))
            """,
        )
        (20L..22L).forEach { a.addChapter(it, mangaId = 2) }
        a.exec("UPDATE chapters SET read = 1 WHERE _id IN (20, 21)")
        a.exec("UPDATE chapters SET last_page_read = 4 WHERE _id = 22")
        a.exec("INSERT INTO history(chapter_id, last_read, time_read) VALUES (21, 50000, 600)")
        a.sync()
        b.sync()
        // History logs whatever the library says, so the chapter read with a history row travels at
        // once, with its manga as a browsed one; the rest of the progress waits for the add.
        b.rows("SELECT M.favorite, C.url, C.read FROM chapters C JOIN mangas M ON M._id = C.manga_id WHERE M.url = '/series/2'", 3) shouldBe
            listOf(listOf("0", "/series/2/chapter/21", "1"))
        a.exec("UPDATE mangas SET favorite = 1 WHERE _id = 2")

        converge()

        assertConverged()
        val manga2 = b.mangaId(SyncTestDevice.SOURCE, "/series/2").shouldNotBeNull()
        b.rows("SELECT url, read, last_page_read FROM chapters WHERE manga_id = $manga2 ORDER BY url", 3) shouldBe listOf(
            listOf("/series/2/chapter/20", "1", "0"),
            listOf("/series/2/chapter/21", "1", "0"),
            listOf("/series/2/chapter/22", "0", "4"),
        )
        b.rows("SELECT C.url, H.last_read, H.time_read FROM history H JOIN chapters C ON C._id = H.chapter_id WHERE C.manga_id = $manga2", 3) shouldBe
            listOf(listOf("/series/2/chapter/21", "50000", "600"))
    }

    /**
     * Moves the clock a local edit just stamped by [ms], under the row's latch so nothing logs or
     * stamps again: what a device whose clock runs [ms] ahead of real time would have stamped.
     */
    private fun SyncTestDevice.skew(column: String, ms: Long) {
        exec("UPDATE mangas SET is_syncing = 1 WHERE url = '/series/1'")
        exec("UPDATE mangas SET $column = $column + $ms WHERE url = '/series/1'")
        exec("UPDATE mangas SET is_syncing = 0 WHERE url = '/series/1'")
    }

    /**
     * Two devices whose clocks differ by a minute. The documented outcome: of two edits to one value
     * made on the two devices within the skew of each other, before either syncs, the device whose
     * clock runs ahead wins, whichever edit came first in real time; outside the skew the later edit
     * wins; and an edit made after a sync always wins, whatever the skew, because every clock is
     * max(now, previous + 1) and the previous is the other device's merged clock.
     */
    @Test
    fun `two devices whose clocks differ by a minute, with the documented outcome`() {
        bothHoldTheLibrary()
        // A's clock runs a minute ahead. A edits the notes, then B edits them 30 real seconds later.
        a.exec("UPDATE mangas SET notes = 'from A' WHERE url = '/series/1'")
        a.skew("notes_modified_at", 60_000)
        b.exec("UPDATE mangas SET notes = 'from B, 30 s later' WHERE url = '/series/1'")
        b.skew("notes_modified_at", 30_000)
        converge()
        assertConverged()
        a.string("SELECT notes FROM mangas WHERE url = '/series/1'") shouldBe "from A"

        // 90 real seconds after A's edit, past the skew, B's edit wins.
        a.exec("UPDATE mangas SET manual_position = 1 WHERE url = '/series/1'")
        a.skew("position_modified_at", 60_000)
        b.exec("UPDATE mangas SET manual_position = 2 WHERE url = '/series/1'")
        b.skew("position_modified_at", 90_000)
        converge()
        assertConverged()
        a.value("SELECT manual_position FROM mangas WHERE url = '/series/1'") shouldBe 2

        // After the sync B holds A's clock, a minute ahead of B's own; B's next edit still wins.
        val merged = b.value("SELECT notes_modified_at FROM mangas WHERE url = '/series/1'")!!
        b.exec("UPDATE mangas SET notes = 'from B, after the sync' WHERE url = '/series/1'")
        b.value("SELECT notes_modified_at FROM mangas WHERE url = '/series/1'") shouldBe merged + 1
        converge()
        assertConverged()
        a.string("SELECT notes FROM mangas WHERE url = '/series/1'") shouldBe "from B, after the sync"

        // A removal and a re-add within the skew: A removes manga 1 from the library by its fast
        // clock; B, 30 real seconds later, removes it and adds it back, which stamps its restore
        // clock. A's removal is the newer event by the clocks, so the manga ends removed on both.
        a.exec("UPDATE mangas SET deleted_at = max($NOW + 60000, coalesce(restored_at, 0) + 1), favorite = 0 WHERE url = '/series/1'")
        a.skew("favorite_modified_at", 60_000)
        b.exec("UPDATE mangas SET favorite = 0 WHERE url = '/series/1'")
        b.exec("UPDATE mangas SET favorite = 1 WHERE url = '/series/1'")
        b.skew("favorite_modified_at", 30_000)
        b.skew("restored_at", 30_000)
        converge()
        assertConverged()
        a.rows("SELECT favorite, deleted_at IS NOT NULL FROM mangas WHERE url = '/series/1'", 2) shouldBe listOf(listOf("0", "1"))
    }

    @Test
    fun `a pull that finds nothing new writes nothing and reports no conflict`() {
        bothHoldTheLibrary()
        val result = b.sync().shouldNotBeNull()
        result.rowsWritten shouldBe 0
        result.conflicts.shouldBeEmpty()
    }
}

package com.shinsaikou.sync.engine

import com.shinsaikou.domain.category.CategorySyncKey
import com.shinsaikou.sync.InMemorySyncBackend
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.random.Random

/**
 * The plan's `two_full_libraries_meet` (phase 3 unit E2's suite): the owner's case. Two devices are
 * restored from one generated 3,000-entry backup through the real importer, each makes a few
 * hundred scattered edits of its own, and then they sync for the first time, both ways. Every OR
 * and max field is checked against both devices' values before the sync, every removal is checked
 * by key, and a third sync changes nothing.
 *
 * B's hard-deleted chapters travel because A exports first: B's pull meets them in A's export while
 * its log still names them, and stores them as tombstones, which B's own export then carries. The
 * other order is `SyncConvergenceTest`'s case of a hard delete that a first export prunes.
 */
class SyncTwoLibrariesTest {

    @TempDir
    lateinit var dir: File

    private val backend = InMemorySyncBackend()
    private val a = SyncTestDevice(backend)
    private val b = SyncTestDevice(backend)

    @AfterEach
    fun close() {
        a.close()
        b.close()
    }

    @Test
    fun `two full libraries meet, and every OR and max field and every removal holds, and a third sync changes nothing`() {
        val bytes = syncBackup(ENTRIES, CHAPTERS).bytes()
        a.restore(bytes, File(dir, "a").apply { mkdirs() }).mangaInserted shouldBe ENTRIES
        b.restore(bytes, File(dir, "b").apply { mkdirs() }).mangaInserted shouldBe ENTRIES
        a.library() shouldBe b.library().withCategoryClocksOf(a)

        val editsA = a.editA(Random(11))
        val editsB = b.editB(Random(22), deletedOnA = editsA.deletedManga)
        val beforeA = a.progress()
        val beforeB = b.progress()
        val favouritesA = a.favourites()
        val favouritesB = b.favourites()

        // The first sync, both ways: A exports, B applies it and exports, A applies B's.
        a.sync().shouldNotBeNull().exported shouldBe true
        b.sync().shouldNotBeNull().exportsApplied shouldBe 1
        a.sync().shouldNotBeNull().exportsApplied shouldBe 1

        a.library() shouldBe b.library()
        a.latchesRaised() shouldBe 0
        b.latchesRaised() shouldBe 0
        val after = a.progress()

        // OR and max, chapter by chapter, over both devices' values before the sync.
        val hardDeleted = editsB.hardDeletedChapters
        for ((key, pa) in beforeA.chapters) {
            val pb = beforeB.chapters[key] ?: continue
            val merged = after.chapters.getValue(key)
            merged.read shouldBe (pa.read || pb.read)
            merged.bookmark shouldBe (pa.bookmark || pb.bookmark)
            merged.lastPage shouldBe maxOf(pa.lastPage, pb.lastPage)
            merged.dateFetch shouldBe listOfNotNull(pa.dateFetch, pb.dateFetch).minOrNull()
        }
        for (key in (beforeA.history.keys + beforeB.history.keys)) {
            val ha = beforeA.history[key]
            val hb = beforeB.history[key]
            after.history[key] shouldBe History(
                maxOf(ha?.lastRead ?: 0, hb?.lastRead ?: 0),
                maxOf(ha?.timeRead ?: 0, hb?.timeRead ?: 0),
            )
        }
        for ((key, ta) in beforeA.tracks) {
            val tb = beforeB.tracks[key] ?: continue
            if (key.first in editsA.unboundTracks) continue
            after.tracks[key] shouldBe maxOf(ta, tb)
        }
        // The favourite: the newer clock's value, OR on a tie, and the clock stored is the merged one.
        for ((url, fa) in favouritesA) {
            val fb = favouritesB.getValue(url)
            val merged = a.favourites().getValue(url)
            merged.clock shouldBe listOfNotNull(fa.clock, fb.clock).maxOrNull()
            merged.favourite shouldBe when {
                (fa.clock ?: -1) > (fb.clock ?: -1) -> fa.favourite
                (fb.clock ?: -1) > (fa.clock ?: -1) -> fb.favourite
                else -> fa.favourite || fb.favourite
            }
        }

        // Every removal, by key, on both devices.
        for (device in listOf(a, b)) {
            for (n in editsA.deletedManga) {
                device.long("SELECT deleted_at IS NOT NULL AND favorite = 0 FROM mangas WHERE url = '/series/$n'") shouldBe 1
            }
            for (url in editsA.softDeletedChapters) {
                device.long("SELECT deleted_at IS NOT NULL FROM chapters WHERE url = '$url'") shouldBe 1
            }
            device.long("SELECT deleted_at IS NOT NULL FROM categories WHERE sync_key = '${CategorySyncKey.of(DROPPED)}'") shouldBe 1
            device.long(
                "SELECT count(*) FROM mangas_categories MC JOIN categories C ON C._id = MC.category_id WHERE C.name = '$DROPPED'",
            ) shouldBe 0
            for ((n, category) in editsB.removedMemberships) {
                device.long(
                    """
                    SELECT count(*) FROM mangas_categories MC JOIN categories C ON C._id = MC.category_id
                    JOIN mangas M ON M._id = MC.manga_id WHERE M.url = '/series/$n' AND C.name = '$category'
                    """,
                ) shouldBe 0
            }
            for (n in editsB.removedExclusions) {
                device.long(
                    "SELECT count(*) FROM excluded_scanlators E JOIN mangas M ON M._id = E.manga_id WHERE M.url = '/series/$n'",
                ) shouldBe 0
            }
            for (n in editsA.unboundTracks) {
                device.long("SELECT count(*) FROM manga_sync T JOIN mangas M ON M._id = T.manga_id WHERE M.url = '/series/$n'") shouldBe 0
            }
            // B dropped these; A's copies are tombstoned, and B holds them as tombstones too.
            for (url in hardDeleted) {
                device.long("SELECT deleted_at IS NOT NULL FROM chapters WHERE url = '$url'") shouldBe 1
            }
        }
        // Every note either device wrote is on both, on a chapter B did not drop with its notes.
        for ((url, page) in editsA.notes + editsB.notes.filter { it.first !in hardDeleted }) {
            a.long(
                "SELECT count(*) FROM bookmark_notes N JOIN chapters C ON C._id = N.chapter_id WHERE C.url = '$url' AND N.page_index = $page",
            ) shouldBe 1
        }

        // A third sync on each changes nothing and pushes nothing.
        val settled = a.library()
        for (device in listOf(a, b, a)) {
            val third = device.sync().shouldNotBeNull()
            third.rowsWritten shouldBe 0
            third.segmentsPushed shouldBe 0
            third.exported shouldBe false
            third.conflicts.shouldBeEmpty()
            device.logCount() shouldBe 0
        }
        a.library() shouldBe settled
        b.library() shouldBe settled
    }

    /** The category clocks each restore stamped differ by device; everything else is equal. */
    private fun Map<String, List<List<String?>>>.withCategoryClocksOf(device: SyncTestDevice) =
        this + ("categories" to device.library().getValue("categories"))

    private class EditsA(
        val deletedManga: Set<Int>,
        val softDeletedChapters: Set<String>,
        val unboundTracks: Set<String>,
        val notes: Set<Pair<String, Int>>,
    )

    private class EditsB(
        val hardDeletedChapters: Set<String>,
        val removedMemberships: Set<Pair<Int, String>>,
        val removedExclusions: Set<Int>,
        val notes: Set<Pair<String, Int>>,
    )

    /** A: reads, pages, history, tracker progress, notes; removes manga, chapters, a category, bindings. */
    private fun SyncTestDevice.editA(random: Random): EditsA {
        repeat(80) { exec("UPDATE chapters SET read = 1 WHERE url = '${random.chapterUrl()}'") }
        repeat(50) { exec("UPDATE chapters SET last_page_read = ${random.nextInt(1, 60)} WHERE url = '${random.chapterUrl()}'") }
        repeat(40) { readAgain(random) }
        repeat(20) { trackProgress(random) }
        repeat(10) { editMangaNotes(random) }
        val notes = (1..10).map { random.chapterUrl() to random.nextInt(0, 3) }.toSet()
        notes.forEach { (url, page) -> addNote(url, page, "Note from A") }

        // Removals. Manga come from the first thousand entries, which B's removals never touch.
        val deleted = (1..15).map { random.nextInt(1, 1_000) }.toSet()
        deleted.forEach {
            exec("UPDATE mangas SET deleted_at = max($NOW, coalesce(restored_at, 0) + 1), favorite = 0 WHERE url = '/series/$it'")
        }
        val softDeleted = (1..15).map { random.chapterUrl(from = 1_000, until = 2_000) }.toSet()
        softDeleted.forEach { exec("UPDATE chapters SET deleted_at = max($NOW, coalesce(restored_at, 0) + 1) WHERE url = '$it'") }
        // DeleteCategory: the row's tombstone, then its memberships.
        val dropped = long("SELECT _id FROM categories WHERE name = '$DROPPED'")!!
        exec("UPDATE categories SET deleted_at = max($NOW, coalesce(restored_at, 0) + 1) WHERE _id = $dropped")
        exec("DELETE FROM mangas_categories WHERE category_id = $dropped")
        val unbound = (1..5).map { "/series/${random.nextInt(250, 500) * 4}" }.toSet()
        unbound.forEach { exec("DELETE FROM manga_sync WHERE manga_id = (SELECT _id FROM mangas WHERE url = '$it')") }
        return EditsA(deleted, softDeleted, unbound, notes)
    }

    /** B: bookmarks, reads, pages, history, tracker progress, notes; removes memberships, exclusions, chapters. */
    private fun SyncTestDevice.editB(random: Random, deletedOnA: Set<Int>): EditsB {
        repeat(80) { exec("UPDATE chapters SET bookmark = 1 WHERE url = '${random.chapterUrl()}'") }
        repeat(60) { exec("UPDATE chapters SET read = 1 WHERE url = '${random.chapterUrl()}'") }
        repeat(50) { exec("UPDATE chapters SET last_page_read = ${random.nextInt(1, 60)} WHERE url = '${random.chapterUrl()}'") }
        repeat(40) { readAgain(random) }
        repeat(20) { trackProgress(random) }
        repeat(10) { editMangaNotes(random) }
        val notes = (1..10).map { random.chapterUrl() to random.nextInt(0, 3) }.toSet()
        notes.forEach { (url, page) -> addNote(url, page, "Note from B, longer") }
        // The race: B reads chapters of manga A removed from its library.
        deletedOnA.take(3).forEach { exec("UPDATE chapters SET read = 1 WHERE url = '/series/$it/chapter/1'") }

        // Removals, from entries 2,001 to 3,000.
        val memberships = (1..20).map { random.nextInt(2_001, 3_001) }.toSet()
            .map { it to BACKUP_CATEGORIES[it % BACKUP_CATEGORIES.size] }
            .filter { (_, category) -> category != DROPPED }.toSet()
        memberships.forEach { (n, category) ->
            exec(
                """
                DELETE FROM mangas_categories WHERE manga_id = (SELECT _id FROM mangas WHERE url = '/series/$n')
                AND category_id = (SELECT _id FROM categories WHERE name = '$category')
                """,
            )
        }
        val exclusions = (1..10).map { random.nextInt(286, 429) * 7 }.toSet()
        exclusions.forEach { exec("DELETE FROM excluded_scanlators WHERE manga_id = (SELECT _id FROM mangas WHERE url = '/series/$it')") }
        val hardDeleted = (1..10).map { random.chapterUrl(from = 2_001) }.toSet()
        hardDeleted.forEach { exec("DELETE FROM chapters WHERE url = '$it'") }
        return EditsB(hardDeleted, memberships, exclusions, notes)
    }

    private fun Random.chapterUrl(from: Int = 1, until: Int = ENTRIES): String =
        "/series/${nextInt(from, until + 1)}/chapter/${nextInt(1, CHAPTERS + 1)}"

    /** As the reader's history write does: the read time moves and the duration adds up. */
    private fun SyncTestDevice.readAgain(random: Random) {
        val url = random.chapterUrl()
        exec(
            """
            INSERT INTO history(chapter_id, last_read, time_read)
            VALUES ((SELECT _id FROM chapters WHERE url = '$url'), ${1_700_000_000_000L + random.nextInt(1_000_000)}, 30000)
            ON CONFLICT(chapter_id) DO UPDATE SET last_read = excluded.last_read, time_read = time_read + excluded.time_read
            """,
        )
    }

    private fun SyncTestDevice.trackProgress(random: Random) {
        val url = "/series/${random.nextInt(1, ENTRIES / 4 + 1) * 4}"
        exec(
            "UPDATE manga_sync SET last_chapter_read = ${random.nextInt(1, CHAPTERS + 1)} " +
                "WHERE manga_id = (SELECT _id FROM mangas WHERE url = '$url')",
        )
    }

    private fun SyncTestDevice.editMangaNotes(random: Random) {
        exec("UPDATE mangas SET notes = 'Edited ${random.nextInt()}' WHERE url = '/series/${random.nextInt(1, ENTRIES + 1)}'")
    }

    private fun SyncTestDevice.addNote(url: String, page: Int, text: String) = exec(
        "INSERT INTO bookmark_notes(chapter_id, page_index, note, date_added) " +
            "VALUES ((SELECT _id FROM chapters WHERE url = '$url'), $page, '$text', 1)",
    )

    private data class Chapter(val read: Boolean, val bookmark: Boolean, val lastPage: Long, val dateFetch: Long?)

    private data class History(val lastRead: Long, val timeRead: Long)

    private data class Favourite(val favourite: Boolean, val clock: Long?)

    private class Progress(
        val chapters: Map<String, Chapter>,
        val history: Map<String, History>,
        val tracks: Map<Pair<String, Long>, Double>,
    )

    private fun SyncTestDevice.progress() = Progress(
        chapters = rows("SELECT url, read, bookmark, last_page_read, date_fetch FROM chapters", 5).associate {
            it[0]!! to Chapter(it[1] == "1", it[2] == "1", it[3]!!.toLong(), it[4]!!.toLong().takeIf { fetch -> fetch != 0L })
        },
        history = rows("SELECT C.url, H.last_read, H.time_read FROM history H JOIN chapters C ON C._id = H.chapter_id", 3)
            .associate { it[0]!! to History(it[1]!!.toLong(), it[2]!!.toLong()) },
        tracks = rows("SELECT M.url, T.sync_id, T.last_chapter_read FROM manga_sync T JOIN mangas M ON M._id = T.manga_id", 3)
            .associate { (it[0]!! to it[1]!!.toLong()) to it[2]!!.toDouble() },
    )

    private fun SyncTestDevice.favourites(): Map<String, Favourite> =
        rows("SELECT url, favorite, favorite_modified_at FROM mangas", 3)
            .associate { it[0]!! to Favourite(it[1] == "1", it[2]?.toLong()) }

    private companion object {
        const val ENTRIES = 3_000
        const val CHAPTERS = 8
        const val DROPPED = "Dropped"
        const val NOW = "strftime('%s', 'now') * 1000"
    }
}

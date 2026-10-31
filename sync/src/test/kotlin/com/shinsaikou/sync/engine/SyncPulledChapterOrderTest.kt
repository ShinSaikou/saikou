package com.shinsaikou.sync.engine

import com.shinsaikou.sync.InMemorySyncBackend
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * A library update numbers an entry's chapters from the top of the source's list, and the
 * renumbering travels nowhere (no rule merges `source_order`). A chapter another device pulls in
 * arrives with the sender's number, so without a renumbering here it would share a number with one
 * of this device's older chapters and the two lists would interleave on the by-source sort.
 */
class SyncPulledChapterOrderTest {

    private val backend = InMemorySyncBackend()
    private val a = SyncTestDevice(backend)
    private val b = SyncTestDevice(backend)

    @AfterEach
    fun close() {
        a.close()
        b.close()
    }

    /** The device's live chapters by the id in their url, in by-source order, with their numbers. */
    private fun SyncTestDevice.order(): List<Pair<String, Long>> = rows(
        "SELECT url, source_order FROM chapters WHERE deleted_at IS NULL ORDER BY source_order, _id",
        columns = 2,
    ).map { (url, order) -> url!!.substringAfterLast('/') to order!!.toLong() }

    /** Entry 1 on A with chapters 1 to 3, the newest on top, as an update numbers them; then B reads it. */
    private fun bothHoldThreeChapters() {
        a.addFavourite(1)
        (1L..3L).forEach { a.addChapter(it, mangaId = 1, order = 3 - it) }
        a.sync()
        b.sync()
        b.order() shouldBe listOf("3" to 0L, "2" to 1L, "1" to 2L)
    }

    /** A's library update: the chapters at [orders] are new, the rest move down past them. */
    private fun aUpdates(orders: Map<Long, Long>) {
        a.exec("UPDATE chapters SET source_order = source_order + ${orders.size} WHERE manga_id = 1")
        orders.forEach { (id, order) -> a.addChapter(id, mangaId = 1, order = order) }
        a.sync()
    }

    @Test
    fun `chapters pulled into an entry held here go above its older ones, and nothing is logged`() {
        bothHoldThreeChapters()
        val logged = b.logCount()
        val versions = b.rows(OLD_VERSIONS, columns = 2)

        aUpdates(mapOf(5L to 0L, 4L to 1L))
        b.sync()

        b.order() shouldBe listOf("5" to 0L, "4" to 1L, "3" to 2L, "2" to 3L, "1" to 4L)
        b.logCount() shouldBe logged
        b.rows(OLD_VERSIONS, columns = 2) shouldBe versions
    }

    @Test
    fun `a pulled chapter the sender lists in the middle takes that place here`() {
        bothHoldThreeChapters()
        a.exec("UPDATE chapters SET source_order = source_order + 1 WHERE manga_id = 1 AND source_order >= 1")
        a.addChapter(9, mangaId = 1, order = 1)
        a.sync()

        b.sync()

        b.order() shouldBe listOf("3" to 0L, "9" to 1L, "2" to 2L, "1" to 3L)
    }

    private companion object {
        /** The versions of the three chapters both devices started with; `addChapter` numbers a chapter by its id. */
        const val OLD_VERSIONS = "SELECT url, version FROM chapters WHERE chapter_number <= 3 ORDER BY url"
    }
}

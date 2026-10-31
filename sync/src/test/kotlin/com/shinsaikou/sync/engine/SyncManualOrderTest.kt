package com.shinsaikou.sync.engine

import com.shinsaikou.sync.InMemorySyncBackend
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.data.manga.MangaRepositoryImpl

/**
 * Two devices' drags of the library's manual order between syncs (phase 4 unit MS), over two real
 * databases. A drag is one `setManualPositions` write through the real repository, the write
 * `LibraryViewModel.reorder` hands `SetManualLibraryOrder`, so the position and version triggers
 * fire as they do in the app.
 *
 * Positions merge per row by that row's clock, not as one list, so two drags made between syncs
 * mix row by row: both devices end with the same rows, which need not be either device's order.
 * This is the known limit the unit's ruling records, as R82 records the trackers'.
 */
class SyncManualOrderTest {

    private val backend = InMemorySyncBackend()
    private val phone = SyncTestDevice(backend)
    private val tablet = SyncTestDevice(backend)

    @AfterEach
    fun close() {
        phone.close()
        tablet.close()
    }

    @Test
    fun `two drags made between syncs mix row by row and both devices end with the same positions`() {
        // a, b, c, d and X, ranked 1 to 5 on the phone, and both devices hold that order.
        for ((id, name) in listOf("a", "b", "c", "d", "X").withIndex()) {
            phone.addFavourite(id + 1L, url = "/series/$name", title = name)
        }
        phone.drag("a", "b", "c", "d", "X")
        phone.sync()
        tablet.sync()
        phone.sync()
        tablet.positions() shouldBe mapOf("a" to 1L, "b" to 2L, "c" to 3L, "d" to 4L, "X" to 5L)

        // The phone moves X from fifth to first, rewriting X, a, b, c and d: one manga line each.
        val phoneLog = phone.logCount()
        phone.drag("X", "a", "b", "c", "d")
        phone.logCount() - phoneLog shouldBe 5L
        // The tablet, before syncing, moves d from fourth to first, rewriting d, a, b and c; X,
        // past the last change, is not written. Its clocks are the newer ones.
        val tabletLog = tablet.logCount()
        tablet.drag("d", "a", "b", "c")
        tablet.logCount() - tabletLog shouldBe 4L
        tablet.advancePositionClocks(60_000, "d", "a", "b", "c")

        phone.sync()
        tablet.sync()
        phone.sync()

        phone.library() shouldBe tablet.library()
        phone.latchesRaised() shouldBe 0
        tablet.latchesRaised() shouldBe 0
        // X keeps the phone's 1, the only clock on it that moved; a, b, c and d take the tablet's.
        // Neither device's drag survives whole: X and d share position 1.
        for (device in listOf(phone, tablet)) {
            device.positions() shouldBe mapOf("a" to 2L, "b" to 3L, "c" to 4L, "d" to 1L, "X" to 1L)
        }
    }

    /**
     * One drag's write, as `SetManualLibraryOrder` makes it: position i + 1 for the i-th entry. The
     * entries are named, since each device numbers the rows it pulled itself.
     */
    private fun SyncTestDevice.drag(vararg names: String) = runBlocking {
        MangaRepositoryImpl(handler).setManualPositions(names.map { mangaId(SyncTestDevice.SOURCE, "/series/$it")!! })
    }

    /**
     * Moves the position clocks of [names] by [ms] under the rows' latch, so nothing logs or stamps
     * again: what a device whose clock runs [ms] ahead would have stamped.
     */
    private fun SyncTestDevice.advancePositionClocks(ms: Long, vararg names: String) {
        val rows = names.joinToString { "'/series/$it'" }
        exec("UPDATE mangas SET is_syncing = 1 WHERE url IN ($rows)")
        exec("UPDATE mangas SET position_modified_at = position_modified_at + $ms WHERE url IN ($rows)")
        exec("UPDATE mangas SET is_syncing = 0 WHERE url IN ($rows)")
    }

    private fun SyncTestDevice.positions(): Map<String, Long> =
        rows("SELECT title, manual_position FROM mangas ORDER BY _id", 2).associate { (title, position) ->
            title!! to position!!.toLong()
        }
}

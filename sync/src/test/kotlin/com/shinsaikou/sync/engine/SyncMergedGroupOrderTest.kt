package com.shinsaikou.sync.engine

import com.shinsaikou.sync.InMemorySyncBackend
import com.shinsaikou.sync.MergeEngine
import com.shinsaikou.sync.SyncDigest
import com.shinsaikou.sync.engine.SyncTestDevice.Companion.SOURCE
import com.shinsaikou.sync.format.MangaKey
import com.shinsaikou.sync.format.MergedGroupRow
import com.shinsaikou.sync.format.MergedMemberEntry
import io.kotest.assertions.withClue
import io.kotest.common.ExperimentalKotest
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * The merged group lattice (R163, `design/plans/merged-entries.md` section 6) through the real
 * apply and store: the same set of lines lands on the same rows in every order they arrive in, and
 * a second pass over them changes nothing. [com.shinsaikou.sync.MergePropertiesTest] holds the laws
 * of the two pure functions; this holds the settling that joins them, which writes a group whole and
 * takes a member from the group that loses it.
 *
 * Each generated set has at most one line per main entry, with the clocks drawn from three values so
 * that equal clocks, and so the key rule, come up in most runs. A set with several versions of one
 * main entry is where the one known limit lives (see the last test).
 */
@OptIn(ExperimentalKotest::class)
class SyncMergedGroupOrderTest {

    private val device = SyncTestDevice(InMemorySyncBackend(), exported = true)
    private val config = PropTestConfig(iterations = ITERATIONS)

    @AfterEach
    fun close() {
        device.close()
    }

    private fun key(id: Long) = MangaKey(SOURCE, "/series/$id")

    private class Line(val main: Long, val members: List<Pair<Long, Long>>, val showAll: Boolean, val clock: Long) {
        override fun toString() = "Line(main=$main, members=$members, showAll=$showAll, clock=$clock)"
    }

    private fun Line.toRow() = MergedGroupRow(
        main = key(main),
        members = members.map { (id, position) -> MergedMemberEntry(key(id), position, checkUpdates = id % 2 == 0L) },
        showAll = showAll,
        modifiedAt = clock,
    )

    private fun line(main: Long, members: List<Long>, clock: Long) =
        Line(main, members.mapIndexed { position, id -> id to position.toLong() }, showAll = false, clock = clock)

    private fun SyncTestDevice.apply(vararg lines: Line): ApplyResult =
        runBlocking { SegmentApply(store, aliases, MergeEngine()).apply(lines.map { it.toRow() }) }

    /** Every group's main, clock and flag, then every member row, in an order that depends on no local id. */
    private fun SyncTestDevice.state(): List<List<String?>> = rows(
        "SELECT main_url, modified_at, show_all, '' FROM merged_groups ORDER BY main_url",
        4,
    ) + rows(
        """
        SELECT G.main_url, M.member_url, M.position, M.check_updates
        FROM merged_members M JOIN merged_groups G ON G._id = M.group_id
        ORDER BY G.main_url, M.position, M.member_url
        """,
        4,
    )

    private fun SyncTestDevice.reset() = exec("DELETE FROM merged_groups")

    /** What every order must land on: a member stays in the line of the newest clock, the larger key at equal clocks. */
    private fun expected(lines: List<Line>): List<List<String?>> {
        fun beats(a: Line, b: Line) = a.clock > b.clock || (a.clock == b.clock && a.main > b.main)
        val owner = lines.flatMap { line -> line.members.map { (id, _) -> id to line } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, claimants) -> claimants.reduce { best, next -> if (beats(next, best)) next else best } }
        val groups = lines.sortedBy { it.main }.map { listOf("/series/${it.main}", "${it.clock}", if (it.showAll) "1" else "0", "") }
        val members = lines.sortedBy { it.main }.flatMap { line ->
            line.members.filter { (id, _) -> owner.getValue(id) === line }
                .sortedWith(compareBy({ it.second }, { "/series/${it.first}" }))
                .map { (id, position) ->
                    listOf("/series/${line.main}", "/series/$id", "$position", if (id % 2 == 0L) "1" else "0")
                }
        }
        return groups + members
    }

    private fun <T> List<T>.permutations(): List<List<T>> =
        if (size <= 1) listOf(this) else indices.flatMap { i -> (this - this[i]).let { rest -> rest.permutations().map { listOf(this[i]) + it } } }

    /** Up to three main entries, each with one line over members drawn from four entries. */
    private val lineSets: Arb<List<Line>> = arbitrary {
        (1L..3L).filter { Arb.boolean().bind() }.map { main ->
            val members = (1L..4L).filter { Arb.boolean().bind() }
                .map { it to Arb.element(0L, 1L, 2L).bind() }
            Line(main, members, showAll = Arb.boolean().bind(), clock = Arb.element(1L, 2L, 3L).bind())
        }
    }

    @Test
    fun `every order of arrival lands on the rows the newest clock and the key rule give`() = runBlocking<Unit> {
        checkAll(config, lineSets) { lines ->
            val want = expected(lines)
            for (order in lines.permutations()) {
                device.reset()
                order.forEach { device.apply(it) }
                withClue("order ${order.map { it.main }} of $lines") { device.state() shouldBe want }
            }
        }
    }

    @Test
    fun `a second pass over the same lines writes nothing, in any order`() = runBlocking<Unit> {
        checkAll(config, lineSets) { lines ->
            for (order in lines.permutations()) {
                device.reset()
                order.forEach { device.apply(it) }
                val settled = device.state()
                val again = order.reversed().map { device.apply(it) }
                withClue("order ${order.map { it.main }} of $lines") {
                    again.sumOf { it.written } shouldBe 0
                    device.state() shouldBe settled
                }
            }
        }
    }

    @Test
    fun `all the lines in one apply land where one at a time does`() = runBlocking<Unit> {
        checkAll(config, lineSets) { lines ->
            device.reset()
            device.apply(*lines.toTypedArray())
            withClue("one apply of $lines") { device.state() shouldBe expected(lines) }
        }
    }

    /**
     * A KNOWN LIMIT, not a goal: a member lost to a newer group is taken out of the loser's stored
     * line, and nothing puts it back. If group 2 then unmerges, member 9 does not go back to group 1
     * on a device that read group 2's middle line, while a device that never read it keeps 9 in group
     * 1. The two stay different until one of them edits group 1, which writes a line with a newer
     * clock. A fix would have to keep the lost members of a loser, or ask for the middle line again.
     */
    @Test
    fun `known limit - a member lost to a group that later unmerges does not return on a device that read it`() {
        val first = line(main = 1, members = listOf(1, 9), clock = 100)
        val middle = line(main = 2, members = listOf(2, 9), clock = 200)
        val unmerge = line(main = 2, members = emptyList(), clock = 300)
        val other = SyncTestDevice(InMemorySyncBackend(), exported = true)
        try {
            device.apply(first)
            device.apply(middle)
            device.apply(unmerge)
            other.apply(first)
            other.apply(unmerge)

            device.state() shouldBe listOf(
                listOf("/series/1", "100", "0", ""),
                listOf("/series/2", "300", "0", ""),
                listOf("/series/1", "/series/1", "0", "0"),
            )
            other.state() shouldBe listOf(
                listOf("/series/1", "100", "0", ""),
                listOf("/series/2", "300", "0", ""),
                listOf("/series/1", "/series/1", "0", "0"),
                listOf("/series/1", "/series/9", "1", "0"),
            )
            runBlocking { SyncDigest.of(device.store) } shouldNotBe runBlocking { SyncDigest.of(other.store) }

            // An edit of group 1 anywhere carries a newer clock and brings both back together.
            val edit = line(main = 1, members = listOf(1, 9), clock = 400)
            device.apply(edit)
            other.apply(edit)

            device.state() shouldBe other.state()
            runBlocking { SyncDigest.of(device.store) } shouldBe runBlocking { SyncDigest.of(other.store) }
        } finally {
            other.close()
        }
    }

    private companion object {
        const val ITERATIONS = 150
    }
}

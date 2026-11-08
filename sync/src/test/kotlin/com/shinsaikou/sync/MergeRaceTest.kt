package com.shinsaikou.sync

import com.shinsaikou.sync.MergeFixtures.note
import com.shinsaikou.sync.merge.ChapterRecord
import com.shinsaikou.sync.merge.MangaRecord
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.shuffle
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * PLAN's race, twice over: two devices edit one chapter offline and sync in any order, as often as
 * they like, in any interleaving with their edits. A read on one side and a bookmark on the other
 * both survive; so does a bookmark note on one side against page commits on the other, the case
 * where the old "higher version" rule deleted the note because paging bumps the version.
 *
 * Each device writes the way its triggers do: every edit bumps the row version, and a note, a
 * change of the read flag and a change of the bookmark stamp their own clock with
 * max(now, previous + 1) on a wall clock skewed per device. A sync is one device merging the other's
 * current row into its own. After the drawn run the two devices sync both ways, and both must hold
 * the same row with both edits in it.
 */
class MergeRaceTest {

    private val engine = MergeEngine()
    private val config = PropTestConfig(iterations = 2_000)

    private sealed interface Step {
        /** One device's local write, applied at the step's wall time plus that device's skew. */
        data class Edit(val device: Int, val name: String, val apply: (ChapterRecord, Long) -> ChapterRecord) : Step {
            override fun toString() = "Edit($device, $name)"
        }

        data class Sync(val into: Int) : Step
    }

    private class Run(val base: ChapterRecord, val skews: List<Long>, val steps: List<Step>)

    private fun runOf(required: List<Step.Edit>, noise: Arb<Step>): Arb<Run> = arbitrary {
        val steps = Arb.shuffle(required + Arb.list(noise, 0..10).bind()).bind()
        Run(
            base = MergeArbs.chapter.bind(),
            skews = listOf(Arb.long(-5L..5L).bind(), Arb.long(-5L..5L).bind()),
            steps = steps,
        )
    }

    /** Plays [run] from one shared base row and returns both devices' rows after a final exchange. */
    private fun play(run: Run): Pair<ChapterRecord, ChapterRecord> {
        val rows = mutableListOf(run.base, run.base)
        run.steps.forEachIndexed { time, step ->
            when (step) {
                is Step.Edit -> {
                    val now = BASE_TIME + time + run.skews[step.device]
                    rows[step.device] = step.apply(rows[step.device], now)
                }
                is Step.Sync -> rows[step.into] = engine.mergeChapter(rows[step.into], rows[1 - step.into]).value
            }
        }
        rows[0] = engine.mergeChapter(rows[0], rows[1]).value
        rows[1] = engine.mergeChapter(rows[1], rows[0]).value
        return rows[0] to rows[1]
    }

    private val syncs: Arb<Step> = Arb.element(Step.Sync(0), Step.Sync(1))

    private fun pageCommit(device: Int, page: Long) = Step.Edit(device, "page $page") { row, _ ->
        row.copy(lastPageRead = page, version = row.version + 1)
    }

    /** The owner marks read or unread; as update_chapter_read_modified_at, only a change stamps the clock. */
    private fun markRead(device: Int, read: Boolean) = Step.Edit(device, if (read) "read" else "unread") { row, now ->
        if (row.read == read) {
            row
        } else {
            row.copy(read = read, readModifiedAt = maxOf(now, row.readModifiedAt + 1), version = row.version + 1)
        }
    }

    /** The owner adds or removes the bookmark, stamped as update_chapter_bookmark_modified_at stamps it. */
    private fun markBookmark(device: Int, bookmark: Boolean) =
        Step.Edit(device, if (bookmark) "bookmark" else "unbookmark") { row, now ->
            if (row.bookmark == bookmark) {
                row
            } else {
                row.copy(
                    bookmark = bookmark,
                    bookmarkModifiedAt = maxOf(now, row.bookmarkModifiedAt + 1),
                    version = row.version + 1,
                )
            }
        }

    @Test
    fun `a read on one device and a bookmark on the other both survive any order`() = runTest {
        val read = markRead(0, true)
        val bookmark = markBookmark(1, true)
        val noise = arbitrary<Step> {
            if (Arb.int(0..1).bind() == 0) syncs.bind() else pageCommit(Arb.int(0..1).bind(), Arb.long(0L..40L).bind())
        }

        checkAll(config, runOf(listOf(read, bookmark), noise)) { run ->
            val (first, second) = play(run)
            withClue("steps ${run.steps}") {
                first shouldBe second
                first.read shouldBe true
                first.bookmark shouldBe true
            }
        }
    }

    @Test
    fun `a bookmark note on one device and page commits on the other both survive any order`() = runTest {
        val noted = note("see page twelve")
        val addNote = Step.Edit(0, "note") { row, now ->
            row.copy(
                bookmarkNotes = row.bookmarkNotes + (12L to noted),
                bookmarkNotesModifiedAt = maxOf(now, (row.bookmarkNotesModifiedAt ?: Long.MIN_VALUE) + 1),
                version = row.version + 1,
            )
        }
        val commits = (1L..3L).map { pageCommit(1, 10L * it) }
        val noise = arbitrary<Step> {
            if (Arb.int(0..1).bind() == 0) syncs.bind() else pageCommit(1, Arb.long(0L..40L).bind())
        }

        checkAll(config, runOf(listOf(addNote) + commits, noise)) { run ->
            val (first, second) = play(run)
            withClue("steps ${run.steps}") {
                first shouldBe second
                first.bookmarkNotes[12L] shouldBe noted
                // The page the reader committed last is kept, or a later one the other side holds.
                (first.lastPageRead >= run.lastCommittedPage()) shouldBe true
            }
        }
    }

    /**
     * R165: an unread and a removed bookmark made on one device after the read and the bookmark
     * arrived from the other win on both, then page commits and syncs in any order. Each stamp passes
     * the clock it replaces, so a wall clock behind the other device's loses nothing.
     */
    @Test
    fun `an unread and an unbookmark made after the read arrived win on both devices, whatever the skew`() = runTest {
        val noise = arbitrary<Step> {
            if (Arb.int(0..1).bind() == 0) syncs.bind() else pageCommit(Arb.int(0..1).bind(), Arb.long(0L..40L).bind())
        }
        val runs = arbitrary {
            val marked = listOf(markRead(0, true), markBookmark(0, true), Step.Sync(1))
            val undone = Arb.shuffle(listOf(markRead(1, false), markBookmark(1, false))).bind()
            Run(
                base = MergeArbs.chapter.bind(),
                skews = listOf(Arb.long(-5L..5L).bind(), Arb.long(-5L..5L).bind()),
                steps = marked + undone + Arb.list(noise, 0..10).bind(),
            )
        }

        checkAll(config, runs) { run ->
            val (first, second) = play(run)
            withClue("steps ${run.steps} skews ${run.skews}") {
                first shouldBe second
                first.read shouldBe false
                first.bookmark shouldBe false
            }
        }
    }

    private fun Run.lastCommittedPage(): Long =
        steps.filterIsInstance<Step.Edit>().mapNotNull { it.name.removePrefix("page ").toLongOrNull() }.last()

    /**
     * The edit's race (phase 5 unit ED, R138 item 2): one device edits an entry's title and
     * description while the other's library update refreshes its source values, in any order and
     * any interleaving with syncs. Each write bumps the version as the version trigger does, the edit
     * stamps the details clock, and a refresh never moves it. Both devices end with the edit, over
     * the source text the refreshes reached.
     */
    @Test
    fun `an edit on one device and source refreshes on the other both survive any order`() = runTest {
        val manga = arbitrary {
            MergeArbs.manga.bind().copy(titleOverride = null, descriptionOverride = null, detailsModifiedAt = null)
        }
        val runs = arbitrary {
            val refreshes = (1..Arb.int(1..3).bind()).map { MangaStep.Refresh("Source title $it") }
            val noise = Arb.list(Arb.element(MangaStep.Sync(0), MangaStep.Sync(1)), 0..10).bind()
            MangaRun(
                base = manga.bind(),
                skews = listOf(Arb.long(-5L..5L).bind(), Arb.long(-5L..5L).bind()),
                steps = Arb.shuffle(listOf(MangaStep.Edit) + refreshes + noise).bind(),
            )
        }

        checkAll(config, runs) { run ->
            val rows = mutableListOf(run.base, run.base)
            run.steps.forEachIndexed { time, step ->
                when (step) {
                    MangaStep.Edit -> rows[0] = rows[0].let {
                        val now = BASE_TIME + time + run.skews[0]
                        it.copy(
                            titleOverride = "Edited title",
                            descriptionOverride = "Edited text",
                            detailsModifiedAt = maxOf(now, (it.detailsModifiedAt ?: Long.MIN_VALUE) + 1),
                            version = it.version + 1,
                        )
                    }
                    is MangaStep.Refresh -> rows[1] = rows[1].copy(title = step.title, version = rows[1].version + 1)
                    is MangaStep.Sync -> rows[step.into] = engine.mergeManga(rows[step.into], rows[1 - step.into]).value
                }
            }
            rows[0] = engine.mergeManga(rows[0], rows[1]).value
            rows[1] = engine.mergeManga(rows[1], rows[0]).value
            withClue("steps ${run.steps}") {
                rows[0] shouldBe rows[1]
                rows[0].titleOverride shouldBe "Edited title"
                rows[0].descriptionOverride shouldBe "Edited text"
            }
        }
    }

    private sealed interface MangaStep {
        data object Edit : MangaStep

        data class Refresh(val title: String) : MangaStep

        data class Sync(val into: Int) : MangaStep
    }

    private class MangaRun(val base: MangaRecord, val skews: List<Long>, val steps: List<MangaStep>)

    private companion object {
        const val BASE_TIME = 1_000L
    }
}

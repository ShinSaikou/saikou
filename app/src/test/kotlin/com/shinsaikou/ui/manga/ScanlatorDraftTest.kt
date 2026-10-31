package com.shinsaikou.ui.manga

import com.shinsaikou.ui.manga.MangaDetailViewModel.RankingChange
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The scanlator dialog's working copy (phase 4 unit SC): the rows it lists, and what its one OK does
 * to the ranking. A ranking is written only when one already exists or the ticked groups are out of
 * name order; it is the ticked groups in the dialog's order; "Reset order" clears it. A change to the
 * title's groups, exclusions or ranking while the dialog is open keeps the owner's edits (review 3).
 */
class ScanlatorDraftTest {

    private val available = setOf("Group C", "Group A", "Group B")

    @Test
    fun `an unranked title lists its groups by name and is not ranked`() {
        val draft = ScanlatorDraft.of(available, excluded = emptySet(), ranking = emptyList())

        draft.order shouldBe listOf("Group A", "Group B", "Group C")
        draft.ranked shouldBe false
    }

    @Test
    fun `a ranked title lists its ranked groups first in rank order, then the rest by name`() {
        // Group D is ranked on another device but has no chapters here, so it has no row.
        val draft = ScanlatorDraft.of(available, excluded = emptySet(), ranking = listOf("Group C", "Group D"))

        draft.order shouldBe listOf("Group C", "Group A", "Group B")
        draft.ranked shouldBe true
    }

    @Test
    fun `no ranking is written when nothing moved on an unranked title`() {
        val draft = ScanlatorDraft.of(available, excluded = emptySet(), ranking = emptyList())
            .toggle("Group B")

        draft.excluded shouldBe setOf("Group B")
        draft.rankingChange(existing = emptyList()) shouldBe RankingChange.Keep
    }

    @Test
    fun `a moved row writes the ticked groups in the dialog's order`() {
        val draft = ScanlatorDraft.of(available, excluded = emptySet(), ranking = emptyList())
            .move(from = 2, to = 1)
            .move(from = 1, to = 0)
            .toggle("Group B")

        draft.order shouldBe listOf("Group C", "Group A", "Group B")
        draft.rankingChange(existing = emptyList()) shouldBe RankingChange.Write(listOf("Group C", "Group A"))
    }

    @Test
    fun `a row moved and moved back on an unranked title writes nothing and hides Reset order`() {
        // Review 1's case: Move up on Group B, then Move down on the same row, now at the top.
        val draft = ScanlatorDraft.of(available, excluded = emptySet(), ranking = emptyList())
            .move(from = 1, to = 0)
            .move(from = 0, to = 1)

        draft.order shouldBe listOf("Group A", "Group B", "Group C")
        draft.ranked shouldBe false
        draft.rankingChange(existing = emptyList()) shouldBe RankingChange.Keep
    }

    @Test
    fun `moving only an unticked row on an unranked title writes nothing`() {
        val draft = ScanlatorDraft.of(available, excluded = emptySet(), ranking = emptyList())
            .toggle("Group B")
            .move(from = 1, to = 0)

        draft.order shouldBe listOf("Group B", "Group A", "Group C")
        draft.rankingChange(existing = emptyList()) shouldBe RankingChange.Keep
    }

    @Test
    fun `an existing ranking is written again without a group the owner unticked`() {
        val existing = listOf("Group C", "Group A", "Group B")
        val draft = ScanlatorDraft.of(available, excluded = emptySet(), ranking = existing)
            .toggle("Group A")

        draft.rankingChange(existing) shouldBe RankingChange.Write(listOf("Group C", "Group B"))
    }

    @Test
    fun `an existing ranking the owner left as it was is not written`() {
        val existing = listOf("Group C", "Group A", "Group B")
        val draft = ScanlatorDraft.of(available, excluded = emptySet(), ranking = existing)

        draft.rankingChange(existing) shouldBe RankingChange.Keep
    }

    @Test
    fun `a ranking holding a group with no chapters here is not written when nothing changed`() {
        // Review 2's case: Group D is ranked on another device and has no row here, so the rows can
        // never equal the stored ranking; an OK that touched nothing must not drop Group D's rank.
        val existing = listOf("Group C", "Group D")
        val draft = ScanlatorDraft.of(available, excluded = emptySet(), ranking = existing)

        draft.rankingChange(existing) shouldBe RankingChange.Keep
    }

    @Test
    fun `a ranked group excluded here keeps its rank when nothing changed`() {
        // The two collections merge apart, so a group can be excluded here and ranked from another device.
        val existing = listOf("Group C", "Group A")
        val draft = ScanlatorDraft.of(available, excluded = setOf("Group A"), ranking = existing)

        draft.rankingChange(existing) shouldBe RankingChange.Keep
    }

    @Test
    fun `groups with no rank yet are not written into the ranking when nothing changed`() {
        // Group A and Group B have chapters but no rank, as when a new group's chapters arrive.
        val existing = listOf("Group C")
        val draft = ScanlatorDraft.of(available, excluded = emptySet(), ranking = existing)

        draft.order shouldBe listOf("Group C", "Group A", "Group B")
        draft.rankingChange(existing) shouldBe RankingChange.Keep
    }

    @Test
    fun `a ranked title with every group excluded is not cleared when nothing changed`() {
        val existing = listOf("Group C", "Group A")
        val draft = ScanlatorDraft.of(available, excluded = available, ranking = existing)

        draft.rankingChange(existing) shouldBe RankingChange.Keep
    }

    @Test
    fun `a move on a ranking holding a group with no chapters here writes the dialog's order`() {
        val existing = listOf("Group C", "Group D")
        val draft = ScanlatorDraft.of(available, excluded = emptySet(), ranking = existing)
            .move(from = 2, to = 1)

        draft.rankingChange(existing) shouldBe RankingChange.Write(listOf("Group C", "Group B", "Group A"))
    }

    @Test
    fun `a move past either end changes nothing`() {
        val draft = ScanlatorDraft.of(available, excluded = emptySet(), ranking = emptyList())

        draft.move(from = 0, to = -1) shouldBe draft
        draft.move(from = 2, to = 3) shouldBe draft
    }

    @Test
    fun `Reset order puts the rows back by name and clears the ranking`() {
        val existing = listOf("Group C", "Group A", "Group B")
        val draft = ScanlatorDraft.of(available, excluded = emptySet(), ranking = existing).resetOrder()

        draft.order shouldBe listOf("Group A", "Group B", "Group C")
        draft.ranked shouldBe false
        draft.rankingChange(existing) shouldBe RankingChange.Clear
    }

    @Test
    fun `a row moved after Reset order writes the new order instead`() {
        val existing = listOf("Group C", "Group A", "Group B")
        val draft = ScanlatorDraft.of(available, excluded = emptySet(), ranking = existing)
            .resetOrder()
            .move(from = 1, to = 0)

        draft.rankingChange(existing) shouldBe RankingChange.Write(listOf("Group B", "Group A", "Group C"))
    }

    @Test
    fun `a row moved back to name order after Reset order still clears the ranking`() {
        val existing = listOf("Group C", "Group A", "Group B")
        val draft = ScanlatorDraft.of(available, excluded = emptySet(), ranking = existing)
            .resetOrder()
            .move(from = 1, to = 0)
            .move(from = 0, to = 1)

        draft.rankingChange(existing) shouldBe RankingChange.Clear
    }

    @Test
    fun `Reset order on a title that had no ranking writes nothing`() {
        val draft = ScanlatorDraft.of(available, excluded = emptySet(), ranking = emptyList())
            .move(from = 1, to = 0)
            .resetOrder()

        draft.rankingChange(existing = emptyList()) shouldBe RankingChange.Keep
    }

    @Test
    fun `unticking every group of a ranked title clears the ranking`() {
        val existing = listOf("Group A")
        val draft = ScanlatorDraft.of(available, excluded = emptySet(), ranking = existing)
            .toggle("Group A").toggle("Group B").toggle("Group C")

        draft.rankingChange(existing) shouldBe RankingChange.Clear
    }

    // Review 3: the title's groups, exclusions or ranking change while the dialog is open.

    @Test
    fun `a move survives a new group's chapters arriving while the dialog is open`() {
        // The verifier's case: Group B moved above Group A, then a library update brings Group C.
        val draft = ScanlatorDraft.of(setOf("Group A", "Group B"), excluded = emptySet(), ranking = emptyList())
            .move(from = 1, to = 0)
            .rebase(available, excluded = emptySet(), ranking = emptyList())

        draft.order shouldBe listOf("Group B", "Group A", "Group C")
        draft.rankingChange(existing = emptyList()) shouldBe
            RankingChange.Write(listOf("Group B", "Group A", "Group C"))
    }

    @Test
    fun `a tick survives a new group's chapters arriving and the rows stay in name order`() {
        val draft = ScanlatorDraft.of(setOf("Group B", "Group C"), excluded = emptySet(), ranking = emptyList())
            .toggle("Group B")
            .rebase(available, excluded = emptySet(), ranking = emptyList())

        draft.order shouldBe listOf("Group A", "Group B", "Group C")
        draft.excluded shouldBe setOf("Group B")
        draft.ranked shouldBe false
        draft.rankingChange(existing = emptyList()) shouldBe RankingChange.Keep
    }

    @Test
    fun `a moved group gone from the title drops out of the owner's order`() {
        val draft = ScanlatorDraft.of(available, excluded = emptySet(), ranking = emptyList())
            .move(from = 2, to = 0)
            .rebase(setOf("Group A", "Group C"), excluded = emptySet(), ranking = emptyList())

        draft.order shouldBe listOf("Group C", "Group A")
        draft.rankingChange(existing = emptyList()) shouldBe RankingChange.Write(listOf("Group C", "Group A"))
    }

    @Test
    fun `an untouched dialog follows a ranking synced in and an OK then writes nothing`() {
        val existing = listOf("Group C")
        val draft = ScanlatorDraft.of(available, excluded = emptySet(), ranking = emptyList())
            .rebase(available, excluded = emptySet(), ranking = existing)

        draft.order shouldBe listOf("Group C", "Group A", "Group B")
        draft.ranked shouldBe true
        draft.rankingChange(existing) shouldBe RankingChange.Keep
    }

    @Test
    fun `an exclusion synced in joins the owner's own tick`() {
        val draft = ScanlatorDraft.of(available, excluded = emptySet(), ranking = emptyList())
            .toggle("Group A")
            .rebase(available, excluded = setOf("Group B"), ranking = emptyList())

        draft.excluded shouldBe setOf("Group A", "Group B")
    }

    @Test
    fun `the owner's tick wins over an exclusion synced in for the same group`() {
        val draft = ScanlatorDraft.of(available, excluded = setOf("Group A"), ranking = emptyList())
            .toggle("Group A")
            .rebase(available, excluded = setOf("Group A", "Group B"), ranking = emptyList())

        draft.excluded shouldBe setOf("Group B")
    }

    @Test
    fun `Reset order survives a ranking synced in and an OK clears it`() {
        val existing = listOf("Group C", "Group A")
        val draft = ScanlatorDraft.of(available, excluded = emptySet(), ranking = listOf("Group C"))
            .resetOrder()
            .rebase(available, excluded = emptySet(), ranking = existing)

        draft.order shouldBe listOf("Group A", "Group B", "Group C")
        draft.ranked shouldBe false
        draft.rankingChange(existing) shouldBe RankingChange.Clear
    }

    @Test
    fun `a rebase onto unchanged data leaves the draft as it was`() {
        val existing = listOf("Group C")
        val opened = ScanlatorDraft.of(available, excluded = setOf("Group A"), ranking = existing)
        val edited = opened.toggle("Group B").move(from = 2, to = 0)
        val reset = opened.resetOrder().toggle("Group C")

        opened.rebase(available, excluded = setOf("Group A"), ranking = existing) shouldBe opened
        edited.rebase(available, excluded = setOf("Group A"), ranking = existing) shouldBe edited
        reset.rebase(available, excluded = setOf("Group A"), ranking = existing) shouldBe reset
    }
}

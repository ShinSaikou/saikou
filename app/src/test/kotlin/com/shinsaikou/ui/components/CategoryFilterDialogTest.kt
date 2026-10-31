package com.shinsaikou.ui.components

import com.shinsaikou.i18n.R
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.model.Category

/**
 * The tri-state category dialog's logic (phase 3 unit AD): upstream's `TriStateListDialog` cycle and
 * result, and its `getCategoriesLabel` summary, over the id strings upstream's preferences store.
 */
class CategoryFilterDialogTest {

    private val system = Category(id = 0, name = "", order = 0, flags = 0)
    private val reading = Category(id = 3, name = "Reading", order = 1, flags = 0)
    private val later = Category(id = 7, name = "Later", order = 2, flags = 0)
    private val categories = listOf(system, reading, later)

    @Test
    fun `a tap cycles unchecked to included to excluded and back`() {
        CategoryFilterState.UNCHECKED.next() shouldBe CategoryFilterState.INCLUDED
        CategoryFilterState.INCLUDED.next() shouldBe CategoryFilterState.EXCLUDED
        CategoryFilterState.EXCLUDED.next() shouldBe CategoryFilterState.UNCHECKED
    }

    /** Phase 5 unit FX, R126 carry-over 2: an excluded row reads as upstream's one "Disabled". */
    @Test
    fun `an excluded row reads as the one Disabled, the others as not selected and selected`() {
        CategoryFilterState.entries.map { it.description() } shouldContainExactly listOf(
            R.string.category_filter_not_selected,
            R.string.category_filter_selected,
            R.string.disabled,
        )
    }

    @Test
    fun `the rows read the stored sets, every category in order, included winning over excluded`() {
        categoryFilterRows(categories, included = setOf("3", "7"), excluded = setOf("7", "0"))
            .map { it.category.id to it.state } shouldContainExactly listOf(
                0L to CategoryFilterState.EXCLUDED,
                3L to CategoryFilterState.INCLUDED,
                7L to CategoryFilterState.INCLUDED,
            )
    }

    @Test
    fun `the result is the ids of each state, as strings`() {
        val rows = categoryFilterRows(categories, included = setOf("3"), excluded = emptySet())
            .map { if (it.category.id == 0L) it.copy(state = it.state.next().next()) else it }

        rows.idsIn(CategoryFilterState.INCLUDED) shouldBe setOf("3")
        rows.idsIn(CategoryFilterState.EXCLUDED) shouldBe setOf("0")
        rows.idsIn(CategoryFilterState.UNCHECKED) shouldBe setOf("7")
    }

    @Test
    fun `nothing stored reads include all and exclude none`() {
        categoryFilterSummary(categories, emptySet(), emptySet()) shouldBe (CategoryFilterPart.All to CategoryFilterPart.None)
    }

    @Test
    fun `some included and some excluded are named in category order`() {
        categoryFilterSummary(categories, included = setOf("7", "3"), excluded = setOf("0")) shouldBe (
            CategoryFilterPart.Some(listOf(reading, later)) to CategoryFilterPart.Some(listOf(system))
            )
    }

    @Test
    fun `every category included reads all, and every one excluded reads include none, exclude all`() {
        categoryFilterSummary(categories, included = setOf("0", "3", "7"), excluded = emptySet()) shouldBe
            (CategoryFilterPart.All to CategoryFilterPart.None)
        categoryFilterSummary(categories, included = emptySet(), excluded = setOf("0", "3", "7")) shouldBe
            (CategoryFilterPart.None to CategoryFilterPart.All)
    }

    @Test
    fun `an id no category has any more is left out`() {
        categoryFilterSummary(categories, included = setOf("99"), excluded = setOf("98", "3")) shouldBe
            (CategoryFilterPart.All to CategoryFilterPart.Some(listOf(reading)))
    }
}

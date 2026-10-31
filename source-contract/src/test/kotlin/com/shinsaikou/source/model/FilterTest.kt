package com.shinsaikou.source.model

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class FilterTest {

    @Test
    fun `header and separator hold a constant state`() {
        Filter.Header("Caption").state shouldBe 0
        Filter.Separator().name shouldBe ""
        Filter.Separator().state shouldBe 0
    }

    @Test
    fun `select defaults to the first value and tracks the selected one`() {
        val filter = Filter.Select("Sort by", listOf("Any", "Manga", "Manhwa"))

        filter.state shouldBe 0
        filter.selected shouldBe "Any"

        filter.state = 2
        filter.selected shouldBe "Manhwa"
    }

    @Test
    fun `select reports an empty string for an out of range state`() {
        val filter = Filter.Select("Type", listOf("Any"), state = 5)

        filter.selected shouldBe ""
    }

    @Test
    fun `select on an empty value list reports an empty string`() {
        Filter.Select("Type", emptyList()).selected shouldBe ""
    }

    @Test
    fun `text defaults to the empty string`() {
        Filter.Text("Author").state shouldBe ""
        Filter.Text("Author", "Someone").state shouldBe "Someone"
    }

    @Test
    fun `checkbox defaults to false`() {
        Filter.CheckBox("Completed").state shouldBe false
        Filter.CheckBox("Completed", state = true).state shouldBe true
    }

    @Test
    fun `tristate defaults to ignore and reports each state`() {
        val filter = Filter.TriState("Action")

        filter.state shouldBe Filter.TriState.STATE_IGNORE
        filter.isIgnored() shouldBe true
        filter.isIncluded() shouldBe false
        filter.isExcluded() shouldBe false

        filter.state = Filter.TriState.STATE_INCLUDE
        filter.isIgnored() shouldBe false
        filter.isIncluded() shouldBe true
        filter.isExcluded() shouldBe false

        filter.state = Filter.TriState.STATE_EXCLUDE
        filter.isIgnored() shouldBe false
        filter.isIncluded() shouldBe false
        filter.isExcluded() shouldBe true
    }

    @Test
    fun `the tristate codes are the ABI codes`() {
        Filter.TriState.STATE_IGNORE shouldBe 0
        Filter.TriState.STATE_INCLUDE shouldBe 1
        Filter.TriState.STATE_EXCLUDE shouldBe 2
    }

    @Test
    fun `a group holds its children as the state`() {
        val child = Filter.CheckBox("Action")
        val group = Filter.Group("Genres", listOf(child))

        group.state shouldBe listOf(child)

        child.state = true
        (group.state.first() as Filter.CheckBox).state shouldBe true
    }

    @Test
    fun `sort defaults to no selection`() {
        val filter = Filter.Sort("Order", listOf("Title", "Date"))

        filter.state shouldBe null

        filter.state = Filter.Sort.Selection(index = 1, ascending = false)
        filter.state shouldBe Filter.Sort.Selection(index = 1, ascending = false)
    }

    @Test
    fun `equality is name and state, ignoring the subtype`() {
        Filter.Text("Name", "x") shouldBe Filter.Text("Name", "x")
        Filter.Header("Same") shouldBe Filter.Separator("Same")
        Filter.Select("Same", listOf("a"), state = 0) shouldBe Filter.TriState("Same", state = 0)
        Filter.Text("Name", "x") shouldNotBe Filter.Text("Name", "y")
        Filter.Text("Name", "x") shouldNotBe Filter.Text("Other", "x")
    }

    @Test
    fun `hashCode follows equality`() {
        Filter.Text("Name", "x").hashCode() shouldBe Filter.Text("Name", "x").hashCode()
        Filter.Header("Same").hashCode() shouldBe Filter.Separator("Same").hashCode()
    }

    @Test
    fun `hashCode tolerates a null state`() {
        Filter.Sort("Order", listOf("Title")).hashCode() shouldBe "Order".hashCode() * 31
    }

    @Test
    fun `toString names the subtype`() {
        Filter.CheckBox("Completed").toString() shouldBe "CheckBox(name=Completed, state=false)"
    }
}

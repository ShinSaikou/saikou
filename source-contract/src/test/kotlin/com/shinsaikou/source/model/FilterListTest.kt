package com.shinsaikou.source.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class FilterListTest {

    private fun tree() = FilterList(
        Filter.Header("Caption"),
        Filter.Separator(),
        Filter.Select("Type", listOf("Any", "Manga"), state = 1),
        Filter.Text("Author", "Someone"),
        Filter.CheckBox("Completed", state = true),
        Filter.TriState("Action", state = Filter.TriState.STATE_EXCLUDE),
        Filter.Group("Genres", listOf(Filter.CheckBox("Drama", state = true), Filter.Text("Free", "x"))),
        Filter.Sort("Order", listOf("Title", "Date"), Filter.Sort.Selection(index = 1, ascending = false)),
    )

    // Compared with == rather than kotest's shouldNotBe: FilterList is a List, so the matchers
    // would compare it element by element and never reach the equals override under test.
    @Test
    fun `equals is always false, even against itself`() {
        val list = tree()

        (list == list) shouldBe false
        (list == tree()) shouldBe false
        (list == FilterList.EMPTY) shouldBe false
        (FilterList.EMPTY == FilterList.EMPTY) shouldBe false
    }

    @Test
    fun `hashCode delegates to the backing list`() {
        val filters = listOf<Filter<*>>(Filter.Text("Author", "Someone"))

        FilterList(filters).hashCode() shouldBe filters.hashCode()
    }

    @Test
    fun `it behaves as the list it delegates to`() {
        val list = tree()

        list.size shouldBe 8
        list[3] shouldBe Filter.Text("Author", "Someone")
        FilterList.EMPTY.isEmpty() shouldBe true
        FilterList().list shouldBe emptyList()
    }

    @Test
    fun `deepCopy produces distinct instances that are equal by fields`() {
        val original = tree()
        val copy = original.deepCopy()

        copy.list.size shouldBe original.list.size
        original.list.zip(copy.list).forEach { (from, to) ->
            (from === to) shouldBe false
            from shouldBe to
            from::class shouldBe to::class
        }
    }

    @Test
    fun `deepCopy carries the values of select and sort`() {
        val copy = tree().deepCopy()

        (copy.list[2] as Filter.Select).values shouldBe listOf("Any", "Manga")
        (copy.list[2] as Filter.Select).selected shouldBe "Manga"
        (copy.list[7] as Filter.Sort).values shouldBe listOf("Title", "Date")
        (copy.list[7] as Filter.Sort).state shouldBe Filter.Sort.Selection(index = 1, ascending = false)
    }

    @Test
    fun `deepCopy copies group children recursively`() {
        val original = tree()
        val copy = original.deepCopy()

        val originalGroup = original.list[6] as Filter.Group
        val copiedGroup = copy.list[6] as Filter.Group

        copiedGroup.state.size shouldBe originalGroup.state.size
        originalGroup.state.zip(copiedGroup.state).forEach { (from, to) ->
            (from === to) shouldBe false
            from shouldBe to
        }
    }

    @Test
    fun `editing a copy does not touch the original`() {
        val original = tree()
        val copy = original.deepCopy()

        (copy.list[4] as Filter.CheckBox).state = false
        (copy.list[6] as Filter.Group).state.filterIsInstance<Filter.CheckBox>()
            .forEach { it.state = false }

        (original.list[4] as Filter.CheckBox).state shouldBe true
        ((original.list[6] as Filter.Group).state[0] as Filter.CheckBox).state shouldBe true
    }
}

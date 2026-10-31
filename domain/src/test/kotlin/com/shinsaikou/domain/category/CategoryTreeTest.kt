package com.shinsaikou.domain.category

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.model.Category

/**
 * Phase 5 unit CT, item 2: every shape the sync merge can store reads as one tree of one level, the
 * same on every device that holds the same rows. Each case is written as "name" for a top-level row
 * and "parent>name" for a row under a parent, in the order the screens list them.
 */
class CategoryTreeTest {

    private fun category(id: Long, name: String, order: Long, parentId: Long? = null, deletedAt: Long? = null) = Category(
        id = id,
        name = name,
        order = order,
        flags = 0,
        parentId = parentId,
        deletedAt = deletedAt,
        syncKey = CategorySyncKey.of(name),
    )

    private val system = Category(id = Category.UNCATEGORIZED_ID, name = "", order = -1, flags = 0)

    private fun List<Category>.tree(): List<String> = CategoryTree.of(this).map { node ->
        val name = node.category.name.ifEmpty { "(system)" }
        node.parent?.let { "${it.name}>$name" } ?: name
    }

    /** Every order of [rows], each read as a tree: the merge can deliver rows in any of them. */
    private fun everyOrder(rows: List<Category>): List<List<String>> {
        fun permutations(left: List<Category>): List<List<Category>> =
            if (left.size <= 1) listOf(left) else left.flatMap { pick -> permutations(left - pick).map { listOf(pick) + it } }
        return permutations(rows).map { it.tree() }
    }

    @Test
    fun `a plain tree lists each parent in sort order followed by its children in sort order`() {
        val rows = listOf(
            system,
            category(1, "Reading", order = 0),
            category(2, "Weekly", order = 1, parentId = 1),
            category(3, "Finished", order = 2),
            category(4, "Monthly", order = 3, parentId = 1),
            category(5, "Rereads", order = 4, parentId = 3),
            category(6, "Dropped", order = 5),
        )

        rows.tree() shouldContainExactly listOf(
            "(system)", "Reading", "Reading>Weekly", "Reading>Monthly", "Finished", "Finished>Rereads", "Dropped",
        )
        CategoryTree.of(rows).map { it.depth } shouldContainExactly listOf(0, 0, 1, 1, 0, 1, 0)
    }

    @Test
    fun `children follow their parent whatever their own sort, and keep their sort among themselves`() {
        val rows = listOf(
            category(1, "Late child", order = 0, parentId = 3),
            category(2, "Early child", order = 1, parentId = 3),
            category(3, "Parent", order = 9),
            category(4, "Other", order = 4),
        )

        rows.tree() shouldContainExactly listOf("Other", "Parent", "Parent>Late child", "Parent>Early child")
    }

    @Test
    fun `a parent this device does not hold leaves the child at the top level`() {
        val rows = listOf(category(1, "Reading", order = 0), category(2, "Orphan", order = 1, parentId = 99))

        rows.tree() shouldContainExactly listOf("Reading", "Orphan")
    }

    @Test
    fun `a soft-deleted parent leaves the child at the top level, and the deleted row is not listed`() {
        val rows = listOf(
            category(1, "Gone", order = 0, deletedAt = 1_000L),
            category(2, "Child of gone", order = 1, parentId = 1),
            category(3, "Reading", order = 2),
        )

        rows.tree() shouldContainExactly listOf("Child of gone", "Reading")
    }

    @Test
    fun `the system category is never a parent, never a child, and lists in its own place`() {
        val rows = listOf(
            category(1, "Under the system one", order = 0, parentId = Category.UNCATEGORIZED_ID),
            system.copy(parentId = 1),
            category(2, "Reading", order = 1),
        )

        rows.tree() shouldContainExactly listOf("(system)", "Under the system one", "Reading")
    }

    @Test
    fun `a category that is its own parent is at the top level`() {
        listOf(category(1, "Self", order = 0, parentId = 1)).tree() shouldContainExactly listOf("Self")
    }

    @Test
    fun `a two-cycle breaks at the lowest sync key, whatever order the rows arrive in`() {
        val north = category(1, "North", order = 0, parentId = 2)
        val south = category(2, "South", order = 1, parentId = 1)
        val (low, high) = listOf(north, south).sortedBy { it.syncKey }

        everyOrder(listOf(north, south)).distinct() shouldContainExactly listOf(
            listOf(low.name, "${low.name}>${high.name}"),
        )
    }

    @Test
    fun `a three-cycle breaks at the lowest sync key and lifts the rest under it, whatever the order`() {
        val one = category(1, "One", order = 0, parentId = 2)
        val two = category(2, "Two", order = 1, parentId = 3)
        val three = category(3, "Three", order = 2, parentId = 1)
        val rows = listOf(one, two, three)
        val low = rows.minBy { it.syncKey!! }
        val rest = (rows - low).sortedBy { it.order }

        val trees = everyOrder(rows).distinct()

        trees shouldContainExactly listOf(listOf(low.name) + rest.map { "${low.name}>${it.name}" })
    }

    @Test
    fun `the same cycle breaks at the same member when the ids differ, as they do between two devices`() {
        val phone = listOf(category(1, "North", order = 0, parentId = 2), category(2, "South", order = 1, parentId = 1))
        val tablet = listOf(category(7, "South", order = 1, parentId = 9), category(9, "North", order = 0, parentId = 7))

        phone.tree() shouldBe tablet.tree()
    }

    @Test
    fun `a chain hanging off a cycle reads under the member the cycle broke at`() {
        val north = category(1, "North", order = 0, parentId = 2)
        val south = category(2, "South", order = 1, parentId = 1)
        val tail = category(3, "Tail", order = 2, parentId = 2)
        val low = listOf(north, south).minBy { it.syncKey!! }

        listOf(north, south, tail).tree().first() shouldBe low.name
        listOf(north, south, tail).tree().drop(1).all { it.startsWith("${low.name}>") } shouldBe true
    }

    @Test
    fun `a three-deep chain lifts the grandchild to one level under its top-level ancestor`() {
        val rows = listOf(
            category(1, "Top", order = 0),
            category(2, "Middle", order = 1, parentId = 1),
            category(3, "Bottom", order = 2, parentId = 2),
            category(4, "Below bottom", order = 3, parentId = 3),
        )

        rows.tree() shouldContainExactly listOf("Top", "Top>Middle", "Top>Bottom", "Top>Below bottom")
        CategoryTree.of(rows).maxOf { it.depth } shouldBe 1
    }

    @Test
    fun `equal sorts are ordered by sync key, so two devices list them alike`() {
        val a = category(1, "Alpha", order = 0)
        val b = category(2, "Beta", order = 0)
        val expected = listOf(a, b).sortedBy { it.syncKey }.map { it.name }

        everyOrder(listOf(a, b)).distinct() shouldContainExactly listOf(expected)
    }

    @Test
    fun `every live row is listed exactly once`() {
        val rows = (1L..40L).map { id ->
            category(id, "Category $id", order = id % 7, parentId = ((id * 13) % 41).takeIf { it != 0L })
        }

        val tree = CategoryTree.of(rows)

        tree.map { it.category.id }.sorted() shouldContainExactly rows.map { it.id }
        // Every parent named is itself listed at the top level, directly above its children's run.
        tree.filter { it.parent != null }.all { child -> tree.any { it.category == child.parent && it.depth == 0 } } shouldBe true
    }
}

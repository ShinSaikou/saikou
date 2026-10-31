package com.shinsaikou.domain.category

import tachiyomi.domain.category.model.Category

/**
 * One row of [CategoryTree.of]: a category, and the top-level category it renders under, or null
 * when it renders at the top level.
 */
data class CategoryTreeNode(val category: Category, val parent: Category?) {
    /** 0 at the top level, 1 under a parent. Nothing renders deeper (plan 2.1). */
    val depth: Int get() = if (parent == null) 0 else 1
}

/**
 * The one reading of the stored categories as a tree of one level (phase 5 unit CT, plan 2.1), used
 * by every screen that lists categories: the Categories screen, the change-category dialog and the
 * library's tabs.
 *
 * The sync merge takes a category's parent per row by its clock (challenge c2, g3 C), so what is
 * stored can be deeper than one level, can point at a parent this device does not hold or holds
 * deleted, and can close a cycle that no screen made. The tree reads every such shape the same way
 * on every device that holds the same rows:
 * - a parent that is missing, soft-deleted, the system category or the category itself is absent,
 *   and the category renders at the top level;
 * - a cycle of parents is broken at its member with the lowest sync key (ties, which only an
 *   unkeyed row can give, by id), which renders at the top level, so two devices break it alike
 *   whatever order the rows arrive in;
 * - anything deeper than one level renders one level under its top-level ancestor.
 *
 * The order is one flat `sort`: top-level categories in `sort` order, each followed by the
 * categories under it in `sort` order, ties by sync key and then id. A soft-deleted row is left out;
 * every live row given is in the output exactly once. Pure, and linear in the number of categories.
 */
object CategoryTree {

    fun of(categories: List<Category>): List<CategoryTreeNode> {
        val live = categories.filter { it.deletedAt == null }
        val byId = live.associateBy { it.id }

        // The stored parent where it can be one: live, held, not the system category, not itself.
        val parentOf = HashMap<Long, Long>(live.size)
        live.forEach { category ->
            if (category.isSystemCategory) return@forEach
            val parentId = category.parentId ?: return@forEach
            if (parentId == category.id || parentId == Category.UNCATEGORIZED_ID || parentId !in byId) return@forEach
            parentOf[category.id] = parentId
        }
        breakCycles(parentOf, byId)

        // Each category's top-level ancestor; the walks end, since no cycle is left.
        val rootOf = HashMap<Long, Long>(live.size)
        fun root(id: Long): Long = rootOf.getOrPut(id) { parentOf[id]?.let(::root) ?: id }

        val children = live.filter { root(it.id) != it.id }.groupBy { root(it.id) }
        return live.filter { root(it.id) == it.id }
            .sortedWith(ORDER)
            .flatMap { top ->
                listOf(CategoryTreeNode(top, null)) +
                    children[top.id].orEmpty().sortedWith(ORDER).map { CategoryTreeNode(it, top) }
            }
    }

    /**
     * Removes one link of every cycle in [parentOf], the one leaving the cycle's member with the
     * lowest sync key. Each category has one parent at most, so each chain of parents ends at the top
     * level or in exactly one cycle, and every chain is walked once.
     */
    private fun breakCycles(parentOf: MutableMap<Long, Long>, byId: Map<Long, Category>) {
        val done = HashSet<Long>(byId.size)
        for (start in byId.keys) {
            if (start in done) continue
            val path = LinkedHashMap<Long, Int>()
            var cursor: Long? = start
            while (cursor != null && cursor !in done && cursor !in path) {
                path[cursor] = path.size
                cursor = parentOf[cursor]
            }
            if (cursor != null && cursor in path) {
                val cycle = path.keys.drop(path.getValue(cursor)).map(byId::getValue)
                parentOf.remove(cycle.minWith(CYCLE_BREAK).id)
            }
            done += path.keys
        }
    }

    /** The member a cycle is broken at: the lowest sync key, which every device holds alike. */
    private val CYCLE_BREAK: Comparator<Category> =
        compareBy<Category, String?>(nullsLast()) { it.syncKey }.thenBy { it.id }

    private val ORDER: Comparator<Category> = compareBy<Category> { it.order }
        .thenBy(nullsLast()) { it.syncKey }
        .thenBy { it.id }
}

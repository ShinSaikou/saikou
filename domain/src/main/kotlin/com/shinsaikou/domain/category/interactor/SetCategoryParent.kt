package com.shinsaikou.domain.category.interactor

import com.shinsaikou.domain.category.CategoryTree
import dev.zacsweers.metro.Inject
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository

/**
 * Sets the display parent of a category. Sub-categories are display-only: membership stays flat
 * (contract decision D8) and only the category list renders the nesting. They go one level deep
 * (phase 5 unit CT, plan 2.1): a category may sit under a top-level category, and a category under
 * another holds none.
 */
@Inject
class SetCategoryParent(
    private val categoryRepository: CategoryRepository,
) {

    /**
     * Refuses the system category as a parent, a category as its own parent, a parent this device
     * does not hold, and any cycle; and, for one level, a parent that is itself under another
     * ([Result.TooDeep]) or a category that has categories under it ([Result.TooDeep]).
     */
    suspend fun await(categoryId: Long, parentId: Long?): Result {
        if (parentId == null) {
            categoryRepository.updateParent(categoryId, null)
            return Result.Success
        }
        if (parentId == Category.UNCATEGORIZED_ID) return Result.IllegalParent
        if (parentId == categoryId) return Result.IllegalParent

        val categories = categoryRepository.getAll()
        val byId = categories.associateBy { it.id }
        var cursor = byId[parentId] ?: return Result.IllegalParent
        if (!mayNest(categories, categoryId, parentId)) return Result.TooDeep
        var hops = 0
        while (hops++ < MAX_DEPTH) {
            if (cursor.id == categoryId) return Result.Cycle
            cursor = byId[cursor.parentId ?: break] ?: break
        }
        if (hops >= MAX_DEPTH) return Result.Cycle

        categoryRepository.updateParent(categoryId, parentId)
        return Result.Success
    }

    sealed interface Result {
        data object Success : Result
        data object IllegalParent : Result
        data object Cycle : Result

        /** The one-level rule refused it: the parent is under another, or the category holds some. */
        data object TooDeep : Result
    }

    companion object {
        private const val MAX_DEPTH = 32

        /**
         * Whether the one-level rule lets [categoryId] go under [parentId], read on [CategoryTree]'s
         * tree of [categories] so the rule refuses exactly what the screens show as nested: not when
         * the parent renders under another category, and not when anything renders under
         * [categoryId]. [categoryId] 0 stands for a category not created yet, which holds nothing.
         */
        fun mayNest(categories: List<Category>, categoryId: Long, parentId: Long): Boolean {
            val tree = CategoryTree.of(categories)
            return tree.none { it.category.id == parentId && it.parent != null } &&
                tree.none { it.parent?.id == categoryId }
        }
    }
}

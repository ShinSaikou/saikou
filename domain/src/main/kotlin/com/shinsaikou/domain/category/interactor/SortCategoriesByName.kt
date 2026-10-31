package com.shinsaikou.domain.category.interactor

import com.shinsaikou.domain.category.CategoryTree
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.lang.compareToWithCollator
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository

/**
 * The Categories screen's Sort A to Z: the top-level categories by name, and the categories under
 * each parent by name under it, so the one-level tree ([CategoryTree]) keeps its shape. The result is
 * written as the one flat `sort` in one transaction, as `ReorderCategory` writes a single move, so
 * sync carries it as any other reorder. Names compare as the library's title sort compares titles;
 * equal names keep their id order.
 */
@Inject
class SortCategoriesByName(
    private val categoryRepository: CategoryRepository,
) {

    /** A failed read is an [Result.InternalError] as a failed write is, so the screen never crashes on it. */
    suspend fun await(): Result = try {
        val categories = categoryRepository.getAll().filterNot(Category::isSystemCategory)
        val rows = CategoryTree.of(categories)
        val children = rows.filter { it.parent != null }.groupBy({ it.parent!!.id }, { it.category })
        val ordered = rows.filter { it.parent == null }.map { it.category }.sortedWith(BY_NAME)
            .flatMap { top -> listOf(top) + children[top.id].orEmpty().sortedWith(BY_NAME) }
            .map { it.id }
        if (ordered == categories.map { it.id }) {
            Result.Unchanged
        } else {
            categoryRepository.updateAllOrders(ordered)
            Result.Success
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logcat(LogPriority.ERROR, e)
        Result.InternalError(e)
    }

    sealed interface Result {
        data object Success : Result
        data object Unchanged : Result
        data class InternalError(val error: Throwable) : Result
    }

    private companion object {
        val BY_NAME: Comparator<Category> =
            Comparator<Category> { a, b -> a.name.compareToWithCollator(b.name) }.thenBy { it.id }
    }
}

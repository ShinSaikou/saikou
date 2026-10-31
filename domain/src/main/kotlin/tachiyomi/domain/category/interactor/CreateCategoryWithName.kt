// Vendored from upstream v0.20.4: domain/src/main/java/tachiyomi/domain/category/interactor/CreateCategoryWithName.kt (modified: yes)
package tachiyomi.domain.category.interactor

import com.shinsaikou.domain.category.interactor.SetCategoryParent
import com.shinsaikou.domain.preferences.LibraryPreferences
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository

class CreateCategoryWithName(
    private val categoryRepository: CategoryRepository,
    private val preferences: LibraryPreferences,
) {

    private val initialFlags: Long
        get() {
            val sort = preferences.sortingMode.get()
            return sort.type.flag or sort.direction.flag
        }

    /**
     * Saikou: [parentId] creates the category under that top-level category (phase 5 unit CT), checked
     * by [SetCategoryParent]'s rules: never under the system category or a category this device
     * does not hold, and never under one that is itself under another.
     */
    suspend fun await(name: String, parentId: Long? = null): Result = withNonCancellableContext {
        val categories = categoryRepository.getAll()
        if (parentId != null) {
            if (parentId == Category.UNCATEGORIZED_ID || categories.none { it.id == parentId }) {
                return@withNonCancellableContext Result.IllegalParent
            }
            if (!SetCategoryParent.mayNest(categories, categoryId = 0, parentId = parentId)) {
                return@withNonCancellableContext Result.TooDeep
            }
        }
        val nextOrder = categories.maxOfOrNull { it.order }?.plus(1) ?: 0
        val newCategory = Category(
            id = 0,
            name = name,
            order = nextOrder,
            flags = initialFlags,
            parentId = parentId,
        )

        try {
            categoryRepository.insert(newCategory)
            Result.Success
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            Result.InternalError(e)
        }
    }

    sealed interface Result {
        data object Success : Result
        data class InternalError(val error: Throwable) : Result

        /** Saikou: the parent is the system category or one this device does not hold. */
        data object IllegalParent : Result

        /** Saikou: the parent is itself under another category (one level, plan 2.1). */
        data object TooDeep : Result
    }
}

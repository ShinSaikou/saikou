// Vendored from upstream v0.20.4: domain/src/main/java/tachiyomi/domain/category/interactor/ResetCategoryFlags.kt (modified: yes)
package tachiyomi.domain.category.interactor

import com.shinsaikou.domain.preferences.LibraryPreferences
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.library.model.plus

class ResetCategoryFlags(
    private val preferences: LibraryPreferences,
    private val categoryRepository: CategoryRepository,
) {

    suspend fun await() {
        val sort = preferences.sortingMode.get()
        categoryRepository.updateAllFlags(sort.type + sort.direction)
    }
}

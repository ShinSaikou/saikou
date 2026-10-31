// Vendored from upstream v0.20.4: domain/src/main/java/tachiyomi/domain/category/interactor/SetDisplayMode.kt (modified: yes)
package tachiyomi.domain.category.interactor

import com.shinsaikou.domain.preferences.LibraryPreferences
import tachiyomi.domain.library.model.LibraryDisplayMode

class SetDisplayMode(
    private val preferences: LibraryPreferences,
) {

    fun await(display: LibraryDisplayMode) {
        preferences.displayMode.set(display)
    }
}

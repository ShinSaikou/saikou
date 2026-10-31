// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/domain/source/interactor/SetMigrateSorting.kt (modified: yes)
package eu.kanade.domain.source.interactor

import com.shinsaikou.domain.preferences.SourcePreferences

class SetMigrateSorting(
    private val preferences: SourcePreferences,
) {

    fun await(mode: Mode, direction: Direction) {
        preferences.migrationSortingMode.set(mode)
        preferences.migrationSortingDirection.set(direction)
    }

    enum class Mode {
        ALPHABETICAL,
        TOTAL,
    }

    enum class Direction {
        ASCENDING,
        DESCENDING,
    }
}

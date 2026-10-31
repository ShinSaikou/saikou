// Vendored from upstream v0.20.4: domain/src/main/java/tachiyomi/domain/manga/model/TriState.kt (modified: no)
package tachiyomi.domain.manga.model

import tachiyomi.core.common.preference.TriState

inline fun applyFilter(filter: TriState, predicate: () -> Boolean): Boolean = when (filter) {
    TriState.DISABLED -> true
    TriState.ENABLED_IS -> predicate()
    TriState.ENABLED_NOT -> !predicate()
}

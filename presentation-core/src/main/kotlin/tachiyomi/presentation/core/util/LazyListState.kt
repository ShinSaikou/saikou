// Vendored from upstream v0.20.4: presentation-core/src/main/java/tachiyomi/presentation/core/util/LazyListState.kt (modified: no)
package tachiyomi.presentation.core.util

import androidx.compose.foundation.lazy.LazyListState

fun LazyListState.shouldExpandFAB(): Boolean = lastScrolledBackward || !canScrollForward || !canScrollBackward

// Vendored from upstream v0.20.4: core/common/src/main/kotlin/tachiyomi/core/common/util/lang/BooleanExtensions.kt (modified: no)
package tachiyomi.core.common.util.lang

fun Boolean.toLong() = if (this) 1L else 0L

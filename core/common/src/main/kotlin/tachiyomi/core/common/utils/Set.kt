// Vendored from upstream v0.20.4: core/common/src/main/kotlin/tachiyomi/core/common/utils/Set.kt (modified: yes) package moved to the tachiyomi prefix
package tachiyomi.core.common.utils

fun <T> Set<T>.mutate(action: (MutableSet<T>) -> Unit): Set<T> {
    return toMutableSet().apply(action)
}

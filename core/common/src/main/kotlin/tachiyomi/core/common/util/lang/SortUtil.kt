// Vendored from upstream v0.20.4: core/common/src/main/kotlin/tachiyomi/core/common/util/lang/SortUtil.kt (modified: no)
package tachiyomi.core.common.util.lang

import java.text.Collator
import java.util.Locale

private val collator by lazy {
    val locale = Locale.getDefault()
    Collator.getInstance(locale).apply {
        strength = Collator.PRIMARY
    }
}

fun String.compareToWithCollator(other: String): Int {
    return collator.compare(this, other)
}

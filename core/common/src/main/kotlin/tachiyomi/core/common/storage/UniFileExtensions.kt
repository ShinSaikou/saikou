// Vendored from upstream v0.20.4: core/common/src/main/kotlin/tachiyomi/core/common/storage/UniFileExtensions.kt (modified: no)
package tachiyomi.core.common.storage

import com.hippo.unifile.UniFile

val UniFile.extension: String?
    get() = name?.substringAfterLast('.')

val UniFile.nameWithoutExtension: String?
    get() = name?.substringBeforeLast('.')

val UniFile.displayablePath: String
    get() = filePath ?: uri.toString()

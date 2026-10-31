// Vendored from upstream v0.20.4: core/common/src/main/kotlin/tachiyomi/core/common/extensions/JsonObject.kt (modified: yes) package moved to the tachiyomi prefix
package tachiyomi.core.common.extensions

import kotlinx.serialization.json.JsonObject

val JsonObjectEmpty = JsonObject(emptyMap())

val JsonObjectEmptyBytes = byteArrayOf(0x7B, 0x7D)

val JsonObject.Companion.EMPTY: JsonObject
    inline get() = JsonObjectEmpty

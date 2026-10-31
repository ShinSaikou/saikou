// Vendored from upstream v0.20.4: core/common/src/main/kotlin/tachiyomi/core/common/util/system/LogcatExtensions.kt (modified: no)
package tachiyomi.core.common.util.system

import logcat.LogPriority
import logcat.asLog
import logcat.logcat

inline fun Any.logcat(
    priority: LogPriority = LogPriority.DEBUG,
    throwable: Throwable? = null,
    message: () -> String = { "" },
) = logcat(priority = priority) {
    var msg = message()
    if (throwable != null) {
        if (msg.isNotBlank()) msg += "\n"
        msg += throwable.asLog()
    }
    msg
}

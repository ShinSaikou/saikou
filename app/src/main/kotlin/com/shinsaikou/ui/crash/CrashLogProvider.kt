package com.shinsaikou.ui.crash

import android.content.Context
import androidx.core.content.FileProvider

/**
 * Serves a crash file shared from the crash screen. The app's own provider lives in the main
 * process, so the app a log is shared with would start that process to read it, and when that
 * process dies on every start the read can die with it. This one runs in the crash screen's
 * process (`:crash` in the manifest) and exposes the crash directory only.
 */
class CrashLogProvider : FileProvider() {

    companion object {
        fun authority(context: Context): String = "${context.packageName}.crashlogs"
    }
}

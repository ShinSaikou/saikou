package com.shinsaikou.ui.crash

import android.app.Application
import android.os.Build
import java.io.File

/** Which process this is: the crash screen's runs `SaikouApp` too, and must build nothing there. */
internal object CrashProcess {

    /** The manifest's `android:process` for [CrashActivity] and [CrashLogProvider]. */
    private const val SUFFIX = ":crash"

    fun isCrashProcess(processName: String?, packageName: String): Boolean = processName == packageName + SUFFIX

    fun currentName(): String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Application.getProcessName()
    } else {
        // No API for it before 28; the process's command line starts with its name.
        runCatching { File("/proc/self/cmdline").readText().substringBefore('\u0000').trim() }.getOrNull()
    }
}

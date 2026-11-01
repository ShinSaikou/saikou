package com.shinsaikou.ui.crash

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
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

    /**
     * The app's main process, if one is running beside this one: the system can bring it back behind
     * the crash screen, and an activity started from here would open in it as it is. An app may end
     * its own processes ([Process.killProcess]); the list holds only this app's.
     */
    fun mainProcessIds(context: Context): List<Int> {
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        return activityManager.runningAppProcesses.orEmpty()
            .filter { it.processName == context.packageName && it.pid != Process.myPid() }
            .map { it.pid }
    }
}

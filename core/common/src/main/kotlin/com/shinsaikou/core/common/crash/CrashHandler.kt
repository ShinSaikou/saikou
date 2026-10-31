package com.shinsaikou.core.common.crash

import java.io.File

/**
 * The uncaught exception handler [CrashLog] installs: it writes the crash, starts the crash screen
 * when [screen] allows it, then hands the crash to the handler that was there before, which ends
 * the process as it always did. Each step is guarded on its own, so a write that fails still
 * chains, and a screen that cannot start never keeps the process alive.
 */
internal class CrashHandler(
    private val write: (Thread, Throwable) -> File,
    private val screen: CrashScreenLauncher?,
    private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        val file = runCatching { write(thread, throwable) }.getOrNull()
        // The screen shows and shares a file, so a crash that wrote none starts nothing.
        if (file != null) runCatching { screen?.launch(file) }
        previous?.uncaughtException(thread, throwable)
    }

    companion object {
        fun install(write: (Thread, Throwable) -> File, screen: CrashScreenLauncher?) {
            Thread.setDefaultUncaughtExceptionHandler(
                CrashHandler(write, screen, Thread.getDefaultUncaughtExceptionHandler()),
            )
        }
    }
}

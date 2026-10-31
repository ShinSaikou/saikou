package com.shinsaikou.core.common.crash

import android.content.Context
import java.io.File

/**
 * Starts the crash screen for a crash file the handler has written, and keeps crashes from starting
 * it in a loop. [start] is the app's: it starts the screen's activity, which runs in a process of
 * its own so it still opens when the main process dies on every start.
 *
 * Two rules decide, in [shouldStart]:
 * - A crash in the crash screen's own process starts nothing, so a screen that crashes showing a
 *   crash is not started again to show its own.
 * - A crash less than [WINDOW_MILLIS] after the last start starts nothing. When the system brings
 *   the main process back behind the screen and it dies again at once, the screen already up stays
 *   as it is instead of being started again for every death.
 *
 * The last start is a file, [stamp], since every crash ends its process and the next process has
 * to read it. It holds the wall clock time of the start; one in the future (the clock was set back)
 * holds nothing back. The screen's Restart and Close remove it ([clear]): a crash after the owner
 * asked for a restart is a new one and shows at once.
 */
class CrashScreenLauncher(
    private val inCrashProcess: Boolean,
    private val stamp: File,
    private val now: () -> Long,
    private val start: (File) -> Unit,
) {

    /** Starts the screen for [file] when the rules allow it, and records the start. True if it did. */
    fun launch(file: File): Boolean {
        val time = now()
        if (!shouldStart(inCrashProcess, lastStart(), time)) return false
        start(file)
        runCatching { stamp.writeText(time.toString()) }
        return true
    }

    /** The owner left the screen with Restart or Close; the next crash shows it again at once. */
    fun clear() {
        stamp.delete()
    }

    private fun lastStart(): Long? = runCatching { stamp.readText().trim().toLong() }.getOrNull()

    companion object {
        /** Longer than a start-up crash takes to repeat, short enough that a later crash still shows. */
        const val WINDOW_MILLIS = 10_000L

        private const val STAMP_NAME = "crash-screen-started"

        /** Outside the crash directory, whose every file is listed as a crash. */
        fun stampOf(context: Context): File = File(context.filesDir, STAMP_NAME)

        /** [clear], for the crash screen's Restart and Close, which hold no launcher. */
        fun clear(context: Context) {
            stampOf(context).delete()
        }

        fun shouldStart(inCrashProcess: Boolean, lastStart: Long?, now: Long): Boolean = when {
            inCrashProcess -> false
            lastStart == null -> true
            else -> now - lastStart !in 0 until WINDOW_MILLIS
        }
    }
}

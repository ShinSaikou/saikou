package com.shinsaikou.core.common.crash

import android.content.Context
import java.io.File

/**
 * How this process started: as usual, or without extensions, the crash screen's way out when an
 * extension takes the app down at every start.
 *
 * The screen leaves a flag file ([requestWithoutExtensions]) and `SaikouApp` takes it when the main
 * process starts ([take]), before the graph exists, so exactly one process goes without: the start
 * after it loads extensions again. For that process the extension manager loads none, while still
 * reporting itself initialized so nothing waiting on the sources hangs; library updates and
 * downloads wait for a start with extensions, since every entry of an extension's source would
 * otherwise fail as not installed; and the main screen says extensions are off.
 *
 * [afterWithoutExtensions] marks the first start with extensions after one without, which picks up
 * what waited: the download worker handed its runs back to WorkManager, whose backoff grew with
 * every one, and would otherwise hold the queue back as if the network had failed.
 */
class StartMode(val withoutExtensions: Boolean, val afterWithoutExtensions: Boolean = false) {

    /** The two files [take] reads, in the app's files directory. */
    class Files(val flag: File, val after: File)

    companion object {
        val NORMAL = StartMode(withoutExtensions = false)

        /** Outside the crash directory, whose every file is listed as a crash. */
        private const val FLAG_NAME = "start-without-extensions"
        private const val AFTER_NAME = "started-without-extensions"

        fun filesOf(context: Context): Files =
            Files(flag = File(context.filesDir, FLAG_NAME), after = File(context.filesDir, AFTER_NAME))

        /** The crash screen's choice: the next start of the main process goes without extensions. */
        fun requestWithoutExtensions(context: Context) {
            filesOf(context).flag.writeText("")
        }

        /**
         * This start's mode. Each file goes as it is read: a flag that could not be removed would
         * turn extensions off at every later start too, so it counts only once it is gone. A start
         * without extensions leaves the second file for the next start with them.
         */
        fun take(files: Files): StartMode {
            if (files.flag.delete()) {
                runCatching { files.after.writeText("") }
                return StartMode(withoutExtensions = true)
            }
            return StartMode(withoutExtensions = false, afterWithoutExtensions = files.after.delete())
        }
    }
}

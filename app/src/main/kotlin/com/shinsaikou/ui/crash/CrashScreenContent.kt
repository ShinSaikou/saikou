package com.shinsaikou.ui.crash

import java.io.File

/**
 * What the crash screen shows, decided from what it is given: the crash directory, and the name of
 * the file the handler wrote, from the intent. Only a plain name of a file in that directory is
 * read, so the screen can never show or share anything else in the app's storage.
 */
internal sealed interface CrashScreenContent {

    /** The crash log: its text, and the file Share hands on. */
    data class Log(val file: File, val text: String) : CrashScreenContent

    /** No name, a name that is not a plain file name, no such file, or a read that failed. */
    data object Unreadable : CrashScreenContent

    companion object {
        fun load(directory: File, name: String?): CrashScreenContent {
            if (name.isNullOrBlank() || name != File(name).name || name == "." || name == "..") return Unreadable
            val file = File(directory, name)
            val text = runCatching { file.readText() }.getOrNull() ?: return Unreadable
            return Log(file, text)
        }
    }
}

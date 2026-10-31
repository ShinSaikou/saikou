package com.shinsaikou.core.common.crash

import android.content.Context
import android.os.Build
import com.shinsaikou.core.common.preferences.BasePreferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.io.File
import java.io.FileOutputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Local crash reporting: an uncaught exception handler that writes one text file per crash into
 * app private storage, and the reader the settings screen exports them with. Nothing leaves the
 * device on its own.
 */
@SingleIn(AppScope::class)
@Inject
class CrashLog(
    private val context: Context,
    private val basePreferences: BasePreferences,
) {

    val directory: File get() = directoryOf(context)

    /**
     * Set by the app once the extension manager has a snapshot to report. Each entry is one line;
     * it never carries a name or a URL, only a package, a version and a library version.
     */
    var extensionSnapshot: () -> List<String> = { emptyList() }

    @Volatile
    private var header: String = ""
    private var installed = false

    /**
     * Installs the handler once; chains to the previous handler so the process still dies. With a
     * [screen], a crash also starts the crash screen once its file is written.
     */
    fun install(appVersionName: String, appVersionCode: Int, screen: CrashScreenLauncher? = null) {
        if (installed) return
        installed = true
        setAppVersion(appVersionName, appVersionCode)
        CrashHandler.install(::write, screen)
    }

    /** The header every file written from now on carries. [install] calls this. */
    fun setAppVersion(appVersionName: String, appVersionCode: Int) {
        header = header(appVersionName, appVersionCode)
    }

    /**
     * Synchronous and allocation light: this runs while the process is dying, possibly out of
     * memory, so it uses one builder and a plain stream and never touches the graph. Returns the
     * file it wrote, which the crash screen is started with.
     */
    fun write(thread: Thread, throwable: Throwable): File {
        val extensions = runCatching { extensionSnapshot() }.getOrDefault(emptyList())
        return writeFile(directory, header, extensions, thread, throwable)
    }

    /** Newest first. */
    fun list(): List<File> = listIn(directory)

    fun latest(): File? = list().firstOrNull()

    /**
     * True while the newest crash is newer than both the last export and the last time the crash
     * screen showed a log ([markShownOnCrashScreen]): a log the owner has read there is no more
     * news on the next launch than one read on the crash log screen (ruling R80).
     */
    fun hasUnexportedCrash(): Boolean {
        val latest = latest() ?: return false
        val settled = maxOf(basePreferences.lastCrashLogExport.get(), shownOnCrashScreenAt(context))
        return latest.lastModified() > settled
    }

    fun markExported() {
        basePreferences.lastCrashLogExport.set(System.currentTimeMillis())
    }

    fun clear() {
        list().forEach { it.delete() }
    }

    companion object {
        const val MAX_FILES = 10
        private const val DIRECTORY_NAME = "crash"
        private const val SHOWN_MARKER_NAME = "crash-shown"
        private const val INITIAL_CAPACITY = 8 * 1024
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

        /** Where the crash files are, for the crash screen, which has no graph to ask. */
        fun directoryOf(context: Context): File = File(context.filesDir, DIRECTORY_NAME).apply { mkdirs() }

        /**
         * The handler for the crash screen's own process, which builds no graph and so has no
         * [CrashLog]. A crash there is written as [write] writes one, without the extension list,
         * and [screen] starts nothing from that process.
         */
        fun installWithoutGraph(
            context: Context,
            appVersionName: String,
            appVersionCode: Int,
            screen: CrashScreenLauncher,
        ) {
            val directory = directoryOf(context)
            val header = header(appVersionName, appVersionCode)
            CrashHandler.install({ thread, throwable -> writeFile(directory, header, emptyList(), thread, throwable) }, screen)
        }

        /**
         * Settles the banner from the crash screen once it shows a log. A file of its own rather than
         * [BasePreferences.lastCrashLogExport]: the screen runs in another process, where opening the
         * preferences is what it must not do, and a preference written there can be overwritten by
         * the main process's cached copy.
         */
        fun markShownOnCrashScreen(context: Context) {
            File(context.filesDir, SHOWN_MARKER_NAME).writeText(System.currentTimeMillis().toString())
        }

        private fun shownOnCrashScreenAt(context: Context): Long =
            runCatching { File(context.filesDir, SHOWN_MARKER_NAME).readText().trim().toLong() }.getOrDefault(0L)

        private fun header(appVersionName: String, appVersionCode: Int): String = buildString {
            appendLine("Saikou $appVersionName ($appVersionCode)")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL}")
        }

        private fun writeFile(
            directory: File,
            header: String,
            extensions: List<String>,
            thread: Thread,
            throwable: Throwable,
        ): File {
            val builder = StringBuilder(INITIAL_CAPACITY)
            builder.append(header)
            builder.append("Thread: ").append(thread.name).append('\n')
            builder.append(TIME_FORMAT.format(ZonedDateTime.now())).append('\n')
            if (extensions.isNotEmpty()) {
                builder.append("Installed extensions:\n")
                extensions.forEach { builder.append(it).append('\n') }
            }
            builder.append('\n')
            builder.append(throwable.stackTraceToString())

            val file = File(directory, "crash-${System.currentTimeMillis()}.txt")
            FileOutputStream(file).use { it.write(builder.toString().toByteArray()) }
            listIn(directory).drop(MAX_FILES).forEach { it.delete() }
            return file
        }

        private fun listIn(directory: File): List<File> = directory.listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }
}

package com.shinsaikou.ui.crash

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.shinsaikou.core.common.crash.CrashLog
import com.shinsaikou.core.common.crash.CrashScreenLauncher
import com.shinsaikou.nightModeOf
import com.shinsaikou.ui.main.MainActivity
import eu.kanade.presentation.theme.SaikouTheme
import eu.kanade.tachiyomi.util.view.setSecureScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The crash screen: the crash the handler has just written, with Share, Restart and Close.
 *
 * It runs in a process of its own (`:crash` in the manifest), so it still opens when the main
 * process dies on every start, and `SaikouApp` builds no graph there. Nothing here reads the
 * database, the preferences, an extension or the network: the file name and the look arrive in
 * the intent, and the file is read from the crash directory. Share hands the file on through
 * [CrashLogProvider], which runs in this process for the same reason. Nothing leaves the device
 * unless the owner taps Share.
 */
class CrashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val look = CrashScreenLook.of(
            themeMode = intent.getStringExtra(EXTRA_THEME_MODE),
            appTheme = intent.getStringExtra(EXTRA_APP_THEME),
            amoled = intent.getBooleanExtra(EXTRA_AMOLED, false),
            secure = intent.getBooleanExtra(EXTRA_SECURE, false),
        )
        // Before super.onCreate, so the window comes up in the app's day or night from its first frame.
        delegate.localNightMode = nightModeOf(look.themeMode)
        super.onCreate(savedInstanceState)
        window.setSecureScreen(look.secure)
        onBackPressedDispatcher.addCallback(this) { close() }

        val name = intent.getStringExtra(EXTRA_FILE_NAME)
        setContent {
            SaikouTheme(appTheme = look.appTheme, amoled = look.amoled, themeMode = look.themeMode) {
                // kotlinx's IO dispatcher: the graph's, which tests substitute, does not exist here.
                val content by produceState<CrashScreenContent?>(null) {
                    value = withContext(Dispatchers.IO) { load(name) }
                }
                CrashScreen(
                    content = content,
                    onShare = { file -> CrashLogExport.share(this, file, CrashLogProvider.authority(this)) },
                    onRestart = ::restart,
                    onClose = ::close,
                )
            }
        }
    }

    /** Showing a log settles the launch banner, as reading one on the crash log screen does (R80). */
    private fun load(name: String?): CrashScreenContent {
        val content = CrashScreenContent.load(CrashLog.directoryOf(this), name)
        if (content is CrashScreenContent.Log) runCatching { CrashLog.markShownOnCrashScreen(this) }
        return content
    }

    /** The owner asked for the app again: the next crash, if any, shows this screen at once. */
    private fun restart() {
        CrashScreenLauncher.clear(this)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        finish()
    }

    /** Close and Back: the task this screen took over goes too, and the next crash shows it again. */
    private fun close() {
        CrashScreenLauncher.clear(this)
        finishAndRemoveTask()
    }

    companion object {
        private const val EXTRA_FILE_NAME = "com.shinsaikou.crash.FILE_NAME"
        private const val EXTRA_THEME_MODE = "com.shinsaikou.crash.THEME_MODE"
        private const val EXTRA_APP_THEME = "com.shinsaikou.crash.APP_THEME"
        private const val EXTRA_AMOLED = "com.shinsaikou.crash.AMOLED"
        private const val EXTRA_SECURE = "com.shinsaikou.crash.SECURE"

        /**
         * What the handler starts. A new task that clears the app's own, so the screen replaces
         * whatever the crashed process showed and nothing of it is left for the system to bring
         * back. The file goes by name; the screen reads it from the crash directory.
         */
        internal fun intent(context: Context, file: File, look: CrashScreenLook): Intent =
            Intent(context, CrashActivity::class.java)
                .putExtra(EXTRA_FILE_NAME, file.name)
                .putExtra(EXTRA_THEME_MODE, look.themeMode.name)
                .putExtra(EXTRA_APP_THEME, look.appTheme.name)
                .putExtra(EXTRA_AMOLED, look.amoled)
                .putExtra(EXTRA_SECURE, look.secure)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

        /**
         * The launcher `SaikouApp` hands the crash handler. [look] is read when a crash happens, in
         * the process that crashed; if reading it fails as well, the screen takes the defaults.
         */
        internal fun launcher(
            application: Application,
            inCrashProcess: Boolean,
            look: () -> CrashScreenLook,
        ): CrashScreenLauncher = CrashScreenLauncher(
            inCrashProcess = inCrashProcess,
            stamp = CrashScreenLauncher.stampOf(application),
            now = System::currentTimeMillis,
            start = { file ->
                val resolved = runCatching { look() }.getOrDefault(CrashScreenLook())
                application.startActivity(intent(application, file, resolved))
            },
        )
    }
}

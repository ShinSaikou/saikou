package com.shinsaikou

import android.app.Application
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.shinsaikou.core.common.crash.CrashLog
import com.shinsaikou.core.common.crash.StartMode
import com.shinsaikou.di.AppGraph
import com.shinsaikou.domain.preferences.ThemeMode
import com.shinsaikou.image.GLUtil
import com.shinsaikou.image.ImageUtil
import com.shinsaikou.migration.MigrationFlagsUpgrade
import com.shinsaikou.reader.di.ReaderGraph
import com.shinsaikou.reader.di.ReaderGraphOwner
import com.shinsaikou.security.AppLock
import com.shinsaikou.ui.crash.CrashActivity
import com.shinsaikou.ui.crash.CrashProcess
import com.shinsaikou.ui.crash.CrashScreenLook
import dev.zacsweers.metro.createGraphFactory
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.WebViewUtil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import logcat.AndroidLogcatLogger
import logcat.LogPriority
import logcat.LogcatLogger
import org.conscrypt.Conscrypt
import tachiyomi.core.common.util.system.logcat
import java.security.Security
import kotlin.time.Clock

/**
 * The process. It owns the graph, the startup order, and the process-wide settings that have to be
 * written before anything logs, themes itself or decodes an image. In the crash screen's process
 * (`:crash`) it installs the crash handler and nothing else, and [graph] is never set.
 *
 * [ReaderGraphOwner] is how `ReaderActivity` reaches the graph: the merged [AppGraph] implements
 * `ReaderGraph.Factory` because `:reader` contributes that factory to `AppScope`, so `:reader`
 * never imports this class (ruling R22).
 */
class SaikouApp : Application(), Configuration.Provider, SingletonImageLoader.Factory, ReaderGraphOwner {

    lateinit var graph: AppGraph
        private set

    /**
     * Completes once the start-up's change log prune has run, pruned or not, failed or not, so a
     * device test that writes and reads the log waits for it instead of racing it (phase 3 unit I).
     * It completes as well when the prune skips (a backend is set, or a sync run holds the engine's
     * lock and the log keeps its rows), so a wait on it always ends, and its completion never means
     * the log was emptied.
     */
    val changeLogPruned: CompletableDeferred<Unit> = CompletableDeferred()

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(graph.workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.INFO)
            .build()

    override val readerGraphFactory: ReaderGraph.Factory get() = graph

    override fun newImageLoader(context: PlatformContext): ImageLoader = graph.imageLoader

    /**
     * Upstream's answer to `X-Requested-With`. Chromium sends the embedding app's package name with
     * every WebView request, which tells a site's challenge that the page is not in a browser, so
     * Chromium alone is given an installed browser's name instead. Only the calling thread's stack
     * is read, so nothing else in the process is ever told a false name.
     */
    override fun getPackageName(): String {
        try {
            if (isChromiumPackageNameCall(Thread.currentThread().stackTrace)) {
                return WebViewUtil.spoofedPackageName(applicationContext)
            }
        } catch (_: Exception) {
        }
        return super.getPackageName()
    }

    override fun onCreate() {
        super.onCreate()
        if (CrashProcess.isCrashProcess(CrashProcess.currentName(), packageName)) {
            // The crash screen's process. Whatever broke the main process may be in the graph, so
            // this one builds none and starts nothing; a crash here is still written, and starts no
            // second screen.
            CrashLog.installWithoutGraph(
                this,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
                CrashActivity.launcher(this, inCrashProcess = true) { CrashScreenLook() },
            )
            return
        }
        // Taken before the graph exists, so the crash screen's "Start without extensions" applies
        // to this process alone: one or two small file operations on the main thread.
        val startMode = StartMode.take(StartMode.filesOf(this))
        graph = createGraphFactory<AppGraph.Factory>().create(this, startMode)

        // First, so a crash in anything below is still written to a file and shown on the crash
        // screen.
        graph.crashLog.install(
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
            CrashActivity.launcher(this, inCrashProcess = false, look = ::crashScreenLook),
        )

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        }
        installLogging()
        applyThemeMode()
        seedHardwareBitmapThreshold()
        seedPreferredBitmapConfig()
        // Before any screen reads the migration options; two reads of the already loaded preference
        // file, and on the one start that changes anything, one write (unit migrate-downloads).
        runCatching { MigrationFlagsUpgrade.run(graph.sourcePreferences) }
            .onFailure { logcat(LogPriority.ERROR, it) { "The migration options upgrade failed" } }

        // Sync's lifecycle triggers (phase 3 unit G). Main thread, as ProcessLifecycleOwner requires;
        // nothing they need is opened until one fires.
        graph.syncTriggers.install(this)

        // Upstream's app lock and secure screen (phase 4 unit AL): the lock timer on the process
        // lifecycle, the unlock check and the window flag on every activity. Main thread, beside the
        // sync triggers, for the same reason.
        graph.appLock.install(this)

        // The in-app updater's check when the app comes to the foreground, at most once a day and
        // only in a release build. Main thread, for the same reason; the check itself runs on IO.
        graph.appUpdateTriggers.install()

        graph.crashLog.extensionSnapshot = {
            graph.extensionManager.installedExtensions.value
                .map { "${it.packageName} ${it.versionCode} lib ${it.libVersion}" }
        }

        graph.appScope.launch(graph.dispatchers.io) {
            // Touching these two builds the OkHttp client and opens the database off the main
            // thread; every later caller then finds them warm.
            graph.networkHelper
            graph.database
            runCatching { graph.databaseInitializer.initialize() }
                .onFailure { logcat(LogPriority.ERROR, it) { "The database start-up sweep failed" } }
            // With no sync backend configured the change log has no reader, and the next first push
            // is a full export, so the whole log goes (phase 3 unit E2).
            runCatching { graph.syncEngine.pruneLogWithoutBackend() }
                .onFailure { logcat(LogPriority.ERROR, it) { "The sync change log prune failed" } }
            changeLogPruned.complete(Unit)
            // The periodic sync, and a run now when the last success is old (Q10). Sync does not wait
            // for the source registry, so this comes before the extensions start.
            runCatching { graph.syncTriggers.onAppStart(Clock.System.now().toEpochMilliseconds()) }
                .onFailure { logcat(LogPriority.ERROR, it) { "The sync start-up trigger failed" } }

            // Before start(): the loader refuses every extension with INJEKT_NOT_READY until the
            // singletons extension bytecode resolves by name are registered. A start without
            // extensions still calls start(), which then lists them without loading any.
            graph.injektBootstrap.install()
            graph.extensionManager.start()
            graph.sourceManager

            graph.backupScheduler.setup()
            graph.libraryUpdateScheduler.setup()
            graph.downloadScheduler.followNetworkSetting(graph.appScope)
            if (startMode.afterWithoutExtensions) {
                runCatching { graph.downloadScheduler.resumeAfterWithoutExtensions() }
                    .onFailure { logcat(LogPriority.ERROR, it) { "Could not resume the download queue" } }
            }

            // Re-match orphan manifest rows and drop rows whose folder is gone (phase 2 Q7). The
            // download worker runs the same sweep when it starts.
            runCatching { graph.downloadManifestSweep.run() }
                .onFailure { logcat(LogPriority.ERROR, it) { "The download manifest sweep failed" } }
        }
    }

    /**
     * One logger for the life of the process, reading its threshold live. Installing a second
     * logger when the verbose preference is toggled would not work: `LogcatLogger.uninstall` does
     * not drop the loggers it installed, so every toggle would duplicate every line.
     */
    private fun installLogging() {
        val verbose = graph.networkPreferences.verboseLogging
        LogcatLogger.install()
        LogcatLogger.loggers += ThresholdLogcatLogger {
            when {
                verbose.get() -> LogPriority.VERBOSE
                BuildConfig.DEBUG -> LogPriority.DEBUG
                else -> LogPriority.INFO
            }
        }
    }

    private fun applyThemeMode() {
        val themeMode = graph.uiPreferences.themeMode
        fun apply(mode: ThemeMode) {
            AppCompatDelegate.setDefaultNightMode(nightModeOf(mode))
        }
        apply(themeMode.get())
        themeMode.changes()
            .onEach { graph.appScope.launch(graph.dispatchers.main) { apply(it) } }
            .launchIn(graph.appScope)
    }

    /**
     * The crash screen's look, read when a crash happens rather than followed, so nothing runs for
     * it until then. The launcher falls back to the defaults if this read fails too.
     */
    private fun crashScreenLook(): CrashScreenLook {
        val ui = graph.uiPreferences
        return CrashScreenLook(
            themeMode = ui.themeMode.get(),
            appTheme = ui.appTheme.get(),
            amoled = ui.themeDarkAmoled.get(),
            secure = AppLock.secureScreenOn(
                graph.securityPreferences.secureScreen.get(),
                graph.basePreferences.incognitoMode.get(),
            ),
        )
    }

    /**
     * The first launch learns the device's real texture limit; after that the preference is the
     * owner's, and [ImageUtil] mirrors whatever it holds.
     */
    private fun seedHardwareBitmapThreshold() {
        val threshold = graph.basePreferences.hardwareBitmapThreshold
        graph.appScope.launch(graph.dispatchers.default) {
            if (!threshold.isSet()) {
                threshold.set(GLUtil.DEVICE_TEXTURE_LIMIT)
            }
            ImageUtil.hardwareBitmapThreshold = threshold.get()
        }
        threshold.changes()
            .onEach { ImageUtil.hardwareBitmapThreshold = it }
            .launchIn(graph.appScope)
    }

    /**
     * Set once, here, and never per activity: a write from an activity races the decode threads of
     * a viewer that is still tearing down.
     */
    private fun seedPreferredBitmapConfig() {
        // The Java field is private with public static accessors, so this is the setter call,
        // not a synthetic property assignment.
        SubsamplingScaleImageView.setPreferredBitmapConfig(
            if (DeviceUtil.isLowRamDevice(this)) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888,
        )
    }
}

/** Whether [stack] is Chromium reading the package name it puts in `X-Requested-With`. */
internal fun isChromiumPackageNameCall(stack: Array<StackTraceElement>): Boolean = stack.any { frame ->
    frame.className.lowercase() in CHROMIUM_BUILD_INFO_CLASSES &&
        frame.methodName.lowercase() in CHROMIUM_BUILD_INFO_METHODS
}

private val CHROMIUM_BUILD_INFO_CLASSES = setOf("org.chromium.base.buildinfo", "org.chromium.base.apkinfo")
private val CHROMIUM_BUILD_INFO_METHODS = setOf("getall", "getpackagename", "<init>")

/** AppCompat's night mode for a [ThemeMode]: the app's, and the crash screen's from its intent. */
internal fun nightModeOf(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
    ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
}

/**
 * An [AndroidLogcatLogger] whose minimum priority is a function rather than a constructor argument,
 * so the verbose-logging preference takes effect without reinstalling anything.
 */
private class ThresholdLogcatLogger(private val minPriority: () -> LogPriority) : LogcatLogger {

    private val delegate = AndroidLogcatLogger(LogPriority.VERBOSE)

    override fun isLoggable(priority: LogPriority, tag: String): Boolean =
        priority.priorityInt >= minPriority().priorityInt

    override fun log(priority: LogPriority, tag: String, message: String) =
        delegate.log(priority, tag, message)
}

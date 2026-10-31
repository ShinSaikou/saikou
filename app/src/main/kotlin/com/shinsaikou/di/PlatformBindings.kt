package com.shinsaikou.di

import android.app.Application
import android.content.Context
import android.os.Trace
import androidx.preference.PreferenceManager
import androidx.work.WorkManager
import com.shinsaikou.BuildConfig
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.core.common.network.CloudflareSolveCoordinator
import com.shinsaikou.core.common.network.NetworkUserMessages
import com.shinsaikou.domain.preferences.SyncRestoreFlagWriter
import com.shinsaikou.downloads.PendingChapterDeleteWriter
import com.shinsaikou.sync.CommittingSecretWriter
import com.shinsaikou.sync.SecretWriter
import com.shinsaikou.sync.webdav.WebDavUrlPolicy
import com.shinsaikou.tracking.DelayedTrackingWriter
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import java.io.IOException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.time.TimeSource

/**
 * Everything the app graph needs that no module can annotate for itself: the platform singletons,
 * the three dispatchers, the clock, and the vendored `:core:common` classes that carry no Metro
 * annotations because they are upstream files.
 *
 * Contract 08 section 1.4 is the shape. Two of its providers are gone: `ProtoBuf` is already
 * contributed by `:backup`'s `BackupBindings`, and `NotificationManagerCompat` has no injection
 * point, because both notifiers build their own from a `Context` and own their channels. One is
 * added: `NetworkPreferences` does need a provider, because its `verboseLogging` default is
 * `BuildConfig.DEBUG` and `:core:common` has no `BuildConfig`. So does sync's `WebDavUrlPolicy`,
 * for the same reason.
 */
@BindingContainer
@ContributesTo(AppScope::class)
object PlatformBindings {

    @Provides
    fun provideContext(application: Application): Context = application

    @Provides
    @SingleIn(AppScope::class)
    fun provideDispatchers(): Dispatchers = Dispatchers(
        io = kotlinx.coroutines.Dispatchers.IO,
        default = kotlinx.coroutines.Dispatchers.Default,
        main = kotlinx.coroutines.Dispatchers.Main.immediate,
    )

    /**
     * The clock that measures elapsed time, for the same reason as the dispatchers: a test hands in
     * its scheduler's virtual time. The library update pass reads each source's completion rate
     * from it.
     */
    @Provides
    fun provideTimeSource(): TimeSource = TimeSource.Monotonic

    /**
     * The wall clock, for the same reason: the Google client dates a token's expiry from it, and its
     * tests hand in a fixed one. The debug build adds [ClockOffsetSetting]'s days, which only an
     * instrumentation test sets; a release build never reads it ([clockFor]).
     */
    @Provides
    fun provideClock(offset: Lazy<ClockOffsetSetting>): Clock = clockFor(debug = BuildConfig.DEBUG) { offset.value.days }

    /**
     * `Clock.System` in a release build, without calling [offsetDays]; in a debug build, a clock
     * that reads [offsetDays] on every [Clock.now], so a test may set it after the graph built its
     * users.
     */
    internal fun clockFor(debug: Boolean, offsetDays: () -> Int): Clock =
        if (debug) OffsetClock(offsetDays) else Clock.System

    private class OffsetClock(private val offsetDays: () -> Int) : Clock {
        override fun now(): Instant = Clock.System.now() + offsetDays().days
    }

    /** The app-wide scope handed to ExtensionManager, DefaultSourceManager and the update pass. */
    @Provides
    @SingleIn(AppScope::class)
    fun provideAppScope(dispatchers: Dispatchers): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatchers.default + CoroutineName("saikou-app"))

    @Provides
    @SingleIn(AppScope::class)
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Provides
    @SingleIn(AppScope::class)
    fun providePreferenceStore(context: Context): PreferenceStore = AndroidPreferenceStore(context)

    /**
     * The secret store's writes, each committed to the file [providePreferenceStore] reads before it
     * returns (phase 3 unit O fix round 1 item 5); every other preference keeps `apply()`.
     */
    @Provides
    fun provideSecretWriter(context: Context): SecretWriter =
        CommittingSecretWriter(PreferenceManager.getDefaultSharedPreferences(context))

    /**
     * The tracker retry store's writes (phase 3 unit T1), committed the same way and to the same
     * file, so a push recorded for a finished chapter survives a kill right after it. Only the
     * store writes this way; its callers are already off the main thread.
     */
    @Provides
    fun provideDelayedTrackingWriter(context: Context): DelayedTrackingWriter =
        DelayedTrackingWriter(CommittingSecretWriter(PreferenceManager.getDefaultSharedPreferences(context))::write)

    /**
     * The chapters staged for deletion after reading (phase 3 unit DR), committed the same way and
     * to the same file, so a chapter finished right before a kill is still deleted at the next close.
     * Its caller is already off the main thread.
     */
    @Provides
    fun providePendingChapterDeleteWriter(context: Context): PendingChapterDeleteWriter =
        PendingChapterDeleteWriter(CommittingSecretWriter(PreferenceManager.getDefaultSharedPreferences(context))::write)

    /**
     * The restore's sync flag (phase 5 unit SC3), committed to the file [providePreferenceStore]
     * reads before the restore returns, so a process killed right after a restore still makes the
     * next sync's full export. Its caller, the restore, is already off the main thread.
     */
    @Provides
    fun provideSyncRestoreFlagWriter(context: Context): SyncRestoreFlagWriter {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        return SyncRestoreFlagWriter { key, value ->
            if (!preferences.edit().putBoolean(key, value).commit()) {
                throw IOException("The preference file did not take the write of $key")
            }
        }
    }

    @Provides
    @SingleIn(AppScope::class)
    fun provideNetworkPreferences(store: PreferenceStore): NetworkPreferences =
        NetworkPreferences(store, BuildConfig.DEBUG)

    /**
     * The WebDAV addresses sync signs in to: `https://` in every build, and in a debug build also
     * `http://10.0.2.2`, where an emulator reaches the sync fixture (phase 3 unit F).
     */
    @Provides
    fun provideWebDavUrlPolicy(): WebDavUrlPolicy = WebDavUrlPolicy.forBuild(debug = BuildConfig.DEBUG)

    /**
     * The one place the application builds its OkHttp client: `NetworkHelper`'s constructor
     * assembles the builder, the disk cache and the interceptors and calls `build()`. Upstream's #3703
     * was this work landing on the main thread at start-up, so it runs inside a trace section that
     * `tools/bench/perfetto_metrics.py --metric main_thread_pm_okhttp_slices` counts when it
     * appears on the main thread. The section is product observability, compiled into every build
     * and one JNI call when nothing is tracing, so it is not a code path a test owns (R39).
     */
    @Provides
    @SingleIn(AppScope::class)
    fun provideNetworkHelper(
        context: Context,
        networkPreferences: NetworkPreferences,
        cloudflareSolveCoordinator: CloudflareSolveCoordinator,
        networkUserMessages: NetworkUserMessages,
    ): NetworkHelper {
        Trace.beginSection(SECTION_OKHTTP_CLIENT_BUILD)
        try {
            return NetworkHelper(context, networkPreferences, cloudflareSolveCoordinator, networkUserMessages)
        } finally {
            Trace.endSection()
        }
    }

    @Provides
    @SingleIn(AppScope::class)
    fun provideJavaScriptEngine(context: Context): JavaScriptEngine = JavaScriptEngine(context)

    @Provides
    @SingleIn(AppScope::class)
    fun provideWorkManager(context: Context): WorkManager = WorkManager.getInstance(context)

    /** The slice name the cold start trace query matches with `OkHttpClient*`. */
    private const val SECTION_OKHTTP_CLIENT_BUILD = "OkHttpClient.build"
}

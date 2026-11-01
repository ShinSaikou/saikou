package com.shinsaikou.ui.main

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.core.common.crash.CrashLog
import com.shinsaikou.core.common.crash.StartMode
import com.shinsaikou.core.common.network.CloudflareSolveCoordinator
import com.shinsaikou.core.common.network.CloudflareSolveCoordinator.InFlightSolve
import com.shinsaikou.core.common.preferences.BasePreferences
import com.shinsaikou.domain.preferences.AppTheme
import com.shinsaikou.domain.preferences.ExtensionsPreferences
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.TabletUiMode
import com.shinsaikou.domain.preferences.ThemeMode
import com.shinsaikou.domain.preferences.UiPreferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MainUiState(
    val appTheme: AppTheme = AppTheme.DEFAULT,
    val amoled: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val tabletUiMode: TabletUiMode = TabletUiMode.AUTOMATIC,
    val incognito: Boolean = false,
    val downloadedOnly: Boolean = false,
    val updatesBadge: Int = 0,
    val extensionsBadge: Int = 0,
    val showBatteryDialog: Boolean = false,
    val showCrashBanner: Boolean = false,
    val crashLogPath: String? = null,
    /** Every browser check running now, keyed by host. The banner above the tabs reads it. */
    val solves: Map<String, InFlightSolve> = emptyMap(),
    /** This process started without extensions, from the crash screen; a banner says so until a restart. */
    val extensionsOff: Boolean = false,
)

/**
 * Process-wide preferences projected into the main activity's Compose tree, together with the
 * browser checks the application's one [CloudflareSolveCoordinator] has in flight. Those come from
 * any request in the process, a background library update included, and are shown whatever tab is
 * open: a slow update is worth a line of explanation on every screen of this activity.
 *
 * Deliberately not `@SingleIn(AppScope::class)`: a `ViewModel` scoped to the graph would outlive
 * the activity that cleared it, and `viewModelScope` is cancelled by `onCleared`. The activity's
 * `ViewModelStore` is the only thing that should decide how long this lives.
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(MainViewModel::class)
@Inject
class MainViewModel(
    private val basePreferences: BasePreferences,
    private val libraryPreferences: LibraryPreferences,
    private val extensionsPreferences: ExtensionsPreferences,
    private val crashLog: CrashLog,
    private val batteryExemption: BatteryExemption,
    private val dispatchers: Dispatchers,
    uiPreferences: UiPreferences,
    solveCoordinator: CloudflareSolveCoordinator,
    startMode: StartMode,
) : ViewModel() {

    private val themeState: Flow<ThemeState> = combine(
        uiPreferences.appTheme.changes().withInitial(uiPreferences.appTheme.get()),
        uiPreferences.themeDarkAmoled.changes().withInitial(uiPreferences.themeDarkAmoled.get()),
        uiPreferences.themeMode.changes().withInitial(uiPreferences.themeMode.get()),
        uiPreferences.tabletUiMode.changes().withInitial(uiPreferences.tabletUiMode.get()),
    ) { appTheme, amoled, themeMode, tabletUiMode ->
        ThemeState(appTheme, amoled, themeMode, tabletUiMode)
    }

    private val activityState: Flow<ActivityState> = combine(
        basePreferences.incognitoMode.changes().withInitial(basePreferences.incognitoMode.get()),
        basePreferences.downloadedOnly.changes().withInitial(basePreferences.downloadedOnly.get()),
        libraryPreferences.newUpdatesCount.changes().withInitial(libraryPreferences.newUpdatesCount.get()),
        extensionsPreferences.extensionUpdatesCount.changes().withInitial(extensionsPreferences.extensionUpdatesCount.get()),
    ) { incognito, downloadedOnly, updatesBadge, extensionsBadge ->
        ActivityState(incognito, downloadedOnly, updatesBadge, extensionsBadge)
    }

    /**
     * What this launch found on disk, answered once rather than followed. Driving the dialog off
     * `batteryExemptionRequested.changes()` would show it on the frame before the exemption check
     * has answered, which is a prompt the owner sees flash on an app that needed nothing.
     */
    private val firstLaunch = MutableStateFlow(FirstLaunchState())

    val state: StateFlow<MainUiState> = combine(
        themeState,
        activityState,
        firstLaunch,
        solveCoordinator.inFlight,
    ) { theme, activity, launch, solves ->
        MainUiState(
            appTheme = theme.appTheme,
            amoled = theme.amoled,
            themeMode = theme.themeMode,
            tabletUiMode = theme.tabletUiMode,
            incognito = activity.incognito,
            downloadedOnly = activity.downloadedOnly,
            updatesBadge = activity.updatesBadge,
            extensionsBadge = activity.extensionsBadge,
            showBatteryDialog = launch.showBatteryDialog,
            showCrashBanner = launch.showCrashBanner,
            crashLogPath = launch.crashLogPath,
            solves = solves,
            extensionsOff = startMode.withoutExtensions,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        // From the first frame, so the banner does not arrive a frame after the screen it sits above.
        MainUiState(extensionsOff = startMode.withoutExtensions),
    )

    init {
        viewModelScope.launch {
            val next = withContext(dispatchers.io) {
                val requested = basePreferences.batteryExemptionRequested.get()
                // Already exempt means never ask, and never ask again: the preference is set anyway.
                val exempt = !requested && batteryExemption.isExempt()
                if (exempt) basePreferences.batteryExemptionRequested.set(true)
                val crash = crashLog.hasUnexportedCrash()
                FirstLaunchState(
                    showBatteryDialog = !requested && !exempt,
                    showCrashBanner = crash,
                    crashLogPath = if (crash) crashLog.latest()?.absolutePath else null,
                )
            }
            firstLaunch.value = next
        }
    }

    fun toggleIncognito() {
        basePreferences.incognitoMode.set(!basePreferences.incognitoMode.get())
    }

    fun toggleDownloadedOnly() {
        basePreferences.downloadedOnly.set(!basePreferences.downloadedOnly.get())
    }

    fun requestBatteryExemption(activity: Activity) {
        batteryExemption.request(activity)
        answerBatteryDialog()
    }

    fun skipBatteryExemption() = answerBatteryDialog()

    /**
     * Both banner buttons end here. Export obviously settles the log, and so does Dismiss: a log
     * the owner has waved away is not news on the next launch either, and the crash log screen
     * still has it.
     */
    fun dismissCrashBanner() {
        firstLaunch.update { it.copy(showCrashBanner = false, crashLogPath = null) }
        viewModelScope.launch { withContext(dispatchers.io) { crashLog.markExported() } }
    }

    private fun answerBatteryDialog() {
        basePreferences.batteryExemptionRequested.set(true)
        firstLaunch.update { it.copy(showBatteryDialog = false) }
    }
}

private data class ThemeState(
    val appTheme: AppTheme,
    val amoled: Boolean,
    val themeMode: ThemeMode,
    val tabletUiMode: TabletUiMode,
)

private data class ActivityState(
    val incognito: Boolean,
    val downloadedOnly: Boolean,
    val updatesBadge: Int,
    val extensionsBadge: Int,
)

/** The three fields one disk read answers, kept together so a banner cannot outlive its path. */
private data class FirstLaunchState(
    val showBatteryDialog: Boolean = false,
    val showCrashBanner: Boolean = false,
    val crashLogPath: String? = null,
)

private fun <T> Flow<T>.withInitial(value: T): Flow<T> = onStart { emit(value) }

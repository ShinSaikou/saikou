package com.shinsaikou.ui.main

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.shinsaikou.i18n.R
import com.shinsaikou.navigation.BrowsePage
import com.shinsaikou.navigation.LocalNavigator
import com.shinsaikou.navigation.LIBRARY_SEARCH_KEY
import com.shinsaikou.navigation.MIGRATION_PICK_KEY
import com.shinsaikou.navigation.Route
import com.shinsaikou.navigation.SettingsSection
import com.shinsaikou.navigation.SaikouNavigator
import com.shinsaikou.presentation.core.components.SolveBanner
import com.shinsaikou.presentation.core.util.LocalTabletUiMode
import com.shinsaikou.presentation.core.util.isTabletUi
import com.shinsaikou.ui.about.AboutScreen
import com.shinsaikou.ui.appupdate.AppUpdatePrompt
import com.shinsaikou.ui.backup.BackupScreen
import com.shinsaikou.ui.backup.ImportReportScreen
import com.shinsaikou.ui.browse.BrowseScreen
import com.shinsaikou.ui.browse.ExtensionStoresScreen
import com.shinsaikou.ui.browse.detail.ExtensionDetailScreen
import com.shinsaikou.ui.browse.extension.ExtensionFilterScreen
import com.shinsaikou.ui.browse.global.GlobalSearchScreen
import com.shinsaikou.ui.browse.settings.SourceSettingsScreen
import com.shinsaikou.ui.browse.source.SourceBrowseScreen
import com.shinsaikou.ui.browse.source.SourcesFilterScreen
import com.shinsaikou.ui.category.CategoriesScreen
import com.shinsaikou.ui.crash.CrashLogScreen
import com.shinsaikou.ui.download.DownloadQueueScreen
import com.shinsaikou.ui.history.HistoryScreen
import com.shinsaikou.ui.library.LibraryScreen
import com.shinsaikou.ui.manga.MangaDetailScreen
import com.shinsaikou.ui.migration.config.MigrationConfigScreen
import com.shinsaikou.ui.migration.list.MigrationListScreen
import com.shinsaikou.ui.migration.picker.MigratePickerScreen
import com.shinsaikou.ui.more.MoreScreen
import com.shinsaikou.ui.settings.AdoptionSummaryScreen
import com.shinsaikou.ui.settings.SettingsScreen
import com.shinsaikou.ui.settings.highlightKey
import com.shinsaikou.ui.stats.StatsScreen
import com.shinsaikou.ui.storage.ClearDatabaseScreen
import com.shinsaikou.ui.storage.StorageDownloadsScreen
import com.shinsaikou.ui.tracking.TrackerCompareScreen
import com.shinsaikou.ui.updates.UpdatesScreen
import com.shinsaikou.ui.webview.WebViewScreen
import eu.kanade.presentation.theme.SaikouTheme
import tachiyomi.presentation.core.components.material.NavigationBar
import tachiyomi.presentation.core.components.material.NavigationRail
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

/**
 * internal, not private, so `RouteTest` can assert against the real list rather than a copy.
 *
 * Each tab has its outlined icon for rest and its filled one for the selected tab, where upstream's
 * tabs animate from the one to the other.
 */
internal data class TabDestination(
    val route: Route.Tab,
    val labelRes: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

internal val tabs = listOf(
    TabDestination(
        Route.Library,
        R.string.tab_library,
        Icons.AutoMirrored.Outlined.LibraryBooks,
        Icons.AutoMirrored.Filled.LibraryBooks,
    ),
    TabDestination(Route.Updates, R.string.tab_updates, Icons.Outlined.NewReleases, Icons.Filled.NewReleases),
    TabDestination(Route.History, R.string.tab_history, Icons.Outlined.History, Icons.Filled.History),
    TabDestination(Route.Browse(), R.string.tab_browse, Icons.Outlined.Explore, Icons.Filled.Explore),
    TabDestination(Route.More, R.string.tab_more, Icons.Outlined.MoreHoriz, Icons.Filled.MoreHoriz),
)

/**
 * The main activity's complete navigation surface. The reader is a separate activity and is
 * reached through [SaikouNavigator], not through this host.
 */
@Composable
fun SaikouAppNav(
    mainState: MainUiState,
    onToggleIncognito: () -> Unit,
    onToggleDownloadedOnly: () -> Unit,
    pendingTarget: MainIntents.Target?,
    onTargetConsumed: () -> Unit,
    onBatteryAllow: () -> Unit,
    onBatterySkip: () -> Unit,
    onCrashExport: (String) -> Unit,
    onCrashDismissed: () -> Unit,
    onRestartWithExtensions: () -> Unit,
) {
    val navController = rememberNavController()
    val activity = checkNotNull(LocalActivity.current) { "the navigation host is not in an activity" }
    val navigator = remember(navController, activity) { SaikouNavigator(navController, activity) }

    // The error notification opens the Updates tab, which is a plain tab route and stays one: a
    // parameter on `Route.Updates` would break the identity comparisons in `tabs` and `matches`.
    // The dialog is asked for out of band instead, once, and the screen reports it back.
    var pendingUpdateErrors by remember { mutableStateOf(false) }

    LaunchedEffect(pendingTarget) {
        pendingTarget?.let { target ->
            val arrival = target.toArrival()
            navigator.arrive(arrival.route)
            pendingUpdateErrors = arrival.showUpdateErrors
            onTargetConsumed()
        }
    }

    CompositionLocalProvider(
        LocalTabletUiMode provides mainState.tabletUiMode,
        LocalNavigator provides navigator,
    ) {
        SaikouTheme(
            appTheme = mainState.appTheme,
            amoled = mainState.amoled,
            themeMode = mainState.themeMode,
        ) {
            AppScaffold(
                navController = navController,
                mainState = mainState,
                onToggleIncognito = onToggleIncognito,
                onToggleDownloadedOnly = onToggleDownloadedOnly,
                onBatteryAllow = onBatteryAllow,
                onBatterySkip = onBatterySkip,
                onCrashExport = onCrashExport,
                onCrashDismissed = onCrashDismissed,
                onRestartWithExtensions = onRestartWithExtensions,
                showUpdateErrors = pendingUpdateErrors,
                onUpdateErrorsShown = { pendingUpdateErrors = false },
            )
        }
    }
}

@Composable
private fun AppScaffold(
    navController: NavHostController,
    mainState: MainUiState,
    onToggleIncognito: () -> Unit,
    onToggleDownloadedOnly: () -> Unit,
    onBatteryAllow: () -> Unit,
    onBatterySkip: () -> Unit,
    onCrashExport: (String) -> Unit,
    onCrashDismissed: () -> Unit,
    onRestartWithExtensions: () -> Unit,
    showUpdateErrors: Boolean,
    onUpdateErrorsShown: () -> Unit,
) {
    val entry by navController.currentBackStackEntryAsState()
    val destination = entry?.destination
    // The rail is a layout decision, not a preference on its own: the preference only says whether
    // the breakpoint applies. Reading the preference alone put a rail on every phone.
    val useRail = isTabletUi()
    val onTab = destination.isTab()
    val navigator = LocalNavigator.current

    Scaffold(
        bottomBar = {
            AnimatedVisibility(visible = !useRail && onTab) {
                NavigationBar {
                    tabs.forEach { tab ->
                        val selected = destination.matches(tab.route)
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.navigateToTab(tab.route) },
                            icon = { TabIcon(tab, mainState, selected) },
                            label = { Text(stringResource(tab.labelRes)) },
                        )
                    }
                }
            }
        },
        startBar = {
            AnimatedVisibility(visible = useRail && onTab) {
                NavigationRail {
                    tabs.forEach { tab ->
                        val selected = destination.matches(tab.route)
                        NavigationRailItem(
                            selected = selected,
                            onClick = { navController.navigateToTab(tab.route) },
                            icon = { TabIcon(tab, mainState, selected) },
                            label = { Text(stringResource(tab.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        // The banners are above the NavHost rather than under a shared top bar, because there is
        // no shared top bar: every screen builds its own, and the tablet library shows two at once.
        // The sides and the bottom are applied here and then consumed, so no screen adds them a
        // second time. The status bar is not (phase 5 unit phase5-chrome-3): every screen's bar is a
        // ScreenTopBar or a SearchTopBar, Material's top bar, which takes the status bar inset itself,
        // so the strip above it is the bar's own colour, at rest and in its scrolled or selection
        // colour, where the shell's background strip sat above it before. A banner sits under the
        // status bar on the shell's background as before, and the screen below it is told the inset
        // is taken.
        val layoutDirection = LocalLayoutDirection.current
        val belowStatusBar = PaddingValues(
            start = padding.calculateStartPadding(layoutDirection),
            end = padding.calculateEndPadding(layoutDirection),
            bottom = padding.calculateBottomPadding(),
        )
        val statusBar = PaddingValues(top = padding.calculateTopPadding())
        val bannerShown = mainState.extensionsOff || mainState.showCrashBanner || mainState.solves.isNotEmpty()
        Column(modifier = Modifier.fillMaxSize().padding(belowStatusBar).consumeWindowInsets(belowStatusBar)) {
            if (bannerShown) Spacer(Modifier.padding(statusBar))
            if (mainState.extensionsOff) ExtensionsOffBanner(onRestart = onRestartWithExtensions)
            if (mainState.showCrashBanner) {
                CrashBanner(
                    canExport = mainState.crashLogPath != null,
                    onExport = { mainState.crashLogPath?.let(onCrashExport) },
                    onDismiss = onCrashDismissed,
                )
            }
            // A challenge the headless solve cannot pass (one that wants a checkbox) has no way
            // out but a person, so the banner offers the host's page in the app's own WebView,
            // which shares the cookie jar the waiting requests will retry with.
            SolveBanner(
                inFlight = mainState.solves,
                action = { host ->
                    TextButton(onClick = { navigator.open(Route.WebView(url = "https://$host")) }) {
                        Text(stringResource(R.string.action_open_in_web_view))
                    }
                },
            )
            NavHost(
                navController = navController,
                startDestination = Route.Library,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .then(if (bannerShown) Modifier.consumeWindowInsets(statusBar) else Modifier),
            ) {
                composable<Route.Library> { backStackEntry ->
                    // A search another screen asked for, which SaikouNavigator.searchLibrary left here.
                    val savedState = backStackEntry.savedStateHandle
                    val searchRequest by savedState.getStateFlow<String?>(LIBRARY_SEARCH_KEY, null).collectAsState()
                    LibraryScreen(
                        searchRequest = searchRequest,
                        onSearchRequestConsumed = { savedState.remove<String>(LIBRARY_SEARCH_KEY) },
                    )
                }
                composable<Route.Updates> {
                    UpdatesScreen(showErrorsOnArrival = showUpdateErrors, onErrorsShown = onUpdateErrorsShown)
                }
                composable<Route.History> { HistoryScreen() }
                composable<Route.Browse> { backStackEntry ->
                    val route = backStackEntry.toRoute<Route.Browse>()
                    BrowseScreen(initialPage = route.page, extensionsQuery = route.extensionsQuery)
                }
                composable<Route.More> {
                    MoreScreen(
                        incognito = mainState.incognito,
                        downloadedOnly = mainState.downloadedOnly,
                        onToggleIncognito = onToggleIncognito,
                        onToggleDownloadedOnly = onToggleDownloadedOnly,
                    )
                }
                composable<Route.MangaDetail> { backStackEntry ->
                    val route = backStackEntry.toRoute<Route.MangaDetail>()
                    MangaDetailScreen(mangaId = route.mangaId, fromSource = route.fromSource)
                }
                composable<Route.SourceBrowse> { backStackEntry ->
                    val route = backStackEntry.toRoute<Route.SourceBrowse>()
                    SourceBrowseScreen(
                        sourceId = route.sourceId,
                        query = route.query,
                        mode = route.mode,
                        migratingMangaId = route.migratingMangaId,
                    )
                }
                composable<Route.SourceSettings> { backStackEntry ->
                    SourceSettingsScreen(sourceId = backStackEntry.toRoute<Route.SourceSettings>().sourceId)
                }
                composable<Route.ExtensionDetail> { backStackEntry ->
                    ExtensionDetailScreen(
                        packageName = backStackEntry.toRoute<Route.ExtensionDetail>().packageName,
                    )
                }
                composable<Route.ExtensionStores> { backStackEntry ->
                    ExtensionStoresScreen(pendingUrl = backStackEntry.toRoute<Route.ExtensionStores>().pendingUrl)
                }
                composable<Route.GlobalSearch> { backStackEntry ->
                    GlobalSearchScreen(initialQuery = backStackEntry.toRoute<Route.GlobalSearch>().query)
                }
                composable<Route.SourcesFilter> { SourcesFilterScreen() }
                composable<Route.ExtensionFilter> { ExtensionFilterScreen() }
                composable<Route.WebView> { backStackEntry ->
                    val route = backStackEntry.toRoute<Route.WebView>()
                    WebViewScreen(url = route.url, sourceId = route.sourceId, title = route.title)
                }
                composable<Route.Categories> { CategoriesScreen() }
                composable<Route.Statistics> { StatsScreen() }
                composable<Route.Settings> { backStackEntry ->
                    val route = backStackEntry.toRoute<Route.Settings>()
                    SettingsScreen(requestedSection = route.section, requestedHighlight = route.highlightKey)
                }
                composable<Route.StorageDownloads> { StorageDownloadsScreen() }
                composable<Route.ClearDatabase> { ClearDatabaseScreen() }
                composable<Route.BackupCreate> { BackupScreen() }
                composable<Route.TrackerCompare> { backStackEntry ->
                    TrackerCompareScreen(trackerId = backStackEntry.toRoute<Route.TrackerCompare>().trackerId)
                }
                composable<Route.BackupRestore> { backStackEntry ->
                    BackupScreen(restoreUri = backStackEntry.toRoute<Route.BackupRestore>().uri)
                }
                composable<Route.ImportReport> { ImportReportScreen() }
                composable<Route.CrashLog> { CrashLogScreen() }
                composable<Route.About> { AboutScreen() }
                composable<Route.DownloadQueue> { DownloadQueueScreen() }
                composable<Route.AdoptionSummary> { AdoptionSummaryScreen() }
                composable<Route.MigratePicker> { backStackEntry ->
                    MigratePickerScreen(sourceId = backStackEntry.toRoute<Route.MigratePicker>().sourceId)
                }
                composable<Route.MigrationConfig> { backStackEntry ->
                    val route = backStackEntry.toRoute<Route.MigrationConfig>()
                    MigrationConfigScreen(
                        mangaIds = route.mangaIds,
                        onSearchManually = { mangaId -> navigator.replace(Route.MigrateSearch(mangaId)) },
                        onStartMigration = { extraSearchQuery ->
                            navigator.replace(Route.MigrationList(route.mangaIds, extraSearchQuery))
                        },
                    )
                }
                composable<Route.MigrationList> { backStackEntry ->
                    val route = backStackEntry.toRoute<Route.MigrationList>()
                    // A target picked by hand, which SaikouNavigator.pickMigrationTarget left here.
                    val savedState = backStackEntry.savedStateHandle
                    val pick by savedState.getStateFlow<LongArray?>(MIGRATION_PICK_KEY, null).collectAsState()
                    MigrationListScreen(
                        mangaIds = route.mangaIds,
                        extraSearchQuery = route.extraSearchQuery,
                        onSearchManually = { mangaId -> navigator.open(Route.MigrateSearch(mangaId)) },
                        pick = pick,
                        onPickConsumed = { savedState.remove<LongArray>(MIGRATION_PICK_KEY) },
                    )
                }
                composable<Route.MigrateSearch> { backStackEntry ->
                    GlobalSearchScreen(
                        initialQuery = null,
                        migratingMangaId = backStackEntry.toRoute<Route.MigrateSearch>().mangaId,
                    )
                }
            }
        }
    }

    if (mainState.showBatteryDialog) {
        BatteryExemptionDialog(onAllow = onBatteryAllow, onSkip = onBatterySkip)
    } else {
        // One question at a time: a first launch answers the battery prompt before an update's.
        AppUpdatePrompt()
    }
}

@Composable
private fun TabIcon(tab: TabDestination, state: MainUiState, selected: Boolean) {
    val badge = when (tab.route) {
        Route.Updates -> state.updatesBadge
        is Route.Browse -> state.extensionsBadge
        else -> 0
    }
    val icon = if (selected) tab.selectedIcon else tab.icon
    if (badge > 0) {
        BadgedBox(badge = { Badge { Text(badge.toString()) } }) {
            Icon(icon, contentDescription = stringResource(tab.labelRes))
        }
    } else {
        Icon(icon, contentDescription = stringResource(tab.labelRes))
    }
}

/** A tab keeps the other tabs' state and never stacks a second copy of itself. */
private fun NavHostController.navigateToTab(route: Route.Tab) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun NavDestination?.isTab(): Boolean = tabs.any { this.matches(it.route) }

private fun NavDestination?.matches(tab: Route.Tab): Boolean = when (tab) {
    Route.Library -> this.matchesRoute(Route.Library::class.qualifiedName)
    Route.Updates -> this.matchesRoute(Route.Updates::class.qualifiedName)
    Route.History -> this.matchesRoute(Route.History::class.qualifiedName)
    is Route.Browse -> this.matchesRoute(Route.Browse::class.qualifiedName)
    Route.More -> this.matchesRoute(Route.More::class.qualifiedName)
}

private fun NavDestination?.matchesRoute(serialName: String?): Boolean =
    serialName != null && this?.route?.startsWith(serialName) == true

/**
 * Where an outside intent lands: the route, and whether the Updates screen opens the last run's
 * error dialog on arrival. One value, so the two halves cannot be read from different intents.
 */
internal data class Arrival(val route: Route, val showUpdateErrors: Boolean)

/**
 * The one place an outside intent becomes a destination in this graph. `internal` so
 * `ArrivalTest` can pin it: the failed-update notification lands on Updates with the dialog up,
 * beside the new-chapters notification of the same run (ruling R43), and nothing else covers that.
 */
internal fun MainIntents.Target.toArrival(): Arrival = Arrival(
    route = toRoute(),
    showUpdateErrors = destination == MainDestination.UPDATE_ERRORS,
)

/**
 * Every payload-carrying case falls back to the library rather than opening its screen on nothing:
 * an entry intent with no id, or a restore intent whose URI the sender did not attach, is a
 * malformed intent, and a screen that opens empty reads to the owner as a screen that is broken.
 */
private fun MainIntents.Target.toRoute(): Route = when (destination) {
    MainDestination.LIBRARY -> Route.Library
    MainDestination.UPDATES, MainDestination.UPDATE_ERRORS -> Route.Updates
    MainDestination.HISTORY -> Route.History
    MainDestination.BROWSE -> Route.Browse()
    MainDestination.EXTENSIONS -> Route.Browse(BrowsePage.EXTENSIONS)
    MainDestination.ENTRY -> mangaId?.let { Route.MangaDetail(it) } ?: Route.Library
    MainDestination.IMPORT_REPORT -> Route.ImportReport
    MainDestination.RESTORE_BACKUP -> dataUri?.let { Route.BackupRestore(it) } ?: Route.Library
    MainDestination.EXTENSION_STORE -> Route.ExtensionStores(pendingUrl = storeUrl)
    MainDestination.DOWNLOAD_QUEUE -> Route.DownloadQueue
    MainDestination.DOWNLOAD_SETTINGS -> Route.Settings(SettingsSection.DOWNLOADS)
    MainDestination.SYNC_SETTINGS -> Route.Settings(SettingsSection.SYNC)
    MainDestination.DATA_SETTINGS -> Route.Settings(SettingsSection.DATA)
    MainDestination.TRACKING_SETTINGS -> Route.Settings(SettingsSection.TRACKING)
    MainDestination.ADVANCED_SETTINGS -> Route.Settings(SettingsSection.ADVANCED)
}

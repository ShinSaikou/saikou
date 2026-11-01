package com.shinsaikou.navigation

import android.app.Activity
import android.content.Intent
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.net.toUri
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.toRoute
import com.shinsaikou.reader.ReaderIntents
import kotlin.reflect.KClass

/**
 * The thin navigation surface handed to screens instead of the raw `NavController`
 * (contract 08 section 2.5). A screen names a [Route] and nothing else: whether that route is a
 * tab, a pushed destination or a separate activity is decided here.
 */
class SaikouNavigator(
    private val navController: NavHostController,
    private val activity: Activity,
) {

    fun open(route: Route) {
        when (route) {
            // The reader is its own activity, so orientation lock and fullscreen never leak into
            // the main back stack.
            is Route.Reader ->
                activity.startActivity(ReaderIntents.newIntent(activity, route.mangaId, route.chapterId, route.page))
            // A tab keeps the other tabs' state and never stacks a second copy of itself. A search
            // another screen asks of Browse (the restore report's Open Extensions) is a step out of
            // that screen instead, so back returns to it; as a tab switch it would come back with
            // the Browse state saved last time, page and search included, and lose its query.
            is Route.Tab -> if (route is Route.Browse && route.extensionsQuery != null) {
                navController.navigate(route)
            } else {
                navController.navigate(route) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
            else -> navController.navigate(route)
        }
    }

    /**
     * Opens [route] in place of the current destination, which leaves the back stack (upstream's
     * `navigator.replace`): back from [route] returns to what was under the one replaced.
     */
    fun replace(route: Route) {
        val current = navController.currentDestination?.id
        navController.navigate(route) {
            if (current != null) popUpTo(current) { inclusive = true }
        }
    }

    /**
     * A migration target picked by hand in manual search or in a source's listing (phase 4 unit MB),
     * the one helper both screens call. With a migration list under this screen, the pair
     * `[entryId, targetId]` goes into that list's saved state under [MIGRATION_PICK_KEY] and the
     * stack is popped back to it (upstream's `addMatchOverride` and `popUntil`), unless [targetId] is one
     * of that list's entries. With none, the caller migrates the one entry itself. [decidePick] says
     * which.
     */
    fun pickMigrationTarget(entryId: Long, targetId: Long): MigrationPick {
        val list = migrationListEntry()
        val pick = decidePick(list?.toRoute<Route.MigrationList>()?.mangaIds, entryId, targetId)
        if (pick == MigrationPick.RETURNED && list != null) {
            list.savedStateHandle[MIGRATION_PICK_KEY] = longArrayOf(entryId, targetId)
            navController.popBackStack(list.destination.id, inclusive = false)
        }
        return pick
    }

    /**
     * One entry migrated from manual search, or from a source's listing opened from it: back
     * through manual search, then the target's screen (upstream's `navigator.replace(MangaScreen)`). Both
     * search screens end on the same stack, where upstream's listing opens the Browse tab, which in
     * Saikou would keep the two search screens waiting under it.
     */
    fun completeSingleMigration(targetId: Long) {
        navController.popBackStack<Route.MigrateSearch>(inclusive = true)
        navController.navigate(Route.MangaDetail(targetId))
    }

    /**
     * The library with its search set to [query], from a genre on the entry screen. The library is
     * the start destination and never leaves the back stack, so the query goes into its saved state
     * under [LIBRARY_SEARCH_KEY] first. Inside another tab's stack this is that tab's switch to the
     * library, as the navigation bar makes it, so the other tab keeps its screens; inside the
     * library's own stack it is a pop back to the library, because the tab switch would restore the
     * very screens it popped. On the tablet library, whose entry pane is part of the library, nothing
     * is popped and only the search changes.
     */
    fun searchLibrary(query: String) {
        navController.getBackStackEntry<Route.Library>().savedStateHandle[LIBRARY_SEARCH_KEY] = query
        val inOtherTab = OTHER_TABS.any { isOnBackStack(it) }
        if (inOtherTab) open(Route.Library) else navController.popBackStack<Route.Library>(inclusive = false)
    }

    /**
     * Whether a destination of [route] is on the back stack. `NavController.currentBackStack` would
     * answer in one read, but it is restricted to the navigation library itself; `getBackStackEntry`
     * is the public way to ask, and it throws when there is none.
     */
    private fun isOnBackStack(route: KClass<out Route>): Boolean = try {
        navController.getBackStackEntry(route)
        true
    } catch (e: IllegalArgumentException) {
        false
    }

    /** The topmost migration list on the back stack, or null. */
    private fun migrationListEntry(): NavBackStackEntry? = try {
        navController.getBackStackEntry<Route.MigrationList>()
    } catch (e: IllegalArgumentException) {
        null
    }

    /** Back out of the current destination, or out of the app when this is the last one. */
    fun back() {
        if (!navController.popBackStack()) activity.finish()
    }

    fun openUrl(url: String) {
        activity.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    }
}

/** Where [SaikouNavigator.pickMigrationTarget] sent a hand-picked target. */
enum class MigrationPick {
    /** The pair went back to the migration list, which is now on screen. */
    RETURNED,

    /** The target is the entry itself or another entry of the list's batch, and is refused. */
    IN_BATCH,

    /** No list is below: the caller migrates the one entry through MA's dialog. */
    NO_LIST,
}

/** The key of a hand-picked `[entryId, targetId]` in the migration list's saved state. */
const val MIGRATION_PICK_KEY = "migration_pick"

/** The key of a search another screen asks of the library, in the library's saved state. */
const val LIBRARY_SEARCH_KEY = "library_search"

/** The tabs other than the library, whose stacks [SaikouNavigator.searchLibrary] leaves by a tab switch. */
private val OTHER_TABS = listOf(Route.Updates::class, Route.History::class, Route.Browse::class, Route.More::class)

/**
 * The pure half of [SaikouNavigator.pickMigrationTarget]. [listIds] are the ids of the migration list
 * below, or null with none. No entry of a batch is ever a target (M4), and an entry is never
 * migrated onto itself.
 */
internal fun decidePick(listIds: List<Long>?, entryId: Long, targetId: Long): MigrationPick = when {
    listIds != null && targetId in listIds -> MigrationPick.IN_BATCH
    listIds != null -> MigrationPick.RETURNED
    targetId == entryId -> MigrationPick.IN_BATCH
    else -> MigrationPick.NO_LIST
}

val LocalNavigator = staticCompositionLocalOf<SaikouNavigator> {
    error("No SaikouNavigator: a screen was composed outside the navigation host")
}

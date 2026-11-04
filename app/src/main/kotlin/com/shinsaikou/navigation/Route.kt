package com.shinsaikou.navigation

import kotlinx.serialization.Serializable

/** Every destination owned by the main activity. Reader is handled by its own activity. */
sealed interface Route {
    sealed interface Tab : Route

    @Serializable
    data object Library : Tab

    @Serializable
    data object Updates : Tab

    /** The releases predicted for the library, day by day, from the Updates bar. */
    @Serializable
    data object Upcoming : Route

    @Serializable
    data object History : Tab

    /**
     * [extensionsQuery] opens the Extensions page searched for it (unit restore-report): the restore
     * report's Open Extensions passes a missing source's name. A route that carries one is pushed
     * above the screen that opened it rather than switched to as a tab (`SaikouNavigator.open`).
     */
    @Serializable
    data class Browse(
        val page: BrowsePage = BrowsePage.SOURCES,
        val extensionsQuery: String? = null,
    ) : Tab

    @Serializable
    data object More : Tab

    @Serializable
    data class MangaDetail(val mangaId: Long, val fromSource: Boolean = false) : Route

    /**
     * [migratingMangaId] opens the listing in pick mode, from manual search for a migration (phase 4
     * unit MB): a tap picks a result as that entry's target.
     */
    @Serializable
    data class SourceBrowse(
        val sourceId: Long,
        val query: String? = null,
        val mode: SourceBrowseMode = SourceBrowseMode.POPULAR,
        val migratingMangaId: Long? = null,
    ) : Route

    /** One source's own preference screen, plus the cookie section contract 03 section 10 asks for. */
    @Serializable
    data class SourceSettings(val sourceId: Long) : Route

    @Serializable
    data class ExtensionDetail(val packageName: String) : Route

    /**
     * [pendingUrl] is the index address a `saikou://extension-store?url=...` intent carried. The
     * screen prefills its add field with it and waits for a tap; nothing is ever stored because an
     * intent asked for it.
     */
    @Serializable
    data class ExtensionStores(val pendingUrl: String? = null) : Route

    /** Every source at once, one query, results grouped by source. */
    @Serializable
    data class GlobalSearch(val query: String? = null) : Route

    /**
     * Upstream's Browse source filter screen, from the Sources page's Filter action (phase 5 unit BF):
     * each language with a switch and, under an enabled one, its sources with a checkbox.
     */
    @Serializable
    data object SourcesFilter : Route

    /**
     * Upstream's Extensions filter screen, from the Extensions page's Filter action (phase 5 unit BX):
     * each language of the available extensions with a switch.
     */
    @Serializable
    data object ExtensionFilter : Route

    /**
     * A page in the app's own WebView, sharing the process cookie jar with OkHttp. This is what a
     * source behind an interstitial needs, and the only way to solve one by hand when the headless
     * path in `CloudflareInterceptor` gives up.
     */
    @Serializable
    data class WebView(
        val url: String,
        val sourceId: Long? = null,
        val title: String? = null,
    ) : Route

    @Serializable
    data object Categories : Route

    /** Upstream's statistics screen: read-only totals over the library, history, tracks and downloads. */
    @Serializable
    data object Statistics : Route

    /**
     * [highlightTitle] and [highlightGroup] name the row a settings search result leads to, by the
     * string resources of its title and of its group's heading, the search's own key for it; 0 is
     * none, an id no resource has. They mean something only with a [section].
     */
    @Serializable
    data class Settings(
        val section: SettingsSection? = null,
        val highlightTitle: Int = 0,
        val highlightGroup: Int = 0,
    ) : Route

    /** Downloads of titles no longer in the library, from the Data section (phase 4 unit ST). */
    @Serializable
    data object StorageDownloads : Route

    /** Upstream's clear database, from the Advanced section (phase 4 unit ST). */
    @Serializable
    data object ClearDatabase : Route

    @Serializable
    data object BackupCreate : Route

    /** Compare with MyAnimeList, from the Tracking section, for one tracker (phase 4 unit TC). */
    @Serializable
    data class TrackerCompare(val trackerId: Long) : Route

    @Serializable
    data class BackupRestore(val uri: String) : Route

    /**
     * The last restore's counts, the sources and trackers it found missing with Install, Open
     * Extensions and Sign in, and its errors with Export error log (unit restore-report), from the
     * completion notification and the Backup screen's Restore report row.
     */
    @Serializable
    data object ImportReport : Route

    @Serializable
    data object CrashLog : Route

    /**
     * Upstream's More > About, from a tap on the logo at the top of the More tab: the build
     * installed, Check for updates, the latest release's APKs, and the bug report.
     */
    @Serializable
    data object About : Route

    @Serializable
    data object DownloadQueue : Route

    /**
     * The last copy from a folder: its counts, what matched nothing grouped by why, with Install or
     * Open Extensions, Search and Open entry, what was not copied, and Copy again (unit
     * adoption-unmatched-reasons), from the Downloads section's Last copy from a folder row.
     */
    @Serializable
    data object AdoptionSummary : Route

    /** One source's library entries, to tick for a migration, from Browse's Migrate page (phase 4 unit MB). */
    @Serializable
    data class MigratePicker(val sourceId: Long) : Route

    /**
     * The target sources of a batch migration and their order, then the sheet (phase 4 unit MB).
     * The ids travel in the route, as upstream's travel in its screen, so the back stack restores them.
     */
    @Serializable
    data class MigrationConfig(val mangaIds: List<Long>) : Route

    /** The migration list: each entry searched on the selected sources, then written (phase 4 unit MB). */
    @Serializable
    data class MigrationList(val mangaIds: List<Long>, val extraSearchQuery: String? = null) : Route

    /** Global Search in migration mode, to pick one entry's target by hand (phase 4 unit MB). */
    @Serializable
    data class MigrateSearch(val mangaId: Long) : Route

    /** Opens the dedicated reader activity and is deliberately absent from the NavHost. */
    @Serializable
    data class Reader(val mangaId: Long, val chapterId: Long, val page: Int? = null) : Route
}

@Serializable
enum class BrowsePage {
    SOURCES,
    EXTENSIONS,

    /** The sources holding library entries, to migrate from (phase 4 unit MB). */
    MIGRATE,
}

/**
 * Which listing a source browse opens on. One enum, shared by the route and by
 * `SourceBrowseViewModel`'s state, so a deep link and the on-screen mode chips can never disagree
 * about what "latest" means.
 */
@Serializable
enum class SourceBrowseMode {
    POPULAR,
    LATEST,
    SEARCH,
}

/**
 * The settings sections in their list order. Tracking sits after Downloads, where upstream puts it, and
 * is listed only while a configured tracker is in the graph (`shownSettingsSections`). Security sits
 * just before Advanced, where upstream puts its "Security and privacy" (phase 4 unit SR).
 * Experimental, Saikou's own, is last: behaviour that could hurt the experience, each switch off by
 * default (R162 item 2).
 */
@Serializable
enum class SettingsSection {
    APPEARANCE,
    LIBRARY,
    READER,
    DOWNLOADS,
    TRACKING,
    BROWSE,
    DATA,
    SYNC,
    SECURITY,
    ADVANCED,
    EXPERIMENTAL,
}

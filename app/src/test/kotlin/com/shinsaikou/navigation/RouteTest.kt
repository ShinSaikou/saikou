package com.shinsaikou.navigation

import androidx.navigation.NavType
import androidx.navigation.serialization.generateNavArguments
import androidx.navigation.serialization.generateRouteWithArgs
import com.shinsaikou.ui.main.tabs
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * Asserts against the real tab list in SaikouAppNav.kt, which is `internal` for exactly this
 * reason: a copy of the list in the test would agree with itself forever while the bar on screen
 * drifted.
 */
class RouteTest {

    private val tabDestinations: List<Route.Tab> = tabs.map { it.route }

    @Test
    fun `the tab destinations map to distinct routes`() {
        tabDestinations.toSet().size shouldBe tabDestinations.size
    }

    @Test
    fun `every route tab case is reachable from the tab destination list`() {
        // Exhaustive when with no else branch: a new Route.Tab case fails to compile here until
        // this test is updated, mirroring the guard SaikouAppNav.kt relies on. Collecting one
        // marker per element then checks the list really carries every case, in tab bar order.
        val covered = tabDestinations.map { tab ->
            when (tab) {
                Route.Library -> "library"
                Route.Updates -> "updates"
                Route.History -> "history"
                is Route.Browse -> "browse"
                Route.More -> "more"
            }
        }

        covered shouldBe listOf("library", "updates", "history", "browse", "more")
    }

    @Test
    fun `browse route round trips with its default page`() {
        val original = Route.Browse()

        val decoded = roundTrip(Route.Browse.serializer(), original)

        decoded shouldBe original
    }

    @Test
    fun `browse route round trips with an explicit page`() {
        val original = Route.Browse(page = BrowsePage.EXTENSIONS)

        val decoded = roundTrip(Route.Browse.serializer(), original)

        decoded shouldBe original
    }

    /** Unit restore-report: Open Extensions passes a missing source's name, spaces and brackets included. */
    @Test
    fun `browse route round trips with an extensions query, and its route pattern carries it`() {
        val original = Route.Browse(page = BrowsePage.EXTENSIONS, extensionsQuery = "Source name (EN)")

        roundTrip(Route.Browse.serializer(), original) shouldBe original
        roundTrip(Route.Browse.serializer(), Route.Browse()).extensionsQuery shouldBe null
        Route.Browse.serializer().generateNavArguments(emptyMap()).single { it.name == "extensionsQuery" }
            .argument.isNullable shouldBe true
    }

    @Test
    fun `the restore report route round trips`() {
        roundTrip(Route.ImportReport.serializer(), Route.ImportReport) shouldBe Route.ImportReport
    }

    @Test
    fun `the upcoming route round trips and is a destination of its own, not a tab`() {
        roundTrip(Route.Upcoming.serializer(), Route.Upcoming) shouldBe Route.Upcoming
        tabDestinations.contains<Route>(Route.Upcoming) shouldBe false
    }

    @Test
    fun `the copy summary route round trips`() {
        roundTrip(Route.AdoptionSummary.serializer(), Route.AdoptionSummary) shouldBe Route.AdoptionSummary
    }

    @Test
    fun `manga detail route round trips`() {
        val original = Route.MangaDetail(mangaId = 42L, fromSource = true)

        val decoded = roundTrip(Route.MangaDetail.serializer(), original)

        decoded shouldBe original
    }

    @Test
    fun `source browse route round trips`() {
        val original = Route.SourceBrowse(sourceId = 7L)

        val decoded = roundTrip(Route.SourceBrowse.serializer(), original)

        decoded shouldBe original
    }

    @Test
    fun `source browse route resolves its query argument to a nullable navigation string type`() {
        // What could actually break on a hostile query is androidx.navigation's own encoding, not
        // kotlinx JSON, which round trips every String identically regardless of content. That
        // encoding runs through android.net.Uri, which this module's plain JVM unit tests cannot
        // call (proved below by the RuntimeException it throws), so the encoding itself is out of
        // reach here. What is reachable, and worth pinning, is the real
        // androidx.navigation.serialization argument resolution SaikouNavigator.open(route) depends
        // on: `query` must keep resolving to NavType.StringType, nullable, so any string content
        // is accepted opaquely by the framework's own type inference rather than by a custom or
        // missing NavType that would either reject arbitrary text or fail to build the route at
        // all. A change to this field's declared type is exactly what would break that silently.
        val queryArgument = Route.SourceBrowse.serializer().generateNavArguments(emptyMap()).single { it.name == "query" }

        queryArgument.argument.type shouldBe NavType.StringType
        queryArgument.argument.isNullable shouldBe true
    }

    @Test
    fun `androidx navigation's own route encoding is unreachable from a plain JVM unit test here`() {
        // Backs the claim in the test above: encoding a Route through the real
        // androidx.navigation.serialization.RouteEncoder calls android.net.Uri.encode, which this
        // module's unit test setup has no shadow for. If a Robolectric shadow is ever added to the
        // :app unit test classpath, this test starts failing and is the signal to replace both
        // tests here with a genuine encode/decode round trip through the real encoder.
        val serializer = Route.SourceBrowse.serializer()
        val original = Route.SourceBrowse(sourceId = 7L, query = "a?b&c", mode = SourceBrowseMode.SEARCH)

        val thrown = runCatching { encodeRoute(serializer, original) }.exceptionOrNull()

        thrown.shouldNotBeNull()
        thrown.message.orEmpty() shouldContain "not mocked"
    }

    @Test
    fun `source settings route round trips`() {
        val original = Route.SourceSettings(sourceId = 7L)

        val decoded = roundTrip(Route.SourceSettings.serializer(), original)

        decoded shouldBe original
    }

    @Test
    fun `extension detail route round trips`() {
        val original = Route.ExtensionDetail(packageName = "example.package.name")

        val decoded = roundTrip(Route.ExtensionDetail.serializer(), original)

        decoded shouldBe original
    }

    @Test
    fun `extension stores route round trips with and without a pending address`() {
        roundTrip(Route.ExtensionStores.serializer(), Route.ExtensionStores()) shouldBe
            Route.ExtensionStores()

        val pending = Route.ExtensionStores(pendingUrl = "https://example.invalid/index.json?v=2")

        roundTrip(Route.ExtensionStores.serializer(), pending) shouldBe pending
    }

    @Test
    fun `global search route round trips with and without a query`() {
        roundTrip(Route.GlobalSearch.serializer(), Route.GlobalSearch()) shouldBe Route.GlobalSearch()

        val queried = Route.GlobalSearch(query = "a?b&c/d %e")

        roundTrip(Route.GlobalSearch.serializer(), queried) shouldBe queried
    }

    @Test
    fun `web view route round trips every optional argument`() {
        val bare = Route.WebView(url = "https://example.invalid/path?a=1&b=2")

        roundTrip(Route.WebView.serializer(), bare) shouldBe bare

        val full = Route.WebView(
            url = "https://example.invalid/path?a=1&b=2",
            sourceId = 9L,
            title = "A page / with % characters",
        )

        roundTrip(Route.WebView.serializer(), full) shouldBe full
    }

    @Test
    fun `settings route round trips with no section`() {
        val original = Route.Settings(section = null)

        val decoded = roundTrip(Route.Settings.serializer(), original)

        decoded shouldBe original
    }

    @Test
    fun `settings route round trips with a section`() {
        val original = Route.Settings(section = SettingsSection.READER)

        val decoded = roundTrip(Route.Settings.serializer(), original)

        decoded shouldBe original
    }

    @Test
    fun `settings route round trips with the sync section`() {
        val original = Route.Settings(section = SettingsSection.SYNC)

        val decoded = roundTrip(Route.Settings.serializer(), original)

        decoded shouldBe original
    }

    @Test
    fun `settings route round trips with the row a search result asks for`() {
        val original = Route.Settings(section = SettingsSection.READER, highlightTitle = 17, highlightGroup = 23)

        val decoded = roundTrip(Route.Settings.serializer(), original)

        decoded shouldBe original
    }

    @Test
    fun `backup restore route round trips`() {
        val original = Route.BackupRestore(uri = "content://fixture/backup.saikou")

        val decoded = roundTrip(Route.BackupRestore.serializer(), original)

        decoded shouldBe original
    }

    @Test
    fun `reader route round trips with no page`() {
        val original = Route.Reader(mangaId = 1L, chapterId = 2L, page = null)

        val decoded = roundTrip(Route.Reader.serializer(), original)

        decoded shouldBe original
    }

    @Test
    fun `reader route round trips with a page`() {
        val original = Route.Reader(mangaId = 1L, chapterId = 2L, page = 5)

        val decoded = roundTrip(Route.Reader.serializer(), original)

        decoded shouldBe original
    }

    @Test
    fun `the Migrate page, the picker, manual search and the listing's pick mode round trip`() {
        roundTrip(Route.Browse.serializer(), Route.Browse(BrowsePage.MIGRATE)) shouldBe Route.Browse(BrowsePage.MIGRATE)
        roundTrip(Route.MigratePicker.serializer(), Route.MigratePicker(sourceId = 3L)) shouldBe Route.MigratePicker(3L)
        val search = Route.MigrateSearch(mangaId = 12L)
        roundTrip(Route.MigrateSearch.serializer(), search) shouldBe search
        val pick = Route.SourceBrowse(sourceId = 7L, query = "harbor", mode = SourceBrowseMode.SEARCH, migratingMangaId = 12L)
        roundTrip(Route.SourceBrowse.serializer(), pick) shouldBe pick
    }

    @Test
    fun `the migration routes round trip their id lists and keywords`() {
        val list = Route.MigrationList(mangaIds = listOf(3L, 1L, 2L), extraSearchQuery = "volume one")

        roundTrip(Route.MigrationList.serializer(), list) shouldBe list
        roundTrip(Route.MigrationList.serializer(), Route.MigrationList(listOf(4L))) shouldBe Route.MigrationList(listOf(4L))
        val config = Route.MigrationConfig(mangaIds = listOf(9L, 8L))
        roundTrip(Route.MigrationConfig.serializer(), config) shouldBe config
    }

    @Test
    fun `the migration routes' id lists resolve to navigation's long list type`() {
        // Phase 4 unit MB: the batch's ids travel in the route, so the back stack's saved state
        // brings them back after a process death. Navigation maps List<Long> to its built-in
        // LongListType with no custom NavType; a field type it cannot map would fail only at
        // navigate time, on the device.
        val listArgument = Route.MigrationList.serializer().generateNavArguments(emptyMap()).single { it.name == "mangaIds" }
        val configArgument = Route.MigrationConfig.serializer().generateNavArguments(emptyMap()).single { it.name == "mangaIds" }

        listArgument.argument.type shouldBe NavType.LongListType
        configArgument.argument.type shouldBe NavType.LongListType
    }

    @Test
    fun `no route name is a prefix of another route name`() {
        // SaikouAppNav.matchesRoute compares a NavDestination's route with startsWith, not with
        // equality, because a route carrying arguments is stored with them appended. That is only
        // safe while no route's serial name is a prefix of another: the day someone adds a
        // Route.LibrarySettings next to Route.Library, the library tab starts reporting itself as
        // selected while that screen is open. This test is the tripwire for that day.
        val names = allRoutes.map { name(it) }

        names.toSet().size shouldBe names.size
        for (name in names) {
            val prefixed = names.filter { it != name && it.startsWith(name) }
            prefixed shouldBe emptyList()
        }
    }

    /**
     * One instance per case, built through an exhaustive `when` so a new [Route] cannot be added
     * without this list being updated.
     */
    private val allRoutes: List<Route> = listOf(
        Route.Library,
        Route.Updates,
        Route.Upcoming,
        Route.History,
        Route.Browse(),
        Route.More,
        Route.MangaDetail(mangaId = 1L),
        Route.SourceBrowse(sourceId = 1L),
        Route.SourceSettings(sourceId = 1L),
        Route.ExtensionDetail(packageName = "example.package"),
        Route.ExtensionStores(),
        Route.GlobalSearch(),
        Route.SourcesFilter,
        Route.ExtensionFilter,
        Route.WebView(url = "https://example.invalid/"),
        Route.Categories,
        Route.Statistics,
        Route.Settings(),
        Route.StorageDownloads,
        Route.ClearDatabase,
        Route.BackupCreate,
        Route.TrackerCompare(trackerId = 1L),
        Route.BackupRestore(uri = "content://fixture/backup.saikou"),
        Route.ImportReport,
        Route.CrashLog,
        Route.About,
        Route.DownloadQueue,
        Route.AdoptionSummary,
        Route.MigratePicker(sourceId = 1L),
        Route.MigrationConfig(mangaIds = listOf(1L)),
        Route.MigrationList(mangaIds = listOf(1L)),
        Route.MigrateSearch(mangaId = 1L),
        Route.MergeSearch(mangaId = 1L),
        Route.Reader(mangaId = 1L, chapterId = 2L),
    )

    private fun name(route: Route): String = when (route) {
        Route.Library -> Route.Library::class.qualifiedName!!
        Route.Updates -> Route.Updates::class.qualifiedName!!
        Route.Upcoming -> Route.Upcoming::class.qualifiedName!!
        Route.History -> Route.History::class.qualifiedName!!
        is Route.Browse -> Route.Browse::class.qualifiedName!!
        Route.More -> Route.More::class.qualifiedName!!
        is Route.MangaDetail -> Route.MangaDetail::class.qualifiedName!!
        is Route.SourceBrowse -> Route.SourceBrowse::class.qualifiedName!!
        is Route.SourceSettings -> Route.SourceSettings::class.qualifiedName!!
        is Route.ExtensionDetail -> Route.ExtensionDetail::class.qualifiedName!!
        is Route.ExtensionStores -> Route.ExtensionStores::class.qualifiedName!!
        is Route.GlobalSearch -> Route.GlobalSearch::class.qualifiedName!!
        Route.SourcesFilter -> Route.SourcesFilter::class.qualifiedName!!
        Route.ExtensionFilter -> Route.ExtensionFilter::class.qualifiedName!!
        is Route.WebView -> Route.WebView::class.qualifiedName!!
        Route.Categories -> Route.Categories::class.qualifiedName!!
        Route.Statistics -> Route.Statistics::class.qualifiedName!!
        is Route.Settings -> Route.Settings::class.qualifiedName!!
        Route.StorageDownloads -> Route.StorageDownloads::class.qualifiedName!!
        Route.ClearDatabase -> Route.ClearDatabase::class.qualifiedName!!
        Route.BackupCreate -> Route.BackupCreate::class.qualifiedName!!
        is Route.TrackerCompare -> Route.TrackerCompare::class.qualifiedName!!
        is Route.BackupRestore -> Route.BackupRestore::class.qualifiedName!!
        Route.ImportReport -> Route.ImportReport::class.qualifiedName!!
        Route.CrashLog -> Route.CrashLog::class.qualifiedName!!
        Route.About -> Route.About::class.qualifiedName!!
        Route.DownloadQueue -> Route.DownloadQueue::class.qualifiedName!!
        Route.AdoptionSummary -> Route.AdoptionSummary::class.qualifiedName!!
        is Route.MigratePicker -> Route.MigratePicker::class.qualifiedName!!
        is Route.MigrationConfig -> Route.MigrationConfig::class.qualifiedName!!
        is Route.MigrationList -> Route.MigrationList::class.qualifiedName!!
        is Route.MigrateSearch -> Route.MigrateSearch::class.qualifiedName!!
        is Route.MergeSearch -> Route.MergeSearch::class.qualifiedName!!
        is Route.Reader -> Route.Reader::class.qualifiedName!!
    }

    // Route itself is not annotated @Serializable, so every round trip goes through the leaf
    // type's own generated serializer instead of a polymorphic base serializer.
    private fun <T> roundTrip(serializer: KSerializer<T>, value: T): T =
        Json.decodeFromString(serializer, Json.encodeToString(serializer, value))

    /**
     * Fills [value]'s route pattern in with real argument values through the same
     * androidx.navigation machinery `NavController.navigate(route)` uses, rather than kotlinx's
     * plain JSON encoder. `Route`'s arguments are all primitives and enums, none of which needs a
     * custom [NavType] entry, so an empty type map is enough for [generateNavArguments] to infer
     * one for every element.
     */
    private fun <T : Any> encodeRoute(serializer: KSerializer<T>, value: T): String {
        val typeMap = serializer.generateNavArguments(emptyMap()).associate { it.name to it.argument.type }
        @Suppress("UNCHECKED_CAST")
        return generateRouteWithArgs(value, typeMap as Map<String, NavType<Any?>>)
    }
}

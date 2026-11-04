package com.shinsaikou.di

import io.kotest.matchers.shouldBe
import java.io.File
import org.junit.jupiter.api.Test

/**
 * The app crashed on its very first launch with
 * "java.lang.IllegalArgumentException: Unknown model class class com.shinsaikou.ui.library.LibraryViewModel"
 * out of MetroViewModelFactory. The root cause: a view model annotated
 * `@ContributesIntoMap(AppScope::class)` with no explicit `binding` lets Metro infer the bound
 * type from the class's own supertype instead of from `androidx.lifecycle.ViewModel`. Every screen
 * view model except MainViewModel extends `StateViewModel<S>`
 * (com.shinsaikou.presentation.core.viewmodel.StateViewModel), so all of them were being contributed
 * into a `Map<KClass<*>, StateViewModel<S>>` that nothing in the graph ever requests, leaving
 * `MetroViewModelMultibindings.viewModelProviders` with exactly one entry: MainViewModel, the one
 * class that extends `androidx.lifecycle.ViewModel` directly. The build was green, all 804 unit
 * tests were green, the APK installed, and the first screen thrown up by the factory crashed
 * instantly. The fix was adding `binding = binding<ViewModel>()` to all eleven declarations so
 * Metro is told explicitly what supertype to bind against.
 *
 * Metro resolves multibindings lazily: an unrequested one is silently dropped rather than
 * reported, so nothing else on the JVM can see a twelfth view model regress this way. This test is
 * a plain text scan standing in for that missing compile time or runtime check, in the same spirit
 * as RouteTest.kt's structural guard over the tab list.
 *
 * Both ways of declaring a view model are scanned, because both end in the same crash. The plain
 * ones are keyed by `@ViewModelKey` and need an explicit binding; the ones taking route arguments
 * are built through an assisted factory and need a pair of annotations instead. Each set is also
 * pinned to its exact known members, so a scan that silently stops matching files fails here
 * rather than passing having checked nothing.
 */
class ViewModelBindingTest {

    private val viewModelKeyMarker = "@ViewModelKey("
    private val explicitBinding = "@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())"
    private val bareContributesIntoMap = "@ContributesIntoMap(AppScope::class)"

    private val assistedFactoryMarker = "ManualViewModelAssistedFactory"
    private val assistedKeyMarker = "@ManualViewModelAssistedFactoryKey"
    private val assistedInjectMarker = "@AssistedInject"

    /**
     * The unit test process runs with the `app/` module directory as its working directory, so
     * `src/main/kotlin/com/shinsaikou` resolves from there. Asserting the directory exists first means
     * a future change to that working directory fails loudly here instead of silently scanning
     * zero files and passing vacuously.
     */
    private val mangaRoot: File = File("src/main/kotlin/com/shinsaikou").also { root ->
        check(root.isDirectory) {
            "Expected ${root.absolutePath} to exist; the working directory for this test is " +
                "assumed to be the app/ module directory."
        }
    }

    private val viewModelFiles: List<File> = mangaRoot
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .filter { it.readText().contains(viewModelKeyMarker) }
        .sortedBy { it.path }
        .toList()

    /**
     * The assisted half of the same problem. A view model taking route arguments is built through
     * an `@AssistedFactory` interface instead of a plain provider, so it carries no `@ViewModelKey`
     * at all and the scan above cannot see it.
     *
     * `@AssistedInject` on the constructor is what identifies a real view model here.
     * `SaikouViewModelFactory` mentions the same factory supertype while declaring the key
     * annotation itself, and it is infrastructure rather than a view model, so keying off the
     * supertype alone would pull it in and every assertion below would then be wrong about it.
     */
    private val assistedFactoryFiles: List<File> = mangaRoot
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .filter { file ->
            val text = file.readText()
            text.contains(assistedFactoryMarker) && text.contains(assistedInjectMarker)
        }
        .sortedBy { it.path }
        .toList()

    @Test
    fun `every view model annotated with ViewModelKey also declares an explicit ViewModel binding`() {
        val offenders = viewModelFiles.filterNot { it.readText().contains(explicitBinding) }

        offenders shouldBe emptyList()
    }

    @Test
    fun `no view model file pairs a bare ContributesIntoMap with ViewModelKey on the next line`() {
        val offenders = viewModelFiles.filter { file ->
            val lines = file.readLines()
            lines.indices.any { index ->
                lines[index].trim() == bareContributesIntoMap &&
                    lines.getOrNull(index + 1)?.trim()?.startsWith(viewModelKeyMarker) == true
            }
        }

        offenders shouldBe emptyList()
    }

    @Test
    fun `the scan finds all thirty known view models, so a shrunken scan cannot pass vacuously`() {
        val expected = setOf(
            // More > About: the latest release's APKs and the bug report.
            "src/main/kotlin/com/shinsaikou/ui/about/AboutViewModel.kt",
            // The in-app updater's prompt and its Settings rows.
            "src/main/kotlin/com/shinsaikou/ui/appupdate/AppUpdateViewModel.kt",
            "src/main/kotlin/com/shinsaikou/ui/backup/BackupViewModel.kt",
            // The sync safety backup's Save a copy (unit storage-location).
            "src/main/kotlin/com/shinsaikou/ui/backup/KeptBackupCopyViewModel.kt",
            // The restore report (unit restore-report).
            "src/main/kotlin/com/shinsaikou/ui/backup/ImportReportViewModel.kt",
            "src/main/kotlin/com/shinsaikou/ui/browse/ExtensionStoresViewModel.kt",
            "src/main/kotlin/com/shinsaikou/ui/browse/ExtensionsViewModel.kt",
            "src/main/kotlin/com/shinsaikou/ui/browse/SourcesViewModel.kt",
            // The extension filter screen (phase 5 unit BX).
            "src/main/kotlin/com/shinsaikou/ui/browse/extension/ExtensionFilterViewModel.kt",
            // The source filter screen (phase 5 unit BF).
            "src/main/kotlin/com/shinsaikou/ui/browse/source/SourcesFilterViewModel.kt",
            "src/main/kotlin/com/shinsaikou/ui/category/CategoriesViewModel.kt",
            "src/main/kotlin/com/shinsaikou/ui/crash/CrashLogViewModel.kt",
            "src/main/kotlin/com/shinsaikou/ui/download/DownloadQueueViewModel.kt",
            "src/main/kotlin/com/shinsaikou/ui/history/HistoryViewModel.kt",
            "src/main/kotlin/com/shinsaikou/ui/library/LibraryViewModel.kt",
            "src/main/kotlin/com/shinsaikou/ui/main/MainViewModel.kt",
            // The migrate dialog's, declared beside its dialog (phase 4 unit MA).
            "src/main/kotlin/com/shinsaikou/ui/migration/MigrateMangaDialog.kt",
            // The batch migration's config screen (phase 4 unit MB).
            "src/main/kotlin/com/shinsaikou/ui/migration/config/MigrationConfigViewModel.kt",
            // Browse's Migrate tab (phase 4 unit MB).
            "src/main/kotlin/com/shinsaikou/ui/migration/sources/MigrateSourcesViewModel.kt",
            // The copy summary (unit adoption-unmatched-reasons).
            "src/main/kotlin/com/shinsaikou/ui/settings/AdoptionSummaryViewModel.kt",
            // The Data section's Export library list.
            "src/main/kotlin/com/shinsaikou/ui/settings/LibraryExportViewModel.kt",
            "src/main/kotlin/com/shinsaikou/ui/settings/SettingsViewModel.kt",
            "src/main/kotlin/com/shinsaikou/ui/settings/SyncSettingsViewModel.kt",
            "src/main/kotlin/com/shinsaikou/ui/settings/TrackingSettingsViewModel.kt",
            // The statistics screen (phase 4 unit SS).
            "src/main/kotlin/com/shinsaikou/ui/stats/StatsViewModel.kt",
            // The storage screen and clear database (phase 4 unit ST).
            "src/main/kotlin/com/shinsaikou/ui/storage/ClearDatabaseViewModel.kt",
            "src/main/kotlin/com/shinsaikou/ui/storage/StorageDownloadsViewModel.kt",
            // The Storage location picker (unit storage-location).
            "src/main/kotlin/com/shinsaikou/ui/storage/StorageLocationViewModel.kt",
            // The upcoming releases screen.
            "src/main/kotlin/com/shinsaikou/ui/upcoming/UpcomingViewModel.kt",
            "src/main/kotlin/com/shinsaikou/ui/updates/UpdatesViewModel.kt",
        )

        val found = viewModelFiles.map { it.path }.toSet()

        found shouldBe expected
    }

    /**
     * The assisted declarations need no `binding = binding<ViewModel>()`, and that is not an
     * oversight: the factory interface's own supertype is `ManualViewModelAssistedFactory`, which
     * is the exact type the graph asks for, so Metro's inference lands on the right one by itself.
     *
     * The failure mode here is a missing annotation rather than a wrongly inferred type, and it
     * ends the same way. Drop either annotation and the factory never reaches the map, the build
     * stays green because Metro resolves multibindings lazily, and the screen crashes the first
     * time it is opened with the "Unknown model class" error this file is named after.
     */
    @Test
    fun `every assisted view model factory carries both annotations that put it in the map`() {
        val offenders = assistedFactoryFiles.filterNot { file ->
            val text = file.readText()
            text.contains(assistedKeyMarker) && text.contains(bareContributesIntoMap)
        }

        offenders shouldBe emptyList()
    }

    @Test
    fun `the scan finds all ten known assisted view models, so a shrunken scan cannot pass vacuously`() {
        val expected = setOf(
            "src/main/kotlin/com/shinsaikou/ui/browse/detail/ExtensionDetailViewModel.kt",
            "src/main/kotlin/com/shinsaikou/ui/browse/global/GlobalSearchViewModel.kt",
            "src/main/kotlin/com/shinsaikou/ui/browse/settings/SourceSettingsViewModel.kt",
            "src/main/kotlin/com/shinsaikou/ui/browse/source/SourceBrowseViewModel.kt",
            "src/main/kotlin/com/shinsaikou/ui/manga/MangaDetailViewModel.kt",
            // The track sheet's (phase 3 unit T3).
            "src/main/kotlin/com/shinsaikou/ui/manga/TrackSheetViewModel.kt",
            // Compare with MyAnimeList (phase 4 unit TC).
            "src/main/kotlin/com/shinsaikou/ui/tracking/TrackerCompareViewModel.kt",
            // The migration list's (phase 4 unit MB).
            "src/main/kotlin/com/shinsaikou/ui/migration/list/MigrationListViewModel.kt",
            // The Migrate tab's per-source picker (phase 4 unit MB).
            "src/main/kotlin/com/shinsaikou/ui/migration/picker/MigratePickerViewModel.kt",
            "src/main/kotlin/com/shinsaikou/ui/webview/WebViewViewModel.kt",
        )

        val found = assistedFactoryFiles.map { it.path }.toSet()

        found shouldBe expected
    }
}

package com.shinsaikou.ui.settings

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shinsaikou.AppInfo
import com.shinsaikou.backup.export.BackupScheduler
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.core.common.network.PREF_DOH_CUSTOM
import com.shinsaikou.core.common.network.customDohUrl
import com.shinsaikou.core.common.preferences.BasePreferences
import com.shinsaikou.core.common.preferences.SecurityPreferences
import com.shinsaikou.domain.download.model.DownloadManifestEntry
import com.shinsaikou.domain.download.repository.DownloadManifestRepository
import com.shinsaikou.domain.library.interactor.RebuildLibraryCounts
import com.shinsaikou.domain.manga.interactor.ClearCustomIntervals
import com.shinsaikou.domain.preferences.BackupPreferences
import com.shinsaikou.domain.preferences.DownloadPreferences
import com.shinsaikou.domain.preferences.ExtensionsPreferences
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.ReaderPreferences
import com.shinsaikou.domain.preferences.SourcePreferences
import com.shinsaikou.domain.preferences.StoragePreferences
import com.shinsaikou.domain.preferences.UiPreferences
import com.shinsaikou.domain.source.interactor.languageOrder
import com.shinsaikou.downloads.DownloadManifest
import com.shinsaikou.downloads.DownloadManifestSweep
import com.shinsaikou.downloads.OrphanDownloads
import com.shinsaikou.downloads.OrphanTitle
import com.shinsaikou.downloads.adoption.AdoptionScheduler
import com.shinsaikou.downloads.adoption.AdoptionSummary
import com.shinsaikou.downloads.adoption.AdoptionSummaryStore
import com.shinsaikou.downloads.export.DownloadExportScheduler
import com.shinsaikou.extensions.ExtensionManager
import com.shinsaikou.extensions.trust.TrustStore
import com.shinsaikou.i18n.R
import com.shinsaikou.libraryupdate.LibraryUpdateScheduler
import com.shinsaikou.navigation.SettingsSection
import com.shinsaikou.presentation.core.viewmodel.StateViewModel
import com.shinsaikou.reader.cache.ChapterCache
import com.shinsaikou.tracking.TrackerManager
import com.shinsaikou.ui.main.BatteryExemption
import com.shinsaikou.ui.util.withCurrent
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.network.NetworkPreferences
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Headers
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.ResetCategoryFlags
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.manga.interactor.ResetViewerFlags

/**
 * The settings screen hands its sections the preference objects themselves rather than a snapshot
 * of their values: the vendored `CheckboxItem(label, pref)` and `Preference.collectAsState()`
 * subscribe to a `Preference` directly, so a copy in [State] would be a second source of truth
 * that every write has to remember to refresh.
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(SettingsViewModel::class)
@Inject
class SettingsViewModel(
    val uiPreferences: UiPreferences,
    val libraryPreferences: LibraryPreferences,
    val readerPreferences: ReaderPreferences,
    val sourcePreferences: SourcePreferences,
    val backupPreferences: BackupPreferences,
    val extensionsPreferences: ExtensionsPreferences,
    val basePreferences: BasePreferences,
    val downloadPreferences: DownloadPreferences,
    val storagePreferences: StoragePreferences,
    /** Upstream's networking rows in Advanced read and write it (phase 4 unit SR). */
    val networkPreferences: NetworkPreferences,
    /** The Security and privacy section's row (phase 4 unit SR). */
    val securityPreferences: SecurityPreferences,
    private val extensionManager: ExtensionManager,
    private val libraryUpdateScheduler: LibraryUpdateScheduler,
    private val backupScheduler: BackupScheduler,
    private val batteryExemption: BatteryExemption,
    private val chapterCache: ChapterCache,
    private val downloadManifestRepository: DownloadManifestRepository,
    private val downloadManifest: DownloadManifest,
    private val downloadManifestSweep: DownloadManifestSweep,
    private val adoptionScheduler: AdoptionScheduler,
    private val adoptionSummaryStore: AdoptionSummaryStore,
    /** Export downloads, in the Downloads section (unit storage-location). */
    private val downloadExportScheduler: DownloadExportScheduler,
    private val getCategories: GetCategories,
    /** Upstream's per-category sort row puts every category back on the library's sort when it goes off. */
    private val resetCategoryFlags: ResetCategoryFlags,
    /** The Experimental custom interval switch going off puts every entry back on its estimate. */
    private val clearCustomIntervals: ClearCustomIntervals,
    /** The Data section's row for downloads not in the library counts through it (phase 4 unit ST). */
    private val orphanDownloads: OrphanDownloads,
    /** Upstream's Advanced rows (phase 4 unit ST): reset per-series reader settings, revoke trust. */
    private val resetViewerFlags: ResetViewerFlags,
    /** Advanced's "Rebuild library counts", beside upstream's clear database (phase 5 unit SC4). */
    private val rebuildLibraryCounts: RebuildLibraryCounts,
    private val trustStore: TrustStore,
    /** The version row at the top of Advanced reads it (phase 3 unit AD). */
    val appInfo: AppInfo,
    trackerManager: TrackerManager,
    private val dispatchers: Dispatchers,
) : StateViewModel<SettingsViewModel.State>(State()) {

    /**
     * The sections the list shows. Tracking only while a configured tracker is in the graph; the
     * graph's trackers and their build-time keys are fixed for the process, so this is read once.
     */
    val sections: List<SettingsSection> = shownSettingsSections(
        trackingOffered = trackerManager.trackers.any { it.isConfigured },
    )

    /** Only what a section cannot read off a preference directly; everything else stays there. */
    data class State(
        /**
         * The codes the enabled languages picker offers, `"all"` first and then by display name:
         * every language a source of an available or an installed extension reports, plus every
         * code already in the stored set, so a restored code no source offers can still be
         * unchecked (ruling R57).
         */
        val languageCandidates: List<String> = emptyList(),
        /**
         * Null until the Data section has asked for it, so the row says it is still measuring
         * rather than showing a zero.
         */
        val chapterCacheSize: String? = null,
        /**
         * Downloads whose chapter row is gone or soft deleted and that the sweep's re-match, run
         * just before, could not match to a current chapter (phase 2 decision Q7), by entry title.
         * Null until the Downloads section has counted.
         */
        val orphanDownloads: List<DownloadManifestEntry>? = null,
        /** Whether an adoption of upstream's downloads is running, and how far it has got. */
        val adoption: AdoptionScheduler.State = AdoptionScheduler.State(running = false),
        /** What the last adoption run did; null when none has finished on this device. */
        val adoptionSummary: AdoptionSummary? = null,
        /** Whether an export of the downloads is running, how far it has got, or how the last one ended. */
        val export: DownloadExportScheduler.State = DownloadExportScheduler.State(running = false),
        /**
         * Every category, the system one included, in their order: what the two category rows'
         * dialog lists and their summaries name (phase 3 unit AD).
         */
        val categories: List<Category> = emptyList(),
        /**
         * The titles whose downloads outlived their place in the library, for the Data section's
         * row (phase 4 unit ST). Null until the Data section has counted.
         */
        val orphanTitles: List<OrphanTitle>? = null,
        /** Every download's size in bytes, from the manifest; null until the Data section has read it. */
        val downloadsSize: Long? = null,
    ) {
        /** The rows the delete confirmation names in full, entry title and chapter name. */
        val orphansNamed: List<DownloadManifestEntry>
            get() = orphanDownloads.orEmpty().take(ORPHANS_NAMED)

        /** The rows the confirmation counts as "and N more" after [orphansNamed]. */
        val orphansMore: Int
            get() = orphanDownloads.orEmpty().size - orphansNamed.size
    }

    init {
        viewModelScope.launch {
            combine(
                extensionManager.availableExtensions,
                extensionManager.installedExtensions,
                sourcePreferences.enabledLanguages.withCurrent(),
            ) { available, installed, enabled ->
                languageCandidates(
                    available.flatMap { extension -> extension.sources.map { it.lang } } +
                        installed.flatMap { extension -> extension.sources.map { it.lang } } +
                        enabled,
                )
            }
                .distinctUntilChanged()
                .flowOn(dispatchers.io)
                .collect { candidates -> mutableState.update { it.copy(languageCandidates = candidates) } }
        }
        viewModelScope.launch {
            adoptionScheduler.state.collect { adoption -> mutableState.update { it.copy(adoption = adoption) } }
        }
        viewModelScope.launch {
            downloadExportScheduler.state.collect { export -> mutableState.update { it.copy(export = export) } }
        }
        viewModelScope.launch {
            getCategories.subscribe()
                .flowOn(dispatchers.io)
                .collect { categories -> mutableState.update { it.copy(categories = categories) } }
        }
        viewModelScope.launch {
            adoptionSummaryStore.load()
            adoptionSummaryStore.summary.collect { summary -> mutableState.update { it.copy(adoptionSummary = summary) } }
        }
    }

    /**
     * Starts the adoption of upstream's downloads from the tree the owner picked, unless one is
     * running already. The scheduler takes the read grant on [treeUri] for a run it starts.
     */
    fun startAdoption(treeUri: String) {
        viewModelScope.launch { adoptionScheduler.start(treeUri) }
    }

    /** Export downloads into the storage location's downloads folder, unless an export is running. */
    fun exportDownloads() {
        viewModelScope.launch { withContext(dispatchers.io) { downloadExportScheduler.start() } }
    }

    /**
     * Writing the interval is only half of it: the periodic worker is armed from the stored value,
     * so it has to be re-armed, and the write shares this coroutine because `setup` reads the
     * value back.
     */
    fun setAutoUpdateInterval(hours: Int) {
        viewModelScope.launch(dispatchers.io) {
            libraryPreferences.autoUpdateInterval.set(hours)
            libraryUpdateScheduler.setup()
        }
    }

    /**
     * The device restrictions are the periodic worker's constraints, so a write re-arms it as
     * [setAutoUpdateInterval] does; upstream posts the same re-arm after the write (phase 3 unit AD).
     */
    fun setAutoUpdateDeviceRestrictions(restrictions: Set<String>) {
        viewModelScope.launch(dispatchers.io) {
            libraryPreferences.autoUpdateDeviceRestrictions.set(restrictions)
            libraryUpdateScheduler.setup()
        }
    }

    /**
     * The Experimental section promises that turning a switch off puts things back as they were,
     * so the custom interval going off takes every chosen interval with it. Runs after the row's
     * write. Not cancellable, as the other bulk writes here are: leaving the section clears this
     * view model, and stopping there would leave some entries on a chosen interval.
     */
    fun onCustomUpdateIntervalToggled() {
        if (!libraryPreferences.customUpdateInterval.get()) {
            viewModelScope.launch {
                withContext(NonCancellable + dispatchers.io) { clearCustomIntervals.await() }
            }
        }
    }

    /** The Library section's update categories, from the tri-state dialog: ids as upstream stores them. */
    fun setUpdateCategories(included: Set<String>, excluded: Set<String>) {
        libraryPreferences.updateCategories.set(included)
        libraryPreferences.updateCategoriesExclude.set(excluded)
    }

    /**
     * Upstream's "Per-category settings for sort" (phase 4 unit SR). The row takes the value at once;
     * turning it off also resets every category's own sort to the library's, launched beside the
     * write as upstream's row launches `ResetCategoryFlags`, so a later turn on starts from one sort.
     */
    fun setCategorizedDisplaySettings(enabled: Boolean) {
        libraryPreferences.categorizedDisplaySettings.set(enabled)
        if (!enabled) viewModelScope.launch(dispatchers.io) { resetCategoryFlags.await() }
    }

    /**
     * Upstream's default user agent row (phase 4 unit SR): a value OkHttp would refuse as a header is
     * not written, as upstream's row refuses it. Returns whether it was written.
     */
    fun setDefaultUserAgent(userAgent: String): Boolean {
        try {
            Headers.Builder().add("User-Agent", userAgent)
        } catch (_: IllegalArgumentException) {
            return false
        }
        networkPreferences.defaultUserAgent.set(userAgent)
        return true
    }

    /** Upstream's reset row: the key is deleted, so the network stack reads its default again. */
    fun resetDefaultUserAgent() = networkPreferences.defaultUserAgent.delete()

    /**
     * The DNS over HTTPS row's Custom choice: a [url] [customDohUrl] accepts is written, in its
     * normal form, and chosen. Anything else writes nothing, so the choice before it stands. Returns
     * whether it was written.
     */
    fun setCustomDoh(url: String): Boolean {
        val server = customDohUrl(url) ?: return false
        networkPreferences.dohProviderCustom.set(server.toString())
        networkPreferences.dohProvider.set(PREF_DOH_CUSTOM)
        return true
    }

    /** The Downloads section's new chapter categories, from the same dialog. */
    fun setDownloadNewChapterCategories(included: Set<String>, excluded: Set<String>) {
        downloadPreferences.downloadNewChapterCategories.set(included)
        downloadPreferences.downloadNewChapterCategoriesExclude.set(excluded)
    }

    /**
     * The repeat path for the prompt the first launch shows once. The platform owns the answer, so
     * there is nothing to write down here and nothing to report back.
     */
    fun requestBatteryExemption(activity: Activity) = batteryExemption.request(activity)

    /**
     * `readableSize` walks the whole cache directory, so the Data section asks once when it is
     * shown rather than the value being kept live.
     */
    fun refreshChapterCacheSize() {
        viewModelScope.launch {
            val size = chapterCache.readableSize()
            mutableState.update { it.copy(chapterCacheSize = size) }
        }
    }

    /**
     * The manual clear behind the Data section's row. The size is read back afterwards, so the row
     * shows what is really left (the cache's journal, at least) rather than an assumed zero.
     */
    fun clearChapterCache() {
        viewModelScope.launch {
            chapterCache.clear()
            val size = chapterCache.readableSize()
            mutableState.update { it.copy(chapterCacheSize = size) }
        }
    }

    /**
     * The Data section's storage rows (phase 4 unit ST): every download's size and the titles whose
     * downloads are not in the library, read each time the section is shown, as a removal from the
     * library or a delete on the storage screen may have changed them.
     */
    fun refreshStorage() {
        viewModelScope.launch {
            val titles = orphanDownloads.list()
            val size = downloadManifest.getTotalSizeBytes()
            mutableState.update { it.copy(orphanTitles = titles, downloadsSize = size) }
        }
    }

    /**
     * Upstream's "Reset per-series reader settings": every entry's reading mode and orientation back to
     * the default. The write moves each favourite's display clock and logs it, so the reset reaches
     * every synced device (R82's display pair). Not cancelled by leaving the screen; [onResult]
     * gets whether it was written, for upstream's toast.
     */
    fun resetViewerFlags(onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = withContext(NonCancellable + dispatchers.io) { resetViewerFlags.await() }
            onResult(success)
        }
    }

    /**
     * "Rebuild library counts" (phase 5 unit SC4): the library's per-entry chapter counts written
     * again from the chapters in one transaction, the repair for a summary table that drifted. Not
     * cancelled by leaving the screen; [onResult] gets whether it was written, for the Settings
     * snackbar message its neighbours show.
     */
    fun rebuildLibraryCounts(onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = withContext(NonCancellable + dispatchers.io) { rebuildLibraryCounts.await() }
            onResult(success)
        }
    }

    /**
     * Upstream's "Refresh library covers", as `design/plans/phase4-start.md` section 5 decides it: Saikou
     * has no details-only job, so this is a manual library update with details, which also fetches
     * chapters and feeds auto-download. [onResult] gets the message to show: started, a run
     * already going, or runs waiting in a start without extensions.
     */
    fun refreshLibraryCovers(onResult: (Int) -> Unit) {
        viewModelScope.launch {
            onResult(
                when {
                    libraryUpdateScheduler.startNow(refreshMetadata = true) -> R.string.library_update_started
                    libraryUpdateScheduler.isPaused -> R.string.library_update_paused
                    else -> R.string.library_update_already_running
                },
            )
        }
    }

    /**
     * Upstream's "Revoke trusted unknown extensions": every manual trust goes; an extension signed by a
     * store's key stays trusted. Trust is checked when an extension loads, so it applies at the next
     * start, as upstream's restart toast says.
     */
    fun revokeTrustedExtensions() = trustStore.revokeAll()

    /**
     * Re-matches first, so a download whose chapter came back under a new row is not listed as
     * unmatched only because the start-up sweep has not run since.
     */
    fun refreshOrphanDownloads() {
        viewModelScope.launch {
            val rows = unmatchedDownloads()
            mutableState.update { it.copy(orphanDownloads = rows) }
        }
    }

    /**
     * Re-matches and re-reads at the moment of the delete, so a row that has a chapter again keeps
     * its files, and deletes only rows the confirmation was showing: one that became unmatched
     * after it opened was never named to the owner. [DownloadManifest] removes each folder before
     * its row.
     */
    fun deleteOrphanDownloads() {
        val confirmed = state.value.orphanDownloads.orEmpty().mapTo(mutableSetOf()) { it.chapterId }
        if (confirmed.isEmpty()) return
        viewModelScope.launch {
            val ids = unmatchedDownloads().map { it.chapterId }.filter { it in confirmed }
            if (ids.isNotEmpty()) downloadManifest.delete(ids)
            val rows = unmatchedDownloads()
            mutableState.update { it.copy(orphanDownloads = rows) }
        }
    }

    private suspend fun unmatchedDownloads(): List<DownloadManifestEntry> {
        downloadManifestSweep.rematch()
        return downloadManifestRepository.getWithoutChapter()
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.mangaTitle })
    }

    fun setBackupInterval(hours: Int) {
        viewModelScope.launch(dispatchers.io) {
            backupPreferences.backupInterval.set(hours)
            backupScheduler.setup()
        }
    }

    /**
     * The "Enabled languages" dialog's rows, in [languageOrder], the order the source and extension
     * filters list languages in (phase 5 unit BX), with no enabled half: the dialog edits a working
     * copy, and an enabled-first order would move a row while the owner ticks it.
     */
    private fun languageCandidates(codes: List<String>): List<String> = codes
        .distinct()
        .sortedWith(languageOrder(emptySet()))

    private companion object {
        /** How many unmatched downloads the delete confirmation names before "and N more". */
        const val ORPHANS_NAMED = 5
    }
}

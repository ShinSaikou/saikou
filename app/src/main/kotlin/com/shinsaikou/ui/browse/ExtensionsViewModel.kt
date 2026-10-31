package com.shinsaikou.ui.browse

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.preferences.SourcePreferences
import com.shinsaikou.extensions.ExtensionManager
import com.shinsaikou.extensions.loader.LibVersionPolicy
import com.shinsaikou.extensions.loader.LoadResult
import com.shinsaikou.extensions.model.Extension
import com.shinsaikou.extensions.store.UpdateExtensionStores
import com.shinsaikou.i18n.R
import com.shinsaikou.presentation.core.viewmodel.StateViewModel
import com.shinsaikou.ui.util.withCurrent
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.extension.model.InstallStep
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.extension.interactor.GetExtensionStoreCountAsFlow

/**
 * One row of the extension list. The extension itself travels with the row so the screen can draw
 * an icon, a version and a language without a second lookup, but every action the row offers is
 * dispatched by package name against the manager's current state: a row composed from an older
 * emission must not be able to install a version the manager has already replaced.
 */
sealed interface ExtensionUi {
    val packageName: String

    /** Installed, and an index carries a newer version or lib version. */
    data class Update(val ext: Extension.Installed, val available: Extension.Available?) : ExtensionUi {
        override val packageName: String get() = ext.packageName
    }

    /**
     * @property updateNeedsNewerApp an index carries a newer version, built for a lib version this
     *   host does not load, so it is shown rather than offered.
     */
    data class Installed(val ext: Extension.Installed, val updateNeedsNewerApp: Boolean = false) : ExtensionUi {
        override val packageName: String get() = ext.packageName
    }

    /** @property hasUpdate an index carries a newer version, which may be signed by a key it trusts. */
    data class Untrusted(val ext: Extension.Untrusted, val hasUpdate: Boolean = false) : ExtensionUi {
        override val packageName: String get() = ext.packageName
    }

    /**
     * On disk and refused by the loader. A file that could not be read as a package at all has no
     * package name, so this is the one row whose [packageName] can be empty.
     *
     * @property hasUpdate an index carries a newer version, which is often what gets it loading again.
     */
    data class LoadError(val error: LoadResult.Error, val hasUpdate: Boolean = false) : ExtensionUi {
        override val packageName: String get() = error.packageName.orEmpty()
    }

    /** @property libSupported false when the entry is built for a lib version this host does not load. */
    data class Available(val ext: Extension.Available, val libSupported: Boolean = true) : ExtensionUi {
        override val packageName: String get() = ext.packageName
    }
}

/** The headings the list is cut into, in the order they are rendered. */
sealed interface ExtensionGroup {
    data object Updates : ExtensionGroup

    data object Installed : ExtensionGroup

    data object Untrusted : ExtensionGroup

    data object Errors : ExtensionGroup

    /** One per language of the available entries; [code] is a source contract language code. */
    data class AvailableLanguage(val code: String) : ExtensionGroup
}

/**
 * The Extensions page of the Browse tab. Every list here is the extension manager's own state flow
 * projected into groups, so an install that finishes while the page is open moves its row from an
 * available language group to Installed without a refresh.
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(ExtensionsViewModel::class)
@Inject
class ExtensionsViewModel(
    private val extensionManager: ExtensionManager,
    private val sourcePreferences: SourcePreferences,
    private val updateExtensionStores: UpdateExtensionStores,
    private val getExtensionStoreCountAsFlow: GetExtensionStoreCountAsFlow,
    private val libVersionPolicy: LibVersionPolicy,
    private val dispatchers: Dispatchers,
) : StateViewModel<ExtensionsViewModel.State>(State()) {

    @Immutable
    data class State(
        val isInitialized: Boolean = false,
        val isRefreshing: Boolean = false,
        val storeCount: Long = 0,
        val groups: List<Pair<ExtensionGroup, List<ExtensionUi>>> = emptyList(),
        val installSteps: Map<String, InstallStep> = emptyMap(),
        /** Why a package's last install or update failed, shown on its row beside Retry. */
        val installFailures: Map<String, Throwable> = emptyMap(),
        /** Every pending update, before the search query narrows anything. */
        val updatesCount: Int = 0,
        val searchQuery: String? = null,
        /**
         * The last refresh failure, kept as the throwable rather than as text: `ErrorText` needs
         * `Resources` and the screen is what has them, the same split the snackbar events use.
         */
        val refreshError: Throwable? = null,
        val dialog: Dialog? = null,
    ) {
        val isEmpty: Boolean get() = groups.isEmpty()

        val hasUpdates: Boolean get() = updatesCount > 0
    }

    sealed interface Dialog {
        /** Shown before [trust], so the signer being vouched for is on screen first. */
        data class Trust(val ext: Extension.Untrusted) : Dialog

        /** [row] is any row with something on disk: installed, untrusted or refused by the loader. */
        data class Uninstall(val row: ExtensionUi) : Dialog

        data class LoadErrorDetail(val error: LoadResult.Error) : Dialog
    }

    sealed interface Event {
        data class Snackbar(@StringRes val messageRes: Int) : Event

        /** ErrorText needs Resources, so the screen is what formats [error] into [messageRes]. */
        data class SnackbarError(@StringRes val messageRes: Int, val error: Throwable) : Event
    }

    private val eventChannel = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = eventChannel.receiveAsFlow()

    private val searchQuery = MutableStateFlow<String?>(null)

    /**
     * The install job per package, so the row's cancel button has something to cancel. Entries are
     * not removed when a job finishes: cancelling a completed job does nothing, the map holds at
     * most one entry per package installed this session, and removing on completion would mean
     * touching this map off the main thread.
     */
    private val installJobs = mutableMapOf<String, Job>()

    init {
        viewModelScope.launch {
            combine(
                managerState(),
                extensionManager.availableExtensions,
                combine(extensionManager.installSteps, extensionManager.installFailures, ::Installs),
                environment(),
                searchQuery,
            ) { manager, available, installs, environment, query ->
                project(manager, available, installs, environment, query)
            }
                .flowOn(dispatchers.io)
                // The refresh flag, the refresh error and the dialog belong to the screen rather
                // than to the manager, so they are carried over inside the atomic update. A
                // refresh ends on the same emission it caused, and an install step arriving while
                // a dialog is up would otherwise close it under the owner's finger.
                .collect { projected ->
                    mutableState.update { current ->
                        projected.copy(
                            isRefreshing = current.isRefreshing,
                            refreshError = current.refreshError,
                            dialog = current.dialog,
                        )
                    }
                }
        }
        refreshIfNothingAvailable()
    }

    /**
     * The available list lives in memory only and the startup check skips a fetch younger than six
     * hours, so after most cold starts the page would open with no available extensions and no
     * updates until a pull. Opening the page fetches once in that case, as upstream's does (ruling
     * R46). This is the index fetch alone: the store pass and the snackbar belong to a refresh the
     * owner asked for, and a failure still reaches the banner.
     */
    private fun refreshIfNothingAvailable() {
        if (extensionManager.availableExtensions.value.isNotEmpty()) return
        mutableState.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            val result = runCatching {
                withContext(dispatchers.io) { extensionManager.refreshAvailable() }
            }.getOrElse { Result.failure(it) }
            mutableState.update { it.copy(isRefreshing = false, refreshError = result.exceptionOrNull()) }
        }
    }

    /**
     * The store pass runs first: a renamed repository or a rotated signing key has to be on disk
     * before the index fetch decides what is trusted.
     */
    fun refresh() {
        if (state.value.isRefreshing) return
        mutableState.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            // runCatching around the whole block, not just the Result that refreshAvailable
            // returns. The store refetch ahead of it reads the database and can throw, and a throw
            // here would leave isRefreshing true for the life of the view model: the spinner would
            // never stop, the guard above would swallow every later refresh, and no event would
            // ever say why.
            val result = runCatching {
                withContext(dispatchers.io) {
                    updateExtensionStores.await()
                    extensionManager.refreshAvailable()
                }
            }.getOrElse { Result.failure(it) }
            mutableState.update { it.copy(isRefreshing = false, refreshError = result.exceptionOrNull()) }
            eventChannel.send(
                result.fold(
                    onSuccess = { Event.Snackbar(R.string.ext_stores_refreshed) },
                    onFailure = { Event.SnackbarError(R.string.ext_refresh_failed, it) },
                ),
            )
        }
    }

    /** Clears the banner without retrying: the installed extensions below it still work. */
    fun dismissRefreshError() {
        mutableState.update { it.copy(refreshError = null) }
    }

    fun install(available: Extension.Available) {
        val target = extensionManager.availableExtensions.value
            .firstOrNull { it.packageName == available.packageName } ?: return
        installJobs[target.packageName] = extensionManager.install(target)
    }

    fun update(installed: Extension.Installed) {
        val target = extensionManager.installedExtensions.value
            .firstOrNull { it.packageName == installed.packageName } ?: return
        installJobs[target.packageName] = extensionManager.update(target)
    }

    /** Updates a package on disk that did not load, while the manager still lists an update for it. */
    fun updateNotLoaded(packageName: String) {
        if (packageName !in extensionManager.notLoadedUpdates.value) return
        installJobs[packageName] = extensionManager.updatePackage(packageName)
    }

    /** Every pending update at once, loaded or not, whatever the search query is currently hiding. */
    fun updateAll() {
        extensionManager.updates.value.forEach(::update)
        extensionManager.notLoadedUpdates.value.forEach(::updateNotLoaded)
    }

    /**
     * Stops an install or an update. The manager drops the package's step when its job is
     * cancelled, so the row goes back to its ordinary button rather than spinning for ever.
     */
    fun cancelInstall(packageName: String) {
        installJobs.remove(packageName)?.cancel()
    }

    /** A row that did not load goes by its load result, which names the file when there is no package. */
    fun uninstall(row: ExtensionUi) {
        dismissDialog()
        viewModelScope.launch {
            withContext(dispatchers.io) {
                if (row is ExtensionUi.LoadError) {
                    extensionManager.uninstall(row.error)
                } else {
                    extensionManager.uninstall(row.packageName)
                }
            }
            eventChannel.send(Event.Snackbar(R.string.ext_uninstalled))
        }
    }

    fun trust(untrusted: Extension.Untrusted) {
        dismissDialog()
        val target = extensionManager.untrustedExtensions.value
            .firstOrNull { it.packageName == untrusted.packageName } ?: return
        viewModelScope.launch {
            withContext(dispatchers.io) { extensionManager.trust(target) }
        }
    }

    fun search(query: String?) {
        searchQuery.value = query
    }

    fun showDialog(dialog: Dialog) {
        mutableState.update { it.copy(dialog = dialog) }
    }

    fun dismissDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    /**
     * Six of the manager's flows in one slot. `combine` takes at most five typed sources, and the
     * projection needs more than that, so they are folded a level at a time; the two update flows
     * share a slot of their own.
     *
     * The flag and the three lists the first load publishes are read here, flag first, instead of
     * taken from the combine (ruling R69 item 3): its collectors resume on their own, so the flag's
     * can run before the lists' and pair a true flag with the lists from before the load, which
     * drops the spinner over a page with no installed rows for a frame. The manager publishes the
     * lists before it sets the flag, so a true flag read first always comes with the loaded lists.
     */
    private fun managerState(): Flow<ManagerState> = combine(
        extensionManager.isInitialized,
        combine(extensionManager.updates, extensionManager.notLoadedUpdates, ::Pair),
        extensionManager.installedExtensions,
        extensionManager.untrustedExtensions,
        extensionManager.loadErrors,
    ) { _, (updates, notLoadedUpdates), _, _, _ ->
        val initialized = extensionManager.isInitialized.value
        ManagerState(
            isInitialized = initialized,
            updates = updates,
            notLoadedUpdates = notLoadedUpdates,
            installed = extensionManager.installedExtensions.value,
            untrusted = extensionManager.untrustedExtensions.value,
            loadErrors = extensionManager.loadErrors.value,
        )
    }

    private fun environment(): Flow<Environment> = combine(
        getExtensionStoreCountAsFlow(),
        sourcePreferences.enabledLanguages.withCurrent(),
        sourcePreferences.showNsfwSource.withCurrent(),
    ) { storeCount, languages, showNsfw -> Environment(storeCount, languages, showNsfw) }

    private fun project(
        manager: ManagerState,
        available: List<Extension.Available>,
        installs: Installs,
        environment: Environment,
        query: String?,
    ): State {
        // Installed extensions answer to the mature sources switch too, as in upstream (ruling R55).
        // The count and Update all still cover every pending update: hiding a row is not the same
        // as declining its update.
        val updatePackages = manager.updates.mapTo(mutableSetOf()) { it.packageName }
        val updateRows = manager.updates
            .filter { environment.showNsfw || !it.isNsfw }
            .sortedBy { it.name.lowercase() }
            .map { ext -> ExtensionUi.Update(ext, available.firstOrNull { it.packageName == ext.packageName }) }
        val installedRows = manager.installed
            .filterNot { it.packageName in updatePackages }
            .filter { environment.showNsfw || !it.isNsfw }
            .sortedBy { it.name.lowercase() }
            .map { ext ->
                val newer = available.firstOrNull { it.packageName == ext.packageName }
                    ?.takeIf { it.versionCode > ext.versionCode || it.libVersion > ext.libVersion }
                ExtensionUi.Installed(ext, updateNeedsNewerApp = newer != null && !libVersionPolicy.isSupported(newer.libVersion))
            }
        val untrustedRows = manager.untrusted
            .sortedBy { it.name.lowercase() }
            .map { ExtensionUi.Untrusted(it, hasUpdate = it.packageName in manager.notLoadedUpdates) }
        val errorRows = manager.loadErrors
            .sortedBy { it.packageName.orEmpty() }
            .map { ExtensionUi.LoadError(it, hasUpdate = it.packageName in manager.notLoadedUpdates) }

        // An index lists what is already on disk too, in any of its three states. Dropping those
        // keeps one row per package, which is also what lets every group share one lazy list.
        val onDisk = buildSet {
            manager.installed.forEach { add(it.packageName) }
            manager.untrusted.forEach { add(it.packageName) }
            manager.loadErrors.forEach { error -> error.packageName?.let(::add) }
        }
        val installable = available
            .filterNot { it.packageName in onDisk }
            .filter { environment.showNsfw || !it.isNsfw }
            .filter { environment.allows(it) }
            .sortedBy { it.name.lowercase() }

        val groups = buildList {
            add(ExtensionGroup.Updates to updateRows)
            add(ExtensionGroup.Installed to installedRows)
            add(ExtensionGroup.Untrusted to untrustedRows)
            add(ExtensionGroup.Errors to errorRows)
            installable.groupBy { it.lang }
                .entries
                .sortedWith(compareBy({ LANGUAGE_ORDER.indexOf(it.key).takeIf { i -> i >= 0 } ?: LANGUAGE_ORDER.size }, { it.key }))
                .forEach { (code, entries) ->
                    add(
                        ExtensionGroup.AvailableLanguage(code) to
                            entries.map { ExtensionUi.Available(it, libVersionPolicy.isSupported(it.libVersion)) },
                    )
                }
        }
            .map { (group, rows) -> group to rows.filter { it.matches(query) } }
            .filter { (_, rows) -> rows.isNotEmpty() }

        return State(
            isInitialized = manager.isInitialized,
            storeCount = environment.storeCount,
            groups = groups,
            installSteps = installs.steps,
            installFailures = installs.failures,
            updatesCount = manager.updates.size + manager.notLoadedUpdates.size,
            searchQuery = query,
        )
    }

    /** Name, package and the names of the sources the entry provides, as the contract asks. */
    private fun ExtensionUi.matches(query: String?): Boolean {
        val needle = query?.trim()?.takeIf { it.isNotEmpty() } ?: return true
        val haystack = when (this) {
            is ExtensionUi.Update -> listOf(ext.name, ext.packageName) + ext.sources.map { it.name }
            is ExtensionUi.Installed -> listOf(ext.name, ext.packageName) + ext.sources.map { it.name }
            is ExtensionUi.Available -> listOf(ext.name, ext.packageName) + ext.sources.map { it.name }
            is ExtensionUi.Untrusted -> listOf(ext.name, ext.packageName)
            is ExtensionUi.LoadError -> listOfNotNull(packageName, error.name)
        }
        return haystack.any { it.contains(needle, ignoreCase = true) }
    }

    /** The manager's list flows, carried through one `combine` slot. */
    private data class ManagerState(
        val isInitialized: Boolean,
        val updates: List<Extension.Installed>,
        val notLoadedUpdates: Set<String>,
        val installed: List<Extension.Installed>,
        val untrusted: List<Extension.Untrusted>,
        val loadErrors: List<LoadResult.Error>,
    )

    /** The manager's two install flows, carried through one `combine` slot. */
    private data class Installs(val steps: Map<String, InstallStep>, val failures: Map<String, Throwable>)

    /** What the rest of the app decides about the available half of the list. */
    private data class Environment(
        val storeCount: Long,
        val enabledLanguages: Set<String>,
        val showNsfw: Boolean,
    ) {
        /**
         * Ruling R57: `"all"` is one more language code, the one multi-language sources report,
         * and is matched literally as upstream matches it. An entry shows when any of its sources is
         * in an enabled language, so a multi-language extension appears for an owner who enabled
         * only one of its languages. An entry that lists no sources falls back to its own code. The
         * extension filter offers the same codes ([languages]).
         */
        fun allows(extension: Extension.Available): Boolean =
            extension.languages().any { it in enabledLanguages }
    }

    private companion object {
        const val ALL_LANGUAGES = "all"

        /** Language groups read in this order, then alphabetically by code. */
        val LANGUAGE_ORDER = listOf(ALL_LANGUAGES, "en")
    }
}

package com.shinsaikou.extensions

import com.shinsaikou.core.common.crash.StartMode
import com.shinsaikou.domain.extension.repository.ExtensionStoreRepository
import com.shinsaikou.domain.preferences.ExtensionsPreferences
import com.shinsaikou.extensions.install.ExtensionInstaller
import com.shinsaikou.extensions.loader.ExtensionLoader
import com.shinsaikou.extensions.loader.LibVersionPolicy
import com.shinsaikou.extensions.loader.LoadResult
import com.shinsaikou.extensions.model.Extension
import com.shinsaikou.extensions.repo.ExtensionRepoClient
import com.shinsaikou.extensions.repo.FetchedIndex
import com.shinsaikou.extensions.repo.RepoRefreshException
import com.shinsaikou.extensions.trust.TrustStore
import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceProvider
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.extension.model.InstallStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import kotlin.time.Duration.Companion.hours

/**
 * The one object the rest of the app talks to about extensions. It drives the loader, the trust
 * store, the index client and the installer, owns the state flows the extension screens render, and
 * publishes the loaded sources as a [SourceProvider].
 *
 * Nothing here runs on the main thread beyond [start]'s first statement. Every mutation of the
 * three package maps goes through one mutex-guarded function, so two concurrent installs cannot
 * lose a publication.
 *
 * In a process started without extensions ([StartMode]) no extension is loaded, not even one
 * installed or updated meanwhile: each package on disk is listed among the [loadErrors] as
 * [com.shinsaikou.extensions.loader.LoadError.EXTENSIONS_OFF], where it keeps its Uninstall and
 * its update, which is how the extension that took the app down can be removed or replaced.
 */
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class)
@Inject
class ExtensionManager(
    private val loader: ExtensionLoader,
    private val trustStore: TrustStore,
    private val repoClient: ExtensionRepoClient,
    private val stores: ExtensionStoreRepository,
    private val installer: ExtensionInstaller,
    private val preferences: ExtensionsPreferences,
    private val libVersionPolicy: LibVersionPolicy,
    private val startMode: StartMode,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SourceProvider {

    private val mutex = Mutex()

    private val _isInitialized = MutableStateFlow(false)

    /**
     * True once the first [loadAll] pass finished, whether or not anything loaded, and set only
     * after that pass's sources are published. A pass that throws still sets it, with nothing
     * loaded, so nothing waiting on the registry hangs.
     */
    override val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _availableExtensions = MutableStateFlow<List<Extension.Available>>(emptyList())

    /** Everything the configured indexes list. Empty until [refreshAvailable]. */
    val availableExtensions: StateFlow<List<Extension.Available>> = _availableExtensions.asStateFlow()

    private val _installedExtensions = MutableStateFlow<List<Extension.Installed>>(emptyList())

    /** Loaded and trusted. */
    val installedExtensions: StateFlow<List<Extension.Installed>> = _installedExtensions.asStateFlow()

    private val _untrustedExtensions = MutableStateFlow<List<Extension.Untrusted>>(emptyList())

    /** On disk but not trusted; each carries the signer hash the user would be vouching for. */
    val untrustedExtensions: StateFlow<List<Extension.Untrusted>> = _untrustedExtensions.asStateFlow()

    private val _loadErrors = MutableStateFlow<List<LoadResult.Error>>(emptyList())

    /** Extensions on disk that did not load, with the reason. */
    val loadErrors: StateFlow<List<LoadResult.Error>> = _loadErrors.asStateFlow()

    private val _installSteps = MutableStateFlow<Map<String, InstallStep>>(emptyMap())

    /** Per-package install progress for the browse UI. */
    val installSteps: StateFlow<Map<String, InstallStep>> = _installSteps.asStateFlow()

    private val _installFailures = MutableStateFlow<Map<String, Throwable>>(emptyMap())

    /**
     * Why the last install or update of a package failed, for as long as its step reads
     * [InstallStep.Error]: an [com.shinsaikou.extensions.model.InstallFailedException] when the installer
     * refused the file, else the download's own failure. [InstallStep] is upstream's enum and has
     * no room for a reason, and without one a refused update looks exactly like nothing happening.
     */
    val installFailures: StateFlow<Map<String, Throwable>> = _installFailures.asStateFlow()

    private val _refreshingStores = MutableStateFlow<Set<String>>(emptySet())

    /** The index URLs [refreshStore] is reading right now, for the screens' loading state. */
    val refreshingStores: StateFlow<Set<String>> = _refreshingStores.asStateFlow()

    private val _storeRefreshFailures = MutableSharedFlow<Throwable>(extraBufferCapacity = 1)

    /**
     * Each failure of [refreshStore], a [RepoRefreshException] as a refresh's would be, for the
     * Extensions page's banner: the fetch outlives the repository screen that asked for it. An add
     * that failed after its screen closed comes here too ([reportStoreFailure]).
     */
    val storeRefreshFailures: SharedFlow<Throwable> = _storeRefreshFailures.asSharedFlow()

    /** What each configured index listed at its last successful fetch this process, by index URL. Guarded by [mutex]. */
    private val lastListed = mutableMapOf<String, List<Extension.Available>>()

    private val _sources = MutableStateFlow<List<Source>>(emptyList())

    override val sources: StateFlow<List<Source>> = _sources.asStateFlow()

    /**
     * Installed extensions with a newer version or lib version in an index, built for a lib version
     * this host loads. An entry the catalog moved to a newer lib is not an update here: installing
     * it would only replace a working extension with one that cannot load.
     */
    val updates: StateFlow<List<Extension.Installed>> = _installedExtensions
        .map { list -> list.filter { it.hasUpdate } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * Packages on disk that did not load, untrusted or refused by the loader, with an update in an
     * index (upstream's 2d86a70ce). An update is often what gets such an extension loading again,
     * so it is offered, counted and covered by Update all like an installed one's.
     */
    val notLoadedUpdates: StateFlow<Set<String>> =
        combine(_untrustedExtensions, _loadErrors, _availableExtensions, ::notLoadedUpdatePackages)
            .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /**
     * Called once from the application after the Injekt singletons are registered. The index
     * refresh here is the automatic check, so it runs only while
     * [ExtensionsPreferences.autoCheckUpdates] is on and the last fetch is older than six hours.
     *
     * The pending update count is not written from this pass. The available list is in memory
     * only, so on a cold start that skips the refresh no installed extension can know it has an
     * update, and a count taken then would overwrite the correct one persisted by the last
     * process. It is written only where it is known (ruling R46): after an index fetch, after a
     * load or an uninstall while the available list is loaded, and here only when nothing is
     * installed, where zero is the answer without an index.
     *
     * Without extensions the pass reads the packages instead of loading them, still turns the flag,
     * and stops there: no index is fetched and the count stays the last process's.
     */
    fun start() {
        scope.launch {
            val results = try {
                withContext(ioDispatcher) {
                    if (startMode.withoutExtensions) loader.readAll() else loader.loadAll()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Nothing is known about what is installed, so neither the refresh nor the count
                // below runs; the flag still turns, or every worker waiting on the registry hangs.
                this@ExtensionManager.logcat(LogPriority.ERROR, e) { "The first extension load failed" }
                _isInitialized.value = true
                return@launch
            }
            publish(results)
            _isInitialized.value = true
            if (startMode.withoutExtensions) return@launch

            if (preferences.autoCheckUpdates.get()) {
                val hasStores = withContext(ioDispatcher) { stores.getAll().isNotEmpty() }
                val age = System.currentTimeMillis() - preferences.lastIndexRefresh.get()
                if (hasStores && age >= REFRESH_INTERVAL_MS) {
                    refreshAvailable()
                }
            }

            if (_installedExtensions.value.isEmpty() && _untrustedExtensions.value.isEmpty() && _loadErrors.value.isEmpty()) {
                preferences.extensionUpdatesCount.set(0)
            }
        }
    }

    /**
     * Re-fetches every configured index and recomputes [availableExtensions] and [updates].
     *
     * An index that fails keeps the entries it listed at its last successful fetch this process, so
     * one repository that is down does not quietly take its extensions out of the list. The result
     * is a failure whenever any index failed, a [RepoRefreshException] naming each; what the others
     * listed is published all the same. When every index fails, nothing is published.
     */
    suspend fun refreshAvailable(): Result<Unit> {
        val rows = withContext(ioDispatcher) { stores.getAll() }
        if (rows.isEmpty()) {
            mutex.withLock { lastListed.clear() }
            _availableExtensions.value = emptyList()
            return Result.success(Unit)
        }

        val outcomes = supervisorScope {
            rows.map { row ->
                async(ioDispatcher) {
                    val result = repoClient.fetch(row.indexUrl)
                        .mapCatching { index -> index to repoClient.extensions(index).getOrThrow() }
                    result.exceptionOrNull()?.let { this@ExtensionManager.logcat(LogPriority.WARN, it) { "Index refresh failed" } }
                    row to result
                }
            }.awaitAll()
        }
        val fetched = outcomes.mapNotNull { (row, result) -> result.getOrNull()?.let { row to it } }
        val failures = outcomes.mapNotNull { (row, result) ->
            result.exceptionOrNull()?.let { RepoRefreshException.Failure(row, it) }
        }

        if (fetched.isEmpty()) {
            return Result.failure(RepoRefreshException(failures))
        }

        val merged = mutex.withLock {
            // The configuration as it is now, not as it was before the fetch: a repository removed
            // meanwhile had nothing for forgetStore to drop yet, and what it listed must not come
            // back here. A remove commits before its forgetStore takes the lock, so whichever of the
            // two takes it first, the other one sees the removal.
            val configured = withContext(ioDispatcher) { stores.getAll() }.mapTo(mutableSetOf()) { it.indexUrl }
            lastListed.keys.retainAll(configured)
            fetched.forEach { (row, index) -> if (row.indexUrl in configured) lastListed[row.indexUrl] = index.second }
            publishListed()
        }
        preferences.lastIndexRefresh.set(System.currentTimeMillis())
        warnOnSourceIdDrift(fetched.map { it.second.first }, merged)
        return if (failures.isEmpty()) Result.success(Unit) else Result.failure(RepoRefreshException(failures))
    }

    /**
     * Reads the one configured index at [indexUrl], a repository just added, and merges what it
     * lists into [availableExtensions], so the Extensions page offers it without a refresh. The read
     * runs in the manager's scope, as an install does, and goes on after the screen that asked for
     * it has closed; [refreshingStores] holds [indexUrl] meanwhile, and a failure also reaches
     * [storeRefreshFailures].
     *
     * While another configured index has not been read this process every index is read instead,
     * as [refreshAvailable] reads them: a list built from this index alone would call every
     * extension from the others obsolete and drop their updates.
     */
    fun refreshStore(indexUrl: String): Deferred<Result<Unit>> {
        // Marked before the read is dispatched, so the page never draws a frame of "nothing to
        // show" in between; ATOMIC starts the body whatever happens, so the mark always comes off.
        _refreshingStores.update { it + indexUrl }
        return scope.async(start = CoroutineStart.ATOMIC) { refreshMarkedStore(indexUrl) }
    }

    /**
     * Passes [error] to [storeRefreshFailures]: a repository add that failed once the screen that
     * started it had closed, so the Extensions page's banner says why it never appeared.
     */
    fun reportStoreFailure(error: Throwable) {
        _storeRefreshFailures.tryEmit(error)
    }

    private suspend fun refreshMarkedStore(indexUrl: String): Result<Unit> {
        try {
            // The table read can throw too, and the caller awaits a result, never a throw.
            val result = try {
                readStore(indexUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
            return result.onFailure { _storeRefreshFailures.tryEmit(it) }
        } finally {
            _refreshingStores.update { it - indexUrl }
        }
    }

    private suspend fun readStore(indexUrl: String): Result<Unit> {
        val rows = withContext(ioDispatcher) { stores.getAll() }
        val row = rows.firstOrNull { it.indexUrl == indexUrl } ?: return Result.success(Unit)
        val othersRead = mutex.withLock { rows.all { it.indexUrl == indexUrl || it.indexUrl in lastListed } }
        if (!othersRead) return refreshAvailable()

        val result = repoClient.fetch(indexUrl)
            .mapCatching { index -> index to repoClient.extensions(index).getOrThrow() }
        val (index, listed) = result.getOrElse { error ->
            logcat(LogPriority.WARN, error) { "Index fetch failed" }
            return Result.failure(RepoRefreshException(listOf(RepoRefreshException.Failure(row, error))))
        }
        val merged = mutex.withLock {
            // Removed while it was read: whatever it listed must not come back after forgetStore.
            if (withContext(ioDispatcher) { stores.get(indexUrl) } == null) return Result.success(Unit)
            lastListed[indexUrl] = listed
            publishListed()
        }
        warnOnSourceIdDrift(listOf(index), merged)
        return Result.success(Unit)
    }

    /**
     * Takes what the index at [indexUrl] listed out of [availableExtensions], once its repository has
     * been removed, so the Extensions page stops offering it without a refresh. An extension
     * installed from it stays installed and now reads as obsolete. An index never read this process
     * listed nothing here, and nothing changes.
     */
    suspend fun forgetStore(indexUrl: String) {
        mutex.withLock {
            if (lastListed.remove(indexUrl) == null) return
            publishListed()
        }
    }

    /**
     * Caller holds [mutex]. Publishes [lastListed] as [availableExtensions], one row per package with
     * the highest version code winning when two indexes carry the same one, and recomputes the
     * update state from it. Just after an index fetch the count is certain to be known, so it is
     * written whatever it is.
     */
    private fun publishListed(): List<Extension.Available> {
        if (lastListed.isEmpty()) {
            // No index left read, the last one removed: nothing is an update or obsolete any more,
            // which is what a start that has read none knows too.
            _availableExtensions.value = emptyList()
            _installedExtensions.value = _installedExtensions.value.map { it.copy(hasUpdate = false, isObsolete = false) }
            preferences.extensionUpdatesCount.set(pendingUpdateCount())
            return emptyList()
        }
        val merged = lastListed.values.flatten().onePerPackage()
        _availableExtensions.value = merged
        _installedExtensions.value = _installedExtensions.value.map { it.withUpdateState(merged) }
        preferences.extensionUpdatesCount.set(pendingUpdateCount())
        return merged
    }

    /** Downloads, verifies, installs privately and loads. Progress reaches [installSteps]. */
    fun install(extension: Extension.Available): Job = scope.launch {
        runInstall(extension.packageName, extension.apkUrl)
    }

    /**
     * [install] in the caller's coroutine, for a run that installs several in turn: returns once
     * the install ended, true when it installed, and a cancel of the caller cancels it. Progress
     * and a failure's reason reach [installSteps] and [installFailures] as for [install].
     */
    suspend fun installAndWait(extension: Extension.Available): Boolean {
        runInstall(extension.packageName, extension.apkUrl)
        return _installSteps.value[extension.packageName] == InstallStep.Installed
    }

    /**
     * What the indexes at [indexUrls], configured rows' addresses, listed at their last successful
     * fetch this process, merged as [availableExtensions] is. The restore installs unattended only
     * from the repositories the owner had before it, and the merged list can carry another
     * repository's entry for the same package.
     */
    suspend fun availableFrom(indexUrls: Set<String>): List<Extension.Available> = mutex.withLock {
        lastListed.filterKeys { it in indexUrls }.values.flatten().onePerPackage()
    }

    /** Every package on disk: loaded, untrusted, or refused by the loader. */
    fun packagesOnDisk(): Set<String> = buildSet {
        _installedExtensions.value.mapTo(this) { it.packageName }
        _untrustedExtensions.value.mapTo(this) { it.packageName }
        _loadErrors.value.mapNotNullTo(this) { it.packageName }
    }

    /** Same as [install], for a package that is already installed and has an update. */
    fun update(extension: Extension.Installed): Job = updatePackage(extension.packageName)

    /** Same as [update], for any package on disk, loaded or not ([notLoadedUpdates]). */
    fun updatePackage(packageName: String): Job = scope.launch {
        val available = _availableExtensions.value.firstOrNull { it.packageName == packageName }
        if (available == null) {
            this@ExtensionManager.logcat(LogPriority.WARN) { "No index entry to update from" }
            return@launch
        }
        runInstall(available.packageName, available.apkUrl)
    }

    /**
     * Removes the `.ext` file and drops the sources. Nothing in the library is deleted; the source
     * manager answers those ids with a stub from now on.
     */
    suspend fun uninstall(packageName: String) {
        loader.uninstallPrivateExtension(packageName)
        mutex.withLock {
            _installedExtensions.value = _installedExtensions.value.filterNot { it.packageName == packageName }
            _untrustedExtensions.value = _untrustedExtensions.value.filterNot { it.packageName == packageName }
            _loadErrors.value = _loadErrors.value.filterNot { it.packageName == packageName }
            _installSteps.value = _installSteps.value - packageName
            _installFailures.value = _installFailures.value - packageName
            republishSources()
            writeUpdatesCountIfKnown()
        }
    }

    /**
     * Removes an extension that did not load: by its package when it has one, else the file that
     * could not be read as a package at all, which would otherwise sit in the list for good.
     */
    suspend fun uninstall(error: LoadResult.Error) {
        error.packageName?.let { return uninstall(it) }
        val file = error.file ?: return
        loader.deleteUnreadable(file)
        mutex.withLock {
            _loadErrors.value = _loadErrors.value.filterNot { it.packageName == null && it.file == file }
        }
    }

    /** Records a manual trust override for one version, then reloads that package. */
    suspend fun trust(extension: Extension.Untrusted) {
        trustStore.trust(extension.packageName, extension.versionCode, extension.signatureHash)
        mutex.withLock {
            _untrustedExtensions.value = _untrustedExtensions.value.filterNot { it.packageName == extension.packageName }
        }
        reload(extension.packageName)
    }

    /**
     * Reloads one installed package; used after a trust override and after an update. Without
     * extensions the package is only read, so an install or an update this process lists it as off.
     */
    suspend fun reload(packageName: String): LoadResult? {
        val result = when {
            startMode.withoutExtensions -> loader.readPackage(packageName)
            else -> loader.loadPackage(packageName)
        } ?: return null
        mutex.withLock { apply(packageName, result) }
        return result
    }

    private suspend fun runInstall(packageName: String, apkUrl: String) {
        try {
            installer.downloadAndInstall(packageName, apkUrl)
                .onEach { step ->
                    mutex.withLock {
                        _installSteps.value = _installSteps.value + (packageName to step)
                        // A retry starts clean; the failure it is retrying goes with the old step.
                        if (step != InstallStep.Error) _installFailures.value = _installFailures.value - packageName
                    }
                }
                .catch { cause ->
                    this@ExtensionManager.logcat(LogPriority.ERROR, cause) { "Install failed" }
                    mutex.withLock {
                        _installSteps.value = _installSteps.value + (packageName to InstallStep.Error)
                        _installFailures.value = _installFailures.value + (packageName to cause)
                    }
                }
                .collect()
        } finally {
            // Cancelling the job returned by install or update is how the browse screen stops an
            // install, and a cancelled collect leaves the last step it published behind: the row
            // would keep its progress indicator for the life of the process. Dropping an
            // incomplete step here means the cancel works for whoever holds the job. The ordinary
            // path is untouched, because it ends on Installed or Error and both are completed.
            withContext(NonCancellable) {
                mutex.withLock {
                    if (_installSteps.value[packageName]?.isCompleted() == false) {
                        _installSteps.value = _installSteps.value - packageName
                    }
                }
            }
        }
        if (_installSteps.value[packageName] == InstallStep.Installed) {
            reload(packageName)
        }
    }

    private suspend fun publish(results: List<LoadResult>) = mutex.withLock {
        _installedExtensions.value = results.filterIsInstance<LoadResult.Success>()
            .map { it.extension.withUpdateState(_availableExtensions.value) }
        _untrustedExtensions.value = results.filterIsInstance<LoadResult.Untrusted>().map { it.extension }
        _loadErrors.value = results.filterIsInstance<LoadResult.Error>()
        republishSources()
    }

    /** Caller holds [mutex]. */
    private fun apply(packageName: String, result: LoadResult) {
        _installedExtensions.value = _installedExtensions.value.filterNot { it.packageName == packageName }
        _untrustedExtensions.value = _untrustedExtensions.value.filterNot { it.packageName == packageName }
        _loadErrors.value = _loadErrors.value.filterNot { it.packageName == packageName }
        when (result) {
            is LoadResult.Success ->
                _installedExtensions.value += result.extension.withUpdateState(_availableExtensions.value)
            is LoadResult.Untrusted -> _untrustedExtensions.value += result.extension
            is LoadResult.Error -> _loadErrors.value += result
        }
        republishSources()
        writeUpdatesCountIfKnown()
    }

    /** Caller holds [mutex]. */
    private fun republishSources() {
        _sources.value = _installedExtensions.value.flatMap { it.sources }
    }

    /**
     * Caller holds [mutex]. After an install, an update, a trust override or an uninstall the
     * count has changed only if an index has been read this process: without one, no installed
     * extension carries an update flag, and zero would be a guess rather than a count.
     */
    private fun writeUpdatesCountIfKnown() {
        if (_availableExtensions.value.isEmpty()) return
        preferences.extensionUpdatesCount.set(pendingUpdateCount())
    }

    /** Caller holds [mutex]. Loaded or not, every package on disk with an update. */
    private fun pendingUpdateCount(): Int = _installedExtensions.value.count { it.hasUpdate } +
        notLoadedUpdatePackages(_untrustedExtensions.value, _loadErrors.value, _availableExtensions.value).size

    /** One entry per package: the highest version code wins when two indexes carry the same one. */
    private fun List<Extension.Available>.onePerPackage(): List<Extension.Available> =
        groupBy { it.packageName }.values.map { candidates -> candidates.maxBy { it.versionCode } }

    private fun Extension.Installed.withUpdateState(available: List<Extension.Available>): Extension.Installed {
        if (available.isEmpty()) return this
        val entry = available.firstOrNull { it.packageName == packageName }
        return copy(
            hasUpdate = isUpdate(entry, versionCode, libVersion),
            isObsolete = entry == null,
            store = entry?.store ?: store,
        )
    }

    private fun notLoadedUpdatePackages(
        untrusted: List<Extension.Untrusted>,
        errors: List<LoadResult.Error>,
        available: List<Extension.Available>,
    ): Set<String> {
        if (available.isEmpty()) return emptySet()
        fun entry(packageName: String) = available.firstOrNull { it.packageName == packageName }
        return buildSet {
            untrusted.filter { isUpdate(entry(it.packageName), it.versionCode, it.libVersion) }
                .forEach { add(it.packageName) }
            errors.forEach { error ->
                val packageName = error.packageName ?: return@forEach
                if (isUpdate(entry(packageName), error.versionCode, error.libVersion)) add(packageName)
            }
        }
    }

    /**
     * Whether [entry] updates a copy at [versionCode] and [libVersion]: newer, and built for a lib
     * version this host loads. A copy whose version could not be read takes any loadable entry.
     */
    private fun isUpdate(entry: Extension.Available?, versionCode: Long?, libVersion: Double?): Boolean {
        if (entry == null || !libVersionPolicy.isSupported(entry.libVersion)) return false
        if (versionCode == null) return true
        return entry.versionCode > versionCode || (libVersion != null && entry.libVersion > libVersion)
    }

    /**
     * Identity rule 2 of the source contract: the ids an extension reports are the ones the host
     * uses, but a disagreement with the index is worth a line in the log.
     */
    private fun warnOnSourceIdDrift(indexes: List<FetchedIndex>, available: List<Extension.Available>) {
        if (indexes.isEmpty()) return
        _installedExtensions.value.forEach { installed ->
            val entry = available.firstOrNull { it.packageName == installed.packageName } ?: return@forEach
            val loadedIds = installed.sources.map { it.id }.toSet()
            val indexIds = entry.sources.map { it.id }.toSet()
            if (loadedIds != indexIds) {
                logcat(LogPriority.WARN) {
                    "Loaded source ids differ from the index entry for ${installed.packageName}; the loaded ids are used"
                }
            }
        }
    }

    private companion object {
        val REFRESH_INTERVAL_MS = 6.hours.inWholeMilliseconds
    }
}

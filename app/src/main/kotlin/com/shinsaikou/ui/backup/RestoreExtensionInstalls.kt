package com.shinsaikou.ui.backup

import com.shinsaikou.backup.importer.ImportReport
import com.shinsaikou.extensions.ExtensionManager
import com.shinsaikou.extensions.model.Extension
import com.shinsaikou.ui.browse.extension.MissingExtensions
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * The installs a restore leaves to the owner: the restore report's Install and Install all, and the
 * Restore tab's Install all for the extensions only a store the backup added lists. One queue for
 * both screens, in the app's scope, so a queue started on one shows on the other and outlives
 * either; the install under way finishes in the extension manager, as one from the Extensions tab.
 *
 * One install per package at a time: a package is started only when no job of this class runs for
 * it, the manager shows no step under way for it, and, for a single Install, Install all has not
 * queued it. The check and the start happen under one lock, since the queue runs off the main
 * thread while the screens call in on it.
 */
@SingleIn(AppScope::class)
@Inject
class RestoreExtensionInstalls(
    private val extensionManager: ExtensionManager,
    private val missingExtensions: MissingExtensions,
    private val appScope: CoroutineScope,
) {

    data class State(
        /** The packages Install all has still to reach; a screen shows each as pending. */
        val queued: Set<String> = emptySet(),
        /** While Install all runs: which of how many it is installing. */
        val installingAll: Progress? = null,
    )

    data class Progress(val done: Int, val total: Int)

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()

    private val lock = Any()

    /** The install job per package, so a row's ring can cancel it, as `ExtensionsViewModel` keeps them. Guarded by [lock]. */
    private val jobs = mutableMapOf<String, Job>()

    private var queue: Job? = null

    /**
     * [report]'s extensions from stores the restore added that are not on this device yet, loaded
     * or not: what still waits for Install all.
     */
    fun waiting(report: ImportReport): Flow<List<ImportReport.FromAddedStore>> {
        val held = report.extensionInstalls?.fromAddedStores.orEmpty()
        if (held.isEmpty()) return flowOf(emptyList())
        return combine(
            extensionManager.installedExtensions,
            extensionManager.untrustedExtensions,
            extensionManager.loadErrors,
        ) { installed, untrusted, errors ->
            val onDisk = HashSet<String>()
            installed.mapTo(onDisk) { it.packageName }
            untrusted.mapTo(onDisk) { it.packageName }
            errors.mapNotNullTo(onDisk) { it.packageName }
            held.filterNot { it.packageName in onDisk }
        }
    }

    /** Installs [available] as the Extensions tab does, against the manager's current list. */
    fun install(available: Extension.Available) {
        synchronized(lock) {
            if (available.packageName in mutableState.value.queued) return
            start(available.packageName)
        }
    }

    /** Takes a queued package out of Install all's queue, or cancels its install under way. */
    fun cancel(packageName: String) {
        synchronized(lock) {
            if (packageName in mutableState.value.queued) {
                mutableState.update { it.copy(queued = it.queued - packageName) }
                return
            }
            jobs.remove(packageName)?.cancel()
        }
    }

    /**
     * Installs, one after another, every extension a repository offers for the sources of
     * [sourceIds] that no live source serves, as the restore's Install extensions does, from any
     * configured store: the rows a restore left, those from stores it added among them. A package
     * already installing is passed over, at the start and again on its turn. With nothing in the
     * available list, which a new process starts with, the indexes are fetched first. A row's ring
     * still cancels its own install, and the next one starts. Does nothing while a queue runs.
     */
    fun installAll(sourceIds: Collection<Long>) {
        synchronized(lock) {
            if (queue?.isActive == true) return
            queue = appScope.launch { runQueue(sourceIds) }
        }
    }

    private suspend fun runQueue(sourceIds: Collection<Long>) {
        try {
            if (extensionManager.availableExtensions.value.isEmpty()) refresh()
            val targets = missingExtensions.forSources(sourceIds).first()
                .mapNotNull { row -> row.available?.packageName }
                .distinct()
                .filterNot(::isInstalling)
            if (targets.isEmpty()) return
            mutableState.update { it.copy(queued = targets.toSet()) }
            targets.forEachIndexed { index, packageName ->
                val job = synchronized(lock) {
                    // Cancelled from its row while it waited.
                    if (packageName !in mutableState.value.queued) return@forEachIndexed
                    mutableState.update {
                        it.copy(queued = it.queued - packageName, installingAll = Progress(index + 1, targets.size))
                    }
                    start(packageName)
                }
                job?.join()
            }
        } finally {
            mutableState.value = State()
        }
    }

    /** Caller holds [lock]. Starts [packageName]'s install unless one is under way; null when none started. */
    private fun start(packageName: String): Job? {
        if (isInstalling(packageName)) return null
        val target = extensionManager.availableExtensions.value.firstOrNull { it.packageName == packageName } ?: return null
        return extensionManager.install(target).also { jobs[packageName] = it }
    }

    private fun isInstalling(packageName: String): Boolean =
        synchronized(lock) { jobs[packageName]?.isActive == true } ||
            extensionManager.installSteps.value[packageName]?.isCompleted() == false

    /** As the report's own fetch: a failure leaves the rows on Open Extensions, whose tab says why. */
    private suspend fun refresh() {
        try {
            extensionManager.refreshAvailable()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "The index fetch before Install all failed" }
        }
    }
}

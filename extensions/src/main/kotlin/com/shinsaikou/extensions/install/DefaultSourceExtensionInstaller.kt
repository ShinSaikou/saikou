package com.shinsaikou.extensions.install

import com.shinsaikou.domain.extension.ExtensionStoreDuplicates
import com.shinsaikou.domain.extension.SourceExtensionInstaller
import com.shinsaikou.domain.extension.SourceExtensionInstaller.Failure
import com.shinsaikou.domain.extension.SourceExtensionInstaller.FromAddedStore
import com.shinsaikou.domain.extension.SourceExtensionInstaller.KnownStore
import com.shinsaikou.domain.extension.SourceExtensionInstaller.Outcome
import com.shinsaikou.domain.extension.SourceExtensionInstaller.Progress
import com.shinsaikou.domain.extension.SourceExtensionInstaller.WantedSource
import com.shinsaikou.domain.extension.repository.ExtensionStoreRepository
import com.shinsaikou.extensions.ExtensionManager
import com.shinsaikou.extensions.repo.RepoRefreshException
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * The restore's "Install extensions": the extension manager's own index refresh and install, one
 * package at a time, so the Extensions tab and the restore report show each install's step as it
 * runs. An install here is the same private install as a tap on Install (no `PackageInstaller`, so
 * no system prompt), which is what lets it run from the restore's worker with no screen open, and
 * why it takes only what a repository the owner had before the restore lists.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DefaultSourceExtensionInstaller(
    private val extensionManager: ExtensionManager,
    private val stores: ExtensionStoreRepository,
) : SourceExtensionInstaller {

    override suspend fun install(
        sources: List<WantedSource>,
        storesBefore: List<KnownStore>,
        onProgress: (Progress) -> Unit,
    ): Outcome {
        if (sources.isEmpty()) return Outcome()

        // Every index, so the repositories the restore just wrote are read whatever the last fetch
        // was. One that fails leaves its extensions out, and the outcome says which it was; a
        // refresh that fails as a whole names none, and what an earlier fetch listed still serves.
        val refresh = try {
            extensionManager.refreshAvailable()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        val unreadStores = when (val error = refresh.exceptionOrNull()) {
            null -> emptyList()
            is RepoRefreshException -> error.failures.map { it.store.name.ifBlank { it.store.indexUrl } }
            else -> {
                logcat(LogPriority.WARN, error) { "The index refresh before the restore's installs failed" }
                emptyList()
            }
        }

        // The rows that are a repository the owner had before, under the same key. The sources
        // they list are matched first, so another repository listing a package under the same
        // source ids cannot take the match from them.
        val before = storesBefore.mapTo(HashSet()) { ExtensionStoreDuplicates.key(it.indexUrl) to it.signingKey }
        val beforeUrls = stores.getAll()
            .filter { (ExtensionStoreDuplicates.key(it.indexUrl) to it.signingKey) in before }
            .mapTo(HashSet()) { it.indexUrl }
        val onDisk = extensionManager.packagesOnDisk()
        val unattended = SourceMatch.match(
            wanted = sources,
            available = extensionManager.availableFrom(beforeUrls),
            onDisk = onDisk,
        )
        val rest = SourceMatch.match(
            wanted = unattended.notFound,
            available = extensionManager.availableExtensions.value,
            onDisk = onDisk,
        )

        // A package one of them installs serves whatever other source its newer entry lists.
        val installing = unattended.toInstall.mapTo(HashSet()) { it.packageName }
        val fromAddedStores = rest.toInstall.filterNot { it.packageName in installing }

        val installed = mutableListOf<String>()
        val failed = mutableListOf<Failure>()
        unattended.toInstall.forEachIndexed { index, extension ->
            onProgress(Progress(done = index, total = unattended.toInstall.size, extensionName = extension.name))
            val succeeded = try {
                extensionManager.installAndWait(extension)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The download, the install or the load after it threw rather than failing as a
                // step: this extension did not install, and the next one still gets its turn.
                logcat(LogPriority.WARN, e) { "A restore install threw" }
                failed += Failure(extension.name, e.message ?: e::class.simpleName.orEmpty())
                return@forEachIndexed
            }
            if (succeeded) {
                installed += extension.name
            } else {
                val cause = extensionManager.installFailures.value[extension.packageName]
                val reason = cause?.message ?: cause?.let { it::class.simpleName } ?: "Not installed"
                failed += Failure(extension.name, reason)
            }
        }
        return Outcome(
            installed = installed,
            failed = failed,
            notFound = rest.notFound,
            alreadyInstalled = (unattended.alreadyInstalled + rest.alreadyInstalled).map { it.name }.distinct(),
            unreadStores = unreadStores,
            fromAddedStores = fromAddedStores.map { extension ->
                FromAddedStore(
                    extensionName = extension.name,
                    packageName = extension.packageName,
                    storeName = extension.store.name,
                    storeUrl = extension.store.indexUrl,
                )
            },
        )
    }
}

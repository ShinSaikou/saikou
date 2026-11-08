package com.shinsaikou.extensionupdate

import com.shinsaikou.backup.BackupRunState
import com.shinsaikou.backup.importer.BackupImportRequest
import com.shinsaikou.domain.extension.repository.ExtensionStoreRepository
import com.shinsaikou.extensions.ExtensionManager
import com.shinsaikou.extensions.loader.ExtensionLoader
import com.shinsaikou.extensions.loader.InstallRejection
import com.shinsaikou.extensions.model.Extension
import com.shinsaikou.extensions.model.InstallFailedException
import com.shinsaikou.ui.backup.RestoreExtensionInstalls
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * One run of the Experimental "Update extensions automatically" (R162 item 2): the indexes are read
 * again, then every loaded extension with an update is installed in turn through the manager's own
 * install, the path the Extensions tab's Update takes, so the tab shows each step as it runs.
 *
 * Only an update that comes out trusted with no prompt is installed. The index names no signer per
 * package, only its repository's signing key, which the row pinned when it was added. An update is
 * installed only when the installed copy is signed by that pinned key, the key of a repository that
 * lists the update now. The loader then refuses, before anything is written, a download that is not
 * signed by every signer of the installed copy ([InstallRejection.SIGNER_MISMATCH]), so whatever it
 * accepts carries the pinned key and loads trusted. Any other update (a copy the owner trusted by
 * hand, which a new version would make ask again, or one now served by a repository with another key)
 * is left for the owner and named in the run's notification. Extensions on disk that did not load or
 * are not trusted are never touched: their update is the owner's to make, as is any obsolete one.
 *
 * A run never installs beside another install: it waits for the manager's steps and the restore
 * report's queue to finish before each one, and a restore under way stops it ([Outcome.busy]).
 */
@SingleIn(AppScope::class)
@Inject
class ExtensionAutoUpdate(
    private val extensionManager: ExtensionManager,
    private val loader: ExtensionLoader,
    private val stores: ExtensionStoreRepository,
    private val restoreInstalls: RestoreExtensionInstalls,
    private val backupImportRequest: BackupImportRequest,
) {

    /** What a run did, by extension name. Empty when it found nothing to do. */
    data class Outcome(
        val updated: List<String> = emptyList(),
        val failed: List<String> = emptyList(),
        /** Left for the owner: signed with another key, or would need trusting again. */
        val needsYou: List<String> = emptyList(),
        /** A restore was running when the run started, so it did nothing and should try again. */
        val busy: Boolean = false,
    ) {
        val isEmpty: Boolean
            get() = updated.isEmpty() && failed.isEmpty() && needsYou.isEmpty()
    }

    private enum class Check { INSTALL, NEEDS_YOU, SKIP }

    suspend fun run(): Outcome {
        if (restoreRunning()) return Outcome(busy = true)
        extensionManager.isInitialized.first { it }
        refresh()

        val updated = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val needsYou = mutableListOf<String>()
        // Read off the list itself, which the refresh has just written; the manager's derived
        // updates flow may not have caught up yet.
        val candidates = extensionManager.installedExtensions.value
            .filter { it.hasUpdate && !it.isObsolete }
            .map { it.packageName }
        for (packageName in candidates) {
            // A restore that started meanwhile installs its own; what is left waits for the next run.
            if (!awaitTurn()) break
            // Read again on its turn: the owner may have updated or removed it meanwhile.
            val installed = extensionManager.installedExtensions.value
                .firstOrNull { it.packageName == packageName && it.hasUpdate && !it.isObsolete } ?: continue
            val entry = extensionManager.availableExtensions.value
                .firstOrNull { it.packageName == packageName } ?: continue
            when (check(installed, entry)) {
                Check.SKIP -> continue
                Check.NEEDS_YOU -> {
                    needsYou += installed.name
                    continue
                }
                Check.INSTALL -> Unit
            }
            val succeeded = try {
                extensionManager.installAndWait(entry)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // As the restore's installs: this one did not update, and the next one gets its turn.
                logcat(LogPriority.WARN, e) { "An automatic extension update threw" }
                failed += installed.name
                continue
            }
            when {
                // The loaded copy, not the step, says whether it updated: the loader takes a file at the
                // installed version code, and a reload that finds nothing leaves the old copy listed.
                succeeded && extensionManager.installedExtensions.value.any { it.isUpdateOf(installed, entry) } ->
                    updated += installed.name
                // Unreachable after the check above unless the repository went between the check and
                // the load; the copy waits in Needs trust for the owner, as any other would.
                succeeded && extensionManager.untrustedExtensions.value.any { it.packageName == packageName } ->
                    needsYou += installed.name
                succeeded -> failed += installed.name
                signerRefused(packageName) -> needsYou += installed.name
                else -> failed += installed.name
            }
        }
        return Outcome(updated = updated, failed = failed, needsYou = needsYou)
    }

    /**
     * Whether [entry] may replace [installed] with no one there: [Check.SKIP] when no configured
     * repository lists it any more (removed since the read), [Check.NEEDS_YOU] when the installed
     * copy is not signed by the pinned key of a repository that serves this file.
     */
    private suspend fun check(installed: Extension.Installed, entry: Extension.Available): Check {
        val serving = stores.getAll().filter { row ->
            extensionManager.availableFrom(setOf(row.indexUrl)).any {
                it.packageName == entry.packageName && it.apkUrl == entry.apkUrl
            }
        }
        if (serving.isEmpty()) return Check.SKIP
        val signers = loader.readArchive(installed.extFile)?.signerFingerprints.orEmpty()
        return if (serving.any { it.signingKey.isNotBlank() && it.signingKey in signers }) Check.INSTALL else Check.NEEDS_YOU
    }

    /**
     * Whether this loaded copy is [entry] replacing [before]: at least the listed version code, and
     * at least the listed lib version when a newer lib is what the index offered.
     */
    private fun Extension.Installed.isUpdateOf(before: Extension.Installed, entry: Extension.Available): Boolean =
        packageName == entry.packageName &&
            versionCode >= entry.versionCode &&
            (entry.libVersion <= before.libVersion || libVersion >= entry.libVersion)

    private fun signerRefused(packageName: String): Boolean {
        val cause = extensionManager.installFailures.value[packageName]
        return cause is InstallFailedException && cause.reason == InstallRejection.SIGNER_MISMATCH
    }

    /**
     * Waits until no install is under way, the owner's Update all or a restore report's Install all
     * among them, then says whether this run may go on: false once a restore is running, whose own
     * installs come with gaps between them that the steps alone would not show.
     */
    private suspend fun awaitTurn(): Boolean {
        combine(extensionManager.installSteps, restoreInstalls.state) { steps, restore ->
            steps.values.all { it.isCompleted() } && restore.installingAll == null && restore.queued.isEmpty()
        }.first { it }
        return !restoreRunning()
    }

    private suspend fun restoreRunning(): Boolean = backupImportRequest.run.first()?.state == BackupRunState.ACTIVE

    /** A read that fails leaves what an earlier one listed, as the restore's installs take it. */
    private suspend fun refresh() {
        try {
            extensionManager.refreshAvailable()
                .onFailure { logcat(LogPriority.WARN, it) { "The index refresh before the automatic extension updates failed" } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "The index refresh before the automatic extension updates failed" }
        }
    }
}

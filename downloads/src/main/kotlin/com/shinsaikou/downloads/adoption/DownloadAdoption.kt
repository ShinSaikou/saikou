package com.shinsaikou.downloads.adoption

import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.downloads.DownloadManifestSweep
import com.shinsaikou.downloads.DownloadRoot
import com.shinsaikou.downloads.MIN_FREE_BYTES
import com.shinsaikou.downloads.isLowOnSpace
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/**
 * One adoption of upstream's downloads from a tree the owner picked: the walk, then each matched
 * chapter through [AdoptionCopier], one at a time, and the [AdoptionSummary] the owner reads at the
 * end. [AdoptionWorker] hosts it.
 *
 * A run is resumable because the manifest is its state. A chapter a previous run adopted has a
 * manifest row and its files, and the copier skips it; a chapter a stopped run was copying has
 * neither, and is copied again from the start. So a second run over the same tree, whether after an
 * interruption or long after the first finished, copies only what is missing.
 *
 * Before the walk, the manifest sweep's re-match runs, so a download whose chapter row was replaced
 * since is pointed at its chapter again and counts as adopted instead of being copied a second time.
 *
 * With less than [MIN_FREE_BYTES] free before a chapter, the run ends there, and the summary says so
 * with a failure of the run itself; a later run over the same tree copies the rest.
 */
@Inject
@SingleIn(AppScope::class)
class DownloadAdoption(
    private val walker: AdoptionWalker,
    private val copier: AdoptionCopier,
    private val sweep: DownloadManifestSweep,
    private val downloadRoot: DownloadRoot,
    private val dispatchers: Dispatchers,
) {

    /** [done] of [total] matched chapters handled; [total] is 0 while the tree is still being walked. */
    data class Progress(val done: Int, val total: Int)

    private val lock = Mutex()

    suspend fun run(tree: AdoptionNode, onProgress: suspend (Progress) -> Unit = {}): AdoptionSummary = lock.withLock {
        onProgress(Progress(0, 0))
        sweep.rematch()
        val walk = walker.walk(tree)

        var copied = 0
        var skipped = 0
        val failures = mutableListOf<AdoptionFailure>()
        for ((index, match) in walk.matches.withIndex()) {
            currentCoroutineContext().ensureActive()
            onProgress(Progress(index, walk.matches.size))
            if (withContext(dispatchers.io) { downloadRoot.isLowOnSpace() }) {
                failures += AdoptionFailure(path = "", reason = AdoptionFailureReason.STORAGE_FULL)
                break
            }
            when (val outcome = copier.adopt(match)) {
                AdoptionCopier.Outcome.Copied -> copied++
                AdoptionCopier.Outcome.Skipped -> skipped++
                is AdoptionCopier.Outcome.Failed -> failures += outcome.failure
            }
        }
        onProgress(Progress(walk.matches.size, walk.matches.size))

        AdoptionSummary(
            finishedAt = Clock.System.now().toEpochMilliseconds(),
            matched = walk.matches.size,
            copied = copied,
            skipped = skipped,
            unmatched = walk.unmatched,
            failures = failures,
        )
    }
}

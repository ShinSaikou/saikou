// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/data/backup/BackupFileValidator.kt (modified: yes)
package eu.kanade.tachiyomi.data.backup

import android.net.Uri
import com.shinsaikou.source.SourceIds
import com.shinsaikou.source.SourceManager
import com.shinsaikou.tracking.TrackerIds
import com.shinsaikou.tracking.TrackerManager
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.first

/**
 * Modified: constructor injection, a `validate(header)` overload so a file is read once per run,
 * and three extra result lists. Upstream's `mapNotNull` over `trackerManager.get(id)` drops every
 * tracker the app does not implement before it ever checks login state, so a backup referencing
 * one produces no warning at all. The signed-out trackers' ids travel beside their names (unit
 * restore-report), so the restore report can offer Sign in for each after the run. `validate`
 * suspends until the source registry has loaded, so a run the system started cold does not call
 * every installed source missing. Both overloads read a [BackupHeader] (phase 5 unit SC2), never
 * the whole decoded file. TachiyomiSY's merged source is no source to install, so it is not missing.
 */
@Inject
class BackupFileValidator(
    private val decoder: BackupDecoder,
    private val sourceManager: SourceManager,
    private val trackerManager: TrackerManager,
) {

    suspend fun validate(uri: Uri): Results = validate(decoder.header(uri))

    suspend fun validate(header: BackupHeader): Results {
        // Before the extension host's first load ends only the Local source is known. A failed
        // load ends the wait too, and then its sources are reported missing, which they are.
        sourceManager.isInitialized.first { it }

        val sources = header.backup.backupSources.associate { it.sourceId to it.name }
        val missingSources = sources
            .filterKeys { it != SourceIds.SY_MERGED && sourceManager.get(it) == null }
            .values
            .map { name ->
                val id = name.toLongOrNull()
                if (id == null) name else sourceManager.getOrStub(id).toString()
            }
            .distinct()
            .sorted()

        val trackers = header.trackerIds

        val unsupportedTrackers = trackers
            .filter { it in TrackerIds.ALL && trackerManager.get(it) == null }
            .sorted()
        val signedOut = trackers
            .mapNotNull { trackerManager.get(it) }
            .filterNot { it.isLoggedIn }
            .sortedBy { it.name }
        val missingTrackers = signedOut.map { it.name }
        val missingTrackerIds = signedOut.map { it.id }
        val unknownTrackers = trackers
            .filterNot { it in TrackerIds.ALL }
            .sorted()

        return Results(missingSources, unsupportedTrackers, missingTrackers, missingTrackerIds, unknownTrackers)
    }

    data class Results(
        /** Names from `backupSources` whose id no live source serves; distinct, sorted. */
        val missingSources: List<String>,
        /** Known tracker ids with no implementation in this build. Phase 1: every referenced id. */
        val unsupportedTrackers: List<Long>,
        /** Names of trackers this build serves but that are not logged in. Upstream's list. */
        val missingTrackers: List<String>,
        /** The ids of [missingTrackers], in the same order, for the restore report's Sign in rows. */
        val missingTrackerIds: List<Long>,
        /** Tracker ids outside [TrackerIds.ALL]; the importer skips these rows. */
        val unknownTrackers: List<Long>,
    )
}

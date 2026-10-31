package com.shinsaikou.sync

import com.shinsaikou.backup.export.AutomaticBackupFiles
import com.shinsaikou.backup.export.BackupExporter
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okio.sink
import tachiyomi.core.common.util.system.logcat
import kotlin.time.TimeSource

/**
 * The backup written before a backend's first pull on this device (plan unit G), and so before the
 * first pull after a backend change: a `.tachibk` through the same exporter and into the same
 * folder as the automatic export, under its own names ([AutomaticBackupFiles.createSync]), which the
 * automatic rotation never deletes. The newest two are kept. If the merge of a first pull does
 * something the owner did not expect, this file is the library as it was.
 */
@Inject
class SyncSafetyBackup(
    private val exporter: BackupExporter,
    private val files: AutomaticBackupFiles,
    private val preferences: SyncWorkPreferences,
) {

    /**
     * Writes the backup and waits for it, unless one was written for [backendId] already. Records the
     * backend and the file's name only once the file is complete, and only then deletes safety files
     * older than the one before it. Throws [SyncSafetyBackupException] when the export fails, after
     * deleting the partial file if this call created it, so the run stops before anything is pulled;
     * a cancel propagates. The time it took is logged, as the engine logs its run's
     * ([SyncEngine.RUN_TIMINGS]), since at 40,000 entries it is a share of the first run's.
     */
    suspend fun ensure(backendId: String, now: Long) {
        if (preferences.safetyBackupBackend.get() == backendId) return
        val started = TimeSource.Monotonic.markNow()
        val name = try {
            write(now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SyncSafetyBackupException(e)
        }
        logcat(LogPriority.DEBUG) { "$WRITTEN_IN ${started.elapsedNow().inWholeMilliseconds}" }
        preferences.safetyBackupFile.set(name)
        preferences.safetyBackupBackend.set(backendId)
    }

    private suspend fun write(now: Long): String {
        val written = exporter.build(BackupOptions()).use { staged ->
            val target = files.createSync(now)
            try {
                withContext(Dispatchers.IO) {
                    target.file.openOutputStream().use { stream -> exporter.write(staged, stream.sink()) }
                }
            } catch (e: Throwable) {
                // A name that was there before this call is an earlier sign-in's file, never this write's to delete.
                if (!target.existed) withContext(NonCancellable + Dispatchers.IO) { target.file.delete() }
                throw e
            }
            target
        }
        val name = checkNotNull(written.file.name) { "The backup file has no name" }
        files.pruneSync(keep = name)
        return name
    }

    companion object {
        /** What the debug line after a safety backup starts with; the ms it took follow (phase 5 unit SC3). */
        const val WRITTEN_IN = "Sync safety backup written in ms:"
    }
}

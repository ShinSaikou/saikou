package com.shinsaikou.backup.importer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where a run had got to. Written after a batch commits, never inside its transaction. */
@Serializable
data class ImportCheckpoint(
    /** Hex SHA-256 of the private copy's raw bytes, gzipped or not. */
    val fileSha256: String,
    val fileSize: Long,
    /** `RestoreOptions.asBooleanArray().toList()`. */
    val options: List<Boolean>,
    val phase: ImportPhase,
    /**
     * How many library entries are committed, counted in file order: the file's first
     * [mangaCursor] field 1 frames, which a resumed run skips undecoded.
     */
    val mangaCursor: Int,
    val mangaTotal: Int,
    val startedAt: Long,
    val updatedAt: Long,
)

enum class ImportPhase { CATEGORIES, PREFERENCES, EXTENSION_STORES, MANGA, EXTENSIONS, DONE }

data class ImportProgress(
    val phase: ImportPhase,
    val done: Int,
    val total: Int,
    val currentTitle: String?,
)

/** One thing that went wrong. Appended to the error log as it happens, never only kept in memory. */
@Serializable
data class ImportError(
    val kind: Kind,
    /** Manga title, preference key, store index url or category name. */
    val subject: String,
    /** The `BackupSource` name, or the id as text; null when the error is not about a manga. */
    val sourceLabel: String?,
    val message: String,
    val at: Long,
) {
    enum class Kind {
        MANGA_ROW,
        CHAPTERS,
        HISTORY,
        TRACKING,
        CATEGORIES_OF_MANGA,
        EXCLUDED_SCANLATORS,
        CATEGORY,
        PREFERENCE_TYPE_MISMATCH,
        PREFERENCE,
        SOURCE_PREFERENCE,
        EXTENSION_STORE,
        UNKNOWN_TRACKER_SKIPPED,
        BATCH_FALLBACK,
        EXTENSION_INSTALL,
    }
}

/**
 * What one run did. Counters cover the current run only: a resumed run reports the work it did
 * itself, while [errors] is the whole log, so earlier failures survive a process death.
 */
@Serializable
data class ImportReport(
    val fileSha256: String,
    val startedAt: Long,
    val finishedAt: Long,
    val cancelled: Boolean,
    val resumed: Boolean,
    val mangaTotal: Int,
    val mangaInserted: Int,
    val mangaMerged: Int,
    val mangaFailed: Int,
    val chaptersWritten: Int,
    val historyWritten: Int,
    val tracksWritten: Int,
    val categoriesCreated: Int,
    val preferencesWritten: Int,
    val sourcePreferenceFilesWritten: Int,
    val extensionStoresUpserted: Int,
    /** The sources of [missingSources] this run gave a stub name, which no earlier run had named. */
    val stubSourcesRemembered: List<Long>,
    /** Known tracker ids whose rows were written even though this build has no tracker for them. */
    val unsupportedTrackers: List<Long>,
    /** Tracker ids outside `TrackerIds.ALL`; their rows were skipped. */
    val unknownTrackers: List<Long>,
    val errors: List<ImportError>,
    /**
     * Trackers this build serves that were signed out when the run started (unit restore-report):
     * the restore report's Sign in rows. Absent from a report an older build wrote.
     */
    val trackersNotSignedIn: List<Long> = emptyList(),
    /**
     * Every source of the file, the Local source aside, that no live source served when the run
     * started, whether or not an earlier run had already remembered its name (unit
     * restore-report). [stubSourcesRemembered] alone leaves out a source a resumed run, or a second
     * restore of the same file, finds already named. Absent from a report an older build wrote.
     */
    val missingSources: List<Long> = emptyList(),
    /**
     * What Install extensions did, when it ran to its end: null when the option was off, the run
     * was stopped first, or an older build wrote the report.
     */
    val extensionInstalls: ExtensionInstalls? = null,
) {
    /**
     * Extensions by name, as the indexes name them, except [notFound], which is the sources by the
     * backup's names, so the owner knows what to search for. Each of [failed] has its reason among
     * the errors, as an [ImportError.Kind.EXTENSION_INSTALL].
     */
    @Serializable
    data class ExtensionInstalls(
        val installed: List<String> = emptyList(),
        val failed: List<String> = emptyList(),
        val notFound: List<String> = emptyList(),
        val alreadyInstalled: List<String> = emptyList(),
        /** Repositories whose index could not be read, so a source in [notFound] may be in one of them. */
        val unreadStores: List<String> = emptyList(),
        /**
         * Extensions that only a repository this restore added lists, so they were not installed:
         * the owner installs them with Install all once they have seen which repository that is.
         */
        val fromAddedStores: List<FromAddedStore> = emptyList(),
    )

    /** An extension waiting for Install all, and the repository it would come from, by name and address. */
    @Serializable
    data class FromAddedStore(
        val extensionName: String,
        val packageName: String,
        val storeName: String,
        val storeUrl: String,
    )

    /** The sources the restore report lists as having no extension, for a report of any build. */
    val sourcesWithoutExtension: List<Long>
        get() = (missingSources + stubSourcesRemembered).distinct()

    companion object {
        /**
         * How `last-report.json` is read back: unknown keys are skipped, so a report a newer build
         * wrote still opens, and an absent null reads as null, since the app's own Json leaves
         * nulls out when it writes (`explicitNulls = false`).
         */
        internal val ReportJson: Json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}

/**
 * The extension stores configured when a restore of one file first started, before it wrote any
 * (`ImportCheckpointStore.storesBeforeFile`). Install extensions installs unattended only from
 * these; a retry of the same file reads them back rather than counting the stores its first
 * attempt added as the owner's own.
 */
@Serializable
data class StoresBefore(
    val fileSha256: String,
    val fileSize: Long,
    val stores: List<Store>,
) {
    /** A store by its address and signing key; the key is stored as the store table holds it, never logged. */
    @Serializable
    data class Store(val indexUrl: String, val signingKey: String)
}

package com.shinsaikou.sync

import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.domain.sync.model.SyncLibraryCounts
import com.shinsaikou.sync.format.RowKind
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * What the sync runs did, for Sync settings (phase 3 unit G): when the last run started and when
 * the last one succeeded, how the last one ended, and the reports of the last [MAX_REPORTS] runs
 * that finished. Every key is app state, never exported.
 *
 * A failure is stored as its [ErrorKey] id and [ErrorKey.detail], never as a message (R67), so it
 * reads in the device's language when shown; an id this build does not know reads as
 * [ErrorKey.GENERIC]. A run that another run's lock turned away records nothing: the holder records
 * its own outcome.
 */
@SingleIn(AppScope::class)
@Inject
class SyncRunRecord(preferenceStore: PreferenceStore) {

    /** Epoch milliseconds when the last run took the lock, or 0. */
    val lastAttemptAt: Preference<Long> =
        preferenceStore.getLong(Preference.appStateKey("sync_last_attempt_at"), 0L)

    /** Epoch milliseconds when the last successful run finished, or 0. */
    val lastSuccessAt: Preference<Long> =
        preferenceStore.getLong(Preference.appStateKey("sync_last_success_at"), 0L)

    /** [OUTCOME_SUCCESS], or the failing run's [ErrorKey.id]; empty before the first run ends. */
    val lastOutcome: Preference<String> =
        preferenceStore.getString(Preference.appStateKey("sync_last_outcome"), "")

    /** The failing run's [ErrorKey.detail], or empty. */
    val lastOutcomeDetail: Preference<String> =
        preferenceStore.getString(Preference.appStateKey("sync_last_outcome_detail"), "")

    /** The last [MAX_REPORTS] finished runs' reports, newest first. A value that does not decode reads as none. */
    val reports: Preference<List<SyncRunReport>> = preferenceStore.getObjectFromString(
        key = Preference.appStateKey("sync_run_reports"),
        defaultValue = emptyList(),
        serializer = { json.encodeToString(REPORTS, it) },
        deserializer = { value -> runCatching { json.decodeFromString(REPORTS, value) }.getOrDefault(emptyList()) },
    )

    /** How the last run ended, or null before any has. */
    fun outcome(): SyncOutcome? = when (val id = lastOutcome.get()) {
        "" -> null
        OUTCOME_SUCCESS -> SyncOutcome.Success
        else -> SyncOutcome.Failure(
            key = ErrorKey.fromId(id) ?: ErrorKey.GENERIC,
            detail = lastOutcomeDetail.get().ifEmpty { null },
        )
    }

    fun attempted(at: Long) {
        lastAttemptAt.set(at)
    }

    fun succeeded(at: Long, result: SyncRunResult) {
        lastSuccessAt.set(at)
        lastOutcome.set(OUTCOME_SUCCESS)
        lastOutcomeDetail.delete()
        reports.set((listOf(SyncRunReport.of(at, result)) + reports.get()).take(MAX_REPORTS))
    }

    fun failed(error: Throwable) {
        lastOutcome.set(ErrorKey.of(error).id)
        val detail = ErrorKey.detail(error)
        if (detail == null) lastOutcomeDetail.delete() else lastOutcomeDetail.set(detail)
    }

    /**
     * Forgets a [ErrorKey.SYNC_SIGNED_OUT] outcome once the owner has signed in again, so Sync
     * settings leaves its Sign in again state; any other outcome is still how the last run ended and
     * stays.
     */
    fun clearSignedOut() {
        if (lastOutcome.get() != ErrorKey.SYNC_SIGNED_OUT.id) return
        lastOutcome.delete()
        lastOutcomeDetail.delete()
    }

    companion object {
        const val OUTCOME_SUCCESS = "success"
        const val MAX_REPORTS = 10

        private val json = Json { ignoreUnknownKeys = true }
        private val REPORTS = ListSerializer(SyncRunReport.serializer())
    }
}

/** How a run ended. */
sealed interface SyncOutcome {
    data object Success : SyncOutcome

    /** A failure by key; [detail] is what the key's text shows beside it (R67). */
    data class Failure(val key: ErrorKey, val detail: String?) : SyncOutcome
}

/**
 * One finished run, as Sync settings shows it: the true conflicts by title and field, the routine
 * merges as one count, the library's counts before and after, and whether this device pushed a full
 * export. [conflicts] keeps the first [MAX_CONFLICTS]; [conflictCount] is how many the run had.
 */
@Serializable
data class SyncRunReport(
    val at: Long,
    val conflicts: List<Conflict>,
    val conflictCount: Int,
    val routineMerges: Int,
    val before: Counts,
    val after: Counts,
    val exported: Boolean,
) {

    /** A `SyncConflict`: the row's kind, its manga's title (a category's name), its chapter, the fields. */
    @Serializable
    data class Conflict(val kind: RowKind, val title: String, val chapter: String? = null, val fields: List<String>)

    /** A `SyncLibraryCounts`. */
    @Serializable
    data class Counts(val libraryEntries: Long, val categories: Long, val readChapters: Long, val bookmarkedChapters: Long)

    companion object {
        /**
         * A first join can report every row it merged; ten runs of that would make the preference
         * file megabytes. The count stays exact.
         */
        const val MAX_CONFLICTS = 100

        fun of(at: Long, result: SyncRunResult): SyncRunReport = SyncRunReport(
            at = at,
            conflicts = result.conflicts.take(MAX_CONFLICTS).map {
                Conflict(it.kind, it.title, it.chapter, it.fields.sorted())
            },
            conflictCount = result.conflicts.size,
            routineMerges = result.routineMerges,
            before = result.before.counts(),
            after = result.after.counts(),
            exported = result.exported,
        )

        private fun SyncLibraryCounts.counts() = Counts(libraryEntries, categories, readChapters, bookmarkedChapters)
    }
}

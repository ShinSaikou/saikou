package com.shinsaikou.sync

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * Sync settings. They live in `:sync` rather than `:domain` because no Phase 1 screen reads them.
 * Every key is app state, so none of them enters a backup.
 */
@SingleIn(AppScope::class)
@Inject
class SyncPreferences(preferenceStore: PreferenceStore) {

    /**
     * The configured backend's [SyncBackend.id], or empty while signed out. Sync settings sets it at
     * sign-in; `SyncEngine.forgetBackend` empties it at sign-out.
     */
    val backendId: Preference<String> =
        preferenceStore.getString(Preference.appStateKey("sync_backend"), "")

    val lastPushedSeq: Preference<Long> =
        preferenceStore.getLong(Preference.appStateKey("sync_last_pushed_seq"), 0L)

    /**
     * For each other device, the seq through which its rows are applied here. A device with no
     * entry has had nothing applied (`SyncChain.NOTHING_APPLIED`, R81 item 1). Written after every
     * applied file, so a run stopped by process death resumes where it stopped (Q10).
     */
    val pullCursors: Preference<Map<String, Long>> = preferenceStore.getObjectFromString(
        key = Preference.appStateKey("sync_pull_cursors"),
        defaultValue = emptyMap(),
        serializer = ::encodeCursors,
        deserializer = ::decodeCursors,
    )

    /**
     * For each other device whose full export the pull is part way through, that export's id and
     * how many of its parts are committed here (R89 carry-over 1). The pull skips those parts when
     * it applies the same export again, writes the entry after each part's last chunk commits, and
     * drops it once the export's cursor moves. An entry for another export id of that device is
     * ignored, and a restore forgets every entry with the cursors.
     */
    val pulledExportParts: Preference<Map<String, PulledExportParts>> = preferenceStore.getObjectFromString(
        key = Preference.appStateKey("sync_pulled_export_parts"),
        defaultValue = emptyMap(),
        serializer = { parts -> if (parts.isEmpty()) "" else Json.encodeToString(PULLED_EXPORT_PARTS, parts) },
        deserializer = { value ->
            if (value.isEmpty()) emptyMap() else runCatching { Json.decodeFromString(PULLED_EXPORT_PARTS, value) }.getOrDefault(emptyMap())
        },
    )

    /**
     * This install's identity to the backend: empty until the sync engine generates it, under the
     * lock every run holds, before its first push (Q10). Nothing else writes it; only
     * `SyncEngine.forgetBackend` deletes it, on sign-out.
     */
    val deviceId: Preference<String> =
        preferenceStore.getString(Preference.appStateKey("sync_device_id"), "")

    /**
     * The [SyncBackend.id] this device's last full export finished on, or empty. A run whose backend
     * is not this one pushes a full export before any segment: a device's first push, its first
     * after a restore and its first after a backend change (Q3). Choosing another backend or
     * signing out empties it, and so does pruning the log with no backend configured.
     */
    val exportedBackend: Preference<String> =
        preferenceStore.getString(Preference.appStateKey("sync_exported_backend"), "")

    /**
     * The full export in progress, or null. It names its backend, id and `lastSeq`, and how far it
     * got, so a pusher that restarts resumes the same export id (Q3).
     */
    val exportProgress: Preference<ExportProgress?> = preferenceStore.getObjectFromString(
        key = Preference.appStateKey("sync_export_progress"),
        defaultValue = null,
        serializer = { progress -> progress?.let { Json.encodeToString(ExportProgress.serializer(), it) }.orEmpty() },
        deserializer = { value ->
            if (value.isEmpty()) null else runCatching { Json.decodeFromString(ExportProgress.serializer(), value) }.getOrNull()
        },
    )

    /**
     * Set once an apply skips a row it could not store (a row whose parent the file never gave).
     * This device's database then lacks something its cursors claim, so it never compacts (Q3)
     * until a restore resets its cursors.
     */
    val skippedRows: Preference<Boolean> =
        preferenceStore.getBoolean(Preference.appStateKey("sync_skipped_rows"), false)
}

/**
 * A full export under way: [exportId] on backend [backendId], ending at [lastSeq]. [parts] are
 * written and read back, and they hold every exported manga up to id [lastMangaId]; the next part
 * starts after it.
 */
@Serializable
data class ExportProgress(
    val backendId: String,
    val exportId: String,
    val lastSeq: Long,
    val parts: Int,
    val lastMangaId: Long,
)

/**
 * How far the pull got through one full export of another device: [parts] of export [exportId] are
 * committed here, parts 1 to [parts].
 */
@Serializable
data class PulledExportParts(val exportId: String, val parts: Int)

private val PULLED_EXPORT_PARTS = MapSerializer(String.serializer(), PulledExportParts.serializer())

/** `id=seq` pairs joined by commas; a device id is letters, digits and '-' (R81 item 8). */
private fun encodeCursors(cursors: Map<String, Long>): String =
    cursors.entries.sortedBy { it.key }.joinToString(",") { (device, seq) -> "$device=$seq" }

private fun decodeCursors(value: String): Map<String, Long> = value.split(",")
    .mapNotNull { pair ->
        val device = pair.substringBefore('=', missingDelimiterValue = "")
        val seq = pair.substringAfter('=', missingDelimiterValue = "").toLongOrNull()
        if (device.isEmpty() || seq == null) null else device to seq
    }
    .toMap()

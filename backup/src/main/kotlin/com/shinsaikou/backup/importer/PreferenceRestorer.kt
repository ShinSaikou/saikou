package com.shinsaikou.backup.importer

import android.content.Context
import com.shinsaikou.backup.UpstreamPreferenceKeys
import com.shinsaikou.domain.extension.repository.ExtensionStoreRepository
import com.shinsaikou.domain.preferences.DownloadPreferences
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.UpdatesPreferences
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.FloatPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.LongPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.PreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import kotlinx.coroutines.CancellationException
import tachiyomi.domain.extension.model.ExtensionStore
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getLongArray
import tachiyomi.core.common.preference.plusAssign

/**
 * Writes the two preference halves of a backup. Values are stored, never interpreted: an enum
 * arrives as its `name` and the reading `*Preferences` class falls back to its own default on a
 * name it does not know, so a theme or a reading mode Saikou has never heard of costs one setting
 * rather than the whole file.
 */
@Inject
class PreferenceRestorer(
    private val context: Context,
    private val preferenceStore: PreferenceStore,
    private val extensionStoreRepository: ExtensionStoreRepository,
) {

    /**
     * Restores the app preferences. [categoryIdByBackupId] maps a backup category id to the live
     * category id the CATEGORIES phase resolved it to. Every key in [prefs] is either written,
     * handled by one of the named cases below, or refused with an entry in [errors], so a key
     * that vanishes without a word is a defect. Returns the number of keys written.
     */
    suspend fun restoreApp(
        prefs: List<BackupPreference>,
        categoryIdByBackupId: Map<Long, Long>,
        errors: MutableList<ImportError>,
    ): Int {
        var written = 0
        val live = preferenceStore.getAll()

        UpstreamPreferenceKeys.toSaikou(prefs).forEach { (key, value) ->
            try {
                // App state is never a user preference. A foreign file carrying one is ignored.
                if (Preference.isAppState(key)) return@forEach

                if (!typesAgree(live[key], value)) {
                    errors += error(
                        kind = ImportError.Kind.PREFERENCE_TYPE_MISMATCH,
                        subject = key,
                        message = "The stored value is a ${live[key]?.let { it::class.simpleName }}, " +
                            "the backup holds a ${value::class.simpleName}",
                    )
                    return@forEach
                }

                if (key == LEGACY_EXTENSION_REPOS_KEY && value is StringSetPreferenceValue) {
                    restoreLegacyExtensionStores(value.value, errors)
                    return@forEach
                }

                val didWrite = writeApp(key, value, categoryIdByBackupId, errors)
                if (didWrite) written++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors += error(ImportError.Kind.PREFERENCE, key, e.message.orEmpty())
            }
        }
        return written
    }

    /**
     * Restores one `SharedPreferences` file per entry, whether or not the source is installed:
     * the file name is the extension ABI's fixed `source_<id>`, so installing the extension later
     * picks the settings up. Returns the number of files written.
     */
    suspend fun restoreSource(prefs: List<BackupSourcePreferences>, errors: MutableList<ImportError>): Int {
        var files = 0
        prefs.forEach { entry ->
            // The name comes from the file. Anything but a source's own file would let a backup
            // write the app's settings (the app lock among them) with App settings unticked.
            if (!SOURCE_PREFERENCES_FILE.matches(entry.sourceKey)) {
                errors += error(ImportError.Kind.SOURCE_PREFERENCE, entry.sourceKey, "Not a source settings file")
                return@forEach
            }
            try {
                val store = AndroidPreferenceStore(
                    context,
                    context.getSharedPreferences(entry.sourceKey, Context.MODE_PRIVATE),
                )
                val live = store.getAll()
                entry.prefs.forEach { (key, value) ->
                    if (Preference.isAppState(key)) return@forEach
                    if (!typesAgree(live[key], value)) {
                        errors += error(
                            kind = ImportError.Kind.PREFERENCE_TYPE_MISMATCH,
                            subject = "${entry.sourceKey}/$key",
                            message = "The stored value and the backup value have different types",
                        )
                        return@forEach
                    }
                    write(store, key, value)
                }
                files++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors += error(ImportError.Kind.SOURCE_PREFERENCE, entry.sourceKey, e.message.orEmpty())
            }
        }
        return files
    }

    /**
     * A null live value means the key is unset, which is not a mismatch. Upstream's `is Int?` tests
     * read the same way and this keeps a first restore into an empty store from being a no-op.
     */
    private fun typesAgree(live: Any?, value: PreferenceValue): Boolean = when (value) {
        is IntPreferenceValue -> live is Int?
        is LongPreferenceValue -> live is Long?
        is FloatPreferenceValue -> live is Float?
        is StringPreferenceValue -> live is String?
        is BooleanPreferenceValue -> live is Boolean?
        is StringSetPreferenceValue -> live is Set<*>?
    }

    /**
     * Writes one app key. Every key the backup carries either lands in the store or leaves an
     * entry in [errors]: a key that disappears with nothing said is the bug this guards against,
     * because the restore then reports success over a setting the file actually held. Returns
     * whether the key was written.
     */
    private fun writeApp(
        key: String,
        value: PreferenceValue,
        categoryIdByBackupId: Map<Long, Long>,
        errors: MutableList<ImportError>,
    ): Boolean {
        if (key == LibraryPreferences.DEFAULT_CATEGORY_PREF_KEY && value is IntPreferenceValue) {
            // The stored value is a category id, which the live database assigned itself. Upstream's
            // -1, "always ask" and the key's default, names no category and is written as it is.
            val liveId = if (value.value.toLong() == ALWAYS_ASK_CATEGORY_ID) {
                ALWAYS_ASK_CATEGORY_ID
            } else {
                liveCategoryId(value.value.toLong(), categoryIdByBackupId)
            }
            if (liveId == null) {
                errors += error(
                    kind = ImportError.Kind.PREFERENCE,
                    subject = key,
                    message = "The stored category id ${value.value} did not resolve to a live " +
                        "category, so the value was left alone rather than written stale",
                )
                return false
            }
            preferenceStore.getInt(key).set(liveId.toInt())
            return true
        }

        if (key in CATEGORY_SET_KEYS && value is StringSetPreferenceValue) {
            val mapped = value.value.mapNotNull { element ->
                element.toLongOrNull()?.let { liveCategoryId(it, categoryIdByBackupId) }?.toString()
            }
            // An empty set in the backup is a value in its own right, it says no category is
            // selected, and mapping it trivially yields an empty set. Only a set that held ids
            // and resolved none of them has failed, so the two cases stay apart here.
            if (value.value.isNotEmpty() && mapped.isEmpty()) {
                errors += error(
                    kind = ImportError.Kind.PREFERENCE,
                    subject = key,
                    message = "None of its category ids resolved to a live category",
                )
                return false
            }
            // Added to the live set, not substituted for it: a restore never drops a category the
            // user already picked.
            preferenceStore.getStringSet(key) += mapped
            return true
        }

        if (key in CATEGORY_LIST_KEYS && value is StringPreferenceValue) {
            // The Updates filter's category lists, stored as upstream's `getLongArray` stores them:
            // one comma-joined string. Remapped as the sets above are, with the same two outcomes
            // for an empty list and for a list none of whose ids resolved (phase 4 unit UF). The
            // empty list is the empty string, and the guard reads the raw string as the set branch
            // reads the raw set: gated on the parsed ids, a value that parses as no id at all would
            // be written as an empty list with nothing said.
            val ids = value.value.split(",").mapNotNull { it.toLongOrNull() }
            val mapped = ids.mapNotNull { liveCategoryId(it, categoryIdByBackupId) }
            if (value.value.isNotEmpty() && mapped.isEmpty()) {
                errors += error(
                    kind = ImportError.Kind.PREFERENCE,
                    subject = key,
                    message = "None of its category ids resolved to a live category",
                )
                return false
            }
            val preference = preferenceStore.getLongArray(key, emptyList())
            preference.set((preference.get() + mapped).distinct())
            return true
        }

        write(preferenceStore, key, value)
        return true
    }

    /**
     * The live id of a backup's category id. The default category, 0, is the system row every
     * database has and no backup lists (upstream's and Saikou's exporters both leave it out), so it is
     * never in [categoryIdByBackupId] and maps to itself; without this a restored "excluded from
     * delete after reading" set lost it, and every entry in no category lost its read chapters'
     * files (phase 3 unit DR). Upstream's own restore drops the 0 too. It takes precedence over a
     * backup category whose id reads 0, which a backup written without `BackupCategory.id` has,
     * since the field defaults to 0 there.
     */
    private fun liveCategoryId(backupId: Long, categoryIdByBackupId: Map<Long, Long>): Long? =
        if (backupId == DEFAULT_CATEGORY_ID) DEFAULT_CATEGORY_ID else categoryIdByBackupId[backupId]

    private fun write(store: PreferenceStore, key: String, value: PreferenceValue) {
        when (value) {
            is IntPreferenceValue -> store.getInt(key).set(value.value)
            is LongPreferenceValue -> store.getLong(key).set(value.value)
            is FloatPreferenceValue -> store.getFloat(key).set(value.value)
            is StringPreferenceValue -> store.getString(key).set(value.value)
            is BooleanPreferenceValue -> store.getBoolean(key).set(value.value)
            is StringSetPreferenceValue -> store.getStringSet(key).set(value.value)
        }
    }

    /**
     * Upstream's legacy repository set holds one base url per entry. Saikou keeps extension stores in
     * a table, so the key itself is never stored; each value becomes a legacy store row.
     */
    private suspend fun restoreLegacyExtensionStores(urls: Set<String>, errors: MutableList<ImportError>) {
        urls.forEach { url ->
            try {
                extensionStoreRepository.upsert(
                    ExtensionStore(
                        indexUrl = "$url/repo.json",
                        name = "",
                        badgeLabel = "",
                        signingKey = "",
                        contact = ExtensionStore.Contact(website = url, discord = null),
                        isLegacy = true,
                        extensionListUrl = null,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors += error(ImportError.Kind.EXTENSION_STORE, url, e.message.orEmpty())
            }
        }
    }

    private fun error(kind: ImportError.Kind, subject: String, message: String) = ImportError(
        kind = kind,
        subject = subject,
        sourceLabel = null,
        message = message,
        at = System.currentTimeMillis(),
    )

    private companion object {
        const val LEGACY_EXTENSION_REPOS_KEY = "extension_repos"

        /** The extension ABI names a source's settings file `source_<id>`, and nothing else. */
        val SOURCE_PREFERENCES_FILE = Regex("source_-?\\d+")

        /** The system category's id, `categories.sq`'s Default row. */
        const val DEFAULT_CATEGORY_ID = 0L

        /** `default_category`'s "always ask", upstream's default for the key; no category has it. */
        const val ALWAYS_ASK_CATEGORY_ID = -1L

        val CATEGORY_SET_KEYS: Set<String> =
            LibraryPreferences.categoryPreferenceKeys + DownloadPreferences.categoryPreferenceKeys -
                LibraryPreferences.DEFAULT_CATEGORY_PREF_KEY

        /** Category ids in one comma-joined string rather than a string set. */
        val CATEGORY_LIST_KEYS: Set<String> = UpdatesPreferences.categoryPreferenceKeys
    }
}

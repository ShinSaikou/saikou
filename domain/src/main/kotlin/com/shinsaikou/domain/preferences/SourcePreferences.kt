package com.shinsaikou.domain.preferences

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.source.interactor.SetMigrateSorting
import tachiyomi.domain.migration.models.MigrationFlag
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import tachiyomi.core.common.preference.getLongArray
import tachiyomi.domain.library.model.LibraryDisplayMode
import java.util.Locale

/**
 * Browse and source-list settings. Upstream's legacy `extension_repos` set is not declared here: the
 * backup importer converts it into extension store rows.
 *
 * Upstream's eight migration keys are declared with upstream's names, helpers and defaults (phase 4 unit
 * MA), so a restored value is stored as the type upstream wrote and read back as the value it held:
 * `migration_flags` is an Int bitmask ([MigrationFlag.toBit]) and `migration_sources` a comma-joined
 * String. The migration dialog reads and writes [migrationFlags]; the batch flow (unit MB) reads the
 * other seven.
 */
@SingleIn(AppScope::class)
@Inject
class SourcePreferences(preferenceStore: PreferenceStore) {

    val sourceDisplayMode: Preference<LibraryDisplayMode> = preferenceStore.getObjectFromString(
        "pref_display_mode_catalogue",
        LibraryDisplayMode.default,
        LibraryDisplayMode.Serializer::serialize,
        LibraryDisplayMode.Serializer::deserialize,
    )

    val enabledLanguages: Preference<Set<String>> =
        preferenceStore.getStringSet("source_languages", defaultEnabledLanguages())

    /** Source ids, as text. */
    val disabledSources: Preference<Set<String>> = preferenceStore.getStringSet("hidden_catalogues", emptySet())

    /** Source ids, as text. */
    val pinnedSources: Preference<Set<String>> = preferenceStore.getStringSet("pinned_catalogues", emptySet())

    /** Package names of extensions whose sources never write history. */
    val incognitoExtensions: Preference<Set<String>> = preferenceStore.getStringSet("incognito_extensions", emptySet())

    val lastUsedSource: Preference<Long> = preferenceStore.getLong(Preference.appStateKey("last_catalogue_source"), -1L)

    val showNsfwSource: Preference<Boolean> = preferenceStore.getBoolean("show_nsfw_source", true)

    val hideInLibraryItems: Preference<Boolean> = preferenceStore.getBoolean("browse_hide_in_library_items", false)

    /** Global search's "Has results" chip, upstream's key. */
    val globalSearchFilterState: Preference<Boolean> =
        preferenceStore.getBoolean(Preference.appStateKey("has_filters_toggle_state"), false)

    /**
     * Global search's Pinned / All chip, true for Pinned. Upstream starts every search on Pinned and
     * stores nothing; Saikou remembers the choice on this device and starts on All.
     */
    val globalSearchPinnedOnly: Preference<Boolean> =
        preferenceStore.getBoolean(Preference.appStateKey("global_search_pinned_only"), false)

    val migrationSortingMode: Preference<SetMigrateSorting.Mode> = preferenceStore.getEnum(
        "pref_migration_sorting",
        SetMigrateSorting.Mode.ALPHABETICAL,
    )

    val migrationSortingDirection: Preference<SetMigrateSorting.Direction> = preferenceStore.getEnum(
        "pref_migration_direction",
        SetMigrateSorting.Direction.ASCENDING,
    )

    val migrationSources: Preference<List<Long>> = preferenceStore.getLongArray("migration_sources", emptyList())

    val migrationFlags: Preference<Set<MigrationFlag>> = preferenceStore.getObjectFromInt(
        key = "migration_flags",
        defaultValue = MigrationFlag.entries.toSet(),
        serializer = { MigrationFlag.toBit(it) },
        deserializer = { value: Int -> MigrationFlag.fromBit(value) },
    )

    /**
     * True once `MigrationFlagsUpgrade` has turned [MigrationFlag.DOWNLOADS] on in a [migrationFlags]
     * stored before that flag existed (unit migrate-downloads). App state, so it never travels in a
     * backup and a restore never runs the upgrade again.
     */
    val migrationFlagsDownloadsAdded: Preference<Boolean> =
        preferenceStore.getBoolean(Preference.appStateKey("migration_flags_downloads_added"), false)

    val migrationDeepSearchMode: Preference<Boolean> = preferenceStore.getBoolean("migration_deep_search", false)

    val migrationPrioritizeByChapters: Preference<Boolean> = preferenceStore.getBoolean(
        "migration_prioritize_by_chapters",
        false,
    )

    val migrationHideUnmatched: Preference<Boolean> = preferenceStore.getBoolean("migration_hide_unmatched", false)

    val migrationHideWithoutUpdates: Preference<Boolean> = preferenceStore.getBoolean(
        "migration_hide_without_updates",
        false,
    )

    private fun defaultEnabledLanguages(): Set<String> =
        setOf("all", "en", Locale.getDefault().language)
}

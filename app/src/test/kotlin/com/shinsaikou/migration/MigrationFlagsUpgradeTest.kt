package com.shinsaikou.migration

import com.shinsaikou.domain.preferences.SourcePreferences
import com.shinsaikou.sync.MapPreferenceStore
import io.kotest.matchers.shouldBe
import tachiyomi.domain.migration.models.MigrationFlag
import org.junit.jupiter.api.Test

/**
 * Unit migrate-downloads: the one-time upgrade that adds Downloaded chapters to migration options
 * saved before the flag existed. Each "start" is a new [SourcePreferences] over the same map, as
 * each process start reads the same preference file.
 */
class MigrationFlagsUpgradeTest {

    private fun start(store: MapPreferenceStore): Boolean = MigrationFlagsUpgrade.run(SourcePreferences(store))

    private fun flags(store: MapPreferenceStore): Set<MigrationFlag> = SourcePreferences(store).migrationFlags.get()

    @Test
    fun `a stored bitmask without the bit gets Downloaded chapters and keeps every other choice`() {
        // Chapters, Categories and Delete downloaded, saved by a build before the flag.
        val store = MapPreferenceStore(mutableMapOf("migration_flags" to 0b10011))

        start(store) shouldBe true

        store.values["migration_flags"] shouldBe 0b1010011
        flags(store) shouldBe setOf(
            MigrationFlag.CHAPTER,
            MigrationFlag.CATEGORY,
            MigrationFlag.DOWNLOADS,
            MigrationFlag.REMOVE_DOWNLOAD,
        )
        store.values["__APP_STATE_migration_flags_downloads_added"] shouldBe true
    }

    @Test
    fun `it runs once, so unticking Downloaded chapters afterwards sticks`() {
        val store = MapPreferenceStore(mutableMapOf("migration_flags" to 0b00001))
        start(store) shouldBe true

        SourcePreferences(store).migrationFlags.set(setOf(MigrationFlag.CHAPTER))

        start(store) shouldBe false
        start(store) shouldBe false
        flags(store) shouldBe setOf(MigrationFlag.CHAPTER)
        store.values["migration_flags"] shouldBe 0b00001
    }

    @Test
    fun `with nothing stored the value stays unset and the default decides`() {
        val store = MapPreferenceStore()

        start(store) shouldBe false

        store.values.containsKey("migration_flags") shouldBe false
        SourcePreferences(store).migrationFlags.isSet() shouldBe false
        flags(store) shouldBe MigrationFlag.entries.toSet()
        // Marked done all the same: a value written later (the dialog, a restore) is not upgraded.
        SourcePreferences(store).migrationFlagsDownloadsAdded.get() shouldBe true
        SourcePreferences(store).migrationFlags.set(setOf(MigrationFlag.NOTES))
        start(store) shouldBe false
        flags(store) shouldBe setOf(MigrationFlag.NOTES)
    }

    @Test
    fun `a stored value that already has the bit is left as it is`() {
        val store = MapPreferenceStore(mutableMapOf("migration_flags" to 0b1000001))

        start(store) shouldBe false

        store.values["migration_flags"] shouldBe 0b1000001
    }
}

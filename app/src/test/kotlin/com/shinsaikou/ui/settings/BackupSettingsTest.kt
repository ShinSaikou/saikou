package com.shinsaikou.ui.settings

import com.shinsaikou.domain.preferences.BackupPreferences
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference

/**
 * The automatic backup rows of the Data and storage section: the intervals upstream offers, weekly
 * among them, and how many backups to keep, four unless the owner chose between two and ten. The
 * count is this device's own, so no backup carries it.
 */
class BackupSettingsTest {

    private val preferences = BackupPreferences(InMemoryPreferenceStore())

    @Test
    fun `the intervals are off, 6, 12, 24 and 48 hours, and weekly`() {
        backupIntervals shouldContainExactly listOf(0, 6, 12, 24, 48, 168)
        WEEK_HOURS shouldBe 7 * 24
    }

    @Test
    fun `four backups are kept unless the owner chose between two and ten, and the count is app state`() {
        preferences.keptBackups.get() shouldBe 4
        BackupPreferences.KEPT_BACKUPS_RANGE shouldBe 2..10
        Preference.isAppState(preferences.keptBackups.key()) shouldBe true
    }
}

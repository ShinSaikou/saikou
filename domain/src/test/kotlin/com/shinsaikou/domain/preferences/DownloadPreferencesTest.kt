package com.shinsaikou.domain.preferences

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference

class DownloadPreferencesTest {

    /** R162 item 2: an Experimental switch is off until the owner turns it on. */
    @Test
    fun `saving chapters as CBZ files defaults to off`() {
        val saveAsCbz = DownloadPreferences(InMemoryPreferenceStore()).saveChaptersAsCBZ

        saveAsCbz.defaultValue() shouldBe false
        saveAsCbz.get() shouldBe false
    }

    /**
     * Saikou's own key: upstream's was stored, usually true, by restores before the switch existed,
     * and must not turn it on (R162 item 7). A restore maps upstream's key onto this one.
     */
    @Test
    fun `saving chapters as CBZ files has Saikou's own key`() {
        DownloadPreferences(InMemoryPreferenceStore()).saveChaptersAsCBZ.key() shouldBe "saikou_save_chapter_as_cbz"
    }

    @Test
    fun `a value stored under upstream's key before this version leaves the switch off`() {
        // Seeded as already stored: this store keeps only its initial values, so a set() on a
        // preference it hands out would never reach it.
        val store = InMemoryPreferenceStore(
            sequenceOf(InMemoryPreferenceStore.InMemoryPreference("save_chapter_as_cbz", true, false)),
        )
        store.getBoolean("save_chapter_as_cbz", false).get() shouldBe true

        DownloadPreferences(store).saveChaptersAsCBZ.get() shouldBe false
    }

    @Test
    fun `saving chapters as CBZ files is not an app state key, so a backup carries it`() {
        val key = DownloadPreferences(InMemoryPreferenceStore()).saveChaptersAsCBZ.key()

        Preference.isAppState(key) shouldBe false
    }
}

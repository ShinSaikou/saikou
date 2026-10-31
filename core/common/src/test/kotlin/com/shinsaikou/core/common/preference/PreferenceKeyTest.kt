package com.shinsaikou.core.common.preference

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference

/**
 * The two key prefixes decide what a backup carries, so they are pinned here: the backup exporter
 * reads them by name and a rename would silently change what leaves the device.
 */
class PreferenceKeyTest {

    @Test
    fun `app state keys are recognised and never private`() {
        val key = Preference.appStateKey("last_app_closed")
        key shouldBe "__APP_STATE_last_app_closed"
        Preference.isAppState(key) shouldBe true
        Preference.isPrivate(key) shouldBe false
    }

    @Test
    fun `private keys are recognised and never app state`() {
        val key = Preference.privateKey("track_token")
        key shouldBe "__PRIVATE_track_token"
        Preference.isPrivate(key) shouldBe true
        Preference.isAppState(key) shouldBe false
    }

    @Test
    fun `a plain key is neither`() {
        Preference.isAppState("library_sorting_mode") shouldBe false
        Preference.isPrivate("library_sorting_mode") shouldBe false
    }

    @Test
    fun `the prefixed key is what the store sees`() {
        val store = InMemoryPreferenceStore()
        val preference = store.getLong(Preference.appStateKey("last_app_closed"), 0L)
        preference.key() shouldBe "__APP_STATE_last_app_closed"
        preference.get() shouldBe 0L
        preference.set(42L)
        preference.get() shouldBe 42L
    }
}

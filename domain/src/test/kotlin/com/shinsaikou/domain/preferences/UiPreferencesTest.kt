package com.shinsaikou.domain.preferences

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class UiPreferencesTest {

    /**
     * Phase 5 (`phase5-start.md` section 3.1.3): the Saikou scheme is the default on every API level,
     * where upstream's default is Dynamic on API 31 and up. The JVM reads `Build.VERSION.SDK_INT` as 0,
     * so this pins the default the preference declares; that no API level branch remains is read
     * from `UiPreferences` itself.
     */
    @Test
    fun `the app theme defaults to the Saikou scheme`() {
        val appTheme = UiPreferences(InMemoryPreferenceStore()).appTheme

        appTheme.defaultValue() shouldBe AppTheme.DEFAULT
        appTheme.get() shouldBe AppTheme.DEFAULT
    }

    @Test
    fun `the app theme keeps upstream's key`() {
        UiPreferences(InMemoryPreferenceStore()).appTheme.key() shouldBe "pref_app_theme"
    }
}

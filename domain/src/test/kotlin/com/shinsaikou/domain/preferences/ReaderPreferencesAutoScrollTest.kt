package com.shinsaikou.domain.preferences

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference

/** The two preferences of the Experimental long strip auto-scroll. */
class ReaderPreferencesAutoScrollTest {

    private val preferences = ReaderPreferences(InMemoryPreferenceStore())

    @Test
    fun `the switch is off by default`() {
        preferences.stripAutoScroll.get() shouldBe false
    }

    @Test
    fun `the switch is app state, so it stays on the device and out of backups`() {
        Preference.isAppState(preferences.stripAutoScroll.key()) shouldBe true
    }

    @Test
    fun `the speed is an ordinary reader preference, so it rides in backups`() {
        Preference.isAppState(preferences.stripAutoScrollSpeed.key()) shouldBe false
        Preference.isPrivate(preferences.stripAutoScrollSpeed.key()) shouldBe false
    }

    @Test
    fun `the speed defaults to level 4`() {
        preferences.stripAutoScrollSpeed.get() shouldBe 4
    }
}

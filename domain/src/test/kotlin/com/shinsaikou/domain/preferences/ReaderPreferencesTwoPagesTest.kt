package com.shinsaikou.domain.preferences

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference

/** The three preferences of the Experimental two pages side by side. */
class ReaderPreferencesTwoPagesTest {

    private val preferences = ReaderPreferences(InMemoryPreferenceStore())

    @Test
    fun `the switch is off by default`() {
        preferences.twoPages.get() shouldBe false
    }

    @Test
    fun `the switch is app state, so it stays on the device and out of backups`() {
        Preference.isAppState(preferences.twoPages.key()) shouldBe true
    }

    @Test
    fun `the layout shows two pages in landscape by default`() {
        preferences.twoPageLayout.get() shouldBe TwoPageLayout.IN_LANDSCAPE
    }

    @Test
    fun `the layout is an ordinary reader preference, so it rides in backups`() {
        Preference.isAppState(preferences.twoPageLayout.key()) shouldBe false
        Preference.isPrivate(preferences.twoPageLayout.key()) shouldBe false
    }

    @Test
    fun `the shifted entries start empty and stay on the device`() {
        preferences.twoPagesShifted.get() shouldBe emptySet()
        Preference.isAppState(preferences.twoPagesShifted.key()) shouldBe true
    }
}

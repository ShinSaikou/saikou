package com.shinsaikou.domain.source.interactor

import com.shinsaikou.domain.preferences.SourcePreferences
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class ToggleLanguageTest {

    private val preferences = SourcePreferences(InMemoryPreferenceStore())
    private val toggle = ToggleLanguage(preferences)

    @Test
    fun `a language that is not enabled is added and the others stay`() {
        preferences.enabledLanguages.set(setOf("all", "en"))

        toggle.await("ja")

        preferences.enabledLanguages.get() shouldBe setOf("all", "en", "ja")
    }

    @Test
    fun `an enabled language is removed and the others stay`() {
        preferences.enabledLanguages.set(setOf("all", "en", "ja"))

        toggle.await("en")

        preferences.enabledLanguages.get() shouldBe setOf("all", "ja")
    }

    @Test
    fun `two toggles of one code leave the set as it was, all included`() {
        preferences.enabledLanguages.set(setOf("en"))

        toggle.await("all")
        preferences.enabledLanguages.get() shouldBe setOf("en", "all")
        toggle.await("all")

        preferences.enabledLanguages.get() shouldBe setOf("en")
    }
}

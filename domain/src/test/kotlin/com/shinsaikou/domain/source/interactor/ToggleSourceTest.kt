package com.shinsaikou.domain.source.interactor

import com.shinsaikou.domain.preferences.SourcePreferences
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class ToggleSourceTest {

    private val preferences = SourcePreferences(InMemoryPreferenceStore())
    private val toggle = ToggleSource(preferences)

    @Test
    fun `a source that is not disabled is disabled by its id as text`() {
        toggle.await(1L)

        preferences.disabledSources.get() shouldBe setOf("1")
    }

    @Test
    fun `a disabled source is enabled and the other disabled sources stay`() {
        preferences.disabledSources.set(setOf("1", "2"))

        toggle.await(1L)

        preferences.disabledSources.get() shouldBe setOf("2")
    }

    @Test
    fun `an explicit enable or disable is applied whatever the current state`() {
        preferences.disabledSources.set(setOf("2"))

        toggle.await(1L, enable = true)
        toggle.await(2L, enable = false)

        preferences.disabledSources.get() shouldBe setOf("2")
    }
}

package com.shinsaikou.domain.source.interactor

import com.shinsaikou.domain.preferences.SourcePreferences
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class ToggleSourcePinTest {

    private val preferences = SourcePreferences(InMemoryPreferenceStore())
    private val toggle = ToggleSourcePin(preferences)

    @Test
    fun `a source that is not pinned is pinned by its id as text`() {
        toggle.await(1L)

        preferences.pinnedSources.get() shouldBe setOf("1")
    }

    @Test
    fun `a pinned source is unpinned and the other pins stay`() {
        preferences.pinnedSources.set(setOf("1", "2"))

        toggle.await(1L)

        preferences.pinnedSources.get() shouldBe setOf("2")
    }

    @Test
    fun `two toggles leave the set as it was`() {
        preferences.pinnedSources.set(setOf("2"))

        toggle.await(1L)
        toggle.await(1L)

        preferences.pinnedSources.get() shouldBe setOf("2")
    }
}

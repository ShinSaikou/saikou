package com.shinsaikou.extensions.loader

import com.shinsaikou.domain.preferences.ExtensionsPreferences
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class LibVersionPolicyTest {

    private val preferences = ExtensionsPreferences(InMemoryPreferenceStore())
    private val policy = LibVersionPolicy(preferences)

    private fun withOverride(value: String): List<Double> {
        preferences.libVersionOverride.set(value)
        return policy.supported()
    }

    @Test
    fun `no override means the build constant`() {
        policy.supported() shouldBe listOf(1.4, 1.6)
    }

    @Test
    fun `an override replaces the whole list`() {
        withOverride("1.4,1.6,1.7") shouldBe listOf(1.4, 1.6, 1.7)
        withOverride(" 1.6 ") shouldBe listOf(1.6)
    }

    @Test
    fun `whitespace only is the same as no override`() {
        withOverride("   ") shouldBe listOf(1.4, 1.6)
    }

    @Test
    fun `garbage falls back instead of locking every extension out`() {
        withOverride("one point four") shouldBe listOf(1.4, 1.6)
        withOverride(",,,") shouldBe listOf(1.4, 1.6)
    }

    @Test
    fun `unparseable entries are dropped, the rest still apply`() {
        withOverride("1.4,nonsense,1.7") shouldBe listOf(1.4, 1.7)
    }

    @Test
    fun `a lib version is supported only when listed, and an unknown one never is`() {
        policy.isSupported(1.6) shouldBe true
        policy.isSupported(1.7) shouldBe false
        policy.isSupported(null) shouldBe false
        withOverride("1.7")
        policy.isSupported(1.7) shouldBe true
    }
}

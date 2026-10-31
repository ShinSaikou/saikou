package com.shinsaikou.reader

import com.shinsaikou.core.common.preferences.BasePreferences
import com.shinsaikou.domain.preferences.SourcePreferences
import com.shinsaikou.extensions.ExtensionManager
import com.shinsaikou.extensions.model.Extension
import com.shinsaikou.source.Source
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.io.File

class AppIncognitoPolicyTest {

    private val store = InMemoryPreferenceStore()
    private val basePreferences = BasePreferences(store)
    private val sourcePreferences = SourcePreferences(store)
    private val installed = MutableStateFlow<List<Extension.Installed>>(emptyList())
    private val extensionManager = mockk<ExtensionManager> {
        every { installedExtensions } returns installed
    }

    private val policy = AppIncognitoPolicy(basePreferences, sourcePreferences, extensionManager)

    @Test
    fun `nothing is incognito by default`() {
        policy.isIncognito(SOURCE_ID) shouldBe false
    }

    @Test
    fun `the global switch covers every source`() {
        basePreferences.incognitoMode.set(true)

        policy.isIncognito(SOURCE_ID) shouldBe true
        policy.isIncognito(OTHER_SOURCE_ID) shouldBe true
    }

    @Test
    fun `a listed extension makes only its own sources incognito`() {
        installed.value = listOf(installedExtension(PACKAGE, listOf(SOURCE_ID)))
        sourcePreferences.incognitoExtensions.set(setOf(PACKAGE))

        policy.isIncognito(SOURCE_ID) shouldBe true
        policy.isIncognito(OTHER_SOURCE_ID) shouldBe false
    }

    @Test
    fun `a listed extension that is no longer installed makes nothing incognito`() {
        sourcePreferences.incognitoExtensions.set(setOf(PACKAGE))

        policy.isIncognito(SOURCE_ID) shouldBe false
    }

    private fun installedExtension(packageName: String, sourceIds: List<Long>) = Extension.Installed(
        packageName = packageName,
        name = "Fixture",
        versionName = "1.0",
        versionCode = 1L,
        libVersion = 1.6,
        lang = "en",
        isNsfw = false,
        sources = sourceIds.map { id -> mockk<Source> { every { this@mockk.id } returns id } },
        extFile = File("fixture.ext"),
    )

    private companion object {
        const val SOURCE_ID = 11L
        const val OTHER_SOURCE_ID = 22L
        const val PACKAGE = "com.shinsaikou.fixture.one"
    }
}

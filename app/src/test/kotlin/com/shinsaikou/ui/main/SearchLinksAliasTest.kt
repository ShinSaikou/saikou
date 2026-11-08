package com.shinsaikou.ui.main

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.shinsaikou.domain.preferences.SourcePreferences
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers

/**
 * The manifest alias follows the Experimental switch: enabled while it is on, back to the manifest
 * default (disabled) while it is off, starting with the first value so a state left behind is
 * repaired. The `PackageManager` is the I/O boundary and is mocked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchLinksAliasTest {

    private val testDispatcher = StandardTestDispatcher()
    private val packageManager = mockk<PackageManager>(relaxed = true)
    private val context = mockk<Context> {
        every { this@mockk.packageManager } returns this@SearchLinksAliasTest.packageManager
    }
    private val sourcePreferences = SourcePreferences(InMemoryPreferenceStore())
    private val dispatchers = AppDispatchers(io = testDispatcher, default = testDispatcher, main = testDispatcher)

    @BeforeEach
    fun stubComponentName() {
        // The android.jar stub of ComponentName throws on construction, so it is intercepted.
        mockkConstructor(ComponentName::class)
    }

    @AfterEach
    fun unstub() {
        unmockkConstructor(ComponentName::class)
        clearAllMocks()
    }

    @Test
    fun `with the switch off at start the alias is set to its manifest default`() = runTest(testDispatcher) {
        SearchLinksAlias(context, sourcePreferences, backgroundScope, dispatchers).install()
        runCurrent()

        verify(exactly = 1) { packageManager.setComponentEnabledSetting(any(), DEFAULT, PackageManager.DONT_KILL_APP) }
        verify(exactly = 0) { packageManager.setComponentEnabledSetting(any(), ENABLED, any()) }
    }

    @Test
    fun `with the switch already on at start the alias is enabled`() = runTest(testDispatcher) {
        sourcePreferences.searchLinks.set(true)

        SearchLinksAlias(context, sourcePreferences, backgroundScope, dispatchers).install()
        runCurrent()

        verify(exactly = 1) { packageManager.setComponentEnabledSetting(any(), ENABLED, PackageManager.DONT_KILL_APP) }
        verify(exactly = 0) { packageManager.setComponentEnabledSetting(any(), DEFAULT, any()) }
    }

    @Test
    fun `turning the switch on and off follows it each time`() = runTest(testDispatcher) {
        SearchLinksAlias(context, sourcePreferences, backgroundScope, dispatchers).install()
        runCurrent()

        sourcePreferences.searchLinks.set(true)
        runCurrent()
        sourcePreferences.searchLinks.set(false)
        runCurrent()

        verifyOrder {
            packageManager.setComponentEnabledSetting(any(), DEFAULT, any())
            packageManager.setComponentEnabledSetting(any(), ENABLED, any())
            packageManager.setComponentEnabledSetting(any(), DEFAULT, any())
        }
    }

    @Test
    fun `nothing is set before install is called`() = runTest(testDispatcher) {
        SearchLinksAlias(context, sourcePreferences, backgroundScope, dispatchers)
        runCurrent()

        verify(exactly = 0) { packageManager.setComponentEnabledSetting(any(), any(), any()) }
    }

    @Test
    fun `a package manager failure does not stop the alias following later changes`() = runTest(testDispatcher) {
        every { packageManager.setComponentEnabledSetting(any(), DEFAULT, any()) } throws SecurityException("denied")
        SearchLinksAlias(context, sourcePreferences, backgroundScope, dispatchers).install()
        runCurrent()

        sourcePreferences.searchLinks.set(true)
        runCurrent()

        verify(exactly = 1) { packageManager.setComponentEnabledSetting(any(), ENABLED, any()) }
    }

    private companion object {
        const val ENABLED = PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        const val DEFAULT = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
    }
}

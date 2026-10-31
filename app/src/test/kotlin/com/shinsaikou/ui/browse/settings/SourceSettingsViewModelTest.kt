package com.shinsaikou.ui.browse.settings

import app.cash.turbine.test
import com.shinsaikou.extensions.cookies.CookieImporter
import com.shinsaikou.i18n.R
import com.shinsaikou.source.ConfigurableSource
import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceManager
import com.shinsaikou.ui.fixture.ColdSourceRegistry
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.Cookie
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers
import kotlinx.coroutines.Dispatchers as KotlinDispatchers

/**
 * Everything cookie related here is addressed by URL rather than by whatever the field currently
 * shows: an import or a clear captures the host at the moment the owner tapped the button, and a
 * slow [refreshCookies][SourceSettingsViewModel.refreshCookies] reply must not overwrite a list the
 * owner has since typed past. Those are the two decisions worth pinning; the rest of the screen is
 * plumbing around them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SourceSettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val cookieImporter = mockk<CookieImporter>()
    private val sourceManager = mockk<SourceManager>()

    @BeforeEach
    fun setUp() {
        KotlinDispatchers.setMain(testDispatcher)
        every { cookieImporter.cookiesFor(any()) } returns emptyList()
        every { cookieImporter.flush() } returns Unit
        // A registry that has finished its first load; the cold-start test passes its own.
        every { sourceManager.isInitialized } returns MutableStateFlow(true)
    }

    @Test
    fun `a screen restored before the registry has loaded shows loading, then the installed source`() =
        runTest(testDispatcher) {
            val configurable = mockk<ConfigurableSource> {
                every { name } returns "Fixture Source"
                every { homeUrl } returns HOME_URL
            }
            val registry = ColdSourceRegistry(mapOf(SOURCE_ID to configurable))

            val viewModel = viewModel(sourceManager = registry.manager)
            advanceUntilIdle()
            viewModel.state.value.loading shouldBe true
            viewModel.configurableSource shouldBe null

            registry.finishLoad()
            advanceUntilIdle()

            viewModel.state.value.loading shouldBe false
            viewModel.state.value.installed shouldBe true
            viewModel.state.value.configurable shouldBe true
            viewModel.state.value.sourceName shouldBe "Fixture Source"
            viewModel.configurableSource shouldBe configurable
        }

    @AfterEach
    fun tearDown() {
        KotlinDispatchers.resetMain()
    }

    @Test
    fun `configurable reflects whether the loaded source implements ConfigurableSource`() = runTest(testDispatcher) {
        val configurable = mockk<ConfigurableSource> {
            every { name } returns "Fixture Source"
            every { homeUrl } returns HOME_URL
        }
        every { sourceManager.get(SOURCE_ID) } returns configurable

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.state.value.configurable shouldBe true
        viewModel.state.value.cookieHost shouldBe HOME_URL
    }

    @Test
    fun `a plain source is not configurable`() = runTest(testDispatcher) {
        val plain = mockk<Source> {
            every { name } returns "Fixture Source"
            every { homeUrl } returns null
        }
        every { sourceManager.get(SOURCE_ID) } returns plain

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.state.value.configurable shouldBe false
    }

    @Test
    fun `importCookies rejects an unparsable host without touching the jar`() = runTest(testDispatcher) {
        every { sourceManager.get(SOURCE_ID) } returns null
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.setCookieHost("not a url")
        advanceUntilIdle()
        viewModel.setCookieHeader("a=1")

        viewModel.events.test {
            viewModel.importCookies()
            advanceUntilIdle()

            awaitItem() shouldBe SourceSettingsViewModel.Event.Message(R.string.source_cookies_invalid_url)
        }
        verify(exactly = 0) { cookieImporter.importCookieHeader(any(), any()) }
    }

    @Test
    fun `importCookies does nothing for a blank header`() = runTest(testDispatcher) {
        every { sourceManager.get(SOURCE_ID) } returns null
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.setCookieHost(HOME_URL)
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.importCookies()
            advanceUntilIdle()

            expectNoEvents()
        }
        verify(exactly = 0) { cookieImporter.importCookieHeader(any(), any()) }
    }

    @Test
    fun `importCookies clears the header and reports the count when something was written`() = runTest(testDispatcher) {
        every { sourceManager.get(SOURCE_ID) } returns null
        every { cookieImporter.importCookieHeader(HOME_URL, "a=1; b=2") } returns 2
        every { cookieImporter.cookiesFor(HOME_URL) } returns listOf(cookie("a"), cookie("b"))

        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.setCookieHost(HOME_URL)
        advanceUntilIdle()
        viewModel.setCookieHeader("a=1; b=2")

        viewModel.events.test {
            viewModel.importCookies()
            advanceUntilIdle()

            awaitItem() shouldBe SourceSettingsViewModel.Event.CountMessage(R.string.source_cookies_imported_format, 2)
        }
        viewModel.state.value.cookieHeader shouldBe ""
        viewModel.state.value.cookieNames shouldContainExactly listOf("a", "b")
    }

    @Test
    fun `importCookies keeps the header text when nothing in it parsed`() = runTest(testDispatcher) {
        every { sourceManager.get(SOURCE_ID) } returns null
        every { cookieImporter.importCookieHeader(HOME_URL, "garbage") } returns 0

        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.setCookieHost(HOME_URL)
        advanceUntilIdle()
        viewModel.setCookieHeader("garbage")

        viewModel.events.test {
            viewModel.importCookies()
            advanceUntilIdle()

            awaitItem() shouldBe SourceSettingsViewModel.Event.CountMessage(R.string.source_cookies_imported_format, 0)
        }
        viewModel.state.value.cookieHeader shouldBe "garbage"
    }

    @Test
    fun `clearCookies rejects an unparsable host without touching the jar`() = runTest(testDispatcher) {
        every { sourceManager.get(SOURCE_ID) } returns null
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.setCookieHost("not a url")
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.clearCookies()
            advanceUntilIdle()

            awaitItem() shouldBe SourceSettingsViewModel.Event.Message(R.string.source_cookies_invalid_url)
        }
        verify(exactly = 0) { cookieImporter.clear(any(), any()) }
    }

    @Test
    fun `clearCookies removes every cookie for the host and reports how many`() = runTest(testDispatcher) {
        every { sourceManager.get(SOURCE_ID) } returns null
        every { cookieImporter.clear(HOME_URL, names = null) } returns 4

        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.setCookieHost(HOME_URL)
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.clearCookies()
            advanceUntilIdle()

            awaitItem() shouldBe SourceSettingsViewModel.Event.CountMessage(R.string.source_cookies_cleared_format, 4)
        }
        verify(exactly = 1) { cookieImporter.clear(HOME_URL, names = null) }
        // Clearing a session must be durable rather than waiting on CookieManager's own write-out.
        verify(exactly = 1) { cookieImporter.flush() }
    }

    @Test
    fun `clearCookies does not flush when nothing was removed`() = runTest(testDispatcher) {
        every { sourceManager.get(SOURCE_ID) } returns null
        every { cookieImporter.clear(HOME_URL, names = null) } returns 0

        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.setCookieHost(HOME_URL)
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.clearCookies()
            advanceUntilIdle()

            awaitItem() shouldBe SourceSettingsViewModel.Event.CountMessage(R.string.source_cookies_cleared_format, 0)
        }
        verify(exactly = 0) { cookieImporter.flush() }
    }

    @Test
    fun `a slow refresh for a host the owner has already edited away from does not land`() = runTest(testDispatcher) {
        every { sourceManager.get(SOURCE_ID) } returns null
        every { cookieImporter.cookiesFor(HOST_A) } returns listOf(cookie("stale"))
        every { cookieImporter.cookiesFor(HOST_B) } returns listOf(cookie("fresh"))

        // The io hop is held open manually so the test can force the reply for HOST_A, which was
        // requested first, to land strictly after the reply for HOST_B. A naive dispatcher would
        // always resolve requests in submission order, which is exactly the ordering that would
        // hide a missing guard: this is the one interleaving that actually distinguishes them.
        val io = ManualIoDispatcher()
        val viewModel = SourceSettingsViewModel(
            sourceId = SOURCE_ID,
            sourceManager = sourceManager,
            cookieImporter = cookieImporter,
            dispatchers = AppDispatchers(io = io, default = testDispatcher, main = testDispatcher),
        )
        advanceUntilIdle()

        viewModel.setCookieHost(HOST_A)
        viewModel.setCookieHost(HOST_B)
        advanceUntilIdle()
        io.pendingCount shouldBe 2

        // HOST_B's reply, requested second, arrives first.
        io.resumeAt(1)
        advanceUntilIdle()
        viewModel.state.value.cookieNames shouldContainExactly listOf("fresh")

        // HOST_A's reply, requested first, arrives last: by now the field has moved on to HOST_B,
        // so this stale reply must be discarded rather than clobbering the current list.
        io.resumeAt(0)
        advanceUntilIdle()
        viewModel.state.value.cookieNames shouldContainExactly listOf("fresh")
    }

    private fun cookie(cookieName: String): Cookie = mockk { every { name } returns cookieName }

    /**
     * A [kotlinx.coroutines.CoroutineDispatcher] that queues every dispatched continuation instead
     * of running it, so a test can resolve two in-flight hops in whichever order it needs to prove
     * an out-of-order reply is handled correctly.
     */
    private class ManualIoDispatcher : kotlinx.coroutines.CoroutineDispatcher() {
        private val pending = mutableListOf<Runnable>()

        val pendingCount: Int get() = pending.size

        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            pending += block
        }

        fun resumeAt(index: Int) {
            pending.removeAt(index).run()
        }
    }

    private fun viewModel(sourceManager: SourceManager = this.sourceManager) = SourceSettingsViewModel(
        sourceId = SOURCE_ID,
        sourceManager = sourceManager,
        cookieImporter = cookieImporter,
        dispatchers = AppDispatchers(io = testDispatcher, default = testDispatcher, main = testDispatcher),
    )

    private companion object {
        const val SOURCE_ID = 11L
        const val HOME_URL = "https://example.invalid/home"
        const val HOST_A = "https://a.example.invalid/"
        const val HOST_B = "https://b.example.invalid/"
    }
}

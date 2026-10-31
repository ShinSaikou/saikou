package com.shinsaikou.ui.webview

import app.cash.turbine.test
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.extensions.cookies.CookieImporter
import com.shinsaikou.i18n.R
import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceManager
import eu.kanade.tachiyomi.network.NetworkHelper
import io.kotest.matchers.nulls.shouldBeNull
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlinx.coroutines.Dispatchers as KotlinDispatchers

/**
 * [WebViewViewModel.clearCookies] is the one method this screen exists for, and the whole point of
 * it is that it targets the page the owner is looking at rather than the address the route opened
 * with. The tests below pin that, plus the small state transitions the WebView callbacks drive: a
 * title that becomes no title at all, a progress value the widget must never see out of range, and
 * an error a fresh page load clears on its way in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WebViewViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val cookieImporter = mockk<CookieImporter>()
    private val networkHelper = mockk<NetworkHelper> {
        every { defaultUserAgentProvider() } returns "Fixture Agent"
    }
    private val initialized = MutableStateFlow(true)
    private val sourceManager = mockk<SourceManager>().also {
        every { it.isInitialized } returns initialized
        every { it.get(any()) } returns null
    }

    @BeforeEach
    fun setUp() {
        KotlinDispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        KotlinDispatchers.resetMain()
    }

    @Test
    fun `a blank or whitespace title falls back to null`() {
        val viewModel = viewModel()

        viewModel.onTitle("  Chapter One  ")
        viewModel.state.value.title shouldBe "Chapter One"

        viewModel.onTitle("   ")
        viewModel.state.value.title.shouldBeNull()

        viewModel.onTitle(null)
        viewModel.state.value.title.shouldBeNull()
    }

    @Test
    fun `progress is coerced into the 0 to 100 range`() {
        val viewModel = viewModel()

        viewModel.onProgress(-15)
        viewModel.state.value.progress shouldBe 0

        viewModel.onProgress(140)
        viewModel.state.value.progress shouldBe 100

        viewModel.onProgress(42)
        viewModel.state.value.progress shouldBe 42
    }

    @Test
    fun `onPageStarted clears a previous error and resets progress`() {
        val viewModel = viewModel()
        viewModel.onProgress(80)
        viewModel.onError("Timed out")
        viewModel.state.value.error shouldBe "Timed out"

        viewModel.onPageStarted(REDIRECTED_URL)

        val state = viewModel.state.value
        state.url shouldBe REDIRECTED_URL
        state.progress shouldBe 0
        state.error.shouldBeNull()
    }

    @Test
    fun `onPageFinished sets progress to 100`() {
        val viewModel = viewModel()

        viewModel.onPageFinished(REDIRECTED_URL)

        val state = viewModel.state.value
        state.url shouldBe REDIRECTED_URL
        state.progress shouldBe 100
    }

    @Test
    fun `clearCookies targets the page the owner is on, not the route's original url`() = runTest(testDispatcher) {
        every { cookieImporter.clear(REDIRECTED_URL) } returns 3
        val viewModel = viewModel()
        // The page moved out from under the route, the way an interstitial does.
        viewModel.onPageStarted(REDIRECTED_URL)

        viewModel.events.test {
            viewModel.clearCookies()
            advanceUntilIdle()

            awaitItem() shouldBe WebViewViewModel.Event.Snackbar(
                R.string.webview_cookies_cleared_format,
                listOf(3),
            )
        }
        verify(exactly = 1) { cookieImporter.clear(REDIRECTED_URL) }
        verify(exactly = 0) { cookieImporter.clear(ROUTE_URL) }
    }

    @Test
    fun `a renderer that dies closes the screen once instead of taking the app with it`() = runTest(testDispatcher) {
        val viewModel = viewModel()

        viewModel.events.test {
            viewModel.onRenderProcessGone(crashed = true)
            // WebView reports it once per view, but nothing here should depend on that.
            viewModel.onRenderProcessGone(crashed = false)
            advanceUntilIdle()

            awaitItem() shouldBe WebViewViewModel.Event.RendererGone
            expectNoEvents()
        }
        viewModel.state.value.rendererGone shouldBe true
    }

    @Test
    fun `flushCookies delegates to the cookie importer`() {
        every { cookieImporter.flush() } returns Unit

        viewModel().flushCookies()

        verify(exactly = 1) { cookieImporter.flush() }
    }

    @Test
    fun `a page opened for a source loads with that source's agent and headers`() = runTest(testDispatcher) {
        every { sourceManager.get(SOURCE_ID) } returns source(
            mapOf("user-agent" to "Source Agent/2.0", "referer" to "https://example.invalid/"),
        )
        val viewModel = viewModel()
        viewModel.state.value.setup.shouldBeNull()

        advanceUntilIdle()

        viewModel.state.value.setup shouldBe WebViewViewModel.Setup(
            userAgent = "Source Agent/2.0",
            headers = mapOf("referer" to "https://example.invalid/"),
        )
    }

    @Test
    fun `headers a WebView refuses are left out, an Origin above all`() = runTest(testDispatcher) {
        every { sourceManager.get(SOURCE_ID) } returns source(
            mapOf(
                "user-agent" to "Source Agent/2.0",
                "referer" to "https://example.invalid/",
                "origin" to "https://example.invalid",
                "host" to "example.invalid",
                "proxy-authorization" to "Basic Zml4dHVyZQ==",
            ),
        )
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.state.value.setup?.headers shouldBe mapOf("referer" to "https://example.invalid/")
    }

    @Test
    fun `the source's headers go to the source's host and nowhere else`() = runTest(testDispatcher) {
        every { sourceManager.get(SOURCE_ID) } returns source(mapOf("referer" to "https://example.invalid/"))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.headersFor(ROUTE_URL) shouldBe mapOf("referer" to "https://example.invalid/")
        viewModel.headersFor("https://example.invalid/manga/2?page=3") shouldBe
            mapOf("referer" to "https://example.invalid/")
        // A configuration change reloads wherever the page went, here a challenge on another host.
        viewModel.onPageStarted(REDIRECTED_URL)
        viewModel.headersFor(viewModel.state.value.url) shouldBe emptyMap()
        viewModel.headersFor("not a url") shouldBe emptyMap()
    }

    @Test
    fun `the source is looked up only once the registry has loaded`() = runTest(testDispatcher) {
        initialized.value = false
        every { sourceManager.get(SOURCE_ID) } returns source(mapOf("user-agent" to "Source Agent/2.0"))
        val viewModel = viewModel()

        advanceUntilIdle()
        viewModel.state.value.setup.shouldBeNull()
        verify(exactly = 0) { sourceManager.get(any()) }

        initialized.value = true
        advanceUntilIdle()
        viewModel.state.value.setup?.userAgent shouldBe "Source Agent/2.0"
    }

    @Test
    fun `a source with no agent of its own, or none at all, takes the configured agent`() = runTest(testDispatcher) {
        every { sourceManager.get(SOURCE_ID) } returns source(mapOf("referer" to "https://example.invalid/"))
        val withSource = viewModel()
        val withoutSource = viewModel(sourceId = 99L)
        advanceUntilIdle()

        withSource.state.value.setup shouldBe WebViewViewModel.Setup(
            userAgent = "Fixture Agent",
            headers = mapOf("referer" to "https://example.invalid/"),
        )
        withoutSource.state.value.setup shouldBe WebViewViewModel.Setup("Fixture Agent", emptyMap())
    }

    @Test
    fun `a source whose headers throw falls back to the configured agent`() = runTest(testDispatcher) {
        val throwing = mockk<Source> { every { requestHeaders } throws IllegalStateException("no preferences") }
        every { sourceManager.get(SOURCE_ID) } returns throwing
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.state.value.setup shouldBe WebViewViewModel.Setup("Fixture Agent", emptyMap())
    }

    @Test
    fun `a page opened for no source is ready at once`() {
        val viewModel = viewModel(sourceId = null)

        viewModel.state.value.setup shouldBe WebViewViewModel.Setup("Fixture Agent", emptyMap())
        verify(exactly = 0) { sourceManager.get(any()) }
    }

    private fun source(headers: Map<String, String>) = mockk<Source> { every { requestHeaders } returns headers }

    private fun viewModel(sourceId: Long? = SOURCE_ID) = WebViewViewModel(
        url = ROUTE_URL,
        sourceId = sourceId,
        cookieImporter = cookieImporter,
        networkHelper = networkHelper,
        sourceManager = sourceManager,
        dispatchers = Dispatchers(testDispatcher, testDispatcher, testDispatcher),
    )

    private companion object {
        const val ROUTE_URL = "https://example.invalid/manga/1"
        const val REDIRECTED_URL = "https://challenge.example.invalid/interstitial"
        const val SOURCE_ID = 11L
    }
}

package com.shinsaikou.ui.webview

import androidx.annotation.StringRes
import androidx.lifecycle.viewModelScope
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.extensions.cookies.CookieImporter
import com.shinsaikou.i18n.R
import com.shinsaikou.presentation.core.viewmodel.StateViewModel
import com.shinsaikou.source.SourceManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.system.toWebViewHeaders
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.core.common.util.system.logcat

/**
 * The state behind the in-app browser, and very nearly the whole of its cookie plumbing.
 *
 * There is almost none, and that is the point. `AndroidCookieJar` is a thin wrapper over the
 * process-wide `CookieManager`; every OkHttp client derived from `NetworkHelper.client`, including
 * the `newBuilder()` clone each extension makes, carries that one jar; and every WebView in the
 * process writes to that same manager. So a cookie the page picks up here, a login or a solved
 * interstitial, is already attached to the next extension request for that host, with nothing
 * copied between the two. [flushCookies] is the only plumbing this screen owes anyone.
 *
 * The screen exists because the headless path cannot always finish the job. `CloudflareInterceptor`
 * solves what a machine can solve off-screen; a challenge that wants a checkbox, or a site that
 * wants a password, has no headless answer, and until now there was no way to answer it by hand.
 *
 * No WebView is held here. One cannot even be constructed on the unit-test JVM, so everything the
 * page reports arrives through the plain callbacks below and everything done to the page is done by
 * the screen to its own view. That split is what makes any of this testable.
 *
 * @property sourceId the source the page was opened for, carried straight off the route. Cookies
 *   are scoped by host, so the jar needs no help from it; the page's headers do (see [Setup]).
 */
@AssistedInject
class WebViewViewModel(
    @Assisted url: String,
    @Assisted val sourceId: Long?,
    private val cookieImporter: CookieImporter,
    private val networkHelper: NetworkHelper,
    private val sourceManager: SourceManager,
    private val dispatchers: Dispatchers,
) : StateViewModel<WebViewViewModel.State>(State(url = url)) {

    /**
     * [progress] is the WebView's own 0 to 100. It doubles as the loading flag, because a separate
     * boolean and a percentage drift apart the moment one callback fires without the other.
     */
    data class State(
        val url: String,
        val title: String? = null,
        val progress: Int = 0,
        val canGoBack: Boolean = false,
        val canGoForward: Boolean = false,
        val error: String? = null,
        val setup: Setup? = null,
        val rendererGone: Boolean = false,
    )

    /**
     * What the page is loaded with, null until it is known; the screen holds the page back until
     * then, because the agent cannot change under a page that has started loading.
     *
     * The page has to present the agent the source's own requests present. A clearance is issued
     * to a browser fingerprint, so one earned under any other agent is refused on the source's next
     * request and the challenge simply comes back. A source names its agent in its headers; any
     * other page takes the configured one, which is what an extension's default headers carry.
     *
     * @property headers the source's other headers (its Referer, for one), sent with the first load
     *   as upstream sends them, less those a WebView refuses: an Origin among them kills the page's
     *   renderer.
     */
    data class Setup(val userAgent: String, val headers: Map<String, String>)

    sealed interface Event {
        data class Snackbar(@StringRes val messageRes: Int, val args: List<Any> = emptyList()) : Event

        /** The page's renderer is gone and the screen closes; see [onRenderProcessGone]. */
        data object RendererGone : Event
    }

    private val eventChannel = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = eventChannel.receiveAsFlow()

    init {
        if (sourceId == null) {
            mutableState.update { it.copy(setup = setupFrom(emptyMap())) }
        } else {
            viewModelScope.launch {
                // A page restored after process death comes back before the extensions have loaded,
                // when its source still reads as missing; ruling R69 item 4 has it wait the same way.
                sourceManager.isInitialized.first { it }
                val headers = withContext(dispatchers.io) {
                    runCatching { sourceManager.get(sourceId)?.requestHeaders.orEmpty() }
                        .onFailure { logcat(LogPriority.WARN, it) { "Source $sourceId failed to build its headers" } }
                        .getOrDefault(emptyMap())
                }
                mutableState.update { it.copy(setup = setupFrom(headers)) }
            }
        }
    }

    /** The host the page was opened on: the source's own, and the only one its headers are for. */
    private val headersHost: String? = url.toHttpUrlOrNull()?.host

    /**
     * What to load [pageUrl] with: the source's headers on the host the page was opened on, and
     * none anywhere else. The screen reloads the live address after a configuration change, and
     * after a redirect or a followed link that is often another site, which has no business seeing
     * the source's Referer or tokens.
     */
    fun headersFor(pageUrl: String): Map<String, String> {
        val headers = state.value.setup?.headers.orEmpty()
        return if (headersHost != null && pageUrl.toHttpUrlOrNull()?.host == headersHost) headers else emptyMap()
    }

    private fun setupFrom(headers: Map<String, String>): Setup = Setup(
        userAgent = headers[USER_AGENT]?.takeIf(String::isNotBlank) ?: networkHelper.defaultUserAgentProvider(),
        headers = (headers - USER_AGENT).toWebViewHeaders(),
    )

    /**
     * A new page began loading. The url comes from the callback rather than staying at the route's:
     * an interstitial is mostly redirects, so the page moves out from under the route within the
     * first second, and every cookie action on this screen is about where the page is now.
     */
    fun onPageStarted(url: String) {
        mutableState.update { it.copy(url = url, progress = 0, error = null) }
    }

    fun onPageFinished(url: String) {
        mutableState.update { it.copy(url = url, progress = 100) }
    }

    /** A blank title is stored as none, so the top bar falls back instead of showing an empty bar. */
    fun onTitle(title: String?) {
        mutableState.update { it.copy(title = title?.trim()?.takeIf(String::isNotEmpty)) }
    }

    fun onProgress(progress: Int) {
        mutableState.update { it.copy(progress = progress.coerceIn(0, 100)) }
    }

    fun onNavigationState(canGoBack: Boolean, canGoForward: Boolean) {
        mutableState.update { it.copy(canGoBack = canGoBack, canGoForward = canGoForward) }
    }

    /**
     * Records a page failure. The screen shows it beside the page rather than instead of it: a
     * challenge often renders perfectly well while one of its subresources times out, and a screen
     * that swapped the page for an error card at that moment would throw away the very form the
     * owner came here to fill in.
     */
    fun onError(message: String) {
        mutableState.update { it.copy(error = message.trim().takeIf(String::isNotEmpty)) }
    }

    /**
     * The page's renderer died, from a crash or because the system reclaimed it. Left unhandled it
     * takes the app with it, and the view it leaves behind can only be destroyed, so the screen
     * says so and closes rather than show a page that no longer answers.
     */
    fun onRenderProcessGone(crashed: Boolean) {
        logcat(LogPriority.ERROR) { "WebView renderer gone: crashed=$crashed" }
        if (state.value.rendererGone) return
        mutableState.update { it.copy(rendererGone = true) }
        viewModelScope.launch { eventChannel.send(Event.RendererGone) }
    }

    /**
     * Clears every cookie for the page the owner is looking at, which is not always the page the
     * route named. After a redirect the two are different hosts as often as not, and clearing the
     * route's host would leave the interstitial's own cookie in place: the one that needs to go.
     */
    fun clearCookies() {
        val removed = cookieImporter.clear(state.value.url)
        viewModelScope.launch {
            eventChannel.send(Event.Snackbar(R.string.webview_cookies_cleared_format, listOf(removed)))
        }
    }

    /**
     * Called when the screen leaves. `CookieManager` holds what a page set in memory and writes it
     * out on a schedule of its own, so without this a clearance earned seconds before the process
     * is killed is simply gone on the next start.
     */
    fun flushCookies() {
        cookieImporter.flush()
    }

    private companion object {
        const val USER_AGENT = "user-agent"
    }

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(url: String, sourceId: Long?): WebViewViewModel
    }
}

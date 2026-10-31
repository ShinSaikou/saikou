package com.shinsaikou.ui.webview

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinsaikou.i18n.R
import com.shinsaikou.navigation.LocalNavigator
import com.shinsaikou.ui.components.ScreenTopBar
import com.shinsaikou.ui.components.SaikouSnackbarHost
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.tachiyomi.util.system.setUserAgent
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The in-app browser: a real page, in front of the owner, for the jobs the headless path cannot
 * finish on its own.
 *
 * Nothing is carried out of here by hand. The page writes to the process-wide cookie store, and
 * that store is the jar every extension request already reads from, so a login typed here or an
 * interstitial ticked here is attached to the next request without a line of plumbing. The view
 * model owns what little there is; this file owns the view.
 *
 * The `WebView` is remembered rather than left to the `AndroidView` factory alone, because the top
 * bar drives the same instance the factory attaches. A handle that only appeared once the factory
 * had run would be a top bar whose first tap did nothing.
 *
 * @param title the label the caller opened the page under. It holds the bar only until the page
 *   reports a title of its own, which is the moment the owner can tell where a redirect landed.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewScreen(url: String, sourceId: Long? = null, title: String? = null) {
    val viewModel: WebViewViewModel =
        assistedMetroViewModel<WebViewViewModel, WebViewViewModel.Factory> {
            create(url, sourceId)
        }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val resources = LocalResources.current
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    // The menu flag is saved, not merely remembered: a rotation with the menu open would otherwise
    // leave the owner looking at a bar that had swallowed their tap.
    var menuExpanded by rememberSaveable { mutableStateOf(false) }

    val webView = remember { WebView(context) }
    // Progress doubles as the loading flag, so the bar and the reload/stop action cannot disagree
    // about whether the page is still coming in.
    val loading = state.progress in 1 until 100

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is WebViewViewModel.Event.Snackbar ->
                    snackbarHostState.showSnackbar(
                        resources.getString(event.messageRes, *event.args.toTypedArray()),
                    )
                // A toast, not a snackbar: the message has to outlive the screen it explains.
                WebViewViewModel.Event.RendererGone -> {
                    context.toast(resources.getString(R.string.webview_renderer_gone), Toast.LENGTH_LONG)
                    navigator.back()
                }
            }
        }
    }

    DisposableEffect(webView) {
        onDispose {
            // The cookie store keeps what the page set in memory and writes it out on a schedule of
            // its own, so without this a clearance earned seconds before the process dies is gone.
            viewModel.flushCookies()
            // Only destroy may be called on a view whose renderer is gone.
            if (!viewModel.state.value.rendererGone) webView.stopLoading()
            // Destroying a view that is still attached to a window leaks it, so it leaves its
            // parent before it is destroyed rather than after.
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
    }

    // Hardware back walks the page's own history first. Leaving the screen on the first press would
    // strand an owner three redirects into a challenge with no way back to where it started.
    BackHandler(enabled = state.canGoBack) { webView.goBack() }

    Scaffold(
        topBar = { scrollBehavior ->
            ScreenTopBar(
                title = state.title ?: title ?: stringResource(R.string.webview_title_fallback),
                onBack = navigator::back,
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = { webView.goBack() }, enabled = state.canGoBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.webview_action_back),
                        )
                    }
                    IconButton(onClick = { webView.goForward() }, enabled = state.canGoForward) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowForward,
                            contentDescription = stringResource(R.string.webview_action_forward),
                        )
                    }
                    // Reload and stop share a slot, as every browser has them share one: only one of
                    // the two means anything at a time, and the icon is how the owner reads which.
                    if (loading) {
                        IconButton(onClick = { webView.stopLoading() }) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.webview_action_stop),
                            )
                        }
                    } else {
                        IconButton(onClick = { webView.reload() }) {
                            Icon(
                                Icons.Outlined.Refresh,
                                contentDescription = stringResource(R.string.webview_action_refresh),
                            )
                        }
                    }
                    WebViewOverflowMenu(
                        expanded = menuExpanded,
                        onExpandedChange = { menuExpanded = it },
                        onClearCookies = viewModel::clearCookies,
                        // The page the owner is looking at, which after a redirect is not the page
                        // the route named, and the route's is not the one worth handing over.
                        onOpenExternally = { navigator.openUrl(state.url) },
                    )
                },
            )
        },
        snackbarHost = { SaikouSnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (loading) {
                LinearProgressIndicator(
                    progress = { state.progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val error = state.error
            if (error != null) {
                // Beside the page, never instead of it. A challenge often renders perfectly well
                // while one of its subresources times out, and an error card in place of the page
                // at that moment would take away the very form the owner came here to fill in.
                Text(
                    stringResource(R.string.webview_error_format, error),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            // Held back until the view model knows which agent and headers the page loads with: the
            // agent cannot change under a page that has started, and the factory below runs once.
            val setup = state.setup
            if (setup == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else if (!state.rendererGone) {
                // A view whose renderer is gone leaves the hierarchy at once, as WebView asks.
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    // Everything about the view is settled here, where it runs exactly once. There
                    // is no update lambda on purpose: the page is driven by the top bar and by its
                    // own navigation, and a lambda that reran on recomposition would fight both.
                    factory = {
                        webView.apply {
                            // Not optional. An interstitial is JavaScript, and a page that cannot run
                            // it cannot present the one thing this screen exists to answer.
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            // The source's agent, with client hints to match (see
                            // WebViewViewModel.Setup): under any other, what is earned here is
                            // refused on the source's next request.
                            setUserAgent(setup.userAgent)
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            // A challenge is usually served inside a frame of its own, and it keeps
                            // no cookie at all without this.
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webViewClient = pageClient(viewModel)
                            webChromeClient = chromeClient(viewModel)
                            // Loaded once, here. In the update lambda this would reload the page on
                            // every recomposition, which on a challenge means never getting through.
                            //
                            // The live address, not the route's. This factory runs again after every
                            // activity re-creation (rotation, day/night, font scale, locale), because
                            // the view above it is a plain remember and the manifest declares no
                            // configChanges. The view model outlives all of those and has been
                            // tracking the page since the first redirect, so it is the only one of
                            // the two that still knows where the owner is. On the first composition
                            // the two are the same value anyway: the view model opens at the route's.
                            // The source's headers go only to the source's host (see headersFor).
                            loadUrl(state.url, viewModel.headersFor(state.url))
                        }
                    },
                )
            }
        }
    }
}

/**
 * The page callbacks, wired straight through to [viewModel].
 *
 * The navigation flags are pushed after every navigation rather than read when the top bar draws.
 * `canGoBack` may only be asked on the main thread and only of a live view, and the bar takes its
 * enablement from state like everything else on this screen.
 */
private fun pageClient(viewModel: WebViewViewModel): WebViewClient = object : WebViewClient() {
    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        viewModel.onPageStarted(url.orEmpty())
        viewModel.onNavigationState(view.canGoBack(), view.canGoForward())
    }

    override fun onPageFinished(view: WebView, url: String?) {
        viewModel.onPageFinished(url.orEmpty())
        viewModel.onNavigationState(view.canGoBack(), view.canGoForward())
    }

    /**
     * Every failure is reported, a subresource's included. Those are the common case on a challenge
     * and the reason the screen shows an error beside the page instead of in place of it.
     */
    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        viewModel.onError(error.description.toString())
    }

    /** Returning true keeps the app alive; the view model then closes the screen. */
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        viewModel.onRenderProcessGone(crashed = detail.didCrash())
        return true
    }
}

private fun chromeClient(viewModel: WebViewViewModel): WebChromeClient = object : WebChromeClient() {
    override fun onProgressChanged(view: WebView, newProgress: Int) {
        viewModel.onProgress(newProgress)
    }

    override fun onReceivedTitle(view: WebView, title: String?) {
        viewModel.onTitle(title)
    }
}

/**
 * The two actions that are not about moving around the page. Both sit behind the overflow because
 * clearing cookies undoes exactly the work the owner came here to do, and neither belongs under a
 * thumb that is aiming for reload.
 */
@Composable
private fun WebViewOverflowMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onClearCookies: () -> Unit,
    onOpenExternally: () -> Unit,
) {
    Box {
        IconButton(onClick = { onExpandedChange(true) }) {
            Icon(
                Icons.Outlined.MoreVert,
                contentDescription = stringResource(R.string.action_menu_overflow_description),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.webview_clear_cookies)) },
                onClick = {
                    onExpandedChange(false)
                    onClearCookies()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_open_in_browser)) },
                onClick = {
                    onExpandedChange(false)
                    onOpenExternally()
                },
            )
        }
    }
}

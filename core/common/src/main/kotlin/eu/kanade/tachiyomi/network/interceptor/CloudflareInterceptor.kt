// Vendored from upstream v0.20.4: core/common/src/main/kotlin/eu/kanade/tachiyomi/network/interceptor/CloudflareInterceptor.kt (modified: yes) per-host solve coalescing, user messages through NetworkUserMessages; upstream v0.21.0 ac249e366 ported (a challenge is detected by its cf-mitigated header, and an interactive one ends the solve at once), with the header read case-insensitively; upstream v0.21.3 f43a0f5d9 ported (the challenge page is loaded without the request's Origin header, which kills the renderer); upstream v0.21.1 649fc83ff ported (a renderer that dies mid-solve fails the solve instead of closing the app); the challenge's fail event from upstream v0.21.2 5ca2a3932 (it ends the solve, and a clearance a failed challenge left is dropped); upstream v0.21.2 cca6b5e2e ported (a challenge to a request other than GET is solved on the site's root), and a finished page is read whichever url it finished on, since that root may redirect; with the Experimental switch that trusts user-installed CAs on, the challenge page may proceed past a certificate one of them signed
package eu.kanade.tachiyomi.network.interceptor

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslError
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import com.shinsaikou.core.common.network.CloudflareBudgetExceededException
import com.shinsaikou.core.common.network.CloudflareSolveCoordinator
import com.shinsaikou.core.common.network.CloudflareSolveRecentlyFailedException
import com.shinsaikou.core.common.network.NetworkUserMessages
import com.shinsaikou.core.common.network.isSignedByUserCertificate
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.util.system.isOutdated
import eu.kanade.tachiyomi.util.system.toWebViewHeaders
import logcat.LogPriority
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import tachiyomi.core.common.util.system.logcat
import java.io.IOException
import java.util.concurrent.CountDownLatch

class CloudflareInterceptor(
    private val context: Context,
    private val cookieManager: AndroidCookieJar,
    defaultUserAgentProvider: () -> String,
    private val coordinator: CloudflareSolveCoordinator,
    private val messages: NetworkUserMessages,
    private val trustsUserCertificates: Boolean,
) : WebViewInterceptor(context, defaultUserAgentProvider, messages) {

    private val executor = ContextCompat.getMainExecutor(context)

    override fun shouldIntercept(response: Response): Boolean = isCloudflareChallenge(response)

    override fun intercept(
        chain: Interceptor.Chain,
        request: Request,
        response: Response,
    ): Response {
        try {
            response.close()
            val url = request.url
            val oldCookie = cookieManager.get(url)
                .firstOrNull { it.name == "cf_clearance" }

            // Modified: one solve at a time per host. A caller that arrives while another solve for
            // the same host runs waits for it and then skips its own, instead of parking a second
            // dispatcher thread on a second WebView solving the same challenge. The cookie is also
            // cleared inside the lock, so a waiter never wipes the cookie the solver just minted.
            coordinator.coalesce(
                host = url.host,
                waitBudget = CloudflareSolveCoordinator.WAITER_BUDGET,
                alreadySolved = {
                    // The clearance cookie is host scoped, so a fresh, different cookie means the
                    // retry passes without a second solve.
                    cookieManager.get(url)
                        .firstOrNull { it.name == "cf_clearance" }
                        .let { it != null && it != oldCookie }
                },
            ) {
                cookieManager.remove(url, COOKIE_NAMES, 0)
                resolveWithWebView(request, oldCookie)
            }

            return chain.proceed(request)
        }
        // Because OkHttp's enqueue only handles IOExceptions, wrap the exception so that
        // we don't crash the entire app
        catch (e: CloudflareBypassException) {
            throw IOException(messages.cloudflareBypassFailed(), e)
        } catch (e: CloudflareBudgetExceededException) {
            throw IOException(messages.cloudflareBypassFailed(), e)
        } catch (e: CloudflareSolveRecentlyFailedException) {
            throw IOException(messages.cloudflareBypassFailed(), e)
        } catch (e: Exception) {
            throw IOException(e)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun resolveWithWebView(originalRequest: Request, oldCookie: Cookie?) {
        // We need to lock this thread until the WebView finds the challenge solution url, because
        // OkHttp doesn't support asynchronous interceptors.
        val latch = CountDownLatch(1)
        val events = ChallengeEvents(latch)

        var webview: WebView? = null

        var challengeFound = false
        var cloudflareBypassed = false
        var isWebViewOutdated = false
        var isRenderProcessGone = false

        val origRequestUrl = originalRequest.url.toString()
        val challengeUrl = challengeUrlOf(originalRequest)
        val headers = originalRequest.headers.toWebViewHeaders()

        executor.execute {
            webview = createWebView(originalRequest)

            webview.addJavascriptInterface(
                object {
                    @Suppress("unused")
                    @JavascriptInterface
                    fun postMessage(event: String?) = events.onEvent(event)
                },
                INTERACTIVE_BRIDGE,
            )

            webview.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    fun isCloudFlareBypassed(): Boolean {
                        return cookieManager.get(origRequestUrl.toHttpUrl())
                            .firstOrNull { it.name == "cf_clearance" }
                            .let { it != null && it != oldCookie }
                    }

                    // A clearance from a failed challenge would only be rejected again
                    if (!events.failed && isCloudFlareBypassed()) {
                        cloudflareBypassed = true
                        latch.countDown()
                    }

                    events.onPageFinished(challengeFound) {
                        // Listen for the challenge's interactiveBegin and fail events
                        view.evaluateJavascript(
                            """
                                addEventListener("message", ({data}) => {
                                    if (data?.source === "cloudflare-challenge") {
                                        $INTERACTIVE_BRIDGE.postMessage(data.event);
                                    }
                                })
                            """.trimIndent(),
                            null,
                        )
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?,
                ) {
                    if (request?.isForMainFrame == true) {
                        val mitigated = errorResponse?.responseHeaders
                            ?.entries?.firstOrNull { it.key.equals(MITIGATED_HEADER, ignoreCase = true) }?.value
                        if (mitigated.equals(CHALLENGE, ignoreCase = true)) {
                            // Found the Cloudflare challenge page.
                            challengeFound = true
                        } else {
                            // Unlock thread, the challenge wasn't found.
                            latch.countDown()
                        }
                    }
                }

                // Modified: the network security config trusts system CAs only, so with the
                // Experimental switch on, a challenge behind a CA the owner installed would fail
                // here while the source client reaches the site. Anything else is refused as before.
                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    if (trustsUserCertificates && isSignedByUserCertificate(error)) {
                        handler.proceed()
                    } else {
                        super.onReceivedSslError(view, handler, error)
                    }
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    // Not handling it kills the app, failing the bypass is enough
                    logcat(LogPriority.ERROR) {
                        "WebView renderer gone during Cloudflare bypass: crashed=${detail.didCrash()}"
                    }
                    isRenderProcessGone = true
                    latch.countDown()
                    return true
                }
            }

            webview.loadUrl(challengeUrl, headers)
        }

        latch.awaitFor30Seconds()

        executor.execute {
            // Only destroy can be called on a WebView whose renderer is gone
            if (!cloudflareBypassed && !isRenderProcessGone) {
                isWebViewOutdated = webview?.isOutdated() == true
            }

            webview?.run {
                if (!isRenderProcessGone) stopLoading()
                destroy()
            }
        }

        // Throw exception if we failed to bypass Cloudflare
        if (!cloudflareBypassed) {
            // A clearance from a failed challenge would only be rejected again
            cookieManager.remove(originalRequest.url, COOKIE_NAMES, 0)
            // Prompt user to update WebView if it seems too outdated
            if (isWebViewOutdated) {
                messages.webViewOutdated()
            }

            throw CloudflareBypassException()
        }
    }
}

/**
 * Whether [response] is a Cloudflare challenge page. The `cf-mitigated` header is the documented way
 * to tell one from the site's own error; a geo block has none, and is never worth a solve.
 */
internal fun isCloudflareChallenge(response: Response): Boolean =
    response.header(MITIGATED_HEADER).equals(CHALLENGE, ignoreCase = true) &&
        response.header("Server") in SERVER_CHECK

/**
 * The page a challenge to [request] is solved on. A WebView can only load a page with GET, and
 * loading the url of another method usually reaches an endpoint that answers GET without a
 * challenge to solve. The clearance covers the whole site, so it's earned on the site's root page
 * instead.
 */
internal fun challengeUrlOf(request: Request): String = if (request.method == "GET") {
    request.url.toString()
} else {
    request.url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString()
}

/**
 * What the challenge page tells the solve about itself, on WebView's own thread: its finished loads,
 * and what it posts through the bridge. A challenge that wants a person, or that failed, ends the
 * solve at once; a failed one also voids any clearance it left, so the solve is not mistaken for a
 * success.
 */
internal class ChallengeEvents(private val latch: CountDownLatch) {
    @Volatile
    var failed: Boolean = false
        private set

    /**
     * A main-frame page finished, on whichever url: a site's root often redirects, and the page then
     * finishes on the url it was sent to. Waiting for the loaded url alone held such a solve for its
     * whole timeout. A page with a challenge gets [listen] to hear its events.
     */
    fun onPageFinished(challengeFound: Boolean, listen: () -> Unit) {
        if (challengeFound) {
            listen()
        } else {
            // The first request didn't return the challenge, abort.
            latch.countDown()
        }
    }

    fun onEvent(event: String?) {
        when (event) {
            // The challenge cannot be solved non-interactively, abort.
            "interactiveBegin" -> latch.countDown()
            "fail" -> {
                failed = true
                latch.countDown()
            }
        }
    }
}

private const val MITIGATED_HEADER = "cf-mitigated"
private const val CHALLENGE = "challenge"
private const val INTERACTIVE_BRIDGE = "saikouChallenge"
private val SERVER_CHECK = arrayOf("cloudflare-nginx", "cloudflare")
private val COOKIE_NAMES = listOf("cf_clearance")

private class CloudflareBypassException : Exception()

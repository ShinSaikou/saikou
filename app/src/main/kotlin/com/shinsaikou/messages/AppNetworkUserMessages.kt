package com.shinsaikou.messages

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.shinsaikou.core.common.network.NetworkUserMessages
import com.shinsaikou.i18n.R
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.util.system.toast

/**
 * The `:core:common` network stack's text, resolved against `strings_core.xml`. The two WebView
 * notices are toasts because they are raised from an interceptor on an OkHttp thread, with no
 * screen in scope; the toast is posted to the main looper because [toast] must not be called off it.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class AppNetworkUserMessages(private val context: Context) : NetworkUserMessages {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun cloudflareBypassFailed(): String =
        context.getString(R.string.information_cloudflare_bypass_failure)

    override fun webViewRequired() = toastOnMain(R.string.information_webview_required)

    override fun webViewOutdated() = toastOnMain(R.string.information_webview_outdated)

    private fun toastOnMain(resId: Int) {
        mainHandler.post { context.toast(context.getString(resId)) }
    }
}

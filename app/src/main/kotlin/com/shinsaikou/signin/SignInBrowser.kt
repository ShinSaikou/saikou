package com.shinsaikou.signin

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.zacsweers.metro.Inject
import okhttp3.HttpUrl

/**
 * Opens a sign-in's authorization page in the owner's browser with `ACTION_VIEW` (phase 3 unit O).
 * The spike kept it over a Custom Tab: no library, and the browser's own task, which reaches the
 * loopback listener the same way. It must be called while the app is in the foreground, as any
 * activity start from the app is.
 */
@Inject
class SignInBrowser(private val context: Context) {

    /** Throws `ActivityNotFoundException` when no app on the device opens a web page. */
    fun open(url: HttpUrl) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.toString())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

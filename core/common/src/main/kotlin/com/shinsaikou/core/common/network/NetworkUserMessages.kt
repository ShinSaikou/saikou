package com.shinsaikou.core.common.network

/**
 * User-facing text and notices the vendored network interceptors need. Upstream reached for its own
 * string resources and showed the toast itself; this module has no resources of its own, so the
 * app module implements this and owns both the wording and the presentation.
 */
interface NetworkUserMessages {

    /** Message for the [java.io.IOException] thrown when a challenge could not be solved. */
    fun cloudflareBypassFailed(): String

    /** The device has no usable WebView, so the challenge cannot be solved at all. */
    fun webViewRequired()

    /** The installed WebView is too old to solve the challenge. */
    fun webViewOutdated()
}

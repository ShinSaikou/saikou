package com.shinsaikou.presentation.core.util

import android.content.res.Resources
import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.domain.error.KeyedFailure
import com.shinsaikou.i18n.R

/**
 * The text of a failure, resolved through [ErrorKey] (ruling R67). It lives here rather than in
 * `:app`'s `ErrorText` so the reader, which cannot see `:app`, names a failure in the same words as
 * every other screen; `ErrorText` delegates to it and keeps the library update error list's rows.
 */
object ErrorKeyText {

    /**
     * A failure as a screen shows it once, at the moment it happens. A generic failure keeps its own
     * message when it has one (R67 rule 4), cut by [ErrorKey.message] as the library update error
     * list keeps it, so both read the same; every named failure reads as its key.
     */
    fun of(resources: Resources, error: Throwable?): String {
        if (error == null) return resources.getString(R.string.error_generic)
        return when (val key = ErrorKey.of(error)) {
            ErrorKey.GENERIC -> ErrorKey.message(error) ?: text(resources, key, null)
            else -> text(resources, key, ErrorKey.httpCode(error), ErrorKey.detail(error))
        }
    }

    /**
     * The text of [key]. The one place a key becomes a string resource. An HTTP failure without a
     * readable code, which only a hand-edited file can hold, reads as generic.
     *
     * [detail] is a [KeyedFailure]'s own: the host of a site the library update stopped asking,
     * the WebDAV status code, the host a redirect names, the
     * reason an address was refused, the loopback port in use, an OAuth `error` code, Google
     * Drive's status and error reason, or the code a tracker refused a sign-in with. A key whose
     * text needs one reads without it when it is absent or unreadable.
     */
    fun text(resources: Resources, key: ErrorKey, httpCode: Int?, detail: String? = null): String = when (key) {
        ErrorKey.CLOUDFLARE_BUDGET -> resources.getString(R.string.error_cloudflare_budget)
        ErrorKey.CLOUDFLARE -> resources.getString(R.string.error_cloudflare)
        ErrorKey.NO_NETWORK -> resources.getString(R.string.error_no_network)
        ErrorKey.HTTP -> httpCode?.let { resources.getString(R.string.error_http_format, it) }
            ?: resources.getString(R.string.error_generic)
        ErrorKey.TIMEOUT -> resources.getString(R.string.error_timeout)
        ErrorKey.NO_CHAPTERS -> resources.getString(R.string.error_no_chapters)
        ErrorKey.SOURCE_NOT_INSTALLED -> resources.getString(R.string.error_source_not_installed)
        ErrorKey.PAGE_LIST_EMPTY -> resources.getString(R.string.error_page_list_empty)
        // The manga screen's own words for the same absence (ruling R119 item 2).
        ErrorKey.MANGA_NOT_FOUND -> resources.getString(R.string.manga_not_found)
        ErrorKey.DOWNLOAD_FOLDER_IN_USE -> resources.getString(R.string.error_download_folder_in_use)
        ErrorKey.SITE_STOPPED -> detail?.let { resources.getString(R.string.error_site_stopped_format, it) }
            ?: resources.getString(R.string.error_site_stopped)
        ErrorKey.SYNC_NEWER_FORMAT -> resources.getString(R.string.error_sync_newer_format)
        ErrorKey.SYNC_MALFORMED_FILE -> resources.getString(R.string.error_sync_malformed_file)
        ErrorKey.SYNC_SIGNED_OUT -> resources.getString(R.string.error_sync_signed_out)
        ErrorKey.SYNC_SAFETY_BACKUP_FAILED -> resources.getString(R.string.error_sync_safety_backup_failed)
        ErrorKey.WEBDAV_UNTRUSTED_CERTIFICATE -> resources.getString(R.string.error_webdav_untrusted_certificate)
        ErrorKey.WEBDAV_HOST_NAME_MISMATCH -> resources.getString(R.string.error_webdav_host_name_mismatch)
        ErrorKey.WEBDAV_WRITE_NOT_STORED -> resources.getString(R.string.error_webdav_write_not_stored)
        ErrorKey.WEBDAV_MALFORMED_RESPONSE -> resources.getString(R.string.error_webdav_malformed_response)
        ErrorKey.WEBDAV_HTTP -> detail?.toIntOrNull()?.let { resources.getString(R.string.error_webdav_http_format, it) }
            ?: resources.getString(R.string.error_generic)
        ErrorKey.WEBDAV_REDIRECT -> detail?.let { resources.getString(R.string.error_webdav_redirect_format, it) }
            ?: resources.getString(R.string.error_webdav_redirect)
        ErrorKey.WEBDAV_URL_REFUSED -> resources.getString(
            when (detail) {
                URL_REFUSED_CLEARTEXT -> R.string.error_webdav_url_cleartext
                URL_REFUSED_NOT_A_BASE_URL -> R.string.error_webdav_url_not_base
                else -> R.string.error_webdav_url_not_a_url
            },
        )
        ErrorKey.WEBDAV_UNREACHABLE -> resources.getString(R.string.error_webdav_unreachable)
        ErrorKey.OAUTH_PORT_IN_USE -> detail?.toIntOrNull()?.let { resources.getString(R.string.error_oauth_port_in_use_format, it) }
            ?: resources.getString(R.string.error_oauth_port_in_use)
        ErrorKey.OAUTH_TIMED_OUT -> resources.getString(R.string.error_oauth_timed_out)
        ErrorKey.OAUTH_DENIED -> detail?.let { resources.getString(R.string.error_oauth_denied_format, it) }
            ?: resources.getString(R.string.error_oauth_denied)
        ErrorKey.GOOGLE_INVALID_GRANT -> resources.getString(R.string.error_google_invalid_grant)
        ErrorKey.GOOGLE_AUTH_REFUSED -> detail?.let { resources.getString(R.string.error_google_auth_refused_format, it) }
            ?: resources.getString(R.string.error_google_auth_refused)
        ErrorKey.GOOGLE_UNAVAILABLE -> detail?.toIntOrNull()?.let { resources.getString(R.string.error_google_unavailable_format, it) }
            ?: resources.getString(R.string.error_google_unavailable)
        ErrorKey.DRIVE_STORAGE_FULL -> resources.getString(R.string.error_drive_storage_full)
        ErrorKey.DRIVE_MALFORMED_RESPONSE -> resources.getString(R.string.error_drive_malformed_response)
        ErrorKey.DRIVE_HTTP -> detail?.let { resources.getString(R.string.error_drive_http_format, it) }
            ?: resources.getString(R.string.error_drive_http)
        ErrorKey.DRIVE_WRITE_NOT_STORED -> resources.getString(R.string.error_drive_write_not_stored)
        ErrorKey.DRIVE_UNAVAILABLE -> detail?.toIntOrNull()?.let { resources.getString(R.string.error_drive_unavailable_format, it) }
            ?: resources.getString(R.string.error_drive_unavailable)
        ErrorKey.TRACKER_SIGN_IN_REFUSED -> detail?.let { resources.getString(R.string.error_tracker_sign_in_refused_format, it) }
            ?: resources.getString(R.string.error_tracker_sign_in_refused)
        ErrorKey.TRACKER_AUTH_EXPIRED -> resources.getString(R.string.error_tracker_auth_expired)
        ErrorKey.GENERIC -> resources.getString(R.string.error_generic)
    }

    // The names of WebDavUrlRefusedException.Reason, which `:sync` stores as the detail; this module
    // cannot see the enum. A detail naming neither reads as "not a web address".
    private const val URL_REFUSED_CLEARTEXT = "CLEARTEXT"
    private const val URL_REFUSED_NOT_A_BASE_URL = "NOT_A_BASE_URL"
}

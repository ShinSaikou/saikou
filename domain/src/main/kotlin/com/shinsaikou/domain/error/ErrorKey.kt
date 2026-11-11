package com.shinsaikou.domain.error

import com.shinsaikou.core.common.network.CloudflareBudgetExceededException
import com.shinsaikou.core.common.network.CloudflareSolveRecentlyFailedException
import com.shinsaikou.core.common.oauth.LoopbackDeniedException
import com.shinsaikou.core.common.oauth.LoopbackPortInUseException
import com.shinsaikou.core.common.oauth.LoopbackTimeoutException
import com.shinsaikou.domain.chapter.model.PageListEmptyException
import com.shinsaikou.domain.download.model.ChapterFolderInUseException
import com.shinsaikou.domain.manga.model.MangaNotFoundException
import com.shinsaikou.source.SourceNotInstalledException
import eu.kanade.tachiyomi.network.HttpException
import kotlinx.coroutines.TimeoutCancellationException
import tachiyomi.domain.chapter.model.NoChaptersException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * What went wrong, as a key rather than as text. This is the one resolver for every failure the
 * screens (`ErrorText`) and the library update error list (`LibraryUpdateErrorStore`) name, so a
 * failure reads the same on either surface and the text is looked up in the reader's language when
 * it is shown, not when it was caught.
 *
 * [id] is what the error list persists. It never changes once shipped; a new case gets a new id.
 */
enum class ErrorKey(val id: String) {
    CLOUDFLARE_BUDGET("cloudflare_budget"),
    CLOUDFLARE("cloudflare"),

    /**
     * Something answered the connection with a refusal, so the device has a network: most often the
     * server is down or nothing listens on its port. A failure stored before this key keeps reading
     * as [NO_NETWORK].
     */
    CONNECTION_REFUSED("connection_refused"),
    NO_NETWORK("no_network"),

    /** Its text names the status code, which [httpCode] reads off the failure. */
    HTTP("http"),
    TIMEOUT("timeout"),
    NO_CHAPTERS("no_chapters"),
    SOURCE_NOT_INSTALLED("source_not_installed"),

    /** A chapter whose page list is empty, which closes the reader. */
    PAGE_LIST_EMPTY("page_list_empty"),

    /** The entry's row left the database while the reader was open on it, which closes the reader. */
    MANGA_NOT_FOUND("manga_not_found"),

    /** A chapter whose download folder another chapter's download already has, so it was not downloaded. */
    DOWNLOAD_FOLDER_IN_USE("download_folder_in_use"),

    /**
     * An entry the library update did not ask its site for, because the site pushed back three
     * times earlier in the run (`SiteStoppedException` in `:library-update`, which declares it).
     * Its detail is the site's host, when it has one.
     */
    SITE_STOPPED("site_stopped"),

    // Sync's failures (phase 3 unit G). Each class declares its key as a [KeyedFailure].

    /** A sync file of a newer format than this build reads: the owner updates Saikou on this device. */
    SYNC_NEWER_FORMAT("sync_newer_format"),

    /** A sync file that is not a well-formed file of a format this build reads. */
    SYNC_MALFORMED_FILE("sync_malformed_file"),

    /** The backend refused this device's sign-in, or nothing is signed in: the owner signs in again. */
    SYNC_SIGNED_OUT("sync_signed_out"),

    /** The backup written before a backend's first pull failed, so the sync did not start. */
    SYNC_SAFETY_BACKUP_FAILED("sync_safety_backup_failed"),

    /** The WebDAV server's certificate does not chain to an authority the device trusts. */
    WEBDAV_UNTRUSTED_CERTIFICATE("webdav_untrusted_certificate"),

    /** The WebDAV server's certificate was issued for another host name. */
    WEBDAV_HOST_NAME_MISMATCH("webdav_host_name_mismatch"),

    /** The WebDAV server reported a write that reading the file back did not find. */
    WEBDAV_WRITE_NOT_STORED("webdav_write_not_stored"),

    /** The WebDAV server's answer was not the WebDAV the backend asked for. */
    WEBDAV_MALFORMED_RESPONSE("webdav_malformed_response"),

    /** The WebDAV server answered a status the call does not accept. Its detail is the status code. */
    WEBDAV_HTTP("webdav_http"),

    /** The WebDAV server redirected. Its detail is the redirect's scheme and host, when it named one. */
    WEBDAV_REDIRECT("webdav_redirect"),

    /** A WebDAV address this build does not sign in to. Its detail is the refusal's reason. */
    WEBDAV_URL_REFUSED("webdav_url_refused"),

    /**
     * The WebDAV server did not answer: a refused connection, an unknown host, no route or a timeout.
     * A sync runs only with a network, so this names the server, never the device's connection.
     */
    WEBDAV_UNREACHABLE("webdav_unreachable"),

    // The browser sign-in's failures (phase 3 unit O). The loopback listener lives in `:core:common`,
    // below this module, so its three are matched by class; the Google client's declare their keys.

    /** The sign-in's loopback port is held by another app (Q13). Its detail is the port. */
    OAUTH_PORT_IN_USE("oauth_port_in_use"),

    /** No approval came back from the browser within five minutes. */
    OAUTH_TIMED_OUT("oauth_timed_out"),

    /** The browser came back without an approval. Its detail is the provider's `error` code. */
    OAUTH_DENIED("oauth_denied"),

    /** Google no longer honours this device's grant (`invalid_grant`): the owner signs in again. */
    GOOGLE_INVALID_GRANT("google_invalid_grant"),

    /**
     * Google's token endpoint refused this app's client with anything but `invalid_grant` (Q1's
     * fallback). Its detail is the `error` code Google sent.
     */
    GOOGLE_AUTH_REFUSED("google_auth_refused"),

    /**
     * Google's token or revoke endpoint did not answer usably: a 5xx, a 429 or a dropped connection.
     * Waiting can fix it. Its detail is the status code, when there was one.
     */
    GOOGLE_UNAVAILABLE("google_unavailable"),

    // The Google Drive backend's failures (phase 3 unit H), each declared by its class. A 401 Drive
    // refuses twice, a grant without `drive.appdata` and an `invalid_grant` refresh are all
    // SYNC_SIGNED_OUT; a token endpoint that did not answer is GOOGLE_UNAVAILABLE.

    /** Google Drive refused a write because the account's storage is full. */
    DRIVE_STORAGE_FULL("drive_storage_full"),

    /** Google Drive's answer was not the JSON the backend asked for, or a listed file lacked its size or time. */
    DRIVE_MALFORMED_RESPONSE("drive_malformed_response"),

    /** Google Drive answered a status no rule accepts. Its detail is the status and the error reason. */
    DRIVE_HTTP("drive_http"),

    /** Google Drive reported a write that reading the file back did not find. */
    DRIVE_WRITE_NOT_STORED("drive_write_not_stored"),

    /**
     * Google Drive did not answer usably after every retry in the run: a 5xx, a 429, a rate limit or
     * a dropped connection. Waiting can fix it. Its detail is the status code, when there was one.
     */
    DRIVE_UNAVAILABLE("drive_unavailable"),

    // A tracker's failures (phase 3 unit T1), each declared by its class in `:tracking`. The
    // loopback listener's failures during a tracker's sign-in keep the three OAUTH_ keys above.

    /**
     * A tracker's token endpoint refused a sign-in's code exchange. Its detail is the OAuth `error`
     * code the service sent, or `http_<status>` when it sent none.
     */
    TRACKER_SIGN_IN_REFUSED("tracker_sign_in_refused"),

    /** A tracker no longer honours the stored sign-in (a refresh refused with 400 or 401): sign in again. */
    TRACKER_AUTH_EXPIRED("tracker_auth_expired"),

    /**
     * A tracker's sign-in was granted without a permission Saikou asked for, so nothing was stored.
     * Its detail is the permission's scope as the service names it (Comick's `library:write` or
     * `offline_access`), which picks the text that says what to turn on.
     */
    TRACKER_PERMISSION_MISSING("tracker_permission_missing"),

    /**
     * Anything not named above. Its text is the generic one. The failure's own [message], when it has
     * one, takes that text's place on a screen and goes under it in the library update error list.
     */
    GENERIC("generic"),
    ;

    companion object {

        private const val CLOUDFLARE_BYPASS = "CloudflareBypassException"

        /** How a refused connection reads in the platform's text, on Android and on a JVM alike. */
        private const val REFUSED_TEXT = "Connection refused"

        /** How deep under a connect failure its refusal is looked for (see [causes]). */
        private const val MAX_CAUSES = 4

        /** The longest [message] kept, its ellipsis included. */
        const val MESSAGE_MAX_LENGTH = 200

        private const val ELLIPSIS = '\u2026'

        /**
         * The key a [KeyedFailure] declares; otherwise the first key that matches, in the order the
         * entries are declared, except that the loopback listener's three failures come first: each
         * is an `IOException` that must not read as a network one.
         *
         * `CloudflareBypassException` is private to its interceptor, so it is matched by class name.
         * Widening its visibility only to write one branch would make an internal failure part of the
         * network module's API.
         *
         * The Cloudflare, network, HTTP and timeout failures are looked for one `cause` deep as well
         * as at the top. The Cloudflare interceptor never lets a failure escape as itself: OkHttp's
         * enqueue only survives an `IOException`, so it rethrows each as a plain `IOException`
         * carrying the original as its cause, and matching the top level alone read every one of
         * them as generic.
         *
         * Both timeouts are one key. A source's own network call gives up with a
         * [SocketTimeoutException]; the update pass's per-entry watchdog throws
         * [TimeoutCancellationException]. To the reader both mean the source took too long.
         *
         * A refused connection and a network that cannot reach the server are both a
         * [ConnectException], so [isRefusal] reads which from its text. Any connect failure that
         * names no refusal (no route, the network unreachable, no message at all) stays [NO_NETWORK].
         * A connect that timed out on one of a host's addresses can carry another's refusal under
         * it, and reads as refused too: something answered.
         */
        fun of(error: Throwable): ErrorKey = when {
            error is KeyedFailure -> error.errorKey
            error is LoopbackPortInUseException -> OAUTH_PORT_IN_USE
            error is LoopbackTimeoutException -> OAUTH_TIMED_OUT
            error is LoopbackDeniedException -> OAUTH_DENIED
            error.isOrCauseIs { it is CloudflareBudgetExceededException } -> CLOUDFLARE_BUDGET
            error.isOrCauseIs { it::class.simpleName == CLOUDFLARE_BYPASS || it is CloudflareSolveRecentlyFailedException } ->
                CLOUDFLARE
            error.isOrCauseIs { (it is ConnectException || it is SocketTimeoutException) && it.isRefusal() } ->
                CONNECTION_REFUSED
            error.isOrCauseIs { it is UnknownHostException || it is ConnectException } -> NO_NETWORK
            httpCode(error) != null -> HTTP
            error.isOrCauseIs { it is SocketTimeoutException || it is TimeoutCancellationException } -> TIMEOUT
            error is NoChaptersException -> NO_CHAPTERS
            error is SourceNotInstalledException -> SOURCE_NOT_INSTALLED
            error is PageListEmptyException -> PAGE_LIST_EMPTY
            error is MangaNotFoundException -> MANGA_NOT_FOUND
            error is ChapterFolderInUseException -> DOWNLOAD_FOLDER_IN_USE
            else -> GENERIC
        }

        /** The status code of an [HttpException] at the top of [error] or as its cause, else null. */
        fun httpCode(error: Throwable): Int? =
            (error as? HttpException ?: error.cause as? HttpException)?.code

        /**
         * What a stored failure keeps beside its key (rule 2): the status code for [HTTP], the
         * exception's class name for [GENERIC], the port for [OAUTH_PORT_IN_USE], the provider's
         * `error` code for [OAUTH_DENIED], a [KeyedFailure]'s own [KeyedFailure.errorDetail], and
         * null otherwise. Never a message. The sync run record stores this; the library update error
         * list stores a [GENERIC] failure's [message] in its place (R162 item 8).
         */
        fun detail(error: Throwable): String? = when (of(error)) {
            HTTP -> httpCode(error)?.toString()
            GENERIC -> error::class.simpleName
            OAUTH_PORT_IN_USE -> (error as? LoopbackPortInUseException)?.port?.toString()
            OAUTH_DENIED -> (error as? LoopbackDeniedException)?.error
            else -> (error as? KeyedFailure)?.errorDetail
        }

        /**
         * A [GENERIC] failure's own message, as a screen shows it and the library update error list
         * and the download queue keep it: the first line that is not blank, trimmed and cut to
         * [MESSAGE_MAX_LENGTH], or null when there is none. An extension's message is often the only
         * thing that says why (a sign-in it needs, a rate limit), but it can also be a whole error page.
         */
        fun message(error: Throwable): String? {
            val line = error.message?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: return null
            if (line.length <= MESSAGE_MAX_LENGTH) return line
            // Never cut between the two halves of a surrogate pair, which would leave half a character.
            val end = (MESSAGE_MAX_LENGTH - 1).let { if (line[it - 1].isHighSurrogate()) it - 1 else it }
            return line.substring(0, end).trimEnd() + ELLIPSIS
        }

        /** The key persisted as [id], or null for one this build does not know. */
        fun fromId(id: String): ErrorKey? = entries.firstOrNull { it.id == id }

        private inline fun Throwable.isOrCauseIs(match: (Throwable) -> Boolean): Boolean =
            match(this) || cause?.let(match) == true

        /**
         * Whether a connect failure was refused. Only the text says so: Android's socket names the
         * errno there ("ECONNREFUSED (Connection refused)"; the `android.system.ErrnoException` under
         * it is not something this module's JVM tests can build), a JVM's says "Connection refused",
         * and OkHttp throws its own `ConnectException` with the platform's as its cause. OkHttp also
         * tries a host's addresses side by side and suppresses the later failures under the first,
         * so a refusal from any address counts: something answered, so the device has a network.
         */
        private fun Throwable.isRefusal(): Boolean = causes().any { failure ->
            failure.saysRefused() || failure.suppressed.any { other -> other.causes().any { it.saysRefused() } }
        }

        // A coroutine's recovered copy, OkHttp's failure, the platform's and the errno under it.
        private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }.take(MAX_CAUSES)

        private fun Throwable.saysRefused(): Boolean = message?.contains(REFUSED_TEXT, ignoreCase = true) == true
    }
}

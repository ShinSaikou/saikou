package com.shinsaikou.sync.webdav

import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.domain.error.KeyedFailure
import java.io.IOException

// The failures the WebDAV backend and its sign-in name, each an IOException so the engine's
// contract holds (R81 item 9) and each its own class so Sync settings can say which one happened
// (R67): each declares its own ErrorKey as a KeyedFailure. A 401 is
// com.shinsaikou.sync.SyncSignedOutException, which both backends share. A server that does not answer
// (a refused connection, an unknown host, no route, a timeout) is WebDavUnreachableException; other
// transport failures are thrown as the exception OkHttp raised.

/** The server answered [method] on [path] with [code], which the call does not accept. */
class WebDavHttpException(val code: Int, method: String, path: String) :
    IOException("WebDAV $method /$path answered HTTP $code"), KeyedFailure {
    override val errorKey: ErrorKey get() = ErrorKey.WEBDAV_HTTP
    override val errorDetail: String get() = code.toString()
}

/**
 * The server answered [method] on [path] with the redirect [code], which the backend never follows:
 * OkHttp would resend a write's body to wherever it points, `http://` included, and take a redirected
 * answer as this server's. [scheme] and [host] are the `Location`'s, null when it had none; nothing
 * else of it (a path, a query, credentials) is kept. Sync settings says the server moved and asks
 * for the final address.
 */
class WebDavRedirectException(val code: Int, method: String, path: String, val scheme: String?, val host: String?) :
    IOException("WebDAV $method $path answered HTTP $code, a redirect to ${if (host == null) "nowhere named" else "$scheme://$host"}"),
    KeyedFailure {
    override val errorKey: ErrorKey get() = ErrorKey.WEBDAV_REDIRECT

    /** The `Location`'s scheme and host, which Sync settings names; null when it had none. */
    override val errorDetail: String? get() = host?.let { "$scheme://$it" }
}

/**
 * The server answered a write with success, and reading the file back found it missing or holding
 * other bytes: it reported a write it did not store. The sync fails and no cursor moves.
 */
class WebDavWriteNotStoredException(val path: String, detail: String) :
    IOException("The server reported writing $path but did not store it: $detail"), KeyedFailure {
    override val errorKey: ErrorKey get() = ErrorKey.WEBDAV_WRITE_NOT_STORED
}

/** An answer that is not the WebDAV the backend asked for: a listing it cannot read, or no collection. */
class WebDavMalformedResponseException(message: String, cause: Throwable? = null) :
    IOException(message, cause), KeyedFailure {
    override val errorKey: ErrorKey get() = ErrorKey.WEBDAV_MALFORMED_RESPONSE
}

/**
 * The server's certificate did not chain to a certificate authority the device trusts. The app
 * trusts only the system's authorities (`network_security_config.xml`), and pinning a certificate
 * for one host is not built (plan unit F).
 */
class WebDavUntrustedCertificateException(val host: String, cause: Throwable) :
    IOException("The certificate of $host is not trusted by this device", cause), KeyedFailure {
    override val errorKey: ErrorKey get() = ErrorKey.WEBDAV_UNTRUSTED_CERTIFICATE
}

/** The server's certificate is trusted but was issued for another name than [host]. */
class WebDavHostNameMismatchException(val host: String, cause: Throwable) :
    IOException("The certificate of $host was issued for another host name", cause), KeyedFailure {
    override val errorKey: ErrorKey get() = ErrorKey.WEBDAV_HOST_NAME_MISMATCH
}

/**
 * The server at [host] did not answer: [cause] is the refused connection, unknown host, missing route
 * or timeout OkHttp raised. Every sync request needs a network (`NetworkType.CONNECTED`), so this is
 * the server, not the device's connection; it stays an [IOException], so the worker retries it. The
 * host stays out of what is stored, as a certificate's does: the section shows the server already.
 */
class WebDavUnreachableException(val host: String, cause: IOException) :
    IOException("$host did not answer: ${cause::class.simpleName}", cause), KeyedFailure {
    override val errorKey: ErrorKey get() = ErrorKey.WEBDAV_UNREACHABLE
}

/** A WebDAV address this build does not sign in to, and why ([WebDavUrlPolicy]). */
class WebDavUrlRefusedException(val reason: Reason, message: String) : IOException(message), KeyedFailure {

    override val errorKey: ErrorKey get() = ErrorKey.WEBDAV_URL_REFUSED

    /** The [reason]'s name, which Sync settings words the refusal by. */
    override val errorDetail: String get() = reason.name

    enum class Reason {
        /** Not an `http://` or `https://` URL at all. */
        NOT_A_URL,

        /** A URL carrying a user name, password, query or fragment, which a base address never has. */
        NOT_A_BASE_URL,

        /** `http://` to a host this build does not allow cleartext to; release allows none. */
        CLEARTEXT,
    }
}

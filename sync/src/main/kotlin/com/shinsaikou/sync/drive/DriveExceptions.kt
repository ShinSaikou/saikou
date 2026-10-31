package com.shinsaikou.sync.drive

import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.domain.error.KeyedFailure
import java.io.IOException

// The failures the Google Drive backend names (phase 3 unit H), each an IOException so the engine's
// contract holds and each its own class with its own ErrorKey (R67, G's ruling draft item 7): none
// reuses a WebDAV id. Drive's answers are decided on the status, `error.code` and
// `error.errors[0].reason`, never on `message` (R83 item 3). A grant Drive no longer honours is
// com.shinsaikou.sync.SyncSignedOutException, which both backends share; a token endpoint that did not
// answer is com.shinsaikou.sync.google.GoogleUnavailableException.

/** Drive refused a write with 403 `storageQuotaExceeded`. Waiting does not free space, so the worker stops. */
class DriveStorageFullException(val path: String) :
    IOException("Google Drive is full: it refused $path with storageQuotaExceeded"), KeyedFailure {
    override val errorKey: ErrorKey get() = ErrorKey.DRIVE_STORAGE_FULL
}

/**
 * An answer that is not the Drive the backend asked for: JSON it cannot read, a listed file without
 * its `size` or `modifiedTime`, or a resumable session without a `Location` on Drive's own host.
 */
class DriveMalformedResponseException(message: String, cause: Throwable? = null) :
    IOException(message, cause), KeyedFailure {
    override val errorKey: ErrorKey get() = ErrorKey.DRIVE_MALFORMED_RESPONSE
}

/**
 * Drive answered [what] with [status] and the error [reason] (null when the body named none), which
 * no rule accepts: any status the backend does not decide otherwise, a redirect included, since no
 * redirect is followed. The worker retries it.
 */
class DriveHttpException(val status: Int, val reason: String?, what: String) :
    IOException("Google Drive answered $what with HTTP $status${reason?.let { " ($it)" }.orEmpty()}"), KeyedFailure {
    override val errorKey: ErrorKey get() = ErrorKey.DRIVE_HTTP

    /** The status, and the reason after a space when there was one: `404` or `400 badRequest`. */
    override val errorDetail: String get() = if (reason == null) status.toString() else "$status $reason"
}

/**
 * Drive answered a create with success, and reading the file back found it missing or holding other
 * bytes: it reported a write it did not store. The sync fails and no cursor moves; the worker retries.
 */
class DriveWriteNotStoredException(val path: String, detail: String) :
    IOException("Google Drive reported writing $path but did not store it: $detail"), KeyedFailure {
    override val errorKey: ErrorKey get() = ErrorKey.DRIVE_WRITE_NOT_STORED
}

/**
 * Drive did not answer usably on the last of the run's attempts: a 5xx, a 429 or a rate-limit 403
 * ([status] and [reason]), or no answer at all ([status] null, the transport failure as the cause).
 * Waiting can fix it: the backend has waited inside the run already, and the worker retries.
 */
class DriveUnavailableException(val status: Int?, val reason: String?, cause: IOException? = null) :
    IOException(
        if (status == null) {
            "Google Drive did not answer: ${cause?.let { it::class.simpleName }}"
        } else {
            "Google Drive answered HTTP $status${reason?.let { " ($it)" }.orEmpty()}"
        },
        cause,
    ),
    KeyedFailure {
    override val errorKey: ErrorKey get() = ErrorKey.DRIVE_UNAVAILABLE
    override val errorDetail: String? get() = status?.toString()
}

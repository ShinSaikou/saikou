package com.shinsaikou.sync

import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.domain.error.KeyedFailure
import java.io.IOException

/**
 * The backend refused this device's credentials, or this device no longer has them. A retry cannot
 * help: the worker ends the run without one (Q8), and while this is the last outcome Sync settings
 * shows its Sign in again state (phase 3 unit G, fix round 1): the saved server and user name with
 * a password field when the sign-in is still stored, the whole form when it is not. Signing in there
 * to the same server and user keeps this device's place on the backend. The WebDAV backend throws it
 * for a 401 on any call it makes during a run, and a run throws it when no backend is configured or
 * the stored sign-in is not the configured one. The Drive backend throws it for a second 401 on
 * one call, for a 403 that says the grant lacks `drive.appdata`, and for a refresh Google answers
 * with `invalid_grant`, which is its [cause] (phase 3 unit H).
 */
class SyncSignedOutException(message: String, cause: Throwable? = null) : IOException(message, cause), KeyedFailure {
    override val errorKey: ErrorKey get() = ErrorKey.SYNC_SIGNED_OUT
}

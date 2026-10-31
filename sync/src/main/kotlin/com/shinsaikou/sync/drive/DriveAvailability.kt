package com.shinsaikou.sync.drive

import com.shinsaikou.sync.google.GoogleClientCredentials
import com.shinsaikou.sync.google.GoogleEndpoints
import dev.zacsweers.metro.Inject

/**
 * Whether this build offers Google Drive (phase 3 unit H, item 6): the one rule, in one place. A
 * build offers it when its client id (`saikou.drive.client.id` or `SAIKOU_DRIVE_CLIENT_ID`, R91 item
 * 8) is not blank, and a debug build also while a Google test base is set. Without either, Sync
 * settings hides Drive rather than failing, and [DriveAccount.backend] builds none.
 *
 * The test base half needs no build check here: the release build's endpoints binding is Google's
 * constants and never reads a test base (R91 item 8), so there it is always [GoogleEndpoints.GOOGLE].
 * [endpoints] is read on each call, since a test sets its base after the graph is built.
 */
@Inject
class DriveAvailability(
    private val endpoints: () -> GoogleEndpoints,
    private val credentials: GoogleClientCredentials,
) {

    val offered: Boolean get() = offered(endpoints(), credentials)

    companion object {
        /** A test base's endpoints, or Google's with the build's client id set. */
        fun offered(endpoints: GoogleEndpoints, credentials: GoogleClientCredentials): Boolean =
            !endpoints.isGoogle || credentials.clientId.isNotBlank()
    }
}

package com.shinsaikou.storage

import android.content.Context
import android.content.Intent
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Gives back the persisted grant a storage location was picked under: when Cancel turns a pick
 * down, when a new location replaces one with nothing left to move, and when the move out of a
 * previous location has emptied it (unit storage-move-between-folders).
 */
@Inject
@SingleIn(AppScope::class)
class StorageGrants(private val context: Context) {

    /**
     * Gives back the grant [uri] was picked under. The grant is on the tree, and [uri] is the tree's
     * document URI, so the persisted grant whose tree it falls under is the one released; nothing
     * is given for one never persisted or a plain file URI.
     */
    fun release(uri: String) {
        runCatching {
            context.contentResolver.persistedUriPermissions
                .filter { grant -> grantCovers(grant.uri.toString(), uri) }
                .forEach { grant ->
                    context.contentResolver.releasePersistableUriPermission(
                        grant.uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
        }
    }

    internal companion object {
        /** A grant on `<authority>/tree/<id>` covers that tree and the documents under it, and no sibling tree. */
        fun grantCovers(grant: String, uri: String): Boolean = uri == grant || uri.startsWith("$grant/")
    }
}

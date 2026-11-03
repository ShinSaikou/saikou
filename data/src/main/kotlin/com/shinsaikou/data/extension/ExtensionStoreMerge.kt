package com.shinsaikou.data.extension

import app.cash.sqldelight.async.coroutines.awaitAsList
import com.shinsaikou.domain.extension.ExtensionStoreDuplicates
import tachiyomi.data.Database

/**
 * Writes [ExtensionStoreDuplicates.merge] over the table: the kept rows filled in, the others
 * deleted. The caller holds the transaction. Returns the rows deleted, zero once nothing is left
 * to merge, so running it again costs one read of a table of a few rows.
 *
 * [com.shinsaikou.data.DatabaseInitializer]'s start-up transaction runs it, which repairs the rows a
 * restore made before the restore merged them itself, and so does the restore, after it writes the
 * repositories ([ExtensionStoreRepositoryImpl.mergeDuplicates]).
 */
internal suspend fun Database.mergeDuplicateExtensionStores(): Int {
    val rows = extension_storeQueries.getAll(::mapExtensionStore).awaitAsList()
    val merge = ExtensionStoreDuplicates.merge(rows)
    if (merge.isEmpty) return 0
    merge.updated.forEach { store ->
        extension_storeQueries.upsert(
            indexUrl = store.indexUrl,
            name = store.name,
            badgeLabel = store.badgeLabel,
            signingKey = store.signingKey,
            contactWebsite = store.contact.website,
            contactDiscord = store.contact.discord,
            isLegacy = store.isLegacy,
            extensionListUrl = store.extensionListUrl,
        )
    }
    merge.removed.forEach { extension_storeQueries.delete(it) }
    return merge.removed.size
}

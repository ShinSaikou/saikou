package com.shinsaikou.data.extension

import com.shinsaikou.data.DatabaseHandler
import com.shinsaikou.domain.extension.repository.ExtensionStoreRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.extension.model.ExtensionStore

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ExtensionStoreRepositoryImpl(
    private val handler: DatabaseHandler,
) : ExtensionStoreRepository {

    override suspend fun get(indexUrl: String): ExtensionStore? =
        handler.awaitOneOrNull { extension_storeQueries.get(indexUrl, ::mapExtensionStore) }

    override suspend fun getAll(): List<ExtensionStore> =
        handler.awaitList { extension_storeQueries.getAll(::mapExtensionStore) }

    override fun getAllAsFlow(): Flow<List<ExtensionStore>> =
        handler.subscribeToList { extension_storeQueries.getAll(::mapExtensionStore) }

    override fun getCountAsFlow(): Flow<Long> =
        handler.subscribeToOne { extension_storeQueries.getCount() }

    override suspend fun upsert(store: ExtensionStore) {
        handler.await {
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
    }

    override suspend fun updateIfSigningKey(store: ExtensionStore, expectedSigningKey: String): Boolean =
        handler.await {
            extension_storeQueries.updateIfSigningKey(
                name = store.name,
                badgeLabel = store.badgeLabel,
                signingKey = store.signingKey,
                contactWebsite = store.contact.website,
                contactDiscord = store.contact.discord,
                isLegacy = store.isLegacy,
                extensionListUrl = store.extensionListUrl,
                indexUrl = store.indexUrl,
                expectedSigningKey = expectedSigningKey,
            ) > 0
        }

    override suspend fun remove(indexUrl: String) {
        handler.await { extension_storeQueries.delete(indexUrl) }
    }

    override suspend fun mergeDuplicates(): Int =
        handler.await(inTransaction = true) { mergeDuplicateExtensionStores() }
}

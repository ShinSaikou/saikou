package com.shinsaikou.domain.extension.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.extension.model.ExtensionStore

/**
 * Table access only. The network half of upstream's interface (validating and refreshing an index)
 * lives in the extension host, which calls [upsert] once a descriptor passes the trust rules.
 */
interface ExtensionStoreRepository {
    suspend fun get(indexUrl: String): ExtensionStore?

    suspend fun getAll(): List<ExtensionStore>

    fun getAllAsFlow(): Flow<List<ExtensionStore>>

    fun getCountAsFlow(): Flow<Long>

    /** ON CONFLICT(index_url) DO UPDATE every other column. */
    suspend fun upsert(store: ExtensionStore)

    /**
     * Writes [store] over its row only while that row still exists and still holds
     * [expectedSigningKey]; never inserts. Returns whether a row was written.
     */
    suspend fun updateIfSigningKey(store: ExtensionStore, expectedSigningKey: String): Boolean

    suspend fun remove(indexUrl: String)
}

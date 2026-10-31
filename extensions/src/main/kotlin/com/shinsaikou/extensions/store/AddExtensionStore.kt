package com.shinsaikou.extensions.store

import com.shinsaikou.domain.extension.repository.ExtensionStoreRepository
import com.shinsaikou.extensions.repo.ExtensionRepoClient
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.domain.extension.model.ExtensionStore
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Validates an index through the same client used by refreshes, then persists the descriptor it
 * actually resolves to. A manually entered URL therefore cannot create a partial store row.
 *
 * Each refusal is a type of its own, or the client's [com.shinsaikou.extensions.repo.RepoFetchException]
 * with its kind, so the screen can say what went wrong in the reader's language.
 */
@SingleIn(AppScope::class)
@Inject
class AddExtensionStore(
    private val repoClient: ExtensionRepoClient,
    private val stores: ExtensionStoreRepository,
) {

    suspend fun await(url: String): Result<ExtensionStore> {
        val normalized = url.trim()
        if (normalized.toHttpUrlOrNull() == null) {
            return Result.failure(InvalidStoreAddressException())
        }
        return repoClient.fetch(normalized).mapCatching { fetched ->
            val store = fetched.store
            if (stores.get(store.indexUrl) != null) throw StoreAlreadyConfiguredException()
            stores.upsert(store)
            store
        }
    }
}

/** The address entered is not an http or https URL. */
class InvalidStoreAddressException : IllegalArgumentException("A repository address must use HTTP or HTTPS")

/** The index the address resolves to is one already configured. */
class StoreAlreadyConfiguredException : IllegalStateException("That repository is already configured")
